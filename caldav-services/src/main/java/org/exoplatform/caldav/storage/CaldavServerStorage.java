/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.caldav.storage;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.caldav.dao.CaldavServerDAO;
import org.exoplatform.caldav.entity.CaldavServerEntity;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.ObservedQuirk;
import org.exoplatform.caldav.model.ServerQuirk;
import org.exoplatform.caldav.model.ServerQuirkEffect;
import org.exoplatform.caldav.utils.ServerQuirkSummary;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Observation;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Retention;
import org.exoplatform.caldav.utils.ServerQuirkSummary.Tally;
import org.exoplatform.commons.file.model.FileInfo;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.utils.IOUtil;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import lombok.SneakyThrows;

/**
 * Maps CalDAV server registrations between their JPA entity and the
 * credential-free {@link CaldavServer} DTO. No business logic lives here: the
 * provider-name derivation is mechanical (the seed name is given by the
 * service, every other row derives its name from its own id) and the choices
 * — who may write, when to seed — belong to the service.
 */
@Component
public class CaldavServerStorage {

  /**
   * FileService namespace the uploaded server images are stored under.
   */
  public static final String NAME_SPACE            = "caldavServer";

  /**
   * Fallback cache-busting version for rows whose image has no readable
   * modification date.
   */
  public static final Long   DEFAULT_LAST_MODIFIED = System.currentTimeMillis();

  @Autowired
  private CaldavServerDAO    caldavServerDAO;

  @Autowired
  private UploadService      uploadService;

  @Autowired
  private FileService        fileService;

  /**
   * Reads every registration, seed first (it holds the lowest id by
   * construction).
   *
   * @return all registrations, ordered by id
   */
  public List<CaldavServer> getServers() {
    return caldavServerDAO.findAll(Sort.by("id")).stream().map(this::fromEntity).toList();
  }

  /**
   * Reads one registration by its technical identifier.
   *
   * @param id technical identifier of the registration
   * @return the registration, or null when the row does not exist
   */
  public CaldavServer getServerById(long id) {
    return caldavServerDAO.findById(id).map(this::fromEntity).orElse(null);
  }

  /**
   * Reads one registration by the agenda remote provider it is bridged to.
   *
   * @param providerName name of the agenda remote provider
   * @return the registration, or null when none carries that name
   */
  public CaldavServer getServerByProviderName(String providerName) {
    return caldavServerDAO.findByProviderName(providerName).map(this::fromEntity).orElse(null);
  }

  /**
   * Counts the registrations — what the seeding decision reads: the seed row
   * is only ever written into an empty registry, so an administrator's edits
   * are never overwritten by a restart.
   *
   * @return number of registration rows
   */
  public long countServers() {
    return caldavServerDAO.count();
  }

  /**
   * Creates a registration whose provider name derives from its own row id
   * ({@code <providerNamePrefix>.<id>}). Two steps in one transaction —
   * insert, then name the row after the id the insert produced — because the
   * column is NOT NULL and the id does not exist before the insert. The
   * transaction keeps the intermediate placeholder name unobservable.
   *
   * @param server registration to create (provider name ignored)
   * @param providerNamePrefix prefix the provider name is derived from
   * @return the created registration, carrying its id and provider name
   */
  @Transactional
  public CaldavServer createServer(CaldavServer server, String providerNamePrefix) {
    CaldavServerEntity entity = toEntity(server);
    entity.setId(null);
    entity.setProviderName(providerNamePrefix + ".pending." + System.nanoTime());
    if (StringUtils.isNotBlank(server.getImageUploadId())) {
      entity.setImageFileId(saveImageFileItem(null, server.getImageUploadId()));
    }
    entity = caldavServerDAO.save(entity);
    entity.setProviderName(providerNamePrefix + "." + entity.getId());
    entity = caldavServerDAO.save(entity);
    return fromEntity(entity);
  }

  /**
   * Creates the seed registration under the fixed legacy provider name — the
   * one row whose provider the kernel plugin already registers, so the
   * accounts connected before the registry existed keep resolving through it.
   *
   * @param server registration to create
   * @param providerName fixed provider name of the seed
   * @return the created registration
   */
  @Transactional
  public CaldavServer createSeedServer(CaldavServer server, String providerName) {
    CaldavServerEntity entity = toEntity(server);
    entity.setId(null);
    entity.setProviderName(providerName);
    return fromEntity(caldavServerDAO.save(entity));
  }

  /**
   * Updates the user-editable fields of a registration: name, description,
   * URL, activation, whether the copies pushed to this server carry answer
   * links, and the two lists of behaviours this server is excused for. The
   * provider name never changes — it is the join key user settings and agenda
   * rows hang from, and neither is the rolling observation summary, which is
   * the sweep's to write (see {@link #mergeObservedQuirks}) and would otherwise
   * be erased by every administrator save.
   *
   * @param server registration carrying the id to update and the new values
   * @return the updated registration, or null when the row does not exist
   */
  @Transactional
  public CaldavServer updateServer(CaldavServer server) {
    return caldavServerDAO.findById(server.getId()).map(entity -> {
      entity.setName(server.getName());
      entity.setDescription(server.getDescription());
      entity.setServerUrl(server.getServerUrl());
      entity.setActive(server.isActive());
      entity.setIcon(server.getIcon());
      entity.setAnswerLinksInCopy(server.isAnswerLinksInCopy());
      entity.setIgnoredProperties(server.getIgnoredProperties());
      entity.setDroppedProperties(server.getDroppedProperties());
      entity.setOmittedProperties(server.getOmittedProperties());
      Long oldImageFileId = entity.getImageFileId();
      boolean imageRemoved = (server.getImageFileId() == null || server.getImageFileId() == 0)
          && oldImageFileId != null && oldImageFileId > 0;
      if (imageRemoved) {
        // The image was explicitly dropped by the drawer: the stored file has
        // no other referrer, so it goes with the reference.
        entity.setImageFileId(null);
        fileService.deleteFile(oldImageFileId);
        oldImageFileId = null;
      }
      if (StringUtils.isNotBlank(server.getImageUploadId())) {
        entity.setImageFileId(saveImageFileItem(oldImageFileId, server.getImageUploadId()));
      }
      return fromEntity(caldavServerDAO.save(entity));
    }).orElse(null);
  }

  /**
   * Deletes a registration row and, with it, its uploaded image — the file
   * has no other referrer, so keeping it would only leak storage.
   *
   * @param serverId technical identifier of the registration
   * @return true when a row was deleted, false when none carried that id
   */
  @Transactional
  public boolean deleteServer(long serverId) {
    return caldavServerDAO.findById(serverId).map(entity -> {
      if (entity.getImageFileId() != null && entity.getImageFileId() > 0) {
        fileService.deleteFile(entity.getImageFileId());
      }
      caldavServerDAO.delete(entity);
      return true;
    }).orElse(false);
  }

  /**
   * Turns a fresh browser upload into a FileService file, updating the
   * existing file when the row already had one — the exact mechanics of
   * email-connector's admin images.
   *
   * @param imageFileId existing file to update, or null to write a new one
   * @param uploadId identifier of the browser upload
   * @return identifier of the stored file, or null when storing failed
   */
  @SneakyThrows
  private Long saveImageFileItem(Long imageFileId, String uploadId) {
    UploadResource uploadResource = uploadService.getUploadResource(uploadId);
    byte[] bytesContent = IOUtil.getFileContentAsBytes(uploadResource.getStoreLocation());
    FileItem fileItem = new FileItem(imageFileId,
                                     "caldavServerIllustration",
                                     "image/png",
                                     NAME_SPACE,
                                     bytesContent.length,
                                     new Date(),
                                     null,
                                     false,
                                     new ByteArrayInputStream(bytesContent));
    if (imageFileId != null && imageFileId > 0) {
      fileItem = fileService.updateFile(fileItem);
    } else {
      fileItem = fileService.writeFile(fileItem);
    }
    return fileItem == null || fileItem.getFileInfo() == null ? null : fileItem.getFileInfo().getId();
  }

  /**
   * Maps an entity to the credential-free DTO.
   *
   * @param entity row to map
   * @return the DTO
   */
  private CaldavServer fromEntity(CaldavServerEntity entity) {
    long imageLastModified = DEFAULT_LAST_MODIFIED;
    if (entity.getImageFileId() != null && entity.getImageFileId() > 0) {
      FileInfo fileInfo = fileService.getFileInfo(entity.getImageFileId());
      if (fileInfo != null && fileInfo.getUpdatedDate() != null) {
        imageLastModified = fileInfo.getUpdatedDate().getTime();
      }
    }
    return new CaldavServer(entity.getId() == null ? 0 : entity.getId(),
                            entity.getProviderName(),
                            entity.getName(),
                            entity.getDescription(),
                            entity.getServerUrl(),
                            entity.isActive(),
                            entity.getIcon(),
                            entity.getImageFileId(),
                            null,
                            getImageUrl(entity.getImageFileId(), entity.getId(), imageLastModified),
                            entity.isAnswerLinksInCopy(),
                            entity.getIgnoredProperties(),
                            entity.getDroppedProperties(),
                            entity.getOmittedProperties(),
                            observedQuirks(entity.getObservedQuirks()));
  }

  /**
   * The stored observation summary as the entries the drawer lists.
   *
   * <p>
   * <b>Grouped by behaviour, not by property.</b> A catalogue entry can cover a
   * family — the Outlook and Thunderbird markers are one sentence about one
   * server habit — and a live BlueMind account produced three of them, which
   * rendered as three identical checkboxes each saying "seen once". They are one
   * decision, so they become one entry whose count is the sum of theirs. It
   * would only have got worse: that entry matches by prefix, so every new marker
   * the server stamps would have added another identical row.
   *
   * <p>
   * A behaviour nothing in the catalogue describes stays one entry per property,
   * because there each property genuinely is a separate thing the server does —
   * which is why the grouping key is the catalogue's identifier where there is
   * one, and the direction and property where there is not.
   *
   * <p>
   * Mapping, not judgement: whether an entry is <i>excused</i> is left false
   * here and decided by the service, which is the layer that knows the
   * deployment-wide fallback.
   *
   * @param summary the stored summary, may be null
   * @return the observed behaviours, largest counts first, never null
   */
  private List<ObservedQuirk> observedQuirks(String summary) {
    Map<String, ObservedQuirk> byBehaviour = new LinkedHashMap<>();
    ServerQuirkSummary.parse(summary)
                      .forEach((observation, tally) -> byBehaviour.merge(behaviourKey(observation),
                                                                         observedQuirk(observation, tally.count()),
                                                                         CaldavServerStorage::mergeBehaviour));
    return byBehaviour.values()
                      .stream()
                      .sorted(Comparator.comparingLong(ObservedQuirk::count)
                                        .reversed()
                                        .thenComparing(ObservedQuirk::property))
                      .toList();
  }

  /**
   * What makes two observations the same behaviour: the catalogue entry
   * describing them, or — when none does — the property and the direction
   * themselves.
   *
   * @param observation what was seen
   * @return the grouping key
   */
  private String behaviourKey(Observation observation) {
    return ServerQuirk.describing(observation.property(), observation.direction())
                      .map(ServerQuirk::getId)
                      .orElseGet(() -> observation.direction() + ":" + observation.property());
  }

  /**
   * Folds a second observation of one behaviour into the entry already holding
   * it: the counts add up, and the property that produced them is kept so the
   * excusal can still be read from the list in force.
   *
   * @param first the entry built so far
   * @param second the entry for the observation being folded in
   * @return the combined entry
   */
  private static ObservedQuirk mergeBehaviour(ObservedQuirk first, ObservedQuirk second) {
    List<String> properties = new ArrayList<>(first.properties());
    second.properties().stream().filter(property -> !properties.contains(property)).forEach(properties::add);
    return new ObservedQuirk(first.quirkId(),
                             properties,
                             first.direction(),
                             first.effect(),
                             first.count() + second.count(),
                             false,
                             first.patterns());
  }

  /**
   * One stored observation as an entry of its own, before any other observation
   * of the same behaviour is folded into it.
   *
   * <p>
   * A described behaviour carries the catalogue's <b>own</b> direction rather
   * than the observed one, so a family whose members were seen pointing
   * different ways still reads — and is ticked — as the single behaviour it is.
   *
   * @param observation what was seen
   * @param count how many times
   * @return the entry, carrying the catalogue's identifier when one describes
   *         it and the observed property alone when none does
   */
  private ObservedQuirk observedQuirk(Observation observation, Long count) {
    return ServerQuirk.describing(observation.property(), observation.direction())
                      .map(quirk -> new ObservedQuirk(quirk.getId(),
                                                      List.of(observation.property()),
                                                      quirk.getDirection(),
                                                      quirk.getEffect(),
                                                      count,
                                                      false,
                                                      quirk.getPatterns()))
                      // TOLERATE for a behaviour nothing describes, and never
                      // OMIT: an entry nobody has written a rule for can relax a
                      // comparison, but there is no rule to make eXo leave
                      // anything out of what it writes.
                      .orElseGet(() -> new ObservedQuirk(null,
                                                         List.of(observation.property()),
                                                         observation.direction(),
                                                         ServerQuirkEffect.TOLERATE,
                                                         count,
                                                         false,
                                                         List.of(observation.property())));
  }

  /**
   * Adds what a pass saw to a registration's rolling observation summary, and
   * forgets what no longer belongs in it.
   *
   * <p>
   * Read, merge, prune and write in one transaction, and touching that one
   * column only. This is the sweep talking, not an administrator: routing it
   * through {@link #updateServer} would let a background pass overwrite a name
   * or a URL somebody is editing in the drawer at the same moment.
   *
   * <p>
   * <b>What may be forgotten is the service's decision and this method's
   * mechanics.</b> The rules arrive as a {@link Retention}, because working out
   * which records a reclassification has replaced, and how long silence is
   * allowed to last, is policy; applying the answer to a stored string is
   * mapping.
   *
   * <p>
   * <b>One record is never dropped, whatever the rules say: an excused one.</b>
   * The excusal itself lives in the three lists on this row and would survive
   * either way, which is precisely the danger — an excusal still in force with
   * no entry in the drawer is one an administrator can neither see nor untick.
   * Forgetting the evidence must never outlive the decision made from it. It is
   * checked here rather than in the policy because it is a fact about this row,
   * and the row is what this layer holds.
   *
   * <p>
   * Two passes racing on one server can still lose an increment, and that is
   * accepted: the number answers "does this server always do this, or did it
   * happen once", and no decision is made from its exact value.
   *
   * @param serverId technical identifier of the registration
   * <p>
   * <b>An empty batch is not a reason to do nothing.</b> A converged account
   * observes no divergence at all, which is exactly the account whose stale
   * records want clearing; gating the whole method on having something to add
   * meant the cleanup only ever ran on servers that were still misbehaving. It
   * reads and prunes regardless, and writes only when the result differs from
   * what is stored — so a settled row costs one read per interval and no write.
   *
   * @param increments how many times each behaviour was seen since the last
   *          write, empty when the pass found nothing
   * @param retention what this write is allowed to forget
   */
  @Transactional
  public void mergeObservedQuirks(long serverId, Map<Observation, Long> increments, Retention retention) {
    Map<Observation, Long> seen = increments == null ? Map.of() : increments;
    caldavServerDAO.findById(serverId).ifPresent(entity -> {
      long today = retention.today();
      Map<Observation, Tally> merged = new LinkedHashMap<>();
      ServerQuirkSummary.parse(entity.getObservedQuirks())
                        .forEach((observation, tally) -> merged.put(observation, tally));
      seen.forEach((observation, times) -> merged.merge(observation,
                                                        new Tally(times, today),
                                                        (stored, fresh) -> stored.seen(fresh.count(), today)));
      merged.entrySet()
            .removeIf(entry -> !excused(entity, entry.getKey().property())
                && retention.forgets(entry.getKey(), entry.getValue()));
      // Back-dated, never today. An entry carried across a write was not seen
      // in it, and dating it now would make the oldest records in the row look
      // like the freshest — holding off both supersession and ageing for a full
      // window, on precisely the entries that should go first. One grace period
      // and a day is the least that leaves it superseded-eligible immediately,
      // and it keeps the rest of its ageing window.
      merged.replaceAll((observation, tally) -> tally.stamped(today - retention.settledGraceDays() - 1));
      String summary = ServerQuirkSummary.format(merged);
      if (StringUtils.equals(summary, entity.getObservedQuirks())) {
        // Nothing to say. A settled server is swept every few minutes for ever,
        // and rewriting an identical row each time would turn a column that
        // should be quiet into the busiest write in the add-on.
        return;
      }
      entity.setObservedQuirks(summary);
      caldavServerDAO.save(entity);
    });
  }

  /**
   * Whether any list on the registration excuses a property today.
   *
   * <p>
   * All three, because all three are decisions an administrator made from an
   * entry in the drawer, and any of them left in force with its entry gone is
   * one they can no longer see.
   *
   * @param entity the registration
   * @param property the property or case name
   * @return true when the row already carries a decision about it
   */
  private boolean excused(CaldavServerEntity entity, String property) {
    return ServerQuirk.listMatches(entity.getIgnoredProperties(), property)
        || ServerQuirk.listMatches(entity.getDroppedProperties(), property)
        || ServerQuirk.listMatches(entity.getOmittedProperties(), property);
  }

  /**
   * The URL the browser fetches a stored image from, versioned by its last
   * modification so an updated image escapes the browser cache. Null when the
   * row has no image.
   *
   * @param imageFileId identifier of the stored file, or null
   * @param id identifier of the registration row
   * @param imageLastModified cache-busting version
   * @return the image URL, or null when no image exists
   */
  private String getImageUrl(Long imageFileId, Long id, long imageLastModified) {
    if (imageFileId == null || imageFileId.longValue() == 0) {
      return null;
    }
    return String.format("/caldav/rest/servers/%s/image?v=%s", id, imageLastModified);
  }

  /**
   * Maps a DTO to an entity, provider name excluded — it is always decided by
   * the storage method doing the write.
   *
   * @param server DTO to map
   * @return the entity
   */
  private CaldavServerEntity toEntity(CaldavServer server) {
    CaldavServerEntity entity = new CaldavServerEntity();
    entity.setId(server.getId() <= 0 ? null : server.getId());
    entity.setName(server.getName());
    entity.setDescription(server.getDescription());
    entity.setServerUrl(server.getServerUrl());
    entity.setActive(server.isActive());
    entity.setIcon(server.getIcon());
    entity.setImageFileId(server.getImageFileId());
    entity.setAnswerLinksInCopy(server.isAnswerLinksInCopy());
    entity.setIgnoredProperties(server.getIgnoredProperties());
    entity.setDroppedProperties(server.getDroppedProperties());
    entity.setOmittedProperties(server.getOmittedProperties());
    return entity;
  }
}

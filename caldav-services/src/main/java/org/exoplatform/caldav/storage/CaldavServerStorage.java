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
import java.util.Comparator;
import java.util.Date;
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
   * Mapping, not judgement: each entry is named, counted and matched to the
   * catalogue entry that describes it, and every entry the catalogue does not
   * describe still comes through with its own property name — an administrator
   * is never blocked by the list being incomplete. Whether an entry is
   * <i>excused</i> is deliberately left false here and decided by the service,
   * which is the layer that knows the deployment-wide fallback.
   *
   * @param summary the stored summary, may be null
   * @return the observed behaviours, largest counts first, never null
   */
  private List<ObservedQuirk> observedQuirks(String summary) {
    return ServerQuirkSummary.parse(summary)
                             .entrySet()
                             .stream()
                             .sorted(Comparator.<Map.Entry<Observation, Long>, Long>comparing(Map.Entry::getValue)
                                               .reversed()
                                               .thenComparing(entry -> entry.getKey().property()))
                             .map(entry -> observedQuirk(entry.getKey(), entry.getValue()))
                             .toList();
  }

  /**
   * One stored observation as the entry the drawer lists.
   *
   * @param observation what was seen
   * @param count how many times
   * @return the entry, carrying the catalogue's identifier when one describes
   *         it and the observed property name alone when none does
   */
  private ObservedQuirk observedQuirk(Observation observation, Long count) {
    return ServerQuirk.describing(observation.property(), observation.direction())
                      .map(quirk -> new ObservedQuirk(quirk.getId(),
                                                      observation.property(),
                                                      observation.direction(),
                                                      quirk.getEffect(),
                                                      count,
                                                      false,
                                                      quirk.getPatterns()))
                      // TOLERATE for a behaviour nothing describes, and never
                      // OMIT: an entry nobody has written a rule for can relax a
                      // comparison, but there is no rule to make eXo leave
                      // anything out of what it writes.
                      .orElseGet(() -> new ObservedQuirk(null,
                                                         observation.property(),
                                                         observation.direction(),
                                                         ServerQuirkEffect.TOLERATE,
                                                         count,
                                                         false,
                                                         List.of(observation.property())));
  }

  /**
   * Adds what a pass saw to a registration's rolling observation summary.
   *
   * <p>
   * Read, merge and write in one transaction, and touching that one column
   * only. This is the sweep talking, not an administrator: routing it through
   * {@link #updateServer} would let a background pass overwrite a name or a URL
   * somebody is editing in the drawer at the same moment.
   *
   * <p>
   * Two passes racing on one server can still lose an increment, and that is
   * accepted: the number answers "does this server always do this, or did it
   * happen once", and no decision is made from its exact value.
   *
   * @param serverId technical identifier of the registration
   * @param increments how many times each behaviour was seen since the last
   *          write
   */
  @Transactional
  public void mergeObservedQuirks(long serverId, Map<Observation, Long> increments) {
    if (increments == null || increments.isEmpty()) {
      return;
    }
    caldavServerDAO.findById(serverId).ifPresent(entity -> {
      Map<Observation, Long> merged = ServerQuirkSummary.parse(entity.getObservedQuirks());
      increments.forEach((observation, seen) -> merged.merge(observation, seen, Long::sum));
      entity.setObservedQuirks(ServerQuirkSummary.format(merged));
      caldavServerDAO.save(entity);
    });
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

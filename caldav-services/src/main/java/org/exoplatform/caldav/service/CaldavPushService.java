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
package org.exoplatform.caldav.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.ics.IcsMerger;
import org.exoplatform.caldav.ics.IcsWriter;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Writes eXo's space events into the user's remote calendar, server-side.
 *
 * <p>
 * This is the half of the migration that stops the browser from wielding the
 * user's CalDAV credentials to build and PUT iCalendar objects. What changes
 * for the user is nothing; what changes for us is that every write becomes
 * assertable — a test can now claim "the mirror calendar exists after we said
 * we created it", which was precisely the assertion nobody could write while
 * the work happened in a page.
 */
@Service
public class CaldavPushService {

  /** The collection eXo copies space events into, derived from this slug alone. */
  public static final String     MIRROR_COLLECTION_SLUG = "exo-meetings";

  /** How the collection presents itself in the user's own calendar client. */
  public static final String     MIRROR_DISPLAY_NAME    = "eXo Meetings";

  /** No CalDAV account is connected, so there is nowhere to write. */
  public static final String     NOT_CONNECTED          = "caldav.error.noCalendar";

  /** The stored credentials were rejected upstream. */
  public static final String     CREDENTIALS            = "caldav.error.credentials";

  /** Someone else changed the object since we last read it. */
  public static final String     CONFLICT               = "caldav.error.conflict";

  /** The write failed for a reason the user cannot act on individually. */
  public static final String     SAVE                   = "caldav.error.save";

  /** The server would not create a collection and no calendar could be adopted. */
  public static final String     CREATION_REFUSED       = "calendarCreationRefused";

  private static final Log       LOG                    = ExoLogger.getLogger(CaldavPushService.class);

  @Autowired
  private CalDavClient           calDavClient;

  @Autowired
  private CaldavConnectorStorage caldavConnectorStorage;

  @Autowired
  private CaldavSyncStorage      caldavSyncStorage;

  @Autowired
  private IcsWriter              icsWriter;

  @Autowired
  private IcsMerger              icsMerger;

  /**
   * Writes one event into the user's mirror calendar, creating the collection
   * and the mapping row if this is the first time.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param event the event to copy, with identities already resolved
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the write cannot be carried out
   */
  public ObjectSync pushEvent(long userIdentityId, IcsEvent event) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    CalDavEndpoint endpoint = endpointOf(settings);
    MirrorTarget mirror = ensureMirror(userIdentityId, settings, endpoint);
    CalendarSync pair = mirrorPair(userIdentityId, settings, mirror);

    String ics = icsWriter.write(event);
    ObjectSync known = caldavSyncStorage.getObjectByUid(pair.getId(), event.getUid());
    String href = known != null && StringUtils.isNotBlank(known.getRemoteHref()) ? known.getRemoteHref()
                                                                                : objectHref(mirror.href(), event.getUid());
    PutResult result = write(endpoint, settings, href, ics, known);
    if (result.preconditionFailed()) {
      // Someone else wrote the object between our read and our write. Never
      // retried blindly: the whole point of the conditional write is that the
      // caller decides what to do about a concurrent edit.
      throw new CaldavPushException(CONFLICT, "The calendar object at " + href + " changed since it was last read");
    }
    ObjectSync mapping = known == null ? new ObjectSync() : known;
    mapping.setCalendarSyncId(pair.getId());
    mapping.setIcsUid(event.getUid());
    mapping.setRemoteHref(href);
    mapping.setEtag(result.etag());
    mapping.setPushedHash(hashOf(ics));
    mapping.setLastSync(new Date());
    return caldavSyncStorage.saveObject(mapping);
  }

  /**
   * Removes one event's object from the mirror calendar.
   *
   * <p>
   * A deletion whose object is already gone is a success, not a failure: the
   * end state the caller asked for is the end state that holds.
   *
   * @param userIdentityId identity of the user
   * @param icsUid the iCalendar UID of the event to remove
   * @throws CaldavPushException when the deletion cannot be carried out
   */
  public void deleteEvent(long userIdentityId, String icsUid) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    CalendarSync pair = existingMirrorPair(userIdentityId, settings);
    if (pair == null) {
      return;
    }
    ObjectSync known = caldavSyncStorage.getObjectByUid(pair.getId(), icsUid);
    if (known == null || StringUtils.isBlank(known.getRemoteHref())) {
      return;
    }
    try {
      calDavClient.deleteObject(endpointOf(settings),
                               known.getRemoteHref(),
                               known.getEtag(),
                               settings.getUsername(),
                               settings.getPassword());
    } catch (CalDavAuthenticationException e) {
      throw new CaldavPushException(CREDENTIALS, "The stored CalDAV credentials were rejected", e);
    } catch (CalDavException e) {
      throw new CaldavPushException(SAVE, "The calendar object could not be removed", e);
    }
    caldavSyncStorage.saveObject(cleared(known));
  }

  /**
   * The collection space events are copied into, creating it when it does not
   * exist yet.
   *
   * <p>
   * Ported from the browser connector, including the parts that look
   * redundant and are not. The path is derived from the slug alone, with
   * nothing random in it, so asking twice for the same calendar means asking
   * for the same collection — a random suffix made every request a different
   * one, and a user who disconnected and reconnected collected a new calendar
   * on the server each time.
   *
   * @param userIdentityId identity of the user
   * @return where the copies go, and whether an existing calendar was adopted
   * @throws CaldavPushException when no destination can be established
   */
  public MirrorTarget ensureMirror(long userIdentityId) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    return ensureMirror(userIdentityId, settings, endpointOf(settings));
  }

  /**
   * The mirror lifecycle, once the account and endpoint are known.
   *
   * @param userIdentityId identity of the user
   * @param settings the user's connected account
   * @param endpoint the resolved server endpoint
   * @return the destination
   */
  private MirrorTarget ensureMirror(long userIdentityId, CaldavUserSetting settings, CalDavEndpoint endpoint) {
    String home = calDavClient.discoverCalendarHome(endpoint, settings.getUsername(), settings.getPassword());
    List<CalendarCollection> calendars = calDavClient.listCalendars(endpoint,
                                                                    home,
                                                                    settings.getUsername(),
                                                                    settings.getPassword());
    String wanted = collectionHref(home, MIRROR_COLLECTION_SLUG);
    Optional<CalendarCollection> existing = findMirror(calendars, settings.getMirrorCalendarHref(), wanted);
    if (existing.isPresent()) {
      caldavConnectorStorage.saveMirrorCalendarHref(existing.get().href(), userIdentityId);
      return new MirrorTarget(existing.get().href(), false, existing.get().displayName());
    }

    MkCalendarResult creation = calDavClient.mkCalendar(endpoint,
                                                        wanted,
                                                        MIRROR_DISPLAY_NAME,
                                                        null,
                                                        settings.getUsername(),
                                                        settings.getPassword());
    // The status is never taken as proof. BlueMind answers 201 while creating
    // nothing when a request omits the supported component set, and that
    // false success cost three rounds of wrong diagnosis — so creation is
    // confirmed by reading the home again, and only that counts.
    Optional<CalendarCollection> created = findMirror(calDavClient.listCalendars(endpoint,
                                                                                 home,
                                                                                 settings.getUsername(),
                                                                                 settings.getPassword()),
                                                      null,
                                                      wanted);
    if (created.isPresent()) {
      caldavConnectorStorage.saveMirrorCalendarHref(created.get().href(), userIdentityId);
      return new MirrorTarget(created.get().href(), false, created.get().displayName());
    }

    LOG.debug("MKCALENDAR at {} did not produce a collection (status {}); falling back to adoption",
              wanted,
              creation.status());
    return adopt(userIdentityId, calendars);
  }

  /**
   * Takes an existing calendar as the destination, because the server would
   * not create one.
   *
   * <p>
   * Only for the mirror, and deliberately: a space event is a copy the user
   * did not ask to be filed anywhere in particular, so putting it in a
   * calendar they already had is a compromise they can see and undo. The same
   * fallback for a personal calendar would be corruption dressed as
   * resilience, which is why PR7 refuses it there.
   *
   * @param userIdentityId identity of the user
   * @param calendars the calendars the account holds
   * @return the adopted destination
   * @throws CaldavPushException when the account holds no calendar at all
   */
  private MirrorTarget adopt(long userIdentityId, List<CalendarCollection> calendars) {
    if (calendars.isEmpty()) {
      throw new CaldavPushException(CREATION_REFUSED,
                                    "The server refused to create a calendar and the account holds none to adopt");
    }
    CalendarCollection adopted = calendars.get(0);
    caldavConnectorStorage.saveMirrorCalendarHref(adopted.href(), userIdentityId);
    return new MirrorTarget(adopted.href(), true, adopted.displayName());
  }

  /**
   * The mirror among the calendars a server enumerates, so that asking for it
   * twice never produces a second one.
   *
   * <p>
   * Two signals, in decreasing order of confidence: the stored href, which is
   * the identity of the collection and the only one that survives a rename;
   * and the path the slug derives, which survives a disconnect — the setting
   * does not — because it depends on nothing but the slug.
   *
   * @param calendars what the server lists
   * @param storedHref the href recorded for this user, possibly absent
   * @param derivedHref the path the slug produces under the account's home
   * @return the mirror, if it is there
   */
  private Optional<CalendarCollection> findMirror(List<CalendarCollection> calendars,
                                                  String storedHref,
                                                  String derivedHref) {
    String stored = CaldavSyncStorage.canonicalHref(storedHref);
    String derived = CaldavSyncStorage.canonicalHref(derivedHref);
    return calendars.stream()
                    .filter(calendar -> {
                      String href = CaldavSyncStorage.canonicalHref(calendar.href());
                      return href != null && (href.equals(stored) || href.equals(derived));
                    })
                    .findFirst();
  }

  /**
   * The pair row backing the mirror, created on first use.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @param mirror the destination
   * @return the pair
   */
  private CalendarSync mirrorPair(long userIdentityId, CaldavUserSetting settings, MirrorTarget mirror) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> mirrors = caldavSyncStorage.getPairsByOrigin(userIdentityId, serverId, SyncOrigin.MIRROR);
    if (!mirrors.isEmpty()) {
      // More than one would mean the database could not stop it — its anchor
      // is null and no unique index covers NULL rows — so it is worth saying
      // rather than silently working on the first.
      if (mirrors.size() > 1) {
        LOG.warn("User {} holds {} mirror pairs on server {}; using the first", userIdentityId, mirrors.size(), serverId);
      }
      CalendarSync pair = mirrors.get(0);
      if (!StringUtils.equals(pair.getRemoteHref(), CaldavSyncStorage.canonicalHref(mirror.href()))) {
        pair.setRemoteHref(mirror.href());
        return caldavSyncStorage.savePair(pair);
      }
      return pair;
    }
    CalendarSync pair = new CalendarSync();
    pair.setUserIdentityId(userIdentityId);
    pair.setServerId(serverId);
    pair.setRemoteHref(mirror.href());
    pair.setOrigin(SyncOrigin.MIRROR);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return caldavSyncStorage.savePair(pair);
  }

  /**
   * The mirror pair, without creating anything.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @return the pair, or null when none exists yet
   */
  private CalendarSync existingMirrorPair(long userIdentityId, CaldavUserSetting settings) {
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> mirrors = caldavSyncStorage.getPairsByOrigin(userIdentityId, serverId, SyncOrigin.MIRROR);
    return mirrors.isEmpty() ? null : mirrors.get(0);
  }

  /**
   * Writes the object, conditionally in both directions.
   *
   * <p>
   * A first push is conditional on the object <i>not</i> existing, so two
   * pushes racing cannot both believe they created it; a later push is
   * conditional on the ETag we last saw, so a concurrent edit surfaces as a
   * 412 rather than as a silent overwrite. When the object already exists and
   * holds content another client wrote, what is sent is the merge of the two,
   * never a replacement.
   *
   * @param endpoint the resolved server endpoint
   * @param settings the connected account
   * @param href where the object lives
   * @param ics the object this engine built
   * @param known the mapping row, or null on a first push
   * @return the server's answer
   */
  private PutResult write(CalDavEndpoint endpoint,
                          CaldavUserSetting settings,
                          String href,
                          String ics,
                          ObjectSync known) {
    try {
      if (known == null || StringUtils.isBlank(known.getEtag())) {
        return calDavClient.putObject(endpoint, href, ics, settings.getUsername(), settings.getPassword());
      }
      CalendarObject existing = calDavClient.fetchObject(endpoint, href, settings.getUsername(), settings.getPassword());
      String merged = existing == null || StringUtils.isBlank(existing.calendarData()) ? ics
                                                                              : icsMerger.merge(existing.calendarData(), ics, false);
      return calDavClient.updateObject(endpoint,
                                       href,
                                       merged,
                                       known.getEtag(),
                                       settings.getUsername(),
                                       settings.getPassword());
    } catch (CalDavAuthenticationException e) {
      throw new CaldavPushException(CREDENTIALS, "The stored CalDAV credentials were rejected", e);
    } catch (CalDavException e) {
      throw new CaldavPushException(SAVE, "The calendar object could not be written to " + href, e);
    }
  }

  /**
   * The user's connected account, or a refusal naming what is missing.
   *
   * @param userIdentityId identity of the user
   * @return the account
   */
  private CaldavUserSetting connectedSettings(long userIdentityId) {
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (settings == null || StringUtils.isBlank(settings.getUsername()) || StringUtils.isBlank(settings.getPassword())) {
      throw new CaldavPushException(NOT_CONNECTED, "User " + userIdentityId + " has no connected CalDAV account");
    }
    return settings;
  }

  /**
   * The endpoint the account's server resolves to.
   *
   * @param settings the connected account
   * @return the endpoint
   */
  private CalDavEndpoint endpointOf(CaldavUserSetting settings) {
    try {
      return calDavClient.endpoint(settings.getServerId(), settings.getUsername());
    } catch (CalDavException e) {
      throw new CaldavPushException(NOT_CONNECTED, "No CalDAV server resolves for this account", e);
    }
  }

  /**
   * A child collection's href under a home.
   *
   * @param home the calendar home
   * @param slug the collection name
   * @return the collection href, with its trailing slash
   */
  private String collectionHref(String home, String slug) {
    return StringUtils.appendIfMissing(home, "/") + slug + "/";
  }

  /**
   * Where one event's object lives inside a collection. The filename
   * convention the browser push has always used, kept so that objects written
   * before this migration are found rather than duplicated.
   *
   * @param collectionHref the collection
   * @param icsUid the iCalendar UID
   * @return the object href
   */
  private String objectHref(String collectionHref, String icsUid) {
    return StringUtils.appendIfMissing(collectionHref, "/") + icsUid + ".ics";
  }

  /**
   * The mapping with everything the remote side owned cleared, kept as the
   * record that this event was once pushed.
   *
   * @param mapping the mapping to clear
   * @return the same mapping, without its remote identity
   */
  private ObjectSync cleared(ObjectSync mapping) {
    mapping.setRemoteHref(null);
    mapping.setEtag(null);
    mapping.setPushedHash(null);
    mapping.setLastSync(new Date());
    return mapping;
  }

  /**
   * A stable digest of what was pushed, so a later read can tell "the server
   * changed this" from "this is exactly what we wrote".
   *
   * @param ics the object that was written
   * @return the digest, hexadecimal
   */
  private String hashOf(String ics) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(ics.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }
}

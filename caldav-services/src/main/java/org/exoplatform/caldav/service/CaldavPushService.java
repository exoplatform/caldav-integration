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
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.RemoteEvent;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
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

  /**
   * The name this add-on registers itself under as an agenda remote provider,
   * in caldav-configuration.xml. It has to match that declaration exactly:
   * agenda resolves the provider by name when it stores the mapping between
   * an eXo event and the object written for it.
   */
  public static final String     CONNECTOR_NAME = "agenda.caldavCalendar";

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

  @Autowired
  private AgendaEventService     agendaEventService;

  @Autowired
  private AgendaEventIcsMapper   agendaEventIcsMapper;

  @Autowired
  private AgendaRemoteEventService agendaRemoteEventService;

  @Autowired
  private AgendaCalendarService  agendaCalendarService;

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
    return pushEvent(userIdentityId, event, null);
  }

  /**
   * Writes one event into the user's mirror calendar, recording which eXo
   * event the resulting object stands for.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param event the event to copy, with identities already resolved
   * @param localEventId the agenda event this object stands for, or null when
   *          the caller has none — the read half fills it in later
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the write cannot be carried out
   */
  public ObjectSync pushEvent(long userIdentityId, IcsEvent event, Long localEventId) {
    return pushEvent(userIdentityId, event, localEventId, false);
  }

  /**
   * Writes one event into the user's mirror calendar, overwriting a drifted
   * copy or not.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param event the event to copy, with identities already resolved
   * @param localEventId the agenda event this object stands for, or null
   * @param overwrite true to write without the conditional guard
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the write cannot be carried out
   */
  private ObjectSync pushEvent(long userIdentityId, IcsEvent event, Long localEventId, boolean overwrite) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    CalDavEndpoint endpoint = endpointOf(settings);
    MirrorTarget mirror = ensureMirror(userIdentityId, settings, endpoint);
    return writeInto(userIdentityId, mirrorPair(userIdentityId, settings, mirror), event, localEventId, overwrite);
  }

  /**
   * The mapping row for an iCalendar UID, in whichever collection holds it.
   *
   * <p>
   * The mirror is searched first because most copies live there, but a
   * personal calendar's collection holds its own, and one UID belongs to at
   * most one of them.
   *
   * @param userIdentityId identity of the user
   * @param settings the connected account
   * @param icsUid the iCalendar UID looked for
   * @return the mapping row, or null when no collection holds it
   */
  private ObjectSync objectAnywhere(long userIdentityId, CaldavUserSetting settings, String icsUid) {
    CalendarSync mirror = existingMirrorPair(userIdentityId, settings);
    if (mirror != null) {
      ObjectSync inMirror = caldavSyncStorage.getObjectByUid(mirror.getId(), icsUid);
      if (inMirror != null) {
        return inMirror;
      }
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    for (CalendarSync pair : caldavSyncStorage.getPairs(userIdentityId, serverId)) {
      // Objects.equals, not ==: these identifiers are Long, so == compares
      // references and answers false for every value a real database issues.
      // Written as ==, the mirror is simply searched a second time — harmless
      // today, and the same mistake that cost a deletion elsewhere.
      if (mirror != null && Objects.equals(pair.getId(), mirror.getId())) {
        continue;
      }
      ObjectSync known = caldavSyncStorage.getObjectByUid(pair.getId(), icsUid);
      if (known != null) {
        return known;
      }
    }
    return null;
  }

  /**
   * The event's calendar, when it is one of this user's own.
   *
   * <p>
   * The question the routing turns on. An event of the user's own calendar is
   * theirs, and belongs in that calendar's collection or nowhere; anything
   * else — a space meeting they attend — belongs in the mirror, which exists
   * precisely because a space calendar has no counterpart on a personal
   * account.
   *
   * @param event the agenda event being pushed
   * @param userIdentityId identity of the user
   * @return the calendar when the user owns it, null otherwise
   */
  private Calendar ownCalendarOf(Event event, long userIdentityId) {
    Calendar calendar = agendaCalendarService.getCalendarById(event.getCalendarId());
    return calendar != null && calendar.getOwnerId() == userIdentityId ? calendar : null;
  }

  /**
   * The collection bound to one of this user's own calendars.
   *
   * <p>
   * Answers null in two cases, and neither sends the event to the mirror —
   * that decision belongs to the caller now, which is what makes the refusal
   * enforceable rather than merely documented. Either the calendar carries no
   * anchor, so nothing stable identifies it; or the server refused to create
   * its collection, and outbound stays unavailable for that calendar until it
   * allows one.
   *
   * @param calendar one of the user's own calendars, already loaded
   * @param userIdentityId identity of the user
   * @return the bound collection, or null when there is none to write into
   */
  private CalendarSync personalPairFor(Calendar calendar, long userIdentityId) {
    if (StringUtils.isBlank(calendar.getSyncUid())) {
      return null;
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    long serverId = settings == null || settings.getServerId() == null ? 0L : settings.getServerId();
    CalendarSync pair = caldavSyncStorage.getPairByLocalCalendar(userIdentityId, serverId, calendar.getSyncUid());
    if (pair == null || pair.getStatus() != CalendarSyncStatus.ACTIVE) {
      LOG.debug("Personal calendar {} has no usable collection; its events are not copied out", calendar.getSyncUid());
      return null;
    }
    return pair;
  }

  /**
   * Writes one event into a collection this user is already bound to.
   *
   * <p>
   * The same write for the space mirror and for a personal calendar: which
   * collection an event belongs in is the caller's decision, and everything
   * that follows — the conditional write, the merge, the mapping row — is the
   * same regardless. Keeping one path means a defect fixed for one is fixed
   * for both, which was not true while the browser held two of them.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param pair the collection to write into
   * @param event the event to copy, with identities already resolved
   * @param localEventId the agenda event this object stands for, or null
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the write cannot be carried out
   */
  public ObjectSync writeInto(long userIdentityId, CalendarSync pair, IcsEvent event, Long localEventId) {
    return writeInto(userIdentityId, pair, event, localEventId, false);
  }

  /**
   * Writes one event into a collection, overwriting a drifted copy or not.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param pair the collection to write into
   * @param event the event to copy, with identities already resolved
   * @param localEventId the agenda event this object stands for, or null
   * @param overwrite true to write without the conditional guard, which only
   *          a repair may ask for
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the write cannot be carried out
   */
  private ObjectSync writeInto(long userIdentityId,
                               CalendarSync pair,
                               IcsEvent event,
                               Long localEventId,
                               boolean overwrite) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    CalDavEndpoint endpoint = endpointOf(settings);

    String ics = icsWriter.write(event);
    ObjectSync known = caldavSyncStorage.getObjectByUid(pair.getId(), event.getUid());
    // Only when the destination holds no mapping, which is a create — or a
    // move. The lookup above is scoped to one collection, so an event that has
    // just changed calendar looks new here while its old mapping, and its old
    // object, are still sitting in the calendar it left.
    ObjectSync leftBehind = known == null ? mappingElsewhere(userIdentityId, pair, event.getUid(), localEventId) : null;
    String href = known != null && StringUtils.isNotBlank(known.getRemoteHref()) ? known.getRemoteHref()
                                                                                : objectHref(pair.getRemoteHref(),
                                                                                             event.getUid());
    PutResult result = write(endpoint, settings, href, ics, known, overwrite);
    if (result.preconditionFailed()) {
      // Someone else wrote the object between our read and our write. Never
      // retried blindly: the whole point of the conditional write is that the
      // caller decides what to do about a concurrent edit.
      throw new CaldavPushException(CONFLICT, "The calendar object at " + href + " changed since it was last read");
    }
    ObjectSync mapping = known == null ? new ObjectSync() : known;
    mapping.setCalendarSyncId(pair.getId());
    mapping.setIcsUid(event.getUid());
    // Never cleared once set: a later push that does not know the event id —
    // a sweep, a repair — must not erase the link the first one established.
    if (localEventId != null) {
      mapping.setLocalEventId(localEventId);
    }
    mapping.setRemoteHref(href);
    mapping.setEtag(result.etag());
    mapping.setPushedHash(hashOf(ics));
    mapping.setLastSync(new Date());
    ObjectSync saved = caldavSyncStorage.saveObject(mapping);
    // Last, and only once the destination holds the event: a move that failed
    // here would otherwise take the copy away without having written the new
    // one, which loses the user's event rather than tidying it.
    removeWhatWasLeftBehind(userIdentityId, leftBehind, endpoint, settings);
    return saved;
  }

  /**
   * The mapping of this event in some other collection of the same account.
   *
   * <p>
   * Answers "was this event somewhere else a moment ago?". A mapping is stored
   * per collection, so moving an event between calendars leaves one behind
   * rather than moving it, and the object it points at stays on the server —
   * where it goes on looking like a real event, on the user's phone, in the
   * calendar they took it out of.
   *
   * @param userIdentityId identity of the user
   * @param destination the binding the event is being written into
   * @param icsUid the event's iCalendar identifier
   * @return the mapping it had elsewhere, or null when this is an ordinary
   *         first write
   */
  private ObjectSync mappingElsewhere(long userIdentityId, CalendarSync destination, String icsUid, Long localEventId) {
    for (CalendarSync other : caldavSyncStorage.getPairs(userIdentityId, destination.getServerId())) {
      if (Objects.equals(other.getId(), destination.getId())) {
        continue;
      }
      // By the eXo event first, and the iCalendar UID only after. The UID is
      // supposed to survive a move — it is adopted from agenda's remote-event
      // mapping rather than minted afresh — but that mapping has been lost
      // before in this codebase, and when it is, the push mints a new UID and
      // a search by UID silently finds nothing. The event's own identifier
      // does not depend on any of that.
      ObjectSync elsewhere = localEventId == null ? null
                                                  : caldavSyncStorage.getObjectByEvent(other.getId(), localEventId);
      if (elsewhere == null && StringUtils.isNotBlank(icsUid)) {
        elsewhere = caldavSyncStorage.getObjectByUid(other.getId(), icsUid);
      }
      if (elsewhere != null && StringUtils.isNotBlank(elsewhere.getRemoteHref())) {
        return elsewhere;
      }
    }
    return null;
  }

  /**
   * Removes the copy an event left in the calendar it moved out of.
   *
   * <p>
   * Conditional on the ETag eXo recorded, so a copy someone has since edited on
   * another device is refused rather than destroyed — the user moved an event
   * between their own calendars, which is not consent to discard a change they
   * made elsewhere. A refusal leaves both the object and its mapping alone: a
   * stray eXo still knows about is a much smaller problem than one it has
   * forgotten, and the next write can try again.
   *
   * @param userIdentityId identity of the user, for the log
   * @param leftBehind the mapping in the old collection, may be null
   * @param endpoint where the account lives
   * @param settings the connected account
   */
  private void removeWhatWasLeftBehind(long userIdentityId,
                                       ObjectSync leftBehind,
                                       CalDavEndpoint endpoint,
                                       CaldavUserSetting settings) {
    if (leftBehind == null) {
      return;
    }
    try {
      calDavClient.deleteObject(endpoint,
                                leftBehind.getRemoteHref(),
                                leftBehind.getEtag(),
                                settings.getUsername(),
                                settings.getPassword());
    } catch (RuntimeException e) {
      LOG.warn("The copy user {} left at {} when moving the event could not be removed; it stays, and so does its mapping",
               userIdentityId,
               leftBehind.getRemoteHref(),
               e);
      return;
    }
    caldavSyncStorage.deleteObject(leftBehind.getId());
    LOG.info("The copy left at {} was removed after the event moved to another calendar", leftBehind.getRemoteHref());
  }

  /**
   * Copies one agenda event into the user's mirror calendar.
   *
   * <p>
   * The entry point the browser now calls instead of building iCalendar
   * itself: it hands over an event id, and every decision that used to happen
   * in the page — which identities are addressable, what the object looks
   * like, where it goes, whether the write is conditional — happens here.
   *
   * <p>
   * The event is read through agenda's own service, so its ACL applies: a user
   * who may not see an event cannot have it copied into their calendar by
   * asking for its id.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param eventId the agenda event to copy
   * @param eventUrl absolute link back to the event in eXo
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the event cannot be read or written
   */
  public ObjectSync pushAgendaEvent(long userIdentityId, long eventId, String eventUrl) {
    return pushAgendaEvent(userIdentityId, eventId, eventUrl, false);
  }

  /**
   * Writes the copy of an agenda event again, over whatever now stands in its
   * place on the server.
   *
   * <p>
   * The entry point for repairs, and the only one that writes unconditionally.
   * A conditional write cannot repair anything: the condition is the stored
   * etag, and an object needs repairing exactly when the server's etag has
   * moved away from it — so the guarded path refuses every repair it is asked
   * to make. What makes the overwrite legitimate is that the caller has
   * already read the object, compared it against the eXo event, and decided
   * which copy wins.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param eventId the agenda event whose copy is rebuilt
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the event cannot be read or written
   */
  public ObjectSync rewriteAgendaEvent(long userIdentityId, long eventId) {
    return pushAgendaEvent(userIdentityId, eventId, null, true);
  }

  /**
   * Copies one agenda event into the user's account, overwriting a drifted
   * copy or not.
   *
   * @param userIdentityId identity of the user whose account is written to
   * @param eventId the agenda event to copy
   * @param eventUrl absolute link back to the event in eXo
   * @param overwrite true to write without the conditional guard, which only
   *          a repair may ask for
   * @return the mapping row as it now stands
   * @throws CaldavPushException when the event cannot be read or written
   */
  private ObjectSync pushAgendaEvent(long userIdentityId, long eventId, String eventUrl, boolean overwrite) {
    Event event;
    try {
      // Read in the event's OWN zone, which is what a null argument asks
      // agenda for. Not UTC, and not a viewer's zone.
      //
      // For a timed event any zone gives the same instant, so it looks like a
      // free choice. It is not, because agenda treats an all-day event
      // differently: it re-anchors the covered days at midnight in whatever
      // zone is asked for. Ask for UTC and an all-day event of a user west of
      // Greenwich comes back starting at 20:00 the previous day — and the
      // copy is then written one day early, silently, for exactly the users
      // whose zone made it happen.
      event = agendaEventService.getEventById(eventId, null, userIdentityId);
    } catch (IllegalAccessException e) {
      throw new CaldavPushException(SAVE, "Event " + eventId + " is not visible to user " + userIdentityId, e);
    }
    if (event == null) {
      throw new CaldavPushException(SAVE, "Event " + eventId + " does not exist");
    }
    long seriesId = event.getParentId() > 0 ? event.getParentId() : event.getId();
    String icsUid = adoptOrMintUid(seriesId, userIdentityId);
    IcsEvent icsEvent = agendaEventIcsMapper.toIcsEvent(event, icsUid, eventUrl, userIdentityId);

    // Where an event goes is decided from the calendar it lives in, not from
    // the caller. An event of one of the user's own calendars belongs in that
    // calendar's own collection; anything else — a space event the user
    // attends — belongs in the mirror, which exists precisely because a space
    // calendar has no counterpart on a personal account.
    Calendar own = ownCalendarOf(event, userIdentityId);
    if (own != null) {
      CalendarSync personal = personalPairFor(own, userIdentityId);
      if (personal == null) {
        // Nothing to write into, and the mirror is not a consolation. A
        // personal event filed among the copies of space meetings is exactly
        // the mixing this refuses to do; outbound stays unavailable for this
        // calendar until it has a collection of its own. Answering null says
        // "nothing was pushed" without pretending it failed.
        LOG.debug("Calendar {} has no usable collection; event {} is not copied out",
                  event.getCalendarId(),
                  event.getId());
        return null;
      }
      return writeInto(userIdentityId, personal, icsEvent, event.getId(), overwrite);
    }
    return pushEvent(userIdentityId, icsEvent, event.getId(), overwrite);
  }

  /**
   * The iCalendar UID this event's object is written under: the one agenda
   * already recorded, or a new one recorded now.
   *
   * <p>
   * This is where events pushed before the migration are adopted rather than
   * duplicated. The browser stored a remote identifier on every event it
   * pushed, and that identifier is the UID of the object sitting on the
   * server. Minting a fresh one here would write a second object for every
   * event a migrated user already has — and since migrated users are exactly
   * the ones with events on the server, that is not an edge case but their
   * normal first run.
   *
   * @param seriesId the agenda event, or its parent for an occurrence — a
   *          series and its overrides share one UID
   * @param userIdentityId identity of the user
   * @return the UID to write under
   */
  private String adoptOrMintUid(long seriesId, long userIdentityId) {
    RemoteEvent known = agendaRemoteEventService.findRemoteEvent(seriesId, userIdentityId);
    if (known != null && StringUtils.isNotBlank(known.getRemoteId())) {
      return known.getRemoteId();
    }
    String minted = UUID.randomUUID().toString();
    RemoteEvent remoteEvent = new RemoteEvent();
    remoteEvent.setEventId(seriesId);
    remoteEvent.setIdentityId(userIdentityId);
    remoteEvent.setRemoteId(minted);
    // Naming the provider is what makes agenda keep this row. Without it —
    // and this connector left it unset — saveRemoteEvent reads the record as
    // an instruction to DELETE the mapping rather than store it, so the
    // identifier minted here was thrown away the moment it was handed over.
    // Every later push then found nothing, minted a fresh identifier, and
    // wrote a second object: an edit duplicated the meeting and orphaned the
    // original, and a delete looked for an identifier the server had never
    // seen. The provider itself already exists — this add-on registers it as
    // a RemoteProviderDefinitionPlugin — so naming it is all that was missing.
    remoteEvent.setRemoteProviderName(CONNECTOR_NAME);
    // Recorded before the write, not after: an interrupted push leaves an
    // identifier pointing at an object that may or may not exist, which the
    // next push reconciles. Recording it afterwards would leave a written
    // object nothing points at, which nothing ever reconciles.
    agendaRemoteEventService.saveRemoteEvent(seriesId, remoteEvent, userIdentityId);
    return minted;
  }

  /**
   * Removes one event's object from wherever this connector wrote it.
   *
   * <p>
   * Every collection the user has, not only the mirror. An event of one of
   * their own calendars is written into that calendar's collection, so a
   * removal that looked only in the mirror would find nothing and quietly
   * succeed — leaving the object on the server for ever, in the one place the
   * user is most likely to notice it. A copy is written in one place and has
   * to be removable from that same place.
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
    ObjectSync known = objectAnywhere(userIdentityId, settings, icsUid);
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
   * Removes one occurrence from a series without removing the series.
   *
   * <p>
   * RFC 4791 puts every component of a series in one object, so a deletion here
   * is a rewrite: the override carrying that instance is dropped and the master
   * gains an EXDATE for it. Deleting the object instead would cancel every
   * meeting of the series to cancel one.
   *
   * <p>
   * The rewrite is conditional on the ETag last seen, so a series someone else
   * changed in the meantime surfaces as a conflict rather than being
   * overwritten with a stale copy.
   *
   * @param userIdentityId identity of the user
   * @param icsUid the iCalendar UID of the series
   * @param occurrence the instance to exclude
   * @throws CaldavPushException when the rewrite cannot be carried out
   */
  public void excludeOccurrence(long userIdentityId, String icsUid, Instant occurrence) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    CalendarSync pair = existingMirrorPair(userIdentityId, settings);
    if (pair == null) {
      return;
    }
    ObjectSync known = caldavSyncStorage.getObjectByUid(pair.getId(), icsUid);
    if (known == null || StringUtils.isBlank(known.getRemoteHref())) {
      return;
    }
    CalDavEndpoint endpoint = endpointOf(settings);
    try {
      CalendarObject existing = calDavClient.fetchObject(endpoint,
                                                         known.getRemoteHref(),
                                                         settings.getUsername(),
                                                         settings.getPassword());
      if (existing == null || StringUtils.isBlank(existing.calendarData())) {
        return;
      }
      String rewritten = icsMerger.excludeOccurrence(existing.calendarData(), occurrence);
      if (rewritten == null) {
        // Nothing left in the object: the last instance was the one excluded.
        calDavClient.deleteObject(endpoint,
                                  known.getRemoteHref(),
                                  known.getEtag(),
                                  settings.getUsername(),
                                  settings.getPassword());
        caldavSyncStorage.saveObject(cleared(known));
        return;
      }
      PutResult result = calDavClient.updateObject(endpoint,
                                                   known.getRemoteHref(),
                                                   rewritten,
                                                   known.getEtag(),
                                                   settings.getUsername(),
                                                   settings.getPassword());
      if (result.preconditionFailed()) {
        throw new CaldavPushException(CONFLICT, "The series at " + known.getRemoteHref() + " changed since it was read");
      }
      known.setEtag(result.etag());
      known.setPushedHash(hashOf(rewritten));
      known.setLastSync(new Date());
      caldavSyncStorage.saveObject(known);
    } catch (CalDavAuthenticationException e) {
      throw new CaldavPushException(CREDENTIALS, "The stored CalDAV credentials were rejected", e);
    } catch (CalDavException e) {
      throw new CaldavPushException(SAVE, "The occurrence could not be excluded", e);
    }
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
   * <p>
   * This reads it; it never creates one. Nothing configured is an answer, not
   * a reason to make a calendar on someone's account — the settings screen
   * asks this question on every render.
   *
   * <p>
   * The name comes from the server on each call rather than from anything
   * stored: the user may have renamed the calendar in their own client, and
   * the screen showing a stale name is how a destination stops being
   * recognisable as the one it names.
   *
   * Recorded href or not, the account is asked. Disconnecting clears the
   * href while leaving the collection on the server, so a reconnected account
   * has a destination it does not remember — and answering "none" there made
   * eXo offer to create a calendar that already existed, and invite a second
   * one beside it. The collection eXo creates lives at a path eXo derives, so
   * it can be recognised without having been remembered: the same second
   * candidate {@link #ensureMirror} adopts. The two now answer alike, where
   * before creation adopted what this reported absent.
   *
   * A destination that was recorded and cannot be reached still raises: the
   * user chose it, so the screen must say the account is unreachable rather
   * than quietly report having no destination. With nothing recorded there is
   * no such claim to keep, and an unreachable server answers null.
   *
   * @param userIdentityId identity of the user
   * @return the destination and its current name, or null when the account
   *         has none — neither the one recorded nor one at the derived path
   */
  public MirrorTarget currentMirror(long userIdentityId) {
    CaldavUserSetting settings = connectedSettings(userIdentityId);
    boolean recorded = StringUtils.isNotBlank(settings.getMirrorCalendarHref());
    try {
      return lookUpMirror(settings);
    } catch (RuntimeException e) {
      if (recorded) {
        throw e;
      }
      // Nothing was recorded, so nothing is being claimed lost: the account
      // simply has no destination as far as anyone knew, and an unreachable
      // server cannot turn that into a failure. Before this method looked for
      // a collection it had not recorded, the same account answered "none"
      // without a request at all — an error here would make every render of
      // the settings page report a problem the user does not have.
      LOG.debug("could not look for a destination calendar of user {}", userIdentityId, e);
      return null;
    }
  }

  /**
   * Asks the account for the calendar holding the copies.
   *
   * @param settings the connected account
   * @return the destination and its current name, or null when the account
   *         holds neither the recorded collection nor one at the derived path
   */
  private MirrorTarget lookUpMirror(CaldavUserSetting settings) {
    CalDavEndpoint endpoint = endpointOf(settings);
    String home = calDavClient.discoverCalendarHome(endpoint, settings.getUsername(), settings.getPassword());
    List<CalendarCollection> calendars = calDavClient.listCalendars(endpoint,
                                                                   home,
                                                                   settings.getUsername(),
                                                                   settings.getPassword());
    return findMirror(calendars, settings.getMirrorCalendarHref(), collectionHref(home, MIRROR_COLLECTION_SLUG))
                                                                                                               .map(collection -> new MirrorTarget(collection.href(),
                                                                                                                                                   false,
                                                                                                                                                   collection.displayName()))
                                                                                                               .orElse(null);
  }

  /**
   * Establishes the calendar the copies are written into, creating it when it
   * is not there.
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
                          ObjectSync known,
                          boolean overwrite) {
    try {
      if (known == null || StringUtils.isBlank(known.getEtag())) {
        if (overwrite) {
          // A repair with nothing recorded against this UID. The create-only
          // write below would send If-None-Match: * and be refused, because
          // the object it is trying to create is usually already there —
          // under a href this connector has lost track of. That is precisely
          // the state a repair exists to leave: forcing the write puts the
          // object back under the href being repaired and re-establishes the
          // mapping, where the create refused for ever.
          return calDavClient.overwriteObject(endpoint, href, ics, settings.getUsername(), settings.getPassword());
        }
        return calDavClient.putObject(endpoint, href, ics, settings.getUsername(), settings.getPassword());
      }
      CalendarObject existing = calDavClient.fetchObject(endpoint, href, settings.getUsername(), settings.getPassword());
      String merged = existing == null || StringUtils.isBlank(existing.calendarData()) ? ics
                                                                              : icsMerger.merge(existing.calendarData(), ics, false);
      if (overwrite) {
        // No precondition at all — and it has to be neither of the two the
        // client otherwise sends. The guard protects against clobbering a
        // change nobody has looked at, and a repair is the one case where
        // somebody has: verification read this object, compared it, and
        // decided the eXo copy is the one to keep. An If-Match would refuse
        // the write precisely when the object has drifted, which is the only
        // time a repair is attempted; an If-None-Match would refuse it
        // because the object exists, which it always does here.
        return calDavClient.overwriteObject(endpoint, href, merged, settings.getUsername(), settings.getPassword());
      }
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

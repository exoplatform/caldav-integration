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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.CalendarObjectWriters;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.ics.IcsMerger;
import org.exoplatform.caldav.ics.IcsParseException;
import org.exoplatform.caldav.ics.IcsParser;
import org.exoplatform.caldav.ics.IcsText;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Lands an invitation a user answered from a mail in their calendar (EXO-90848):
 * the event the mail describes ends up on the user's CalDAV account and in the
 * eXo calendar standing for it, under the identity the inbound sweep knows it
 * by, carrying the answer the user gave.
 *
 * <h2>The sweep's own path, never a second one</h2>
 *
 * <p>
 * The eXo event is created by {@link CaldavInboundService#importInto} reading
 * the object back from the server, exactly as the sweep would have on its next
 * pass: the same mapping row ({@code ObjectSync}: binding, UID, href, ETag),
 * the same remote identity agenda records, the same recurrence and override
 * handling. What this service adds is only what the sweep cannot know — that
 * the user answered — and, when the server does not yet hold the object,
 * putting it there first. A mail server that files invitations in the calendar
 * itself (BlueMind, Google) already holds it: it is then read, not written, and
 * the user ends up with one event, not two.
 *
 * <h2>Where the answer lives</h2>
 *
 * <p>
 * The answer is recorded in agenda like any answer given in eXo, through
 * {@link AgendaEventAttendeeService#sendEventResponse}, so the one listener
 * that carries eXo answers onto the server's copy carries this one too. The
 * import records the user as having accepted — its reading of a calendar of
 * their own — so an answer that is "accepted" would never move and never be
 * carried; that one is pushed here, through the same {@code pushAnswer}, which
 * writes nothing when the copy already says it.
 *
 * <h2>What is trusted</h2>
 *
 * <p>
 * The object is the sender's. Only the answering user's own bindings are
 * consulted, never the mirror ledger: a UID this deployment minted for one of
 * its own meetings is refused before anything is written, because answering
 * through it would act on an event the mail did not show. The addresses the
 * answer is written under are the user's own account's and the mailbox the mail
 * was read for; no attendee line is resolved to anybody.
 *
 * <h2>Left for later</h2>
 *
 * <p>
 * A message about one occurrence of a series (a lone RECURRENCE-ID) is refused:
 * landing it means splicing an override into a copy and answering an instance,
 * which the engine does for eXo answers and this path does not yet. A newer
 * revision of an event the user already holds is written over the server's copy
 * when the mail's SEQUENCE is strictly higher; whether eXo's own copy then
 * follows is the sweep's freshness rule, as for any change made on the server.
 */
@Service
public class CaldavInvitationLandingService {

  private static final Log        LOG           = ExoLogger.getLogger(CaldavInvitationLandingService.class);

  /**
   * How far either side of the event's start the import reads: enough for the
   * object just written, or just filed by the mail server, to be the one the
   * sweep's own path brings in, and small enough for one calendar-query.
   */
  private static final Duration   IMPORT_MARGIN = Duration.ofDays(1);

  @Autowired
  private IdentityManager            identityManager;

  @Autowired
  private CaldavConnectorStorage     caldavConnectorStorage;

  @Autowired
  private CaldavServerService        caldavServerService;

  @Autowired
  private CaldavSyncStorage          caldavSyncStorage;

  @Autowired
  private CalDavClient               calDavClient;

  @Autowired
  private CalendarObjectWriters      calendarObjectWriters;

  @Autowired
  private IcsParser                  icsParser;

  @Autowired
  private IcsMerger                  icsMerger;

  @Autowired
  private CaldavInboundService       caldavInboundService;

  @Autowired
  private CaldavPushService          caldavPushService;

  @Autowired
  private AgendaCalendarService      agendaCalendarService;

  @Autowired
  private AgendaEventAttendeeService agendaEventAttendeeService;

  /**
   * Lands the answered invitation in the user's calendar.
   *
   * @param landing the invitation, the user and their answer
   * @return true when the user's calendar now holds the event with this answer;
   *         false when the user has no connected account or no calendar bound
   *         on it
   * @throws IllegalArgumentException when the message cannot be landed as it is
   *           — unreadable, about another event than the one answered, about one
   *           occurrence only, or naming a meeting this deployment wrote
   * @throws IllegalStateException when the landing was attempted and failed
   */
  public boolean land(InvitationLanding landing) {
    long userIdentityId = identityOf(landing.username());
    if (userIdentityId <= 0) {
      return false;
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!caldavServerService.isConnected(settings)) {
      return false;
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> bindings = bindingsOf(userIdentityId, serverId);
    if (bindings.isEmpty()) {
      LOG.debug("The invitation answered by user {} is not landed: no calendar is bound on their account", userIdentityId);
      return false;
    }
    IcsEvent master = masterOf(landing);
    String uid = master.getUid();
    Long mirrored = caldavSyncStorage.getMirrorEventIdOnServer(serverId, uid);
    if (mirrored != null && mirrored > 0) {
      throw new IllegalArgumentException("The invitation names a meeting this deployment wrote (" + uid
          + "); it is answered in agenda, not landed from a mail");
    }
    CalDavEndpoint endpoint = calDavClient.endpoint(serverId, landing.username());
    List<String> addresses = addressesOf(userIdentityId, settings, landing.attendeeAddress());
    String partStat = IcsText.partStat(landing.answer().name());
    CalendarSync binding = bindingHolding(bindings, uid);
    if (binding == null) {
      binding = homeBinding(bindings, endpoint);
      file(endpoint, binding, landing, uid, addresses, partStat);
    } else {
      refreshIfNewer(endpoint, caldavSyncStorage.getObjectByUid(binding.getId(), uid), landing, uid, addresses, partStat);
    }
    Calendar calendar = calendarOf(binding, userIdentityId, landing.username());
    importAround(userIdentityId, landing.username(), binding, calendar, master);
    ObjectSync known = caldavSyncStorage.getObjectByUid(binding.getId(), uid);
    if (known == null || known.getLocalEventId() == null) {
      throw new IllegalStateException("The invitation " + uid + " is on the server and was not imported into calendar "
          + calendar.getId() + " of user " + userIdentityId);
    }
    answer(userIdentityId, landing, known.getLocalEventId());
    LOG.debug("The invitation {} answered by user {} landed as event {} in calendar {}",
              uid,
              userIdentityId,
              known.getLocalEventId(),
              calendar.getId());
    return true;
  }

  /**
   * The identity of the answering user.
   *
   * @param username their eXo login
   * @return the identity id, 0 when it cannot be resolved
   */
  private long identityOf(String username) {
    Identity identity = identityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, username);
    return identity == null || StringUtils.isBlank(identity.getId()) ? 0L : Long.parseLong(identity.getId());
  }

  /**
   * The user's calendar bindings on the account: the active ones standing for a
   * calendar of theirs, never the mirror ledger, whose copies are eXo's own.
   *
   * @param userIdentityId identity of the user
   * @param serverId the declared server registration
   * @return the bindings, possibly empty
   */
  private List<CalendarSync> bindingsOf(long userIdentityId, long serverId) {
    List<CalendarSync> bindings = new ArrayList<>();
    for (CalendarSync pair : caldavSyncStorage.getPairs(userIdentityId, serverId)) {
      if (pair.getOrigin() != SyncOrigin.MIRROR && pair.getStatus() == CalendarSyncStatus.ACTIVE
          && StringUtils.isNotBlank(pair.getRemoteHref())) {
        bindings.add(pair);
      }
    }
    return bindings;
  }

  /**
   * The event the message is about, read from the message itself and checked
   * against what the reader said it answered.
   *
   * @param landing the invitation
   * @return the master component
   * @throws IllegalArgumentException when the message is unreadable, carries no
   *           master, or names another event than the one answered
   */
  private IcsEvent masterOf(InvitationLanding landing) {
    List<IcsEvent> parsed;
    try {
      parsed = icsParser.parseOrFail(landing.icalendar());
    } catch (IcsParseException e) {
      throw new IllegalArgumentException("The invitation cannot be read as iCalendar", e);
    }
    IcsEvent master = null;
    for (IcsEvent event : parsed) {
      if (StringUtils.isBlank(event.getOccurrenceId())) {
        master = event;
        break;
      }
    }
    if (master == null) {
      throw new IllegalArgumentException(parsed.isEmpty() ? "The invitation carries no event"
                                                          : "The invitation is about one occurrence of a series, which is not landed");
    }
    if (!StringUtils.equals(master.getUid(), StringUtils.trim(landing.uid()))) {
      throw new IllegalArgumentException("The invitation names event " + master.getUid() + ", not the one answered, " + landing.uid());
    }
    return master;
  }

  /**
   * Every address the user's own copy may name them by: their account's and
   * their profile's, as the answer push offers them, and the mailbox the mail was
   * read for, which is the one the organiser invited.
   *
   * @param userIdentityId identity of the user
   * @param settings their connected account
   * @param attendeeAddress their mailbox address, possibly null
   * @return the addresses, the mailbox first
   */
  private List<String> addressesOf(long userIdentityId, CaldavUserSetting settings, String attendeeAddress) {
    List<String> addresses = new ArrayList<>();
    if (StringUtils.isNotBlank(attendeeAddress)) {
      addresses.add(StringUtils.trim(attendeeAddress));
    }
    addresses.addAll(caldavPushService.addressesNaming(userIdentityId, settings));
    return addresses;
  }

  /**
   * The binding whose collection already holds the event, as the sweep mapped
   * it.
   *
   * @param bindings the user's bindings
   * @param uid the event's UID
   * @return the binding, or null when none maps that UID
   */
  private CalendarSync bindingHolding(List<CalendarSync> bindings, String uid) {
    for (CalendarSync binding : bindings) {
      if (caldavSyncStorage.getObjectByUid(binding.getId(), uid) != null) {
        return binding;
      }
    }
    return null;
  }

  /**
   * The binding a new invitation goes to: the only one, else the one standing
   * for the server's default calendar — where a mail server files invitations
   * itself — else the first.
   *
   * @param bindings the user's bindings, at least one
   * @param endpoint the account's endpoint
   * @return the binding
   */
  private CalendarSync homeBinding(List<CalendarSync> bindings, CalDavEndpoint endpoint) {
    if (bindings.size() == 1) {
      return bindings.get(0);
    }
    String defaultCalendar = null;
    try {
      defaultCalendar = calDavClient.discoverDefaultCalendar(endpoint);
    } catch (CalDavException e) {
      LOG.debug("The default calendar of the account could not be asked; the first binding takes the invitation", e);
    }
    if (StringUtils.isNotBlank(defaultCalendar)) {
      String wanted = CaldavSyncStorage.canonicalHref(defaultCalendar);
      for (CalendarSync binding : bindings) {
        if (Objects.equals(wanted, CaldavSyncStorage.canonicalHref(binding.getRemoteHref()))) {
          return binding;
        }
      }
    }
    LOG.debug("None of the {} bindings stands for the account's default calendar; the first takes the invitation",
              bindings.size());
    return bindings.get(0);
  }

  /**
   * Puts the invitation's object on the server when it is not there yet: at the
   * path the sweep computes for the UID, created only — an object already at
   * that path, or that UID held elsewhere in the collection (RFC 4791
   * no-uid-conflict), means the server has it and nothing is written.
   *
   * @param endpoint the account's endpoint
   * @param binding the binding written into
   * @param landing the invitation
   * @param uid the event's UID
   * @param addresses the addresses the user's line may carry
   * @param partStat the answer, as a PARTSTAT token
   * @throws IllegalStateException when the server refused the write
   */
  private void file(CalDavEndpoint endpoint,
                    CalendarSync binding,
                    InvitationLanding landing,
                    String uid,
                    List<String> addresses,
                    String partStat) {
    String href = CaldavPushService.objectHref(binding.getRemoteHref(), uid);
    try {
      CalendarObject existing = calDavClient.fetchObject(endpoint, href);
      if (existing != null && StringUtils.isNotBlank(existing.calendarData())) {
        LOG.debug("The invitation {} is already at {}; it is read, not written", uid, href);
        return;
      }
      PutResult result = calendarObjectWriters.writer(endpoint).putObject(endpoint, href, withAnswer(landing, uid, addresses, partStat));
      if (result.preconditionFailed()) {
        LOG.debug("The invitation {} is already held by collection {}; it is read, not written", uid, binding.getRemoteHref());
      }
    } catch (CalDavException e) {
      throw new IllegalStateException("The invitation " + uid + " could not be written at " + href, e);
    }
  }

  /**
   * Writes the mail's revision over the server's copy when it is strictly newer
   * (SEQUENCE), so an updated invitation answered from the mail reaches a server
   * that did not file it itself; an older or equal mail leaves the copy alone.
   *
   * @param endpoint the account's endpoint
   * @param known the mapping of the copy
   * @param landing the invitation
   * @param uid the event's UID
   * @param addresses the addresses the user's line may carry
   * @param partStat the answer, as a PARTSTAT token
   * @throws IllegalStateException when the server refused the write
   */
  private void refreshIfNewer(CalDavEndpoint endpoint,
                              ObjectSync known,
                              InvitationLanding landing,
                              String uid,
                              List<String> addresses,
                              String partStat) {
    if (known == null || StringUtils.isBlank(known.getRemoteHref())) {
      return;
    }
    try {
      CalendarObject existing = calDavClient.fetchObject(endpoint, known.getRemoteHref());
      if (existing == null || StringUtils.isBlank(existing.calendarData())) {
        LOG.debug("The copy of {} mapped at {} is not served; the import decides what became of it", uid, known.getRemoteHref());
        return;
      }
      if (landing.sequence() <= sequenceOf(existing.calendarData())) {
        return;
      }
      PutResult result = calendarObjectWriters.writer(endpoint)
                                              .updateObject(endpoint,
                                                            known.getRemoteHref(),
                                                            withAnswer(landing, uid, addresses, partStat),
                                                            existing.etag());
      if (result.preconditionFailed()) {
        throw new IllegalStateException("The copy of " + uid + " at " + known.getRemoteHref() + " changed while it was being updated");
      }
    } catch (CalDavException e) {
      throw new IllegalStateException("The newer revision of " + uid + " could not be written at " + known.getRemoteHref(), e);
    }
  }

  /**
   * The highest SEQUENCE the server's copy carries.
   *
   * @param calendarData the copy
   * @return the sequence, 0 when the copy cannot be read
   */
  private int sequenceOf(String calendarData) {
    int sequence = 0;
    for (IcsEvent event : icsParser.parse(calendarData)) {
      sequence = Math.max(sequence, event.getSequence());
    }
    return sequence;
  }

  /**
   * The object to store: the message's event with METHOD gone and the user's
   * answer on their own attendee line.
   *
   * @param landing the invitation
   * @param uid the event's UID
   * @param addresses the addresses the user's line may carry
   * @param partStat the answer, as a PARTSTAT token
   * @return the object
   * @throws IllegalArgumentException when the message cannot be turned into one
   */
  private String withAnswer(InvitationLanding landing, String uid, List<String> addresses, String partStat) {
    try {
      String stored = icsMerger.storedObject(landing.icalendar(), uid);
      IcsMerger.AnswerRewrite rewrite = icsMerger.setAttendeeResponse(stored, addresses, partStat);
      if (!rewrite.attendeeNamed()) {
        LOG.debug("The invitation {} names none of {}; it is stored as sent, and the answer is carried by agenda", uid, addresses);
      }
      return rewrite.hasChange() ? rewrite.document() : stored;
    } catch (IcsParseException e) {
      throw new IllegalArgumentException("The invitation cannot be stored as a calendar object", e);
    }
  }

  /**
   * The eXo calendar standing for a binding.
   *
   * @param binding the binding
   * @param userIdentityId identity of the user
   * @param username their eXo login, which agenda's ACL reads
   * @return the calendar
   * @throws IllegalStateException when the user's calendars cannot be read, or
   *           none stands for the binding any more
   */
  private Calendar calendarOf(CalendarSync binding, long userIdentityId, String username) {
    List<Calendar> calendars;
    try {
      calendars = agendaCalendarService.getCalendars(0, Integer.MAX_VALUE, username);
    } catch (Exception e) { // NOSONAR agenda declares a bare Exception here
      throw new IllegalStateException("The calendars of user " + userIdentityId + " could not be read", e);
    }
    for (Calendar calendar : calendars) {
      if (calendar.getOwnerId() == userIdentityId && !calendar.isDeleted()
          && StringUtils.equals(calendar.getSyncUid(), binding.getLocalCalendarSyncUid())) {
        return calendar;
      }
    }
    throw new IllegalStateException("No calendar of user " + userIdentityId + " stands for binding " + binding.getId());
  }

  /**
   * Reads the collection back around the event, through the sweep's own path.
   *
   * @param userIdentityId identity of the user
   * @param username their eXo login
   * @param binding the binding read
   * @param calendar the eXo calendar it fills
   * @param master the event, for its dates
   */
  private void importAround(long userIdentityId, String username, CalendarSync binding, Calendar calendar, IcsEvent master) {
    Instant start = master.getStart();
    Instant end = master.getEnd() == null || master.getEnd().isBefore(start) ? start : master.getEnd();
    caldavInboundService.importInto(userIdentityId, username, binding, calendar, start.minus(IMPORT_MARGIN), end.plus(IMPORT_MARGIN));
  }

  /**
   * Records the answer in agenda, and carries it onto the server's copy when the
   * listener that does so for a changed answer would not: the import already
   * reads the user as accepted, so an accepted answer never changes there.
   *
   * @param userIdentityId identity of the user
   * @param landing the invitation
   * @param eventId the imported event
   * @throws IllegalStateException when agenda refuses the answer
   */
  private void answer(long userIdentityId, InvitationLanding landing, long eventId) {
    EventAttendeeResponse response = EventAttendeeResponse.valueOf(landing.answer().name());
    EventAttendeeResponse before;
    try {
      before = agendaEventAttendeeService.getEventResponse(eventId, null, userIdentityId);
      // No "response sent" broadcast: the imported event's organiser is the
      // user, and the notification it drives would be addressed to them.
      agendaEventAttendeeService.sendEventResponse(eventId, userIdentityId, response, false);
    } catch (Exception e) { // NOSONAR agenda declares checked refusals
      throw new IllegalStateException("The answer of user " + userIdentityId + " to event " + eventId + " was refused by agenda", e);
    }
    if (before == response && !caldavPushService.pushAnswer(userIdentityId, landing.username(), eventId, response.name())) {
      LOG.debug("The answer {} of user {} to event {} is held by agenda; the copy was not rewritten", response, userIdentityId, eventId);
    }
  }
}

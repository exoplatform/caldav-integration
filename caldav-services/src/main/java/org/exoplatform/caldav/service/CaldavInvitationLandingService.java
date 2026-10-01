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
import org.exoplatform.caldav.model.MailInvitation;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
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
 * the collection around the event, exactly as the sweep would on its next pass:
 * the same mapping row ({@code ObjectSync}: binding, UID, href, ETag), the same
 * remote identity agenda records, the same recurrence and override handling.
 * The read comes <i>first</i>: a mail server that files invitations in the
 * calendar itself (BlueMind, Google) already holds the object, under whatever
 * name it chose for it, and the import finds it by the UID inside it. Only an
 * object the import did not find is written, at the path the sweep computes,
 * created and never overwritten; then read back the same way. What this
 * service adds is what the sweep cannot know — that the user answered.
 *
 * <h2>Where the answer lives</h2>
 *
 * <p>
 * The answer is written onto the copy first ({@code pushAnswerOnto}, offered
 * every address the copy may name the user by, their mailbox first), then
 * recorded in agenda like any answer given in eXo, through
 * {@link AgendaEventAttendeeService#sendEventResponse}: the listener that
 * carries eXo answers onto copies then finds this one already said. A copy
 * naming the user by an address that is neither their account's nor their
 * profile's is answered here and is reported by that listener as naming none
 * of its addresses — one warning, and a correct copy.
 *
 * <h2>What is trusted</h2>
 *
 * <p>
 * The object is the sender's. Only the answering user's own bindings are
 * consulted, never the mirror ledger; a UID this deployment minted for one of
 * its own meetings is refused before anything is written. A copy the user
 * already holds is rewritten only by its own organiser (RFC 5546 section
 * 3.2.2): a message whose ORGANIZER is not the copy's, or whose copy the user
 * organises themselves, is refused — so an invitee who learnt the UID of an
 * event the user organises on their own server cannot rewrite it from a mail.
 * The addresses the answer is written under are the user's own; no attendee
 * line is resolved to anybody.
 *
 * <h2>Cost and limits</h2>
 *
 * <p>
 * This runs on the request thread, after the REPLY left: at most one
 * default-calendar discovery, two reads of the collection's window, one
 * creating write, and the answer's read and write, each bounded by the client's
 * request timeout — minutes on a server that does not answer, which the user
 * then sees as a failed update. A message about one occurrence of a series (a
 * lone RECURRENCE-ID) is refused: landing it means splicing an override into a
 * copy and answering an instance, which this path does not do yet. A newer
 * revision of an event the user holds is written over the server's copy when
 * the mail's SEQUENCE is strictly higher than the copy's master's; whether
 * eXo's own copy then follows is the sweep's freshness rule, as for any change
 * made on the server.
 */
@Service
public class CaldavInvitationLandingService {

  private static final Log        LOG           = ExoLogger.getLogger(CaldavInvitationLandingService.class);

  /**
   * How far either side of the event's start the import reads: enough for the
   * object just written, or filed by the mail server, to be the one the
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
   * @param invitation the invitation, the user and their answer
   * @return true when the user's calendar now holds the event with this answer;
   *         false when the user has no connected account or no calendar bound
   *         on it
   * @throws IllegalArgumentException when the message cannot be landed as it is
   *           — unreadable, about another event than the one answered, about one
   *           occurrence only, naming no organiser, naming a meeting this
   *           deployment wrote, or rewriting a copy of another organiser's
   * @throws IllegalStateException when the landing was attempted and failed
   */
  public boolean land(MailInvitation invitation) {
    long userIdentityId = identityOf(invitation.username());
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
    IcsEvent master = masterOf(invitation);
    String uid = master.getUid();
    Long mirrored = caldavSyncStorage.getMirrorEventIdOnServer(serverId, uid);
    if (mirrored != null && mirrored > 0) {
      throw new IllegalArgumentException("The invitation names a meeting this deployment wrote (" + uid
          + "); it is answered in agenda, not landed from a mail");
    }
    String username = invitation.username();
    CalDavEndpoint endpoint = calDavClient.endpoint(serverId, username);
    List<String> addresses = addressesOf(userIdentityId, settings, invitation.attendeeAddress());
    String partStat = IcsText.partStat(invitation.response().name());
    CalendarSync binding = bindingHolding(bindings, uid);
    ObjectSync known = binding == null ? null : caldavSyncStorage.getObjectByUid(binding.getId(), uid);
    if (binding == null) {
      binding = homeBinding(bindings, endpoint);
    }
    Calendar calendar = calendarOf(binding, userIdentityId, username);
    if (known == null) {
      // The sweep's own read first: an object the server filed itself is
      // found by its UID, whatever the server named it.
      importAround(userIdentityId, username, binding, calendar, master);
      known = caldavSyncStorage.getObjectByUid(binding.getId(), uid);
    }
    if (known == null) {
      file(endpoint, binding, invitation, uid, addresses, partStat);
      importAround(userIdentityId, username, binding, calendar, master);
      known = caldavSyncStorage.getObjectByUid(binding.getId(), uid);
      if (known == null || known.getLocalEventId() == null) {
        throw new IllegalStateException("The invitation " + uid + " is on the server and was not imported into calendar "
            + calendar.getId() + " of user " + userIdentityId);
      }
    } else if (known.getLocalEventId() == null) {
      throw new IllegalStateException("The invitation " + uid + " is mapped on binding " + binding.getId() + " and stands for no event");
    } else if (refreshIfNewer(endpoint, known, invitation, master, addresses, partStat)) {
      importAround(userIdentityId, username, binding, calendar, master);
    }
    answer(userIdentityId, invitation, known, addresses);
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
   * @param invitation the invitation
   * @return the master component, with an organiser
   * @throws IllegalArgumentException when the message is unreadable, carries no
   *           master, names another event than the one answered, or no organiser
   */
  private IcsEvent masterOf(MailInvitation invitation) {
    List<IcsEvent> parsed;
    try {
      parsed = icsParser.parseOrFail(invitation.icalendar());
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
    if (!StringUtils.equals(master.getUid(), StringUtils.trim(invitation.uid()))) {
      throw new IllegalArgumentException("The invitation names event " + master.getUid() + ", not the one answered, " + invitation.uid());
    }
    if (organiserOf(master) == null) {
      throw new IllegalArgumentException("The invitation " + master.getUid() + " names no organiser");
    }
    return master;
  }

  /**
   * The organiser's address of a component, comparable.
   *
   * @param event the component, may be null
   * @return the address, or null when the component names no organiser
   */
  private static String organiserOf(IcsEvent event) {
    return event == null || event.getOrganizer() == null ? null : IcsText.bareAddress(event.getOrganizer().getEmail());
  }

  /**
   * Every address the user's own copy may name them by: the mailbox the mail was
   * read for, which is the one the organiser invited, first; then their
   * account's and their profile's, as the answer push offers them.
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
   * Creates the invitation's object on the server, at the path the sweep
   * computes for the UID, created only ({@code If-None-Match: *}): an object
   * already at that path answers 412 and is left to the read that follows. A
   * server refusing the creation otherwise — a {@code no-uid-conflict} for an
   * object the read did not cover, or no right to write — is a failure.
   *
   * @param endpoint the account's endpoint
   * @param binding the binding written into
   * @param invitation the invitation
   * @param uid the event's UID
   * @param addresses the addresses the user's line may carry
   * @param partStat the answer, as a PARTSTAT token
   * @throws IllegalStateException when the server refused the write
   */
  private void file(CalDavEndpoint endpoint,
                    CalendarSync binding,
                    MailInvitation invitation,
                    String uid,
                    List<String> addresses,
                    String partStat) {
    String href = CaldavPushService.objectHref(binding.getRemoteHref(), uid);
    try {
      PutResult result = calendarObjectWriters.writer(endpoint).putObject(endpoint, href, withAnswer(invitation, uid, addresses, partStat));
      if (result.preconditionFailed()) {
        LOG.debug("Something is already at {}; the invitation {} is read, not written", href, uid);
      }
    } catch (CalDavException e) {
      throw new IllegalStateException("The invitation " + uid + " could not be written at " + href, e);
    }
  }

  /**
   * Writes the mail's revision over the copy the user holds when its organiser
   * sent it and it is strictly newer (SEQUENCE, master against master), so an
   * updated invitation answered from the mail reaches a server that did not file
   * it itself; an older or equal mail leaves the copy alone.
   *
   * @param endpoint the account's endpoint
   * @param known the mapping of the copy
   * @param invitation the invitation
   * @param master the message's event
   * @param addresses the addresses the user's line may carry
   * @param partStat the answer, as a PARTSTAT token
   * @return true when the copy was rewritten
   * @throws IllegalArgumentException when the message is not the copy's
   *           organiser's, or the user organises the copy
   * @throws IllegalStateException when the server refused the write
   */
  private boolean refreshIfNewer(CalDavEndpoint endpoint,
                                 ObjectSync known,
                                 MailInvitation invitation,
                                 IcsEvent master,
                                 List<String> addresses,
                                 String partStat) {
    if (StringUtils.isBlank(known.getRemoteHref())) {
      return false;
    }
    try {
      CalendarObject existing = calDavClient.fetchObject(endpoint, known.getRemoteHref());
      if (existing == null || StringUtils.isBlank(existing.calendarData())) {
        LOG.debug("The copy of {} mapped at {} is not served; the import decides what became of it",
                  master.getUid(),
                  known.getRemoteHref());
        return false;
      }
      IcsEvent copy = masterOfCopy(existing.calendarData());
      refuseUnlessItsOrganiser(master, copy, addresses);
      if (invitation.sequence() <= (copy == null ? 0 : copy.getSequence())) {
        return false;
      }
      PutResult result = calendarObjectWriters.writer(endpoint)
                                              .updateObject(endpoint,
                                                            known.getRemoteHref(),
                                                            withAnswer(invitation, master.getUid(), addresses, partStat),
                                                            existing.etag());
      if (result.preconditionFailed()) {
        throw new IllegalStateException("The copy of " + master.getUid() + " at " + known.getRemoteHref()
            + " changed while it was being updated");
      }
      return true;
    } catch (CalDavException e) {
      throw new IllegalStateException("The newer revision of " + master.getUid() + " could not be written at " + known.getRemoteHref(),
                                      e);
    }
  }

  /**
   * The master component of a copy the server holds.
   *
   * @param calendarData the copy
   * @return the master, or null when the copy cannot be read or carries none
   */
  private IcsEvent masterOfCopy(String calendarData) {
    for (IcsEvent event : icsParser.parse(calendarData)) {
      if (StringUtils.isBlank(event.getOccurrenceId())) {
        return event;
      }
    }
    return null;
  }

  /**
   * Only an event's organiser rewrites it (RFC 5546 section 3.2.2): a copy the
   * user holds takes a message from its own ORGANIZER and nobody else, and a
   * copy the user organises themselves takes none — such a message was written
   * by somebody who learnt the UID, not by the organiser.
   *
   * @param master the message's event
   * @param copy the copy's master, null when the copy names none
   * @param addresses the user's own addresses
   * @throws IllegalArgumentException when the message is not the copy's
   *           organiser's
   */
  private void refuseUnlessItsOrganiser(IcsEvent master, IcsEvent copy, List<String> addresses) {
    String copyOrganiser = organiserOf(copy);
    for (String address : addresses) {
      if (copyOrganiser != null && copyOrganiser.equals(IcsText.bareAddress(address))) {
        throw new IllegalArgumentException("The invitation " + master.getUid() + " names an event the user organises; a mail does not rewrite it");
      }
    }
    if (copyOrganiser == null || !copyOrganiser.equals(organiserOf(master))) {
      throw new IllegalArgumentException("The invitation " + master.getUid() + " is not from the organiser of the copy the user holds");
    }
  }

  /**
   * The object to store: the message's event with METHOD gone and the user's
   * answer on their own attendee line.
   *
   * @param invitation the invitation
   * @param uid the event's UID
   * @param addresses the addresses the user's line may carry
   * @param partStat the answer, as a PARTSTAT token
   * @return the object
   * @throws IllegalArgumentException when the message cannot be turned into one
   */
  private String withAnswer(MailInvitation invitation, String uid, List<String> addresses, String partStat) {
    try {
      String stored = icsMerger.storedObject(invitation.icalendar(), uid);
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
   * Reads the collection around the event, through the sweep's own path.
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
   * Writes the answer onto the copy, then records it in agenda. The copy first,
   * so the listener that carries eXo answers onto copies finds this one already
   * said; agenda through the same method every eXo answer takes, without the
   * "response sent" broadcast — the imported event's organiser is the user, and
   * that notification would be addressed to them.
   *
   * @param userIdentityId identity of the user
   * @param invitation the invitation
   * @param known the copy's mapping
   * @param addresses the addresses the copy may name the user by
   * @throws IllegalStateException when agenda refuses the answer
   * @throws CaldavPushException when the copy could not be written
   */
  private void answer(long userIdentityId, MailInvitation invitation, ObjectSync known, List<String> addresses) {
    long eventId = known.getLocalEventId();
    CaldavPushService.AnswerOutcome outcome = caldavPushService.pushAnswerOnto(userIdentityId,
                                                                               invitation.username(),
                                                                               known,
                                                                               addresses,
                                                                               invitation.response().name(),
                                                                               eventId);
    LOG.debug("The answer {} of user {} to event {} on the copy at {}: {}",
              invitation.response(),
              userIdentityId,
              eventId,
              known.getRemoteHref(),
              outcome);
    try {
      agendaEventAttendeeService.sendEventResponse(eventId, userIdentityId, invitation.response(), false);
    } catch (Exception e) { // NOSONAR agenda declares checked refusals
      throw new IllegalStateException("The answer of user " + userIdentityId + " to event " + eventId + " was refused by agenda", e);
    }
  }
}

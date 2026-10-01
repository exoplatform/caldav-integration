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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.util.EventIcsBuilder;
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
import org.exoplatform.caldav.model.LandedMailInvitation;
import org.exoplatform.caldav.model.MailInvitation;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Lands an invitation a user received by mail in their calendar (EXO-90848):
 * the event the mail describes ends up on the user's CalDAV account and in the
 * eXo calendar standing for it, under the identity the inbound sweep knows it
 * by — with the answer the user gave, or without one when they only asked to
 * add it — and leaves it when its organiser cancelled it.
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
 * object the import did not find is written, under a name of this service's
 * own, created and never overwritten; then read back the same way. What this
 * service adds is what the sweep cannot know — what the user asked.
 *
 * <h2>What each click does</h2>
 *
 * <p>
 * An invitation accepted or tentatively accepted, or added without an answer,
 * lands: created, or the copy the user holds rewritten by its organiser's newer
 * revision. A decline creates nothing — a meeting the user turned down and
 * never added does not belong in their calendar — and sets the answer on a
 * copy they do hold, found by the same read first (a copy the mail server
 * filed and the sweep has not mapped yet takes the decline too, or the next
 * sweep would bring it in accepted), as answering Decline in agenda on a
 * synced copy does; the copy stays, declined, which is what the server and
 * every client of it then agree on. A cancellation removes the copy the user holds — the object on the
 * server, the mapping, the eXo event — as every calendar client does with the
 * organiser's CANCEL; a cancellation of an event the user never added does
 * nothing.
 *
 * <h2>Where the answer lives</h2>
 *
 * <p>
 * The answer is written onto the copy here ({@code pushAnswerOnto}, offered
 * every address the copy may name the user by, their mailbox first), and
 * nowhere else: the import makes the user the creator of the event it brings
 * in, and this add-on's {@code EventResponseSavedListener}, which carries eXo
 * answers onto copies through {@code CaldavPushService#pushAnswer}, declines
 * every event its user organises — so it never reaches a landed one. The write
 * is conditioned on the version the server holds <i>now</i>, read on this very
 * call, not on the one the last sweep recorded: a mail server that filed the
 * organiser's update itself, or a phone that touched the copy, moved it since,
 * and the sweep leaves a version unrecorded on purpose when eXo's copy is the
 * newer one. The answer touches one attendee line and nothing else, which is
 * what makes that safe. Then it is recorded in agenda like any answer given
 * in eXo, through {@link AgendaEventAttendeeService#sendEventResponse}. A copy
 * naming the user only by an address outside the offered set is stored as
 * sent and the answer lives in agenda alone — said at WARN, since nothing
 * retries it.
 *
 * <h2>What is trusted</h2>
 *
 * <p>
 * The object is the sender's. Only the answering user's own bindings are
 * consulted, never the mirror ledger; a UID this deployment minted for one of
 * its own meetings is refused before anything is written, and the UID agenda
 * mails for one of its own meetings ({@code agenda-event-<id>@<this host>})
 * lands nothing: the meeting is in agenda already, so adding it hands back
 * agenda's page when the user may read it, and an answer or a cancellation
 * carried by such a message is refused. Only an invitation, a published event
 * or a cancellation is landed — a REPLY or a COUNTER speaks to an organiser,
 * not to a calendar. A copy the user
 * already holds is rewritten or removed only by its own organiser (RFC 5546
 * section 3.2.2): a message whose ORGANIZER is not the copy's, or whose copy
 * the user organises themselves, is refused — so an invitee who learnt the
 * UID of an event the user organises on their own server cannot rewrite or
 * remove it from a mail. The sender's UID never becomes a path: the object this
 * service creates is named by a UUID of its own, and found afterwards by the
 * UID inside it, as the sweep finds everything. The addresses the answer is
 * written under are the user's own; no attendee line is resolved to anybody.
 * The link handed back is agenda's own page for the event, built from the
 * platform's domain and the event's id, never from the object.
 *
 * <h2>Cost and limits</h2>
 *
 * <p>
 * This runs on the request thread, after the REPLY left when there was one: at
 * most one default-calendar discovery, two reads of the collection's window,
 * one write of the object, and the answer's read and write, each bounded by
 * the client's request timeout — minutes on a server that does not answer,
 * which the user then sees as a failed update. A message about one occurrence
 * of a series (a lone RECURRENCE-ID) is refused: landing it means splicing an
 * override into a copy and answering an instance, which this path does not do
 * yet. A newer revision of an event the user holds is written over the
 * server's copy when the mail's SEQUENCE is strictly higher than the copy's
 * master's; whether eXo's own copy then follows is the sweep's freshness rule,
 * as for any change made on the server. A user with no connected account, or
 * no calendar bound on it, is not this add-on's to land for.
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

  /** The methods a message may carry to be landed: an invitation, a published event, a cancellation, or none. */
  private static final Set<String> LANDABLE_METHODS = Set.of("REQUEST", "PUBLISH", MailInvitation.CANCEL);

  /**
   * The UID agenda stamps on the object it mails for one of its own meetings:
   * {@code agenda-event-<id>@<host>}, the host being this deployment's — the
   * derivation agenda's own {@code Utils.icsUid} makes, which is deliberately
   * not the per-user UID a CalDAV copy carries.
   */
  private static final Pattern    OWN_AGENDA_UID = Pattern.compile("^agenda-event-(\\d{1,18})@(.+)$");

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
  private CaldavEventPropagationService caldavEventPropagationService;

  @Autowired
  private AgendaCalendarService      agendaCalendarService;

  @Autowired
  private AgendaEventService         agendaEventService;

  @Autowired
  private AgendaEventAttendeeService agendaEventAttendeeService;

  /**
   * Whether this add-on holds a calendar for the user: a connected account with
   * a calendar bound on it. Database reads only — this is asked on every read
   * of an invitation.
   *
   * @param username the user's eXo login
   * @return true when a landing has somewhere to go
   */
  public boolean holdsCalendarFor(String username) {
    long userIdentityId = identityOf(username);
    if (userIdentityId <= 0) {
      return false;
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!caldavServerService.isConnected(settings)) {
      return false;
    }
    return !bindingsOf(userIdentityId, settings.getServerId() == null ? 0L : settings.getServerId()).isEmpty();
  }

  /**
   * Lands the invitation in the user's calendar: creates or updates the event,
   * answers it, or removes it, as the message and the click say.
   *
   * @param invitation the invitation, the user and what they asked
   * @return what was done, or null when the user has no connected account or
   *         no calendar bound on it, or when there was nothing to do — a
   *         decline, or a cancellation, of an event the user never added
   * @throws IllegalArgumentException when the message cannot be landed as it is
   *           — unreadable, about another event than the one shown, about one
   *           occurrence only, naming no organiser where one is needed, naming a
   *           meeting this deployment wrote, or touching a copy of another
   *           organiser's
   * @throws IllegalStateException when the landing was attempted and failed
   */
  public LandedMailInvitation land(MailInvitation invitation) {
    long userIdentityId = identityOf(invitation.username());
    if (userIdentityId <= 0) {
      return null;
    }
    CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
    if (!caldavServerService.isConnected(settings)) {
      return null;
    }
    long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
    List<CalendarSync> bindings = bindingsOf(userIdentityId, serverId);
    if (bindings.isEmpty()) {
      LOG.debug("The invitation of user {} is not landed: no calendar is bound on their account", userIdentityId);
      return null;
    }
    IcsEvent master = masterOf(invitation);
    String uid = master.getUid();
    Long ownMeeting = ownAgendaEventOf(uid);
    if (ownMeeting != null) {
      return heldInAgenda(ownMeeting, userIdentityId, invitation);
    }
    Long mirrored = caldavSyncStorage.getMirrorEventIdOnServer(serverId, uid);
    if (mirrored != null && mirrored > 0) {
      throw new IllegalArgumentException("The invitation names a meeting this deployment wrote (" + uid
          + "); it is answered in agenda, not landed from a mail");
    }
    String username = invitation.username();
    CalDavEndpoint endpoint = calDavClient.endpoint(serverId, username);
    List<String> addresses = addressesOf(userIdentityId, settings, invitation.attendeeAddress());
    String partStat = invitation.response() == null ? null : IcsText.partStat(invitation.response().name());
    CalendarSync binding = bindingHolding(bindings, uid);
    ObjectSync known = binding == null ? null : caldavSyncStorage.getObjectByUid(binding.getId(), uid);
    if (invitation.isCancellation()) {
      return known == null ? nothingHeld(invitation, "cancellation")
                           : remove(endpoint, userIdentityId, username, known, master, addresses);
    }
    if (binding == null) {
      binding = homeBinding(bindings, endpoint);
    }
    Calendar calendar = calendarOf(binding, userIdentityId, username);
    String served = null;
    if (known == null) {
      // The sweep's own read first: an object the server filed itself is
      // found by its UID, whatever the server named it — and takes a decline
      // too, which would otherwise come in accepted on the next sweep.
      importAround(userIdentityId, username, binding, calendar, master);
      known = caldavSyncStorage.getObjectByUid(binding.getId(), uid);
      if (known == null) {
        if (invitation.response() == EventAttendeeResponse.DECLINED) {
          return nothingHeld(invitation, "decline");
        }
        file(endpoint, binding, invitation, uid, addresses, partStat);
        importAround(userIdentityId, username, binding, calendar, master);
        known = caldavSyncStorage.getObjectByUid(binding.getId(), uid);
      }
    } else {
      String recorded = known.getEtag();
      served = refreshIfNewer(endpoint, known, invitation, master, addresses, partStat);
      // What the server holds now — the organiser's update the mail server
      // filed, a phone's edit, the revision just written — comes into eXo the
      // way every change made on the server does.
      importAround(userIdentityId, username, binding, calendar, master);
      known = caldavSyncStorage.getObjectByUid(binding.getId(), uid);
      if (known != null && !StringUtils.equals(known.getEtag(), recorded)) {
        // The import recorded a version of its own, read after this call's:
        // the later read wins.
        served = null;
      }
    }
    if (known == null || known.getLocalEventId() == null) {
      throw new IllegalStateException("The invitation " + uid + " is on the server and was not imported into calendar "
          + calendar.getId() + " of user " + userIdentityId);
    }
    if (invitation.response() != null) {
      answer(userIdentityId, invitation, asServed(known, served), addresses);
    }
    long eventId = known.getLocalEventId();
    LOG.debug("The invitation {} of user {} landed as event {} in calendar {}", uid, userIdentityId, eventId, calendar.getId());
    return new LandedMailInvitation(eventId, linkOf(eventId), false);
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
   * Nothing to do: the user never added the event the message is about.
   *
   * @param invitation the invitation
   * @param what the click, for the log
   * @return null
   */
  private static LandedMailInvitation nothingHeld(MailInvitation invitation, String what) {
    LOG.debug("The {} of {} by user {} lands nothing: they hold no copy of it", what, invitation.uid(), invitation.username());
    return null;
  }

  /**
   * The event the message is about, read from the message itself and checked
   * against what the reader said it showed. An organiser is required where
   * the message speaks for one — an invitation, a cancellation, an answer —
   * and not for a published event added as it is.
   *
   * @param invitation the invitation
   * @return the master component
   * @throws IllegalArgumentException when the message is unreadable, carries no
   *           master, names another event than the one shown, is not an
   *           invitation, a published event or a cancellation, or names no
   *           organiser where one is needed
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
      throw new IllegalArgumentException("The invitation names event " + master.getUid() + ", not the one shown, " + invitation.uid());
    }
    if (invitation.method() != null && !LANDABLE_METHODS.contains(invitation.method())) {
      // A REPLY, a COUNTER, a REFRESH… speak to an organiser, not to a calendar.
      throw new IllegalArgumentException("A " + invitation.method() + " is not landed in a calendar");
    }
    boolean speaksForAnOrganiser = invitation.response() != null || invitation.isCancellation()
        || "REQUEST".equals(invitation.method());
    if (speaksForAnOrganiser && organiserOf(master) == null) {
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
   * Creates the invitation's object on the server, under a name of this
   * service's own — a UUID, as the mirror names its copies — created only
   * ({@code If-None-Match: *}). The name is free because the read that follows
   * finds the object by the UID inside it, as the sweep finds everything; a
   * sender's UID is content, and content does not become a path on the user's
   * server. A server refusing the creation — a {@code no-uid-conflict} for an
   * object the read did not cover, or no right to write — is a failure.
   *
   * @param endpoint the account's endpoint
   * @param binding the binding written into
   * @param invitation the invitation
   * @param uid the event's UID
   * @param addresses the addresses the user's line may carry
   * @param partStat the answer as a PARTSTAT token, null to store the object as
   *          sent
   * @throws IllegalStateException when the server refused the write
   */
  private void file(CalDavEndpoint endpoint,
                    CalendarSync binding,
                    MailInvitation invitation,
                    String uid,
                    List<String> addresses,
                    String partStat) {
    String href = CaldavPushService.objectHref(binding.getRemoteHref(), UUID.randomUUID().toString());
    try {
      PutResult result = calendarObjectWriters.writer(endpoint).putObject(endpoint, href, withAnswer(invitation, uid, addresses, partStat));
      if (result.preconditionFailed()) {
        throw new IllegalStateException("The invitation " + uid + " could not be created at " + href + ": something is already there");
      }
    } catch (CalDavException e) {
      throw new IllegalStateException("The invitation " + uid + " could not be written at " + href, e);
    }
  }

  /**
   * Writes the mail's revision over the copy the user holds when its organiser
   * sent it and it is strictly newer (SEQUENCE, master against master), so an
   * updated invitation reaches a server that did not file it itself; an older
   * or equal mail leaves the copy alone. Either way, says which version the
   * server holds once this is done.
   *
   * @param endpoint the account's endpoint
   * @param known the mapping of the copy
   * @param invitation the invitation
   * @param master the message's event
   * @param addresses the addresses the user's line may carry
   * @param partStat the answer as a PARTSTAT token, null to write the object as
   *          sent
   * @return the ETag the copy carries now — the one just read, or the one the
   *         rewrite produced; null when the copy is not served
   * @throws IllegalArgumentException when the message is not the copy's
   *           organiser's, or the user organises the copy
   * @throws IllegalStateException when the server refused the write
   */
  private String refreshIfNewer(CalDavEndpoint endpoint,
                                ObjectSync known,
                                MailInvitation invitation,
                                IcsEvent master,
                                List<String> addresses,
                                String partStat) {
    if (StringUtils.isBlank(known.getRemoteHref())) {
      return null;
    }
    try {
      CalendarObject existing = calDavClient.fetchObject(endpoint, known.getRemoteHref());
      if (existing == null || StringUtils.isBlank(existing.calendarData())) {
        LOG.debug("The copy of {} mapped at {} is not served; the import decides what became of it",
                  master.getUid(),
                  known.getRemoteHref());
        return null;
      }
      IcsEvent copy = masterOfCopy(existing.calendarData());
      boolean newer = invitation.sequence() > (copy == null ? 0 : copy.getSequence());
      if (copy != null && organiserOf(copy) == null && organiserOf(master) == null && !newer) {
        // A published event, held as it was added: adding it again is adding
        // nothing. A newer revision of it meets the organiser rule below, and
        // there is no organiser to take it from.
        return existing.etag();
      }
      refuseUnlessItsOrganiser(master, copy, addresses);
      if (!newer) {
        return existing.etag();
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
      if (StringUtils.isNotBlank(result.etag())) {
        return result.etag();
      }
      // A server that altered what it stored sends no ETag with the write
      // (RFC 4791 section 5.3.4): the version is read back, so the answer that
      // follows is conditioned on something.
      CalendarObject rewritten = calDavClient.fetchObject(endpoint, known.getRemoteHref());
      return rewritten == null ? null : StringUtils.defaultIfBlank(rewritten.etag(), null);
    } catch (CalDavException e) {
      throw new IllegalStateException("The newer revision of " + master.getUid() + " could not be written at " + known.getRemoteHref(),
                                      e);
    }
  }

  /**
   * Removes the copy the user holds of an event its organiser called off: the
   * object on the server, under the version just read; then the eXo event,
   * announced as the server's own change so this add-on's deletion listener
   * does not ask the server again; then the mapping, dropped only once agenda
   * agreed — the sweep's own order for an object that vanished. A copy the
   * server does not serve is left alone: whether it is gone or the server is
   * not answering, the sweep tells the two apart and this cannot.
   *
   * @param endpoint the account's endpoint
   * @param userIdentityId identity of the user
   * @param username their eXo login
   * @param known the mapping of the copy
   * @param master the cancellation's event
   * @param addresses the user's own addresses
   * @return what was removed
   * @throws IllegalArgumentException when the cancellation is not the copy's
   *           organiser's, or the user organises the copy
   * @throws IllegalStateException when the copy is not served, or the server
   *           or agenda refused the removal
   */
  private LandedMailInvitation remove(CalDavEndpoint endpoint,
                                      long userIdentityId,
                                      String username,
                                      ObjectSync known,
                                      IcsEvent master,
                                      List<String> addresses) {
    CalendarObject existing;
    try {
      existing = StringUtils.isBlank(known.getRemoteHref()) ? null : calDavClient.fetchObject(endpoint, known.getRemoteHref());
      if (existing == null || StringUtils.isBlank(existing.calendarData())) {
        throw new IllegalStateException("The copy of " + master.getUid() + " at " + known.getRemoteHref()
            + " is not served; the sweep decides what became of it");
      }
      refuseUnlessItsOrganiser(master, masterOfCopy(existing.calendarData()), addresses);
      int status = calendarObjectWriters.writer(endpoint).deleteObject(endpoint, known.getRemoteHref(), existing.etag());
      if (status == PutResult.PRECONDITION_FAILED) {
        throw new IllegalStateException("The copy of " + master.getUid() + " at " + known.getRemoteHref()
            + " changed while it was being removed");
      }
    } catch (CalDavException e) {
      throw new IllegalStateException("The copy of " + master.getUid() + " could not be removed from " + known.getRemoteHref(), e);
    }
    long eventId = known.getLocalEventId() == null ? 0L : known.getLocalEventId();
    long mappingId = known.getId() == null ? 0L : known.getId();
    if (eventId > 0) {
      caldavEventPropagationService.changedOnTheServer(eventId, mappingId);
      try {
        agendaEventService.deleteEventById(eventId, userIdentityId);
      } catch (ObjectNotFoundException e) {
        caldavEventPropagationService.notChangedAfterAll(eventId);
        LOG.debug("Event {} was already gone from agenda; only its mapping is dropped", eventId, e);
      } catch (Exception e) { // NOSONAR agenda declares checked refusals
        caldavEventPropagationService.notChangedAfterAll(eventId);
        // The mapping stays: dropping it would hide an event eXo can no
        // longer account for.
        throw new IllegalStateException("Event " + eventId + " of user " + userIdentityId + " could not be removed from agenda", e);
      }
    }
    if (mappingId > 0) {
      caldavSyncStorage.deleteObject(mappingId);
    }
    LOG.debug("The cancelled invitation {} of user {} was removed from their calendar (event {}, {})",
              master.getUid(),
              userIdentityId,
              eventId,
              known.getRemoteHref());
    return new LandedMailInvitation(eventId, null, true);
  }

  /**
   * The agenda event one of this deployment's own meetings is, when the UID
   * is the one agenda mails for it — {@code agenda-event-<id>@<this host>}.
   * Another deployment's meeting, whose host differs, is an external event
   * like any other.
   *
   * @param uid the message's UID
   * @return the event id, or null when the UID is not agenda's own
   */
  private static Long ownAgendaEventOf(String uid) {
    Matcher matcher = uid == null ? null : OWN_AGENDA_UID.matcher(uid.trim());
    if (matcher == null || !matcher.matches()) {
      return null;
    }
    String host = ownHost();
    return host != null && host.equalsIgnoreCase(matcher.group(2)) ? Long.valueOf(matcher.group(1)) : null;
  }

  /**
   * This deployment's host as agenda writes it into its UIDs: the configured
   * domain without scheme, port or path, {@code exo} when none is configured.
   *
   * @return the host, or null when the portal could not be asked
   */
  private static String ownHost() {
    String domain;
    try {
      domain = CommonsUtils.getCurrentDomain();
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("This deployment's own address could not be resolved; agenda's own UIDs go unrecognised", e);
      return null;
    }
    return StringUtils.isBlank(domain) ? "exo" : domain.replaceFirst("^https?://", "").replaceAll("[/:].*$", "");
  }

  /**
   * One of this deployment's own meetings, mailed by agenda to a user holding
   * no copy of it: it is in agenda already, and agenda is where it is
   * answered or cancelled. Adding it hands back agenda's own page when the
   * user may read it; nothing is written anywhere, and an answer or a
   * cancellation carried by such a message is refused — the message is the
   * sender's, and a UID is not a reason to act on an event the user was not
   * shown.
   *
   * @param eventId the agenda event
   * @param userIdentityId identity of the user
   * @param invitation the invitation
   * @return the event, with its link
   * @throws IllegalArgumentException for an answer or a cancellation, or when
   *           the user may not read the event
   */
  private LandedMailInvitation heldInAgenda(long eventId, long userIdentityId, MailInvitation invitation) {
    if (invitation.response() != null || invitation.isCancellation()) {
      throw new IllegalArgumentException("The message names eXo meeting " + eventId + ", which is answered and cancelled in agenda");
    }
    try {
      if (agendaEventService.getEventById(eventId, null, userIdentityId) == null) {
        throw new IllegalArgumentException("The message names eXo meeting " + eventId + ", which does not exist");
      }
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException("The message names eXo meeting " + eventId + ", which the user may not read", e);
    }
    LOG.debug("The message of user {} names eXo meeting {}; it is in agenda already and nothing is written", userIdentityId, eventId);
    return new LandedMailInvitation(eventId, linkOf(eventId), false);
  }

  /**
   * The mapping as the server serves the copy now: the row just reloaded,
   * carrying the version read on this call when the recorded one is behind it
   * — the sweep leaves a version unrecorded on purpose when eXo's copy is the
   * newer one, and an answer conditioned on the stale version would be refused
   * for a line it does not touch. The row is the one the answer's write then
   * saves, so it is amended in place, never copied.
   *
   * @param known the mapping as just reloaded
   * @param served the version the server answered on this call, null when
   *          none was read or the import recorded a later one
   * @return the mapping to answer on
   */
  private static ObjectSync asServed(ObjectSync known, String served) {
    if (StringUtils.isNotBlank(served)) {
      known.setEtag(served);
    }
    return known;
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
   * Only an event's organiser rewrites or removes it (RFC 5546 section 3.2.2):
   * a copy the user holds takes a message from its own ORGANIZER and nobody
   * else, and a copy the user organises themselves takes none — such a message
   * was written by somebody who learnt the UID, not by the organiser.
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
        throw new IllegalArgumentException("The invitation " + master.getUid() + " names an event the user organises; a mail does not touch it");
      }
    }
    if (copyOrganiser == null || !copyOrganiser.equals(organiserOf(master))) {
      throw new IllegalArgumentException("The invitation " + master.getUid() + " is not from the organiser of the copy the user holds");
    }
  }

  /**
   * The object to store: the message's event with METHOD gone and, when an
   * answer was given, the user's answer on their own attendee line.
   *
   * @param invitation the invitation
   * @param uid the event's UID
   * @param addresses the addresses the user's line may carry
   * @param partStat the answer as a PARTSTAT token, null to store the object as
   *          sent
   * @return the object
   * @throws IllegalArgumentException when the message cannot be turned into one
   */
  private String withAnswer(MailInvitation invitation, String uid, List<String> addresses, String partStat) {
    try {
      String stored = icsMerger.storedObject(invitation.icalendar(), uid);
      if (partStat == null) {
        return stored;
      }
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
   * Writes the answer onto the copy, then records it in agenda. The copy here
   * and nowhere else: this add-on's {@code EventResponseSavedListener}, through
   * {@code CaldavPushService#pushAnswer}, declines every event its user
   * organises, which the import makes of every landed one. Agenda through the
   * same method every eXo answer takes, without the "response sent" broadcast —
   * the imported event's organiser is the user, and that notification would be
   * addressed to them.
   *
   * @param userIdentityId identity of the user
   * @param invitation the invitation, with an answer
   * @param known the copy's mapping, carrying the version the server holds
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
    if (outcome == null || !outcome.settles()) {
      // Nothing retries this: the answer lives in agenda alone until somebody
      // repairs the copy, and the person who can is told here.
      LOG.warn("The answer {} of user {} to event {} is held by agenda and not by the copy at {} ({}); the copy names"
          + " none of {}, or cannot be written",
               invitation.response(),
               userIdentityId,
               eventId,
               known.getRemoteHref(),
               outcome,
               addresses);
    } else {
      LOG.debug("The answer {} of user {} to event {} on the copy at {}: {}",
                invitation.response(),
                userIdentityId,
                eventId,
                known.getRemoteHref(),
                outcome);
    }
    try {
      agendaEventAttendeeService.sendEventResponse(eventId, userIdentityId, invitation.response(), false);
    } catch (Exception e) { // NOSONAR agenda declares checked refusals
      throw new IllegalStateException("The answer of user " + userIdentityId + " to event " + eventId + " was refused by agenda", e);
    }
  }

  /**
   * Agenda's own page for the event, built by agenda from the platform's
   * configured domain and the event's id — never from anything in the message.
   *
   * @param eventId the agenda event
   * @return the link, or null when agenda names none
   */
  private static String linkOf(long eventId) {
    try {
      return EventIcsBuilder.eventUrl(eventId);
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("No link could be built for event {}", eventId, e);
      return null;
    }
  }
}

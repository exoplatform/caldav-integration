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

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.agenda.constant.EventStatus;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.agenda.model.EventConference;
import org.exoplatform.agenda.model.EventReminder;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventConferenceService;
import org.exoplatform.agenda.service.AgendaEventReminderService;
import org.exoplatform.agenda.util.EventIcsBuilder;
import org.exoplatform.agenda.util.Utils;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.IcsPerson;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.IcsReminder;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.SpaceIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Turns an agenda event into what the ICS engine takes.
 *
 * <p>
 * This is the seam the engine was deliberately kept clean of. Agenda holds
 * identities as numbers — a creator id, attendee identity ids — while an
 * iCalendar object needs a display name and a mail address, and resolving one
 * into the other means identity services and profile visibility. That
 * resolution lives here, where the services are, so the engine stays a pure
 * function of its argument and stays judgeable against the golden corpus.
 *
 * <p>
 * An address is never invented. A person whose profile does not expose one is
 * left off the copy entirely rather than given a plausible-looking address:
 * RFC 5545 makes the value a CAL-ADDRESS, and a fabricated one would be
 * forwarded as a reply-to by any client acting on the copy. The roster on the
 * phone may therefore be shorter than the one in eXo, which the URL property
 * links back to in full.
 */
@Component
public class AgendaEventIcsMapper {

  /** Logger for what is degraded rather than failed: an undeterminable language. */
  private static final Log            LOG = ExoLogger.getLogger(AgendaEventIcsMapper.class);

  /** The roster of an event, before it is filtered to the addressable. */
  @Autowired
  private AgendaEventAttendeeService  agendaEventAttendeeService;

  /** Holds the account a copy names its owner by. */
  @Autowired
  private CaldavConnectorStorage     caldavConnectorStorage;

  /** The conference links an event carries; only the first is written. */
  @Autowired
  private AgendaEventConferenceService agendaEventConferenceService;

  /** The user's own reminders, which become the copy's alarms. */
  @Autowired
  private AgendaEventReminderService  agendaEventReminderService;

  /** Resolves an identity id into the name and address a calendar user needs. */
  @Autowired
  private IdentityManager             identityManager;

  /** The calendar an event sits on, which is what names the space it came from. */
  @Autowired
  private AgendaCalendarService        agendaCalendarService;

  /** Turns a space identity into the display name the attribution carries. */
  @Autowired
  private SpaceService                 spaceService;

  /**
   * Maps one agenda event, resolving the identities it references and the
   * address the pushing user's own account answers to.
   *
   * <p>
   * A calendar client decides whether an event is an invitation TO ITS OWNER
   * by matching the ATTENDEE addresses against the ones the account owns. The
   * copy names everyone by their eXo profile address, which the account
   * knows nothing about — so the owner's own line looked like somebody else's
   * and no client offered to answer it. Their line carries the account's own
   * address instead, and only theirs: the other attendees are described to
   * them, not to their server.
   *
   * <p>
   * The description is composed here rather than in the writer, from agenda's
   * shared builder, so the copy and the mailed document describe one meeting
   * in one set of words (EXO-89732).
   *
   * @param event the agenda event to copy
   * @param icsUid the UID the copy is written under, which the caller owns —
   *          a series and its overrides share one, and it must survive an
   *          agenda id changing
   * @param pusherIdentityId identity of the user whose calendar receives the
   *          copy, which decides whether ORGANIZER carries SCHEDULE-AGENT and
   *          whose account address names them on the roster
   * @return the event as the ICS engine takes it
   */
  public IcsEvent toIcsEvent(Event event, String icsUid, long pusherIdentityId) {
    String pusherAccountAddress = accountAddressOf(pusherIdentityId);
    IcsPerson organizer = personOf(event.getCreatorId());
    String conference = conferenceUrl(event.getId());
    String link = eventUrl(event);
    return IcsEvent.builder()
                   .uid(icsUid)
                   .summary(event.getSummary())
                   .location(event.getLocation())
                   .description(description(event, conference, link, pusherIdentityId))
                   .eventUrl(link)
                   .conferenceUrl(conference)
                   .start(instantOf(event.getStart()))
                   .end(instantOf(event.getEnd()))
                   .allDay(event.isAllDay())
                   .timeZoneId(event.getTimeZoneId() == null ? null : event.getTimeZoneId().getId())
                   .created(instantOf(event.getCreated()))
                   .updated(instantOf(event.getUpdated()))
                   .organizer(organizer)
                   .organizerIsPusher(event.getCreatorId() == pusherIdentityId)
                   .attendees(attendees(event.getId(), pusherIdentityId, pusherAccountAddress))
                   .recurrenceRule(event.getRecurrence() == null ? null : event.getRecurrence().getRrule())
                   .occurrenceId(occurrenceId(event))
                   .reminders(reminders(event.getId(), pusherIdentityId))
                   // eXo hides a cancelled event from its own screens, so the
                   // only place its attendees can still be told the meeting is
                   // off is the copy. Carried as a flag rather than the status
                   // itself: TENTATIVE is eXo's word for a date poll, which is
                   // never pushed, so CANCELLED is the only other thing a copy
                   // can truthfully say.
                   .cancelled(event.getStatus() == EventStatus.CANCELLED)
                   // Exclusions are deliberately absent: agenda's model exposes
                   // no list of excluded instances, and deleting an occurrence
                   // goes through a rewrite of the stored object instead. Left
                   // empty rather than invented — an EXDATE for an instance
                   // agenda did not exclude would delete a meeting nobody
                   // cancelled.
                   .exceptionDates(List.of())
                   .build();
  }

  /**
   * What the copy says the meeting is, in the words the mail already uses.
   *
   * <p>
   * The whole text — the attribution, the conference line and the event's own
   * description rendered out of the markup the editor stored it as — comes
   * from agenda's {@link EventIcsBuilder}, which is the same code that writes
   * the description of the document attached to the notification mail
   * (EXO-89732). Before that, the copy carried the event's raw description and
   * nothing else, so a meeting with no description of its own reached the
   * user's calendar with no DESCRIPTION at all: an entry among fifty others
   * with nothing to say which system put it there or which space it came from.
   *
   * <p>
   * What comes back is <b>plain text</b>, and that is the whole contract with
   * {@link org.exoplatform.caldav.ics.IcsWriter}: the rendering happens here,
   * where the identity and calendar services are, exactly as the roster's does.
   * It also aligns the write path with the read path, which has always put the
   * DESCRIPTION it read off the wire — plain text by RFC 5545 &sect;3.8.1.5 —
   * into this same field.
   *
   * @param event the event being copied
   * @param conference the conference link, already resolved, or null
   * @param link the link back to the event in eXo, already resolved, or null
   * @param pusherIdentityId the user whose calendar receives the copy, whose
   *          language the text is written in
   * @return the description the copy carries
   */
  private String description(Event event, String conference, String link, long pusherIdentityId) {
    return EventIcsBuilder.description(localeOf(pusherIdentityId),
                                       fullNameOf(event.getCreatorId()),
                                       spaceNameOf(event),
                                       conference,
                                       link,
                                       event.getDescription());
  }

  /**
   * The link back to the event in eXo, written as {@code URL} and repeated on
   * a labelled line in the description.
   *
   * <p>
   * <b>Derived here, never taken from the caller</b> — and that is the whole
   * point of EXO-89751. The value used to arrive on the push request, put
   * there by the browser, so only a browser push carried one: a sweep and a
   * repair rendered the same event with no {@code URL} at all, and the link
   * appeared once and was stripped by the next repair. It is also why the
   * mirror comparison had to exempt the property. Reading it off the event
   * makes every render agree, which is what lets
   * {@link org.exoplatform.caldav.ics.IcsEquivalence} compare it like anything
   * else.
   *
   * <p>
   * The shape belongs to agenda: {@link EventIcsBuilder#eventUrl} is the same
   * {@code agenda?eventId=} address that goes into the body of every
   * notification mail about this event.
   *
   * <p>
   * A series override links to the series, not to itself, for the same reason
   * the copy shares its UID with the series: the object carries
   * {@code RECURRENCE-ID} to say which instance it amends, and the parent id
   * is the one agenda's screens open.
   *
   * <p>
   * No guest clause here, unlike the mail. A copy is written into the calendar
   * of a user who has an eXo account by construction — there is nobody on this
   * path the link could send to a login screen.
   *
   * @param event the event being copied
   * @return the absolute link, or null when it cannot be composed
   */
  private String eventUrl(Event event) {
    return EventIcsBuilder.eventUrl(event.getParentId() > 0 ? event.getParentId() : event.getId());
  }

  /**
   * The language the copy's description is written in: the recipient's own.
   *
   * <p>
   * The copy lands in <i>their</i> calendar, so it is read in their language,
   * not the organizer's. A user whose language cannot be determined gets the
   * platform default, which is what agenda falls back to as well.
   *
   * <p>
   * A language that cannot be determined is never a reason to fail the push:
   * the copy is the user's record of a meeting, and one written in the platform
   * default language is worth incomparably more than one that never arrived.
   * The lookup reaches into the portal's locale policy, which walks a chain of
   * contributed plugins, so the guard covers a {@link LinkageError} as well as
   * a runtime failure: a plugin class absent from the classpath is not a
   * runtime exception, and left uncaught it would take down the push of a
   * meeting over the language its description is written in.
   *
   * @param identityId the user whose calendar receives the copy
   * @return their locale, never null
   */
  private Locale localeOf(long identityId) {
    try {
      Identity identity = identityManager.getIdentity(identityId);
      if (identity == null || StringUtils.isBlank(identity.getRemoteId())) {
        return Locale.getDefault();
      }
      String language = Utils.getUserLanguage(identity.getRemoteId());
      return StringUtils.isBlank(language) ? Locale.getDefault() : Locale.forLanguageTag(language.replace('_', '-'));
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("No language could be determined for identity {}; the copy is described in the default language",
                identityId,
                e);
      return Locale.getDefault();
    }
  }

  /**
   * The display name of one identity, whether or not it exposes an address.
   *
   * <p>
   * Deliberately not read through {@link #personOf(long)}: that one answers
   * null for anybody with no visible mail address, which is the right rule for
   * a roster — an address is never invented — and the wrong one for an
   * attribution, where a name is all that is being written and no address is
   * implied by it.
   *
   * @param identityId the social identity
   * @return the display name, or null when the identity resolves to nothing
   */
  private String fullNameOf(long identityId) {
    Identity identity = identityManager.getIdentity(identityId);
    if (identity == null || identity.getProfile() == null) {
      return null;
    }
    return StringUtils.trimToNull(identity.getProfile().getFullName());
  }

  /**
   * The space the event belongs to, when it belongs to one.
   *
   * <p>
   * An event lives on a calendar, and a calendar is owned by an identity that
   * is either a space or a user. Only the first has a name to attribute the
   * meeting to; an event on somebody's personal calendar answers null here,
   * and the builder drops the clause rather than writing the word "null".
   *
   * @param event the event being copied
   * @return the space display name, or null
   */
  private String spaceNameOf(Event event) {
    Calendar calendar = agendaCalendarService.getCalendarById(event.getCalendarId());
    if (calendar == null) {
      return null;
    }
    Identity owner = identityManager.getIdentity(calendar.getOwnerId());
    if (owner == null || !SpaceIdentityProvider.NAME.equals(owner.getProviderId())) {
      return null;
    }
    Space space = spaceService.getSpaceByPrettyName(owner.getRemoteId());
    return space == null ? null : StringUtils.trimToNull(space.getDisplayName());
  }

  /**
   * The instance an override amends, when the event is one.
   *
   * @param event the agenda event
   * @return the occurrence identifier as an ISO instant, or null
   */
  private String occurrenceId(Event event) {
    if (event.getOccurrence() == null || event.getOccurrence().getId() == null) {
      return null;
    }
    return event.getOccurrence().getId().toInstant().toString();
  }

  /**
   * The first conference link, which is the only one written — a parameter
   * value holding a comma gets quoted into one value a strict reader ignores,
   * so one correct token beats two read as none.
   *
   * @param eventId the agenda event
   * @return the link, or null
   */
  private String conferenceUrl(long eventId) {
    List<EventConference> conferences = agendaEventConferenceService.getEventConferences(eventId);
    if (conferences == null || conferences.isEmpty()) {
      return null;
    }
    return StringUtils.trimToNull(conferences.get(0).getUrl());
  }

  /**
   * The roster, with the people whose address is not visible left off.
   *
   * @param eventId the agenda event
   * @return the attendees that can be named truthfully
   */
  private List<IcsPerson> attendees(long eventId, long pusherIdentityId, String pusherAccountAddress) {
    List<IcsPerson> people = new ArrayList<>();
    List<EventAttendee> attendees = agendaEventAttendeeService.getEventAttendees(eventId).getEventAttendees();
    if (attendees == null) {
      return people;
    }
    for (EventAttendee attendee : attendees) {
      IcsPerson person = personOf(attendee.getIdentityId());
      if (person != null && attendee.getIdentityId() == pusherIdentityId
          && StringUtils.isNotBlank(pusherAccountAddress)) {
        // Their own line, spelled the way their account spells them.
        person.setEmail(pusherAccountAddress);
      }
      if (person != null) {
        person.setResponse(attendee.getResponse() == null ? null : attendee.getResponse().name());
        people.add(person);
      }
    }
    return people;
  }

  /**
   * The <b>profile</b> address a copy names one person by, or null when it
   * names them not at all.
   *
   * <p>
   * Public because propagating an answer outward has to find that person's
   * ATTENDEE line in an object already on the server. It is deliberately named
   * for what it is and not "the address of" that user, because it is not the
   * only one a copy may carry: the account's own owner is named by the address
   * their CalDAV account answers to, so that a client recognises the meeting
   * as an invitation to itself. Treating this one as authoritative is how the
   * propagation silently matched nothing on a live rig — the caller offers
   * both and lets the object decide.
   *
   * <p>
   * The rule that a person with no visible address is left off the copy is the
   * same rule read from the other end: no address here means this mapper wrote
   * no ATTENDEE line for them to rewrite.
   *
   * @param identityId the social identity
   * @return the profile mail address, or null when none is visible
   */
  public String addressOf(long identityId) {
    IcsPerson person = personOf(identityId);
    return person == null ? null : person.getEmail();
  }

  /**
   * The address the user's own CalDAV account answers to, when they have one.
   *
   * @param identityId the user whose account is written into
   * @return the account address, or null when there is none to use
   */
  private String accountAddressOf(long identityId) {
    CaldavUserSetting account = caldavConnectorStorage == null ? null
                                                               : caldavConnectorStorage.getCaldavSetting(identityId);
    return account == null ? null : StringUtils.trimToNull(account.getUsername());
  }

  /**
   * One identity as a calendar user, or null when no address is visible.
   *
   * <p>
   * Spaces land here too and have no mail address, which is the same answer
   * for a different reason: a space is not a calendar user.
   *
   * @param identityId the social identity
   * @return the person, or null
   */
  private IcsPerson personOf(long identityId) {
    Identity identity = identityManager.getIdentity(identityId);
    if (identity == null || identity.getProfile() == null) {
      return null;
    }
    Profile profile = identity.getProfile();
    String email = StringUtils.trimToNull(profile.getEmail());
    return email == null ? null : new IcsPerson(profile.getFullName(), email, null);
  }

  /**
   * The reminders, in agenda's own quantity-and-unit terms.
   *
   * @param eventId the agenda event
   * @param userIdentityId whose reminders to read
   * @return the reminders, possibly empty
   */
  private List<IcsReminder> reminders(long eventId, long userIdentityId) {
    List<IcsReminder> reminders = new ArrayList<>();
    List<EventReminder> eventReminders = agendaEventReminderService.getEventReminders(eventId, userIdentityId);
    if (eventReminders == null) {
      return reminders;
    }
    for (EventReminder reminder : eventReminders) {
      reminders.add(new IcsReminder(reminder.getBefore(),
                                    reminder.getBeforePeriodType() == null ? null
                                                                           : reminder.getBeforePeriodType().name()));
    }
    return reminders;
  }

  /**
   * A zoned time as an instant, or null.
   *
   * @param value the value to convert
   * @return the instant, or null
   */
  private java.time.Instant instantOf(ZonedDateTime value) {
    return value == null ? null : value.toInstant();
  }
}

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

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.EventAttendee;
import org.exoplatform.agenda.model.EventConference;
import org.exoplatform.agenda.model.EventReminder;
import org.exoplatform.agenda.service.AgendaEventAttendeeService;
import org.exoplatform.agenda.service.AgendaEventConferenceService;
import org.exoplatform.agenda.service.AgendaEventReminderService;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.IcsPerson;
import org.exoplatform.caldav.model.IcsReminder;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

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

  /** The roster of an event, before it is filtered to the addressable. */
  @Autowired
  private AgendaEventAttendeeService  agendaEventAttendeeService;

  /** The conference links an event carries; only the first is written. */
  @Autowired
  private AgendaEventConferenceService agendaEventConferenceService;

  /** The user's own reminders, which become the copy's alarms. */
  @Autowired
  private AgendaEventReminderService  agendaEventReminderService;

  /** Resolves an identity id into the name and address a calendar user needs. */
  @Autowired
  private IdentityManager             identityManager;

  /**
   * Maps one agenda event, resolving the identities it references.
   *
   * @param event the agenda event to copy
   * @param icsUid the UID the copy is written under, which the caller owns —
   *          a series and its overrides share one, and it must survive an
   *          agenda id changing
   * @param eventUrl absolute link back to the event in eXo
   * @param pusherIdentityId identity of the user whose calendar receives the
   *          copy, which decides whether ORGANIZER carries SCHEDULE-AGENT
   * @return the event as the ICS engine takes it
   */
  public IcsEvent toIcsEvent(Event event, String icsUid, String eventUrl, long pusherIdentityId) {
    IcsPerson organizer = personOf(event.getCreatorId());
    return IcsEvent.builder()
                   .uid(icsUid)
                   .summary(event.getSummary())
                   .location(event.getLocation())
                   .description(event.getDescription())
                   .eventUrl(eventUrl)
                   .conferenceUrl(conferenceUrl(event.getId()))
                   .start(instantOf(event.getStart()))
                   .end(instantOf(event.getEnd()))
                   .allDay(event.isAllDay())
                   .timeZoneId(event.getTimeZoneId() == null ? null : event.getTimeZoneId().getId())
                   .created(instantOf(event.getCreated()))
                   .updated(instantOf(event.getUpdated()))
                   .organizer(organizer)
                   .organizerIsPusher(event.getCreatorId() == pusherIdentityId)
                   .attendees(attendees(event.getId()))
                   .recurrenceRule(event.getRecurrence() == null ? null : event.getRecurrence().getRrule())
                   .occurrenceId(occurrenceId(event))
                   .reminders(reminders(event.getId(), pusherIdentityId))
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
  private List<IcsPerson> attendees(long eventId) {
    List<IcsPerson> people = new ArrayList<>();
    List<EventAttendee> attendees = agendaEventAttendeeService.getEventAttendees(eventId).getEventAttendees();
    if (attendees == null) {
      return people;
    }
    for (EventAttendee attendee : attendees) {
      IcsPerson person = personOf(attendee.getIdentityId());
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

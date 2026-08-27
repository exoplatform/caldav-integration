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
package org.exoplatform.caldav.model;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything the ICS engine needs about one event, and nothing else.
 *
 * <p>
 * Deliberately not agenda's own {@code Event}. That model carries identities
 * as numbers — a creator id, attendee identity ids — which the copy cannot
 * use: what an iCalendar object needs is a display name and a mail address,
 * and resolving one into the other means identity services, profile
 * visibility and ACL. Taking an already-resolved input keeps the engine a
 * pure function of its argument, which is what lets it be judged against the
 * golden corpus at all, and keeps identity resolution where the services are.
 *
 * <p>
 * Instants rather than zoned times, with the zone named separately: agenda
 * supplies both a date and an instant for the two ends of an all-day event,
 * and every rule here reads a wall clock in an explicit zone rather than
 * trusting whatever zone a value arrived carrying.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IcsEvent {

  /** The UID shared by the series and every one of its overrides. */
  private String            uid;

  /** Title of the event. */
  private String            summary;

  /** Where it takes place, free text. */
  private String            location;

  /** The description as agenda holds it, HTML included; converted on the way out. */
  private String            description;

  /** Link back to the event in eXo, written as URL. */
  private String            eventUrl;

  /** Conference link, written both into the description and as CONFERENCE. */
  private String            conferenceUrl;

  /** Start instant. */
  private Instant           start;

  /** End instant; for an all-day event, the last day the event covers. */
  private Instant           end;

  /** Whether the event covers whole days rather than a span of time. */
  private boolean           allDay;

  /** IANA zone the wall clock is read in. */
  private String            timeZoneId;

  /** When the event was created in eXo; omitted when absent. */
  private Instant           created;

  /** When the event was last modified in eXo; omitted when absent. */
  private Instant           updated;

  /**
   * Who called the meeting — the eXo event's organizer, never the connected
   * CalDAV account. Naming the account owner on a meeting they merely
   * accepted would put a subtly false event in their calendar, and their
   * client could then offer to send invitations on their behalf for a meeting
   * somebody else called.
   */
  private IcsPerson         organizer;

  /**
   * Whether the user pushing this copy is its organizer. Decides whether
   * ORGANIZER carries SCHEDULE-AGENT=CLIENT: the parameter belongs on
   * ORGANIZER precisely in an attendee's copy (RFC 6638 section 7.1).
   */
  private boolean           organizerIsPusher;

  /** Who is expected; those without a visible address are not written. */
  private List<IcsPerson>   attendees;

  /** The repetition rule, in RFC 5545 form, for a master component. */
  private String            recurrenceRule;

  /**
   * Instances removed from the series, as agenda supplies them: a plain date
   * for an all-day series, an instant otherwise.
   */
  private List<String>      exceptionDates;

  /**
   * The instance this component amends, when it is an override rather than a
   * master. Its presence is what makes the component an occurrence.
   */
  private String            occurrenceId;

  /** Reminders to turn into VALARM components. */
  private List<IcsReminder> reminders;

  /**
   * Whether this meeting has been called off.
   *
   * <p>
   * The one thing about a meeting that is not a property of the meeting but a
   * statement about it, which is why it is a flag and not a status string. Every
   * event this engine writes is CONFIRMED except a cancelled one — eXo spells a
   * date poll TENTATIVE and a poll is never pushed, so the only other value
   * RFC 5545 offers that eXo can ever mean is CANCELLED.
   */
  private boolean           cancelled;

}

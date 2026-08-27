/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.ics;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.IcsPerson;
import org.exoplatform.caldav.model.IcsReminder;

/**
 * Reads a golden driver input — the agenda-event JSON the browser connector
 * was fed when the corpus was captured — into the engine's own input model.
 *
 * <p>
 * This mapping is the test's honest weak point and is worth naming: the
 * fixtures are in the shape agenda hands the <i>browser</i>, and PR5 will hand
 * the engine an equivalent object built from agenda's Java model instead. What
 * the corpus proves is therefore that the engine turns a given event into the
 * right iCalendar object — not that PR5 will populate that event correctly.
 * The second half is PR5's to demonstrate.
 */
public final class GoldenEventFixture {

  /**
   * The identity the capture driver ran as ({@code capture-goldens.mjs} sets
   * {@code userIdentityId: '5'}). It decides whether ORGANIZER carries
   * SCHEDULE-AGENT=CLIENT, so reproducing a golden means reproducing the
   * identity that produced it.
   */
  private static final String CAPTURE_USER_IDENTITY_ID = "5";

  private GoldenEventFixture() {
    // fixture helper
  }

  /**
   * Builds the engine input for one golden case.
   *
   * @param event the {@code event} node of a driver-input fixture
   * @return the event as the ICS engine takes it
   */
  public static IcsEvent toIcsEvent(JsonNode event) {
    return IcsEvent.builder()
                   .uid(text(event, "remoteId"))
                   .summary(text(event, "summary"))
                   .location(text(event, "location"))
                   .description(description(event))
                   .eventUrl(eventUrl(event))
                   .conferenceUrl(conferenceUrl(event))
                   .start(IcsText.parseInstant(text(event, "start")))
                   .end(IcsText.parseInstant(text(event, "end")))
                   .allDay(event.path("allDay").asBoolean(false))
                   .timeZoneId(text(event, "timeZoneId"))
                   .created(IcsText.parseInstant(text(event, "created")))
                   .updated(IcsText.parseInstant(text(event, "updated")))
                   .organizer(person(event.path("creator")))
                   .organizerIsPusher(CAPTURE_USER_IDENTITY_ID.equals(text(event.path("creator"), "id")))
                   .attendees(attendees(event))
                   .recurrenceRule(text(event.path("recurrence"), "rrule"))
                   .exceptionDates(exceptionDates(event))
                   .occurrenceId(text(event.path("occurrence"), "id"))
                   .reminders(reminders(event))
                   .build();
  }

  /**
   * The eXo back-link the connector writes as URL. Reproduced here from the
   * captured goldens rather than derived: the browser builds it from the
   * portal's own location, which no test can stand in for.
   *
   * @param event the event node
   * @return the link, or null when the fixture carries no id
   */
  private static String eventUrl(JsonNode event) {
    JsonNode id = event.get("id");
    return id == null || id.isNull() ? null : "https://exo.example.test/portal/dw/agenda?eventId=" + id.asText();
  }

  /**
   * The plain-text description the engine now takes, composed here because in
   * production it is composed before the engine sees it.
   *
   * <p>
   * Since EXO-89732 the rendering of the description — markup to text, plus the
   * conference line — happens in
   * {@link org.exoplatform.caldav.service.AgendaEventIcsMapper}, and
   * {@link IcsWriter} writes the plain text RFC 5545 &sect;3.8.1.5 defines
   * DESCRIPTION to be. This fixture stands in for that mapper, so the
   * composition moves here with it and the corpus keeps judging the engine on
   * the same input the engine is given for real.
   *
   * <p>
   * One thing production adds that this does <b>not</b>: the space attribution
   * ("Invitation sent by X in space Y"). The goldens were captured from the
   * browser connector, which never wrote it, and adding it here would make
   * every text case differ from its capture for a reason that has nothing to do
   * with the engine. The attribution is a deliberate departure from that
   * captured behaviour and is pinned where it belongs — in
   * {@code AgendaEventIcsMapperTest} and {@code IcsWriterPayloadContractTest} —
   * rather than by weakening what this corpus can still see.
   *
   * @param event the {@code event} node of a driver-input fixture
   * @return the description as the engine takes it, or null when there is none
   */
  private static String description(JsonNode event) {
    String conferenceUrl = conferenceUrl(event);
    java.util.List<String> parts = new java.util.ArrayList<>();
    String text = IcsText.htmlToText(text(event, "description"));
    if (text != null && !text.isBlank()) {
      parts.add(text);
    }
    if (conferenceUrl != null) {
      parts.add(conferenceUrl);
    }
    return parts.isEmpty() ? null : String.join("\n\n", parts);
  }

  /**
   * The first conference link, which is the only one the connector writes.
   *
   * @param event the event node
   * @return the link, or null
   */
  private static String conferenceUrl(JsonNode event) {
    JsonNode conferences = event.path("conferences");
    return conferences.isArray() && !conferences.isEmpty() ? text(conferences.get(0), "url") : null;
  }

  /**
   * A calendar user from an identity-bearing node.
   *
   * @param node a creator node, or an attendee's identity
   * @return the person, or null when the node carries no profile
   */
  private static IcsPerson person(JsonNode node) {
    JsonNode profile = node.path("profile");
    if (profile.isMissingNode()) {
      return null;
    }
    return new IcsPerson(text(profile, "fullname"), text(profile, "email"), null);
  }

  /**
   * The attendee roster, responses included.
   *
   * @param event the event node
   * @return the attendees, possibly empty
   */
  private static List<IcsPerson> attendees(JsonNode event) {
    List<IcsPerson> people = new ArrayList<>();
    for (JsonNode attendee : event.path("attendees")) {
      IcsPerson person = person(attendee.path("identity"));
      if (person != null) {
        person.setResponse(text(attendee, "response"));
      }
      // A null person is kept out, but an address-less one is passed through
      // on purpose: dropping it here would hide the engine's own rule about
      // never inventing an address, which one golden exists to pin.
      if (person != null) {
        people.add(person);
      }
    }
    return people;
  }

  /**
   * The instances removed from a series.
   *
   * @param event the event node
   * @return the exception dates, possibly empty
   */
  private static List<String> exceptionDates(JsonNode event) {
    List<String> dates = new ArrayList<>();
    for (JsonNode exception : event.path("recurrence").path("exceptions")) {
      String date = text(exception, "date");
      if (date != null) {
        dates.add(date);
      }
    }
    return dates;
  }

  /**
   * The reminders, in agenda's own quantity-and-unit terms.
   *
   * @param event the event node
   * @return the reminders, possibly empty
   */
  private static List<IcsReminder> reminders(JsonNode event) {
    List<IcsReminder> reminders = new ArrayList<>();
    for (JsonNode reminder : event.path("reminders")) {
      reminders.add(new IcsReminder(reminder.path("before").asLong(-1), text(reminder, "beforePeriodType")));
    }
    return reminders;
  }

  /**
   * A text field, or null when it is absent or null.
   *
   * @param node the containing node
   * @param field the field name
   * @return the value, or null
   */
  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }
}

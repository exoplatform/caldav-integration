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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.exoplatform.caldav.model.RemoteIcsEvent;

/**
 * The read path against the captured read corpus (EXO-89521).
 *
 * <p>
 * One thing has to be said plainly about what this corpus can and cannot
 * prove, because the difference decides what the tests below are allowed to
 * claim. The read goldens were captured through Stalwart, which honours
 * CALDAV:expand: the browser connector parsed <b>the server's expansion</b>,
 * not the stored object. So a golden records the behaviour of the whole system
 * — connector plus an expanding server — and the input the connector actually
 * saw is only preserved for r01, in the calendar-query transcript.
 *
 * <p>
 * The two tests therefore ask different questions. The transcript replay is an
 * exact reproduction: same input, same expected output, no allowance. The
 * client-side expansion cannot be exact, because it starts from the stored
 * object instead, and that is the branch that matters for a server-side engine
 * — whether a given server expands is unproven for BlueMind. What it pins is
 * the load-bearing part: which occurrences a series produces, and at which
 * instants.
 */
public class IcsReaderGoldenTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant      FROM   = Instant.parse("2026-09-30T22:00:00Z");

  private static final Instant      TO     = Instant.parse("2026-11-30T23:00:00Z");

  private final IcsReader           reader = new IcsReader();

  @Test
  public void reproducesTheCapturedReadExactlyFromTheServerExpansion() throws Exception {
    // r01 is the one case whose real input survives: the transcript holds the
    // calendar-data Stalwart answered, which is what the connector parsed.
    String expanded = calendarDataOf(resource("caldav/transcripts/stalwart-calendar-query-expand.xml"));
    List<RemoteIcsEvent> read = reader.read(expanded, FROM, TO);
    JsonNode golden = goldenEvents("r01-tb-weekly");

    // The transcript captures a narrower window than the golden — four
    // occurrences against nine — so it is compared against the golden's first
    // four rather than against all of it. Asserting the count against the
    // golden would have been comparing two different questions.
    assertEquals(4, read.size(), "the transcript carries four occurrences");
    for (int i = 0; i < read.size(); i++) {
      JsonNode expected = golden.get(i);
      RemoteIcsEvent actual = read.get(i);
      assertEquals(expected.get("uid").asText(), actual.getUid(), "uid of occurrence " + i);
      assertEquals(expected.get("summary").asText(), actual.getSummary(), "summary of occurrence " + i);
      assertEquals(Instant.parse(expected.get("start").asText()), actual.getStart(), "start of occurrence " + i);
      assertEquals(Instant.parse(expected.get("end").asText()), actual.getEnd(), "end of occurrence " + i);
    }
  }

  @Test
  public void expandsTheStoredSeriesOntoTheSameInstants() throws Exception {
    // The same series, read from the object the server stores rather than from
    // its expansion — the branch a non-expanding server leaves us. The
    // instants are what must agree: this is the EXO-89402 class, where a
    // TZID-anchored series crossing the October transition holds its wall
    // clock and therefore moves in UTC (07:00Z before, 08:00Z after).
    String stored = Files.readString(resource("caldav/golden/read/objects/r01-thunderbird-weekly-paris.ics"));
    List<RemoteIcsEvent> read = reader.read(stored, FROM, TO);
    JsonNode golden = goldenEvents("r01-tb-weekly");

    assertEquals(golden.size(), read.size(), "client-side expansion must produce the same occurrences");
    for (int i = 0; i < golden.size(); i++) {
      assertEquals(Instant.parse(golden.get(i).get("start").asText()),
                   read.get(i).getStart(),
                   "start of occurrence " + i + " — the wall clock must hold across the transition");
    }
    assertTrue(read.stream().anyMatch(event -> event.getStart().toString().endsWith("T07:00:00Z")),
               "occurrences before the transition sit at 07:00Z");
    assertTrue(read.stream().anyMatch(event -> event.getStart().toString().endsWith("T08:00:00Z")),
               "occurrences after it sit at 08:00Z");
  }

  @Test
  public void appliesAnOverrideListedBeforeItsMaster() throws Exception {
    // r03's object lists its override first. Order must not decide which
    // component is the series.
    String stored = Files.readString(resource("caldav/golden/read/objects/r03-override-first-ordering.ics"));
    List<RemoteIcsEvent> read = reader.read(stored, FROM, TO);

    assertTrue(read.size() > 1, "the series must expand, whatever order its components are in");
    assertTrue(read.stream().allMatch(event -> "r03-override-first@example.test".equals(event.getUid())),
               "every occurrence carries the series UID");
  }

  @Test
  public void readsAnAllDayObjectAsAllDay() throws Exception {
    // The stored object says VALUE=DATE, so the occurrence is all-day. The
    // golden says otherwise — it recorded a server rendition that had already
    // rewritten the date into a UTC instant — which is a divergence in the
    // corpus's favour rather than a defect: reading the stored object, the
    // honest answer is the one the object gives.
    String stored = Files.readString(resource("caldav/golden/read/objects/r02-apple-allday.ics"));
    List<RemoteIcsEvent> read = reader.read(stored, FROM, TO);

    assertEquals(1, read.size());
    assertTrue(read.get(0).isAllDay(), "a VALUE=DATE event read from the stored object is all-day");
  }

  @Test
  public void keepsAnEventWhoseZoneTheObjectNeverDefines() throws Exception {
    // r04 carries a TZID with no VTIMEZONE. The event must still be read:
    // dropping it would lose a real meeting over a missing definition.
    String stored = Files.readString(resource("caldav/golden/read/objects/r04-tzid-without-vtimezone.ics"));
    List<RemoteIcsEvent> read = reader.read(stored, FROM, TO);

    assertEquals(1, read.size());
    assertEquals("r04-dangling-tzid@example.test", read.get(0).getUid());
  }

  /**
   * The events a read golden recorded.
   *
   * @param name the golden name, without extension
   * @return the events array
   * @throws Exception when the golden cannot be read
   */
  private JsonNode goldenEvents(String name) throws Exception {
    return MAPPER.readTree(resource("caldav/golden/read/" + name + ".read-golden.json").toFile()).get("events");
  }

  /**
   * The calendar-data a captured multistatus carries.
   *
   * @param transcript the transcript file
   * @return the iCalendar document inside it
   * @throws Exception when the transcript cannot be read
   */
  private String calendarDataOf(Path transcript) throws Exception {
    String xml = Files.readString(transcript);
    int start = xml.indexOf("<![CDATA[");
    int end = xml.indexOf("]]>", start);
    return xml.substring(start + "<![CDATA[".length(), end);
  }

  /**
   * A classpath resource as a path.
   *
   * @param path the resource path
   * @return the resolved path
   * @throws Exception when the resource is unresolvable
   */
  private static Path resource(String path) throws Exception {
    return Paths.get(IcsReaderGoldenTest.class.getClassLoader().getResource(path).toURI());
  }
}

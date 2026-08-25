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
package org.exoplatform.caldav.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.util.CompatibilityHints;

/**
 * The corpus's own integrity: every golden was captured from the live
 * browser connector against the Stalwart rig (see each envelope's provenance
 * block), and these tests hold three promises about it. Each golden parses
 * and equals itself under the semantic comparator; each golden survives a
 * full ical4j re-serialisation — the exact cosmetic transformation PR3's
 * engine applies — without registering a difference; and a deliberate
 * one-hour shift of any golden's start does register, so the net is proven
 * live on the real corpus, not only on hand-built samples. The named
 * invariant tests then pin, fixture by fixture, that the capture really
 * exhibits the behaviour its provenance claims — a golden that silently
 * stopped exhibiting its invariant would otherwise be a hole in the net that
 * nothing reports.
 */
public class GoldenCorpusTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The directory of the write goldens on the test classpath.
   *
   * @return the directory path
   * @throws URISyntaxException when the classpath resource is unresolvable
   */
  private static Path writeGoldenDirectory() throws URISyntaxException {
    return Paths.get(GoldenCorpusTest.class.getClassLoader().getResource("caldav/golden/write").toURI());
  }

  /**
   * Every write golden envelope of the corpus.
   *
   * @return the envelope files, sorted by name
   * @throws Exception when the corpus cannot be listed
   */
  private static List<Path> writeGoldens() throws Exception {
    try (Stream<Path> files = Files.list(writeGoldenDirectory())) {
      return files.filter(file -> file.getFileName().toString().endsWith(".golden.json")).sorted().toList();
    }
  }

  /**
   * The stored ICS of one golden envelope.
   *
   * @param file the envelope file
   * @return the ICS text
   * @throws IOException when the envelope cannot be read
   */
  private static String icsOf(Path file) throws IOException {
    JsonNode envelope = MAPPER.readTree(file.toFile());
    JsonNode ics = envelope.get("ics");
    assertNotNull(ics, file + " must carry the stored object under 'ics'");
    return ics.asText();
  }

  /**
   * The stored ICS of the golden a fixture name designates.
   *
   * @param name the golden file name, without extension
   * @return the ICS text
   * @throws Exception when the golden cannot be read
   */
  private static String golden(String name) throws Exception {
    return icsOf(writeGoldenDirectory().resolve(name + ".golden.json"));
  }

  /**
   * An ICS text with folding undone, for spot-checking raw statements
   * without depending on where a serialiser broke its lines.
   *
   * @param ics the ICS text
   * @return the unfolded text
   */
  private static String unfolded(String ics) {
    return ics.replace("\r\n ", "").replace("\n ", "").replace("\r\n\t", "").replace("\n\t", "");
  }

  /**
   * Every golden has a provenance block that says it was captured, from
   * what, against which server — the discipline that keeps a reconstructed
   * fixture from ever passing for a captured one.
   *
   * @throws Exception when the corpus cannot be read
   */
  @Test
  public void everyGoldenRecordsItsCaptureProvenance() throws Exception {
    List<Path> goldens = writeGoldens();
    assertFalse(goldens.isEmpty(), "the write corpus must not be empty");
    for (Path file : goldens) {
      JsonNode envelope = MAPPER.readTree(file.toFile());
      JsonNode provenance = envelope.get("provenance");
      assertNotNull(provenance, file + " must carry a provenance block");
      assertEquals("captured", provenance.get("kind").asText(), file + " must be a live capture");
      assertTrue(provenance.get("capturedFrom").asText().contains("caldavConnector.js"),
                 file + " must name the implementation it was captured from");
      assertTrue(provenance.get("server").asText().contains("Stalwart"), file + " must name the server it was captured against");
    }
  }

  /**
   * Every golden parses, equals itself, and — the promise PR3 leans on —
   * survives a full ical4j re-serialisation with no reported difference,
   * while a one-hour shift of its DTSTART is always reported. This proves
   * both directions of the comparator on every real captured object at once.
   *
   * @throws Exception when the corpus cannot be read
   */
  @Test
  public void everyGoldenSurvivesReserialisationAndFlagsAShiftedStart() throws Exception {
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true);
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true);
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true);
    for (Path file : writeGoldens()) {
      String ics = icsOf(file);
      List<SemanticDifference> self = IcsSemanticComparator.compare(ics, ics);
      assertTrue(self.isEmpty(), () -> file + " must equal itself, got:\n" + self);
      Calendar reserialised = new CalendarBuilder().build(new StringReader(ics));
      List<SemanticDifference> cosmetic = IcsSemanticComparator.compare(ics, reserialised.toString());
      assertTrue(cosmetic.isEmpty(),
                 () -> file + " must survive ical4j re-serialisation (the cosmetic transformation PR3 applies), got:\n"
                     + cosmetic);
      String shifted = shiftFirstStartByOneHour(ics);
      if (shifted != null) {
        assertFalse(IcsSemanticComparator.compare(ics, shifted).isEmpty(),
                    () -> file + " must flag a one-hour shift of its start");
      }
    }
  }

  /**
   * Shifts the first timed DTSTART of an object by one hour, textually, to
   * fabricate the archetypal semantic mutation. All-day objects, having no
   * timed start, yield null and are mutated by a day shift instead.
   *
   * @param ics the ICS text
   * @return the mutated text, or null when no start could be shifted
   */
  private static String shiftFirstStartByOneHour(String ics) {
    // Only an event's own start is mutated: the DTSTARTs inside a VTIMEZONE
    // name rule onsets, and moving one does not move any occurrence.
    int firstEvent = ics.indexOf("BEGIN:VEVENT");
    java.util.regex.Matcher timed = java.util.regex.Pattern.compile("(DTSTART[^:\\r\\n]*:\\d{8}T)(\\d{2})").matcher(ics);
    if (firstEvent >= 0 && timed.find(firstEvent)) {
      int hour = (Integer.parseInt(timed.group(2)) + 1) % 24;
      return new StringBuilder(ics).replace(timed.start(2), timed.end(2), String.format("%02d", hour)).toString();
    }
    java.util.regex.Matcher allDay =
                                   java.util.regex.Pattern.compile("(DTSTART;VALUE=DATE:\\d{6})(\\d{2})").matcher(ics);
    if (allDay.find()) {
      int day = Integer.parseInt(allDay.group(2)) % 27 + 1;
      return new StringBuilder(ics).replace(allDay.start(2), allDay.end(2), String.format("%02d", day)).toString();
    }
    return null;
  }

  /**
   * 01 — a single timed event is UTC-anchored, with no VTIMEZONE; its update
   * golden still holds exactly one VEVENT, updated in place.
   *
   * @throws Exception when the goldens cannot be read
   */
  @Test
  public void simpleTimedEventIsUtcAnchoredAndUpdatesInPlace() throws Exception {
    String created = unfolded(golden("01-simple-timed"));
    assertTrue(created.contains("DTSTART:20260908T090000Z"), "single timed events anchor in UTC");
    assertFalse(created.contains("VTIMEZONE"), "a single timed event carries no VTIMEZONE");
    String updated = unfolded(golden("01-simple-timed.update"));
    assertEquals(1, countOf(updated, "BEGIN:VEVENT"), "the update replaces the master, never adds a second one");
    assertTrue(updated.contains("DTSTART:20260908T100000Z") && updated.contains("Weekly steering point (moved)"),
               "the update golden reflects the second push");
  }

  /**
   * 02 and 03 — all-day events state their DTEND exclusively: one day after
   * the last covered day, in the event's own zone.
   *
   * @throws Exception when the goldens cannot be read
   */
  @Test
  public void allDayEventsCarryExclusiveEnds() throws Exception {
    String single = unfolded(golden("02-allday-single"));
    assertTrue(single.contains("DTSTART;VALUE=DATE:20260910") && single.contains("DTEND;VALUE=DATE:20260911"),
               "a one-day event ends on the day after it");
    String multi = unfolded(golden("03-allday-multiday"));
    assertTrue(multi.contains("DTSTART;VALUE=DATE:20261102") && multi.contains("DTEND;VALUE=DATE:20261105"),
               "a Sydney Nov 2-4 event ends exclusively on Nov 5, read in the event zone");
  }

  /**
   * 04 and 06 — a timed series is TZID-anchored and carries the derived
   * VTIMEZONE; the DST-spanning series keeps its 09:00 Paris wall clock, so
   * the first post-transition occurrence lands one UTC-hour later — asserted
   * through the comparator's own expansion against a UTC-anchored twin, the
   * exact drift EXO-89402 is about.
   *
   * @throws Exception when the goldens cannot be read
   */
  @Test
  public void timedSeriesAreTzidAnchoredAndKeepTheirWallClockAcrossDst() throws Exception {
    String weekly = unfolded(golden("04-weekly-paris"));
    assertTrue(weekly.contains("DTSTART;TZID=Europe/Paris:20260907T090000"), "series anchor on the zone wall clock");
    assertTrue(weekly.contains("BEGIN:VTIMEZONE") && weekly.contains("TZID:Europe/Paris"),
               "the TZID travels with its VTIMEZONE");
    String spanning = golden("06-dst-span");
    String utcTwin = spanning.replace("DTSTART;TZID=Europe/Paris:20261012T090000", "DTSTART:20261012T070000Z")
                             .replace("DTEND;TZID=Europe/Paris:20261012T091500", "DTEND:20261012T071500Z");
    List<SemanticDifference> drift = IcsSemanticComparator.compare(spanning, utcTwin);
    assertTrue(drift.stream()
                    .anyMatch(difference -> difference.getKind() == SemanticDifference.Kind.EXPANSION
                        && difference.getLeft().contains("2026-10-26T08:00:00Z")),
               () -> "the golden series must expand to 08:00Z after the transition where a UTC anchor stays at 07:00Z, got:\n"
                   + drift);
  }

  /**
   * 05 and 12 — overrides carry exactly one RECURRENCE-ID, in the master's
   * own value form: the timed TZID form for a timed series, VALUE=DATE for an
   * all-day one, and the master survives the override push.
   *
   * @throws Exception when the goldens cannot be read
   */
  @Test
  public void overridesCarryOneRecurrenceIdInTheMastersForm() throws Exception {
    String timed = unfolded(golden("05-series-override.override"));
    assertEquals(2, countOf(timed, "BEGIN:VEVENT"), "the master survives the override push");
    assertEquals(1, countOf(timed, "RECURRENCE-ID"), "exactly one RECURRENCE-ID, exactly once");
    assertTrue(timed.contains("RECURRENCE-ID;TZID=Europe/Paris:20260916T150000"),
               "a timed override denotes its instance on the master's wall clock");
    String allDay = unfolded(golden("12-allday-series-override.override"));
    assertTrue(allDay.contains("RECURRENCE-ID;VALUE=DATE:20260921"),
               "an all-day override denotes its instance as a date");
  }

  /**
   * 07 — deleting one occurrence rewrites the object: the master gains an
   * exclusion and the object survives. The capture also pinned a latent
   * quirk: the EXDATE the rewrite adds is written WITHOUT the TZID the
   * master's other EXDATE carries (a floating wall clock), so PR3 must treat
   * this golden's exclusion list as a documented known-divergence — fixing
   * the form is a behaviour change to surface, never to slip through.
   *
   * @throws Exception when the goldens cannot be read
   */
  @Test
  public void occurrenceDeletionRewritesTheObjectAndItsQuirkIsPinned() throws Exception {
    String after = unfolded(golden("07-exdate.occurrence-delete"));
    assertTrue(after.contains("EXDATE;TZID=Europe/Paris:20260910T083000"), "the pushed exclusion keeps its TZID form");
    assertTrue(after.contains("EXDATE:20260922T083000"),
               "the rewrite-added exclusion is floating — the pinned quirk this test documents");
    assertTrue(after.contains("RRULE:FREQ=WEEKLY;BYDAY=TU,TH"), "the series itself survives the occurrence deletion");
  }

  /**
   * 08 — scheduling identities: the organizer is the eXo organizer with
   * SCHEDULE-AGENT=NONE (the pusher merely accepted), a CN holding a comma
   * is quoted, every attendee carries SCHEDULE-AGENT=NONE and its mapped
   * PARTSTAT, and the address-less attendee was left off the copy. NONE
   * rather than the captured CLIENT since EXO-89681: no agent schedules for
   * the copy, so a client does not email a reply when its owner answers it.
   *
   * @throws Exception when the goldens cannot be read
   */
  @Test
  public void schedulingIdentitiesAreTruthful() throws Exception {
    String ics = unfolded(golden("08-attendees-organizer"));
    assertTrue(ics.contains("ORGANIZER;CN=\"Martin, Alice\";SCHEDULE-AGENT=NONE:mailto:alice.martin@example.test"),
               "the organizer is the eXo organizer, quoted CN, marked for no scheduling agent");
    assertEquals(3, countOf(ics, "ATTENDEE"), "the attendee without a visible address is omitted, never invented");
    assertTrue(ics.contains("PARTSTAT=ACCEPTED") && ics.contains("PARTSTAT=DECLINED") && ics.contains("PARTSTAT=TENTATIVE"),
               "participation statuses map through");
  }

  /**
   * 09 and 10 — one DISPLAY VALARM per reminder with minute triggers; the
   * conference URL rides both the DESCRIPTION and a single-feature
   * CONFERENCE property with its URI commas unescaped-in-meaning; text
   * properties escape their commas and semicolons.
   *
   * @throws Exception when the goldens cannot be read
   */
  @Test
  public void alarmsConferenceAndTextEscapingHold() throws Exception {
    String alarms = unfolded(golden("09-valarm"));
    assertEquals(2, countOf(alarms, "BEGIN:VALARM"), "one alarm per reminder");
    assertTrue(alarms.contains("TRIGGER:-PT10M") && alarms.contains("TRIGGER:-PT1440M"),
               "reminder units are converted to minutes");
    String conference = unfolded(golden("10-conference-description"));
    assertTrue(conference.contains("SUMMARY:Rétrospective\\; sprint 42\\, équipe cœur"), "TEXT escaping holds");
    assertEquals(1, countOf(conference, "CONFERENCE;"), "a single CONFERENCE property, single feature");
    assertTrue(unfoldedContains(conference, "DESCRIPTION", "https://visio.example.test/room/42?pwd=abc\\,def\\;ghi"),
               "the conference URL also rides the description, the one line every client shows");
  }

  /**
   * 11 and 14 — the zone edges: the lunar-rule Casablanca zone is described
   * by the two transitions of the reference year projected as YEARLY rules
   * (the documented Intl approximation PR3's registry will rightly diverge
   * from — a waiver, surfaced and named, never silent), and an unresolvable
   * zone falls back to UTC anchoring with no dangling TZID.
   *
   * @throws Exception when the goldens cannot be read
   */
  @Test
  public void zoneEdgesArePinned() throws Exception {
    String casablanca = unfolded(golden("11-casablanca-lunar-zone"));
    assertTrue(casablanca.contains("TZID:Africa/Casablanca") && casablanca.contains("FREQ=YEARLY"),
               "the lunar zone is approximated by projected yearly rules — the pinned limitation");
    assertTrue(casablanca.contains("DTSTART;TZID=Africa/Casablanca:"), "the series anchors on the approximated zone");
    String fallback = unfolded(golden("14-unknown-zone-utc-fallback"));
    assertFalse(fallback.contains("VTIMEZONE"), "no VTIMEZONE could be produced");
    assertFalse(fallback.contains("TZID"), "and therefore no dangling TZID reference either");
    assertTrue(fallback.contains("DTSTART:20260903T090000Z") && fallback.contains("EXDATE:20260910T090000Z"),
               "the whole series, exclusions included, falls back to the UTC form");
  }

  /**
   * 13 — merging into an object another client also writes: the override
   * contradicted by the pushed EXDATE is pruned, the other foreign override
   * survives untouched (its X-MOZ marker still there), and the object's own
   * VTIMEZONE is kept rather than doubled.
   *
   * @throws Exception when the goldens cannot be read
   */
  @Test
  public void mergePreservesForeignOverridesAndPrunesContradictedOnes() throws Exception {
    String merged = unfolded(golden("13-merge-prune-foreign"));
    assertFalse(merged.contains("moved by phone"), "the override contradicted by the EXDATE is pruned");
    assertTrue(merged.contains("moved on desktop") && merged.contains("X-MOZ-GENERATION:5"),
               "the other foreign override survives with its own properties");
    assertEquals(1, countOf(merged, "BEGIN:VTIMEZONE"), "the existing zone definition is kept, not doubled");
    assertTrue(merged.contains("EXDATE;TZID=Europe/Paris:20260915T090000"), "the exclusion that did the pruning is there");
  }

  /**
   * The read golden of the Thunderbird-shaped series pins the DST boundary
   * from the reading side: the occurrences the connector produced stay at the
   * 09:00 Paris wall clock, so their instants move from 07:00Z to 08:00Z at
   * the October transition.
   *
   * @throws Exception when the golden cannot be read
   */
  @Test
  public void readGoldenKeepsTheWallClockAcrossTheDstBoundary() throws Exception {
    Path file = Paths.get(GoldenCorpusTest.class.getClassLoader()
                                                .getResource("caldav/golden/read/r01-tb-weekly.read-golden.json")
                                                .toURI());
    JsonNode events = MAPPER.readTree(file.toFile()).get("events");
    assertNotNull(events, "the read golden must carry its events");
    boolean beforeTransition = false;
    boolean afterTransition = false;
    for (JsonNode event : events) {
      String start = event.get("start").asText();
      beforeTransition |= start.equals("2026-10-19T07:00:00.000Z");
      afterTransition |= start.equals("2026-10-26T08:00:00.000Z");
    }
    assertTrue(beforeTransition, "the last pre-transition Monday reads 07:00Z");
    assertTrue(afterTransition, "the first post-transition Monday reads 08:00Z — the wall clock held, the instant moved");
  }

  /**
   * Counts the occurrences of a marker in a text.
   *
   * @param text the text searched
   * @param marker the marker counted
   * @return how many times the marker occurs
   */
  private static int countOf(String text, String marker) {
    int count = 0;
    int index = text.indexOf(marker);
    while (index >= 0) {
      count++;
      index = text.indexOf(marker, index + marker.length());
    }
    return count;
  }

  /**
   * Whether the unfolded line of a named property contains a fragment.
   *
   * @param unfoldedIcs the unfolded ICS text
   * @param propertyName the property whose line is inspected
   * @param fragment the fragment looked for
   * @return true when the property's line carries the fragment
   */
  private static boolean unfoldedContains(String unfoldedIcs, String propertyName, String fragment) {
    for (String line : unfoldedIcs.split("\r?\n")) {
      if (line.startsWith(propertyName) && line.contains(fragment)) {
        return true;
      }
    }
    return false;
  }
}

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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.exoplatform.caldav.golden.IcsSemanticComparator;
import org.exoplatform.caldav.golden.SemanticDifference;
import org.exoplatform.caldav.model.IcsEvent;

/**
 * The engine judged against reality: for every write case of the golden corpus
 * (EXO-89521), what {@link IcsWriter} produces must show no semantic
 * difference from what the browser connector actually stored on a real server.
 *
 * <p>
 * The comparator is blind to folding, property order, PRODID and DTSTAMP, and
 * sighted about everything that changes what a calendar means — instants,
 * value types, rosters, rules, expansions. So a difference reported here is a
 * behaviour change, not a formatting one, and the two the corpus expects are
 * waived <b>by name</b> below rather than by a loosened comparison.
 */
public class IcsWriterGoldenTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final IcsWriter           writer = new IcsWriter();

  private final IcsMerger           merger = new IcsMerger();

  /**
   * The driver inputs of the corpus, one per write case.
   *
   * @return the fixture names, sorted
   * @throws Exception when the corpus cannot be listed
   */
  static Stream<String> writeCases() throws Exception {
    try (Stream<Path> files = Files.list(resource("caldav/golden/events"))) {
      return files.map(file -> file.getFileName().toString())
                  .filter(name -> name.endsWith(".json"))
                  .map(name -> name.substring(0, name.length() - ".json".length()))
                  .sorted()
                  .toList()
                  .stream();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("writeCases")
  public void engineReproducesTheGolden(String name) throws Exception {
    JsonNode fixture = MAPPER.readTree(resource("caldav/golden/events/" + name + ".json").toFile());
    String golden = goldenIcs(name);

    IcsEvent event = GoldenEventFixture.toIcsEvent(fixture.get("event"));
    String produced = produce(fixture, event);
    List<SemanticDifference> differences = IcsSemanticComparator.compare(golden, produced);

    assertTrue(waived(name, differences).isEmpty(),
               () -> "The engine's object differs from the captured golden for " + name + ":\n"
                   + String.join("\n", waived(name, differences).stream().map(Object::toString).toList())
                   + "\n\nEngine produced:\n" + produced + "\nGolden:\n" + golden);
  }

  /**
   * What the engine writes for one case: the object on its own, or — when the
   * fixture seeds the server with an object another client already wrote —
   * the result of splicing into it. The second is the whole point of case 13,
   * and running it through the plain writer would have compared a fresh
   * document against a merged one and called the difference a defect.
   *
   * @param fixture the driver input
   * @param event the event as the engine takes it
   * @return the object the engine would send
   * @throws Exception when the seed cannot be read
   */
  private String produce(JsonNode fixture, IcsEvent event) throws Exception {
    JsonNode seed = fixture.get("seedObjectFile");
    String written = writer.write(event);
    if (seed == null || seed.isNull()) {
      return written;
    }
    String existing = Files.readString(resource("caldav/golden/events/" + seed.asText()));
    return merger.merge(existing, written, event.getOccurrenceId() != null);
  }

  @Test
  public void everyWriteCaseIsCovered() throws Exception {
    // A corpus case whose driver input quietly disappeared would otherwise
    // shrink the net without failing anything.
    assertTrue(writeCases().count() >= 14, "the write corpus must still carry its fourteen cases");
  }

  /**
   * The differences that remain once the corpus's two documented waivers are
   * removed. Both are places where today's behaviour is wrong or approximate,
   * so a faithful-to-ical4j port is expected to differ — the README records
   * them, and they are dropped here by name rather than by weakening the
   * comparison for everything else.
   *
   * @param name the fixture name
   * @param differences everything the comparator reported
   * @return the differences that are genuinely failures
   */
  private List<SemanticDifference> waived(String name, List<SemanticDifference> differences) {
    if (name.startsWith("11-")) {
      // Waiver 1 — Africa/Casablanca. The browser derivation projects the
      // reference year's two Ramadan transitions as YEARLY rules; ical4j's
      // registry carries the real ones. Instants around Ramadan may
      // legitimately differ, and the registry is the better answer.
      return List.of();
    }
    if (name.startsWith("14-")) {
      // Waiver 2 — a zone the browser declined to describe and ical4j knows.
      // The golden falls back to UTC for the whole series; the engine anchors
      // it properly. Again a fix, not a regression.
      return List.of();
    }
    return differences;
  }

  /**
   * The ICS a golden envelope stored.
   *
   * @param name the fixture name
   * @return the stored object
   * @throws Exception when the envelope cannot be read
   */
  private String goldenIcs(String name) throws Exception {
    Path envelope = resource("caldav/golden/write/" + name + ".golden.json");
    JsonNode ics = MAPPER.readTree(envelope.toFile()).get("ics");
    assertNotNull(ics, envelope + " must carry the stored object under 'ics'");
    return ics.asText();
  }

  /**
   * A classpath resource as a path.
   *
   * @param path the resource path
   * @return the resolved path
   * @throws Exception when the resource is unresolvable
   */
  private static Path resource(String path) throws Exception {
    return Paths.get(IcsWriterGoldenTest.class.getClassLoader().getResource(path).toURI());
  }
}

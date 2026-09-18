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
package org.exoplatform.caldav.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.MirrorTargetKind;

/**
 * The destination kind, as it travels in {@code GET /caldav/rest/servers}
 * (EXO-90396).
 *
 * <p>
 * The browser decides whether to offer the calendar-creation step by reading
 * {@code mirrorTarget} off each registration — {@code caldavConnector.js}
 * stamps {@code canCreateCalendar} from it, and agenda reads that flag in
 * three places. Everything in that chain is a string comparison against
 * {@code mirrorTargets.js}, so the whole fix is inert the moment this property
 * stops arriving under this name in this spelling: no test in either repository
 * would notice, and the browser would silently fall back to the default and
 * offer the step again on every server.
 *
 * <p>
 * Pinned by property NAME and by the exact string, on the engine that actually
 * serves the endpoint — Jackson 3 ({@code tools.jackson}), default mapper, as
 * {@code RemoteCalendarWireShapeTest} and {@code HiddenCalendarWireShapeTest}
 * do for their own contracts. The enum spelling is the reason this one matters
 * more than most: of the three {@code spring.jackson.*} keys the platform sets,
 * two concern exactly this — {@code write-enums-using-to-string} and
 * {@code read-enums-using-to-string}, both false, so the wire carries
 * {@code name()}. {@code MirrorTargetKind} overrides no {@code toString()}
 * today, which is what makes the two spellings agree; an override added later
 * for a log message would part them, and this is where that is caught.
 */
public class CaldavServerWireShapeTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  /**
   * A registration is built the way an administrator's row arrives, through
   * the setters, so the pin says nothing about the order of the all-args
   * constructor.
   *
   * @param kind the destination the registration carries
   * @return the registration
   */
  private CaldavServer serverWith(MirrorTargetKind kind) {
    CaldavServer server = new CaldavServer();
    server.setId(6L);
    server.setProviderName("agenda.caldavCalendar.6");
    server.setName("Bluemind");
    server.setServerUrl("https://caldav.example.invalid/dav/");
    server.setActive(true);
    server.setMirrorTarget(kind);
    return server;
  }

  /**
   * The value the browser must be able to recognise: the defect was a server
   * registered this way being offered a calendar {@code ensureMirror} then
   * refuses to create. The string is asserted literally rather than through
   * the enum, because the browser compares it against a literal of its own
   * ({@code MIRROR_TARGET_MAIN_CALENDAR} in {@code mirrorTargets.js}) and the
   * two are only kept in step by a test that spells both.
   */
  @Test
  public void theMainCalendarKindTravelsUnderItsOwnName() {
    JsonNode json = mapper.readTree(mapper.writeValueAsString(serverWith(MirrorTargetKind.MAIN_CALENDAR)));

    assertTrue(json.has("mirrorTarget"), "the browser's only evidence of where the copies go");
    assertEquals("MAIN_CALENDAR", json.get("mirrorTarget").asText());
  }

  /**
   * The other kind, which must NOT read as the first one: a flag derived from
   * a value the browser cannot tell apart would hide the creation step from
   * the servers that need it — the expensive direction of the two.
   */
  @Test
  public void theDedicatedCalendarKindTravelsUnderItsOwnName() {
    JsonNode json = mapper.readTree(mapper.writeValueAsString(serverWith(MirrorTargetKind.DEDICATED_CALENDAR)));

    assertEquals("DEDICATED_CALENDAR", json.get("mirrorTarget").asText());
  }

  /**
   * A registration nobody set a destination on still carries one on the wire:
   * the field initialiser answers before Jackson does, so the browser's own
   * fallback for an absent value is a guard it never exercises through this
   * endpoint. Stated here because the connector's comment leans on it.
   */
  @Test
  public void aRegistrationNobodySetADestinationOnStillCarriesTheDefault() {
    CaldavServer untouched = new CaldavServer();

    JsonNode json = mapper.readTree(mapper.writeValueAsString(untouched));

    assertTrue(json.has("mirrorTarget"), "present, never omitted");
    assertEquals("DEDICATED_CALENDAR", json.get("mirrorTarget").asText());
  }

}

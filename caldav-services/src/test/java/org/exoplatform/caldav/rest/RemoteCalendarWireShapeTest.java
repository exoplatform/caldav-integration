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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.exoplatform.caldav.model.RemoteCalendar;
import org.exoplatform.caldav.model.RemoteCalendarsRead;

/**
 * The shape {@code GET /caldav/rest/calendars} answers in, as agenda's
 * connector reads it (EXO-90237): the contract the agenda half of the task
 * is coded against, pinned by property name so that a rename on one side is
 * caught here rather than in a browser.
 *
 * <p>
 * Serialised with Jackson 3 ({@code tools.jackson}), the engine the
 * platform's Spring MVC serves this endpoint with (Spring Boot 4 —
 * {@code spring-boot-starter-jackson} is on this module's classpath), on a
 * default mapper: every property present, an unknown owner spelled
 * {@code null} and never omitted — the connector copies the entry through
 * unchanged, and agenda tests the four fields for null or absence alike.
 * The three {@code spring.jackson.*} keys the platform sets concern
 * primitives-from-null and enum spelling, neither of which this bean has.
 */
public class RemoteCalendarWireShapeTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  /**
   * CAL2 as eric receives it: shared, owned by root, named in full.
   */
  @Test
  public void aColleaguesExoCalendarCarriesTheOwnerInFull() throws Exception {
    RemoteCalendar cal2 = new RemoteCalendar("/dav/calendars/eric/exo-cal-959b5529-ea4c-4ae4-a793-a2c201c3af9f/",
                                             "CAL2",
                                             "#4a90d9",
                                             true,
                                             true,
                                             1L,
                                             "root",
                                             "Root Root");

    JsonNode json = mapper.readTree(mapper.writeValueAsString(new RemoteCalendarsRead(List.of(cal2), false))).get("calendars").get(0);

    assertEquals("/dav/calendars/eric/exo-cal-959b5529-ea4c-4ae4-a793-a2c201c3af9f/", json.get("id").asText());
    assertEquals("CAL2", json.get("name").asText());
    assertEquals("#4a90d9", json.get("color").asText());
    assertTrue(json.get("readOnly").asBoolean());
    assertTrue(json.get("shared").asBoolean());
    assertEquals(1L, json.get("ownerIdentityId").asLong());
    assertEquals("root", json.get("ownerUsername").asText());
    assertEquals("Root Root", json.get("ownerDisplayName").asText());
  }

  /**
   * Alice's default as bob receives it: shared, named "Alice", no identity —
   * the two identity fields are present and null, not absent.
   */
  @Test
  public void aServerReportedShareCarriesANameAndNullIdentityFields() throws Exception {
    RemoteCalendar alices = new RemoteCalendar("/dav/cal/alice%40stalwart.local/default/", "Alice's", "#b8e986", true, true, null, null, "Alice");

    JsonNode json = mapper.readTree(mapper.writeValueAsString(alices));

    assertTrue(json.get("shared").asBoolean());
    assertTrue(json.has("ownerIdentityId") && json.get("ownerIdentityId").isNull(), "present and null");
    assertTrue(json.has("ownerUsername") && json.get("ownerUsername").isNull(), "present and null");
    assertEquals("Alice", json.get("ownerDisplayName").asText());
  }

  /**
   * The user's own calendar keeps the shape every caller knew, with the new
   * fields false or null — a read-only calendar of their own is not a share.
   */
  @Test
  public void theUsersOwnCalendarIsNotSharedAndNamesNobody() throws Exception {
    JsonNode json = mapper.readTree(mapper.writeValueAsString(new RemoteCalendar("/dav/calendars/john/work/", "Work", "#112233", true)));

    assertTrue(json.get("readOnly").asBoolean());
    assertFalse(json.get("shared").asBoolean(), "read-only, and still the user's own");
    assertTrue(json.get("ownerIdentityId").isNull());
    assertTrue(json.get("ownerUsername").isNull());
    assertTrue(json.get("ownerDisplayName").isNull());
  }
}

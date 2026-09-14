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

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.exoplatform.caldav.model.HiddenCalendar;
import org.exoplatform.caldav.rest.model.HideCalendarRequest;

/**
 * The JSON the hidden-calendars endpoints exchange with the browser
 * (EXO-90239).
 *
 * <p>
 * The drawer branches on {@code shared} and prints {@code ownerDisplayName};
 * a record component renamed or dropped would leave every row reading as a
 * deleted calendar with nobody named, and no Java test on the service would
 * notice. The request side is pinned for the same reason: the agenda half
 * sends {@code {"calendarId": …}} and nothing else.
 */
public class HiddenCalendarWireShapeTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  /**
   * A hidden share carries both new fields, set.
   */
  @Test
  public void aHiddenShareSaysItIsSharedAndByWhom() throws Exception {
    JsonNode json = mapper.readTree(mapper.writeValueAsString(new HiddenCalendar(12L, "Alice", true, "Alice Martin")));

    assertEquals(12L, json.get("id").asLong());
    assertEquals("Alice", json.get("name").asText());
    assertTrue(json.get("shared").asBoolean());
    assertEquals("Alice Martin", json.get("ownerDisplayName").asText());
  }

  /**
   * A deleted calendar of the user's own keeps the shape every caller knew,
   * with the new fields false and present-but-null — never absent, so a
   * reader can tell "nobody named" from "an older services jar".
   */
  @Test
  public void aDeletedCalendarIsNotSharedAndNamesNobody() throws Exception {
    JsonNode json = mapper.readTree(mapper.writeValueAsString(new HiddenCalendar(9L, "Family")));

    assertEquals(9L, json.get("id").asLong());
    assertEquals("Family", json.get("name").asText());
    assertFalse(json.get("shared").asBoolean());
    assertTrue(json.has("ownerDisplayName") && json.get("ownerDisplayName").isNull(), "present and null");
  }

  /**
   * The hide request binds from the one field the browser sends, with the
   * href exactly as the calendar list spelled it.
   */
  @Test
  public void theHideRequestBindsTheCalendarIdAsSent() throws Exception {
    HideCalendarRequest request = mapper.readValue("{\"calendarId\":\"/dav/cal/alice%40stalwart.local/default/\"}",
                                                   HideCalendarRequest.class);

    assertEquals("/dav/cal/alice%40stalwart.local/default/", request.calendarId());
  }
}

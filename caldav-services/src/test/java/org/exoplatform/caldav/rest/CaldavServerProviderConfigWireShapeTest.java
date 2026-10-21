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

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.exoplatform.caldav.model.CaldavServerProviderConfig;

/**
 * The provider configuration as it travels in
 * {@code GET /caldav/rest/servers/{id}/provider-config} (EXO-90738).
 *
 * <p>
 * The server drawer reads {@code values} to repopulate its fields and
 * {@code storedSecretKeys} to know which blank secret keeps the stored one; a
 * renamed record component would make every stored secret read as missing and
 * every save ask for it again, with no Java test noticing. Pinned by property
 * name on the engine that serves the endpoint, Jackson 3's default mapper, as
 * {@code CaldavServerWireShapeTest} does for the registration.
 */
public class CaldavServerProviderConfigWireShapeTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  /** Both parts, under the names the drawer reads, and never a secret value. */
  @Test
  public void theValuesAndTheStoredSecretKeysTravelUnderTheirOwnNames() {
    CaldavServerProviderConfig config = new CaldavServerProviderConfig(Map.of("technicalLogin", "svc"),
                                                                       Set.of("technicalSecret"));

    JsonNode json = mapper.readTree(mapper.writeValueAsString(config));

    assertEquals(mapper.readTree("{\"values\":{\"technicalLogin\":\"svc\"},\"storedSecretKeys\":[\"technicalSecret\"]}"),
                 json);
  }

}

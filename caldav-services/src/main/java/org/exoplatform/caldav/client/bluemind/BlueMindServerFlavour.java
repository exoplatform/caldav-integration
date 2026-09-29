/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.caldav.client.bluemind;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.plugin.CalendarServerFlavour;
import org.exoplatform.caldav.service.BlueMindSessionService;

/**
 * BlueMind as a server flavour: recognised by its registration's name, it
 * may write through {@link WriteChannel#BLUEMIND_IMPORT}, and it keeps a REST
 * session per account (EXO-90397) that eXo drops when the account or the
 * registration changes.
 *
 * <p>
 * <b>Recognised by the name</b>, because nothing else on the row says which
 * product it is: a preset is a copy, never a link (the drawer's
 * {@code serverPresets.js} states why), so no preset identifier is stored; the
 * provider name is the agenda bridge's, not the product's; and the sharing
 * mechanism is a runtime capability read from the server, not a stored fact a
 * save can be judged by. An administrator who renames a BlueMind registration
 * to something else therefore also gives up its BlueMind-only choices — and
 * is told so by the registry's refusal rather than by a silent reset.
 */
@Service
public class BlueMindServerFlavour implements CalendarServerFlavour {

  /** The flavour's identifier, the same as the front end's BlueMind preset. */
  public static final String FLAVOUR_ID          = "bluemind";

  /**
   * What a registration's name carries when it stands for a BlueMind server:
   * the shipped seed row ({@code "Bluemind"}) and the drawer's preset
   * ({@code "BlueMind"}) both write it.
   */
  public static final String NAME_MARKER = "bluemind";

  /**
   * The REST sessions kept per account. Optional: it resolves through the
   * credentials contract, a bean of another web application, so it is
   * undefined in this add-on's own Spring test contexts.
   */
  @Autowired(required = false)
  private BlueMindSessionService blueMindSessionService;

  /**
   * The bean Spring makes, its session service injected.
   */
  public BlueMindServerFlavour() {
    // Field-injected
  }

  /**
   * A flavour over a given session service, as a test states it.
   *
   * @param blueMindSessionService the session service, may be null
   */
  public BlueMindServerFlavour(BlueMindSessionService blueMindSessionService) {
    this.blueMindSessionService = blueMindSessionService;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String id() {
    return FLAVOUR_ID;
  }

  /**
   * Whether the registration's name carries {@value #NAME_MARKER}, ignoring
   * case.
   *
   * @param server the registration, may be null
   * @return true when its name says BlueMind
   */
  @Override
  public boolean recognises(CaldavServer server) {
    return server != null && StringUtils.containsIgnoreCase(server.getName(), NAME_MARKER);
  }

  /**
   * BlueMind's own ICS import door.
   *
   * @return {@link WriteChannel#BLUEMIND_IMPORT}
   */
  @Override
  public Set<WriteChannel> writeChannels() {
    return Set.of(WriteChannel.BLUEMIND_IMPORT);
  }

  /**
   * Drops the REST session kept for one account.
   *
   * @param userIdentityId the social identity of the connected user
   * @param serverId the registration the account was on
   */
  @Override
  public void forgetSession(long userIdentityId, Long serverId) {
    if (blueMindSessionService != null) {
      blueMindSessionService.forget(userIdentityId, serverId);
    }
  }

  /**
   * Drops every kept REST session.
   */
  @Override
  public void forgetAllSessions() {
    if (blueMindSessionService != null) {
      blueMindSessionService.forgetAll();
    }
  }

}

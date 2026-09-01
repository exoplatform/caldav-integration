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
package org.exoplatform.caldav.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.model.CaldavManagedMode;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;

/**
 * Whether the instance chooses the CalDAV server for its users instead of
 * letting each of them connect an account of their own.
 *
 * <p>
 * <b>One key, and only one.</b> The whole mode is the presence of
 * {@code managedServerId} in a single global setting: absent means off,
 * present means on and names the server. The obvious alternative — an
 * {@code enabled} boolean beside the id — buys nothing and adds a state that
 * cannot be rendered honestly: enabled with no server. A single key cannot
 * reach it.
 *
 * <p>
 * The dependency runs from {@link CaldavServerService} to this class and never
 * back. This one reads the registry's storage directly — the same storage that
 * service reads, in the same add-on — precisely so that the registry service
 * can call {@link #checkServerNotManaged(long)} on the two writes that would
 * otherwise leave the mode pointing at a server nobody can synchronise with.
 * Injecting the registry service here instead would close a bean cycle, which
 * Spring Boot refuses outright.
 *
 * <p>
 * Nothing here provisions anything. Managed mode says which server a user's
 * account belongs on; how that account acquires its credentials is a separate
 * concern, and until it exists a managed user who never connected by hand is
 * simply assigned and not provisioned. The screens state that rather than
 * paper over it.
 */
@Service
public class CaldavManagedModeService {

  /** Where the choice lives: global, not per user. */
  public static final Scope   MANAGED_SCOPE       = Scope.APPLICATION.id("CaldavManagedMode");

  /**
   * The one key. Its ABSENCE is what "managed mode is off" means, so nothing
   * ever writes an empty value here — see {@link #clearManagedServer()}.
   *
   * <p>
   * The exclusions planned next ({@code excludedGroups}, groups whose members
   * keep choosing their own server) belong beside this one, in this same
   * scope: they qualify the same decision, and reading them will change
   * {@link #isManagedFor(Identity)} and nothing else.
   */
  public static final String  MANAGED_SERVER_KEY  = "managedServerId";

  /** What a save is refused with when the chosen row cannot be managed. */
  private static final String NOT_ELIGIBLE        = "caldav.managed.serverNotEligible";

  /** What a registry write is refused with when it targets the managed row. */
  private static final String IN_USE              = "caldav.managed.serverInUse";

  @Autowired
  private SettingService      settingService;

  @Autowired
  private CaldavServerStorage caldavServerStorage;

  @Autowired
  private UserACL             userAcl;

  /**
   * The registration the instance chose, or null when it chose none.
   *
   * @return the managed registration's identifier, null when managed mode is
   *         off
   */
  public Long getManagedServerId() {
    SettingValue<?> value = settingService.get(Context.GLOBAL, MANAGED_SCOPE, MANAGED_SERVER_KEY);
    if (value == null || value.getValue() == null || StringUtils.isBlank(value.getValue().toString())) {
      return null;
    }
    try {
      return Long.parseLong(value.getValue().toString());
    } catch (NumberFormatException e) {
      // Something that is not a number was written into the setting. Reading
      // that as "managed" would hide every user's connect button while naming
      // no server at all, which is the one unrecoverable state this screen has
      // to stay out of. Off is the honest answer.
      return null;
    }
  }

  /**
   * Whether this user is governed by the instance's choice.
   *
   * <p>
   * The per-viewer verdict, and the single place it is decided. Today it is
   * "the instance chose a server", so the identity changes nothing; group
   * exclusions land <i>here</i> — one method, rather than a condition
   * scattered over every screen that hides an affordance.
   *
   * @param userIdentity the user being asked about, null for an anonymous
   *          caller
   * @return true when the user's CalDAV account is the instance's to decide
   */
  public boolean isManagedFor(Identity userIdentity) {
    if (userIdentity == null) {
      // Nobody is managed on nobody's behalf. An anonymous caller has no
      // account to govern, and answering true would hide affordances from a
      // page that has no user to hide them from.
      return false;
    }
    return getManagedServerId() != null;
  }

  /**
   * Whether this user is governed by the instance's choice, by name.
   *
   * <p>
   * The identity resolution lives here rather than in the REST layer, which
   * holds no ACL knowledge by contract: a controller has a username and
   * nothing else, and turning one into an identity is exactly the kind of
   * question this layer answers.
   *
   * @param username the user being asked about, blank for an anonymous caller
   * @return true when the user's CalDAV account is the instance's to decide
   */
  public boolean isManagedFor(String username) {
    if (StringUtils.isBlank(username)) {
      return false;
    }
    return isManagedFor(userAcl.getUserIdentity(username));
  }

  /**
   * What the instance decided, and whether it applies to this caller.
   *
   * <p>
   * The server is named, not merely identified: every screen showing this
   * prints a name, and having each of them fetch the registry to turn an id
   * into a name would be three round trips for one word. A row deleted out
   * from under the setting leaves the name null rather than failing — the
   * registry refuses that deletion, so this is the belt to that braces.
   *
   * @param username the user asking, blank for an anonymous caller
   * @return the mode as it stands for that caller
   */
  public CaldavManagedMode getManagedMode(String username) {
    Long serverId = getManagedServerId();
    if (serverId == null) {
      return new CaldavManagedMode(null, null, false);
    }
    CaldavServer server = caldavServerStorage.getServerById(serverId);
    return new CaldavManagedMode(serverId, server == null ? null : server.getName(), isManagedFor(username));
  }

  /**
   * Records the server the instance synchronises everybody with.
   *
   * <p>
   * The row has to exist and to be active. A deactivated registration is not a
   * candidate: it is precisely the row nobody can connect to, and pointing the
   * whole instance at it would take every user's connect affordance away in
   * exchange for a server that answers nothing. The check is here rather than
   * in the drawer for the usual reason — a form validates what someone types,
   * a service validates what reaches it.
   *
   * @param serverId the registration to synchronise everybody with
   * @throws IllegalArgumentException carrying
   *           {@code caldav.managed.serverNotEligible} when the row does not
   *           exist or is not active
   */
  public void saveManagedServer(long serverId) {
    CaldavServer server = caldavServerStorage.getServerById(serverId);
    if (server == null || !server.isActive()) {
      throw new IllegalArgumentException(NOT_ELIGIBLE);
    }
    settingService.set(Context.GLOBAL, MANAGED_SCOPE, MANAGED_SERVER_KEY, SettingValue.create(String.valueOf(serverId)));
  }

  /**
   * Switches managed mode off: users choose their own server again.
   *
   * <p>
   * The key is removed, never emptied. Absence is the whole definition of
   * "off", and a setting holding an empty string would be a second way to say
   * the same thing — the sort of second way that survives a refactoring of the
   * first.
   */
  public void clearManagedServer() {
    settingService.remove(Context.GLOBAL, MANAGED_SCOPE, MANAGED_SERVER_KEY);
  }

  /**
   * Refuses a registry write that would strand managed mode on a server it can
   * no longer point at.
   *
   * <p>
   * Deactivating or deleting the managed row is not forbidden out of caution:
   * either one leaves every user of the instance with no connect affordance —
   * managed mode took it away — and a server that cannot serve them. The
   * administrator switches managed mode off first, which is one click in the
   * row above, and the refusal says so through the message code the admin
   * snackbars already render.
   *
   * @param serverId the registration being deactivated or deleted
   * @throws IllegalArgumentException carrying
   *           {@code caldav.managed.serverInUse} when that row is the managed
   *           one
   */
  public void checkServerNotManaged(long serverId) {
    Long managedServerId = getManagedServerId();
    if (managedServerId != null && managedServerId == serverId) {
      throw new IllegalArgumentException(IN_USE);
    }
  }
}

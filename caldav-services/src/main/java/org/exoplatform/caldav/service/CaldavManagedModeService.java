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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.model.CaldavManagedMode;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;

/**
 * Whether the instance chooses the CalDAV server for its users instead of
 * letting each of them connect an account of their own.
 *
 * <p>
 * The decision itself — which connector is designated for the {@code caldav}
 * kind, which groups it does not reach, whether it applies to a given user —
 * is held and answered by {@link ManagedConnectorService} in commons-exo, the
 * same for calendars and mailboxes. What this class adds is the CalDAV
 * knowledge commons-exo does not have: that the designated id must be a
 * registration that exists and is active, which provider it is configured
 * with (the eligibility criterion commons-exo enforces), and the name the
 * screens print instead of the id.
 *
 * <p>
 * The dependency runs from {@link CaldavServerService} to this class and never
 * back. This one reads the registry's storage directly — the same storage that
 * service reads, in the same add-on — precisely so that the registry service
 * can call {@link #checkServerNotManaged(long)} on the two writes that would
 * otherwise leave the mode pointing at a server nobody can synchronise with.
 * Injecting the registry service here instead would close a bean cycle, which
 * Spring Boot refuses outright.
 */
@Service
public class CaldavManagedModeService {

  /** What a save is refused with when the chosen row cannot be managed. */
  private static final String     NOT_ELIGIBLE = "caldav.managed.serverNotEligible";

  /** What a registry write is refused with when it targets the managed row. */
  private static final String     IN_USE       = "caldav.managed.serverInUse";

  /** What an edit of the managed row is refused with when its new provider asks the user. */
  private static final String     PROVIDER_NOT_ELIGIBLE = "caldav.managed.providerNotEligible";

  @Autowired
  private ManagedConnectorService managedConnectorService;

  @Autowired
  private CaldavServerStorage     caldavServerStorage;

  /**
   * The registration the instance points everybody at, when managed mode is
   * on.
   *
   * @return the managed registration's id, null when managed mode is off
   */
  public Long getManagedServerId() {
    return managedConnectorService.designationOf(CaldavCredentialsResolver.CONNECTOR_KIND);
  }

  /**
   * The groups the instance's choice does not reach.
   *
   * @return the excluded eXo group ids, empty when none
   */
  public List<String> getExcludedGroups() {
    return managedConnectorService.exclusionsOf(CaldavCredentialsResolver.CONNECTOR_KIND);
  }

  /**
   * Whether this user is governed by the instance's choice: managed mode is on
   * and they belong to none of the excluded groups.
   *
   * <p>
   * Nobody is managed on nobody's behalf: an anonymous caller has no account
   * to govern, and answering true would hide affordances from a page that has
   * no user to hide them from.
   *
   * @param username the eXo login, null or blank for an anonymous caller
   * @return true when the choice applies to them
   */
  public boolean isManagedFor(String username) {
    if (StringUtils.isBlank(username)) {
      return false;
    }
    return managedConnectorService.designatedConnectorFor(CaldavCredentialsResolver.CONNECTOR_KIND, username) != null;
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
   * @param username the eXo login of the caller, null or blank when anonymous
   * @return the mode as it stands for that caller
   */
  public CaldavManagedMode getManagedMode(String username) {
    Long serverId = getManagedServerId();
    if (serverId == null) {
      return new CaldavManagedMode(null, null, List.of(), false);
    }
    CaldavServer server = caldavServerStorage.getServerById(serverId);
    return new CaldavManagedMode(serverId,
                                 server == null ? null : server.getName(),
                                 getExcludedGroups(),
                                 isManagedFor(username));
  }

  /**
   * Points the whole instance at one registration, minus the given groups.
   * <p>
   * This class refuses a registration that does not exist or is deactivated —
   * the row nobody can connect to, which only this add-on can recognise.
   * Everything else is commons-exo's, in one call so that nothing is written
   * when anything is refused: that the caller is an administrator, that the
   * row's provider asks the user for nothing (Personal cannot be designated,
   * because it would produce an instance connector nobody can connect
   * through), and the order of the two writes.
   *
   * @param serverId technical identifier of the registration
   * @param excludedGroups the eXo group ids the choice must not reach, null
   *          for none
   * @param username the eXo login of the caller
   * @throws IllegalAccessException when the caller is not an administrator
   * @throws IllegalArgumentException carrying {@code caldav.managed.serverNotEligible}
   *           when the row is unknown or deactivated, or the commons-exo code
   *           when its provider asks the user for something
   */
  public void saveManagedServer(long serverId, List<String> excludedGroups, String username) throws IllegalAccessException {
    CaldavServer server = caldavServerStorage.getServerById(serverId);
    if (server == null || !server.isActive()) {
      throw new IllegalArgumentException(NOT_ELIGIBLE);
    }
    managedConnectorService.designate(CaldavCredentialsResolver.CONNECTOR_KIND,
                                      serverId,
                                      server.getAuthProviderName(),
                                      excludedGroups,
                                      username);
  }

  /**
   * Switches managed mode off: users choose their own server again, and the
   * exclusions go with the designation they qualified. Accounts already
   * connected are untouched — nobody is detached and nothing synchronised is
   * removed by this; what an administrator's change does to the users it
   * attached is EXO-89654's.
   *
   * @param username the eXo login of the caller
   * @throws IllegalAccessException when the caller is not an administrator
   */
  public void clearManagedServer(String username) throws IllegalAccessException {
    managedConnectorService.clearDesignation(CaldavCredentialsResolver.CONNECTOR_KIND, username);
  }

  /**
   * Refuses the edit that would leave the managed row on a provider asking
   * each user for something — the very thing designating it refused. Any
   * other row may change provider freely.
   *
   * @param serverId the registration being edited
   * @param providerName the provider it would be configured with after the edit
   * @throws IllegalArgumentException carrying {@code caldav.managed.providerNotEligible}
   *           when the row is the managed one and the provider is not eligible
   */
  public void checkProviderChangeAllowed(long serverId, String providerName) {
    Long managedServerId = getManagedServerId();
    if (managedServerId == null || managedServerId != serverId) {
      return;
    }
    try {
      managedConnectorService.requireEligible(providerName);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(PROVIDER_NOT_ELIGIBLE, e);
    }
  }

  /**
   * Refuses the registry writes that would strand the mode.
   *
   * @param serverId the registration about to be deactivated or deleted
   * @throws IllegalArgumentException carrying {@code caldav.managed.serverInUse}
   *           when managed mode points at that row
   */
  public void checkServerNotManaged(long serverId) {
    Long managedServerId = getManagedServerId();
    if (managedServerId != null && managedServerId == serverId) {
      throw new IllegalArgumentException(IN_USE);
    }
  }
}

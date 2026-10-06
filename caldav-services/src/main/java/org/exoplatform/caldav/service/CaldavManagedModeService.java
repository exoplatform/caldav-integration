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
import org.springframework.context.ApplicationEventPublisher;

import org.exoplatform.caldav.exception.ManagedConnectionLockedException;
import org.exoplatform.caldav.model.CaldavManagedMode;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.caldav.storage.CaldavServerStorage;
import org.exoplatform.caldav.event.CaldavManagedModeChangedEvent;
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

  @Autowired
  private ApplicationEventPublisher eventPublisher;

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
    return designatedServerFor(username) != null;
  }

  /**
   * The registration this user is to be attached to at login, or null when
   * managed mode does not apply to them - nothing designated, or a population
   * the administrator excluded. The caller does not need to know which: in
   * both cases it does nothing. Recomputed on every call, never stored.
   *
   * @param username the eXo login, null or blank for an anonymous caller
   * @return the designated registration's id, or null
   */
  public Long designatedServerFor(String username) {
    if (StringUtils.isBlank(username)) {
      return null;
    }
    return managedConnectorService.designatedConnectorFor(CaldavCredentialsResolver.CONNECTOR_KIND, username);
  }

  /**
   * The registration managed mode governs this user with in the stored state, for a
   * decision that disconnects them: the same rule as
   * {@link #designatedServerFor(String)}, except that a user whose identity cannot be
   * resolved is refused rather than counted as excluded.
   *
   * @param username the eXo login
   * @return the designated registration's id, or null when managed mode does not apply
   * @throws IllegalStateException when exclusions apply and the identity cannot be
   *           resolved
   */
  public Long governingServerFor(String username) {
    return managedConnectorService.designatedConnectorFor(getManagedServerId(), getExcludedGroups(), username);
  }

  /**
   * Refuses the connection changes managed mode takes away from the users it governs:
   * disconnecting, connecting an account with typed credentials, and connecting any
   * server but the designated one. A user managed mode does not govern changes their
   * connection freely.
   * <p>
   * The verdict is {@link #governingServerFor(String)}'s: a user whose identity cannot
   * be resolved, or a caller with no login, is refused rather than counted as excluded,
   * since the change they ask for is one the instance may have taken from them.
   *
   * @param username the eXo login of the caller
   * @param targetServerId the registration the caller asks to connect to in one click,
   *          null for a disconnection or a connection with typed credentials
   * @return the designated registration managed mode governs the caller with, null when
   *         it does not govern them
   * @throws ManagedConnectionLockedException when managed mode governs the caller, or
   *           cannot tell whether it does, and the change is not a one-click connection
   *           to the designated registration
   */
  public Long checkUserMayChangeConnection(String username, Long targetServerId) throws ManagedConnectionLockedException {
    Long governing;
    try {
      governing = governingServerFor(username);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new ManagedConnectionLockedException();
    }
    if (governing != null && !governing.equals(targetServerId)) {
      throw new ManagedConnectionLockedException();
    }
    return governing;
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
    // The users managed mode attached are checked against what was just stored: another
    // server, or a newly excluded group, disconnects them.
    eventPublisher.publishEvent(new CaldavManagedModeChangedEvent());
  }

  /**
   * Switches managed mode off: users choose their own server again, and the
   * exclusions go with the designation they qualified. The accounts managed
   * mode attached are disconnected in the background; the accounts users
   * connected themselves are untouched.
   *
   * @param username the eXo login of the caller
   * @throws IllegalAccessException when the caller is not an administrator
   */
  public void clearManagedServer(String username) throws IllegalAccessException {
    managedConnectorService.clearDesignation(CaldavCredentialsResolver.CONNECTOR_KIND, username);
    eventPublisher.publishEvent(new CaldavManagedModeChangedEvent());
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

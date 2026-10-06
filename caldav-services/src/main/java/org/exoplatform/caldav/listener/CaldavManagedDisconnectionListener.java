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
package org.exoplatform.caldav.listener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.caldav.event.CaldavManagedModeChangedEvent;
import org.exoplatform.caldav.event.CaldavServerProviderChangedEvent;
import org.exoplatform.caldav.service.CaldavManagedDisconnectionService;

/**
 * Hands the disconnections an administrator's change causes to
 * {@link CaldavManagedDisconnectionService}, which runs them in the background.
 * <p>
 * After the commit, with {@code fallbackExecution}: the change being answered must be
 * stored before its users are read, and a managed-mode write runs without a Spring
 * transaction.
 */
@Component
public class CaldavManagedDisconnectionListener {

  @Autowired
  private CaldavManagedDisconnectionService disconnectionService;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void handleManagedModeChanged(CaldavManagedModeChangedEvent event) {
    disconnectionService.disconnectUsersNoLongerManaged();
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void handleProviderChanged(CaldavServerProviderChangedEvent event) {
    disconnectionService.disconnectAllUsersOf(event.getServerId());
  }
}

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
package org.exoplatform.caldav.dao;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.exoplatform.caldav.entity.CaldavServerEntity;

/**
 * Persistence access to the CalDAV server registrations. The table is a
 * registry a deployment holds a handful of rows in, which is why the reads
 * here are whole-registry reads rather than paginated ones.
 */
public interface CaldavServerDAO extends JpaRepository<CaldavServerEntity, Long> {

  /**
   * Finds the registration bridged to the given agenda remote provider.
   *
   * @param providerName name of the agenda remote provider
   * @return the registration carrying that provider name, if any
   */
  Optional<CaldavServerEntity> findByProviderName(String providerName);

}

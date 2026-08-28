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

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Name resolution as a seam. The address checks of
 * {@link CaldavServerUrlValidator} have to know where a declared host
 * actually points, and calling {@link InetAddress} statically from the
 * validator would make every one of its tests reach the network — flaky, slow,
 * and dependent on whatever the machine's resolver believes today. The
 * production wiring hands over the JDK resolver; the tests hand over a table.
 */
@FunctionalInterface
public interface CaldavHostResolver {

  /**
   * Every address a host name — or an IP literal, which resolves to itself
   * without a query — currently points at.
   *
   * @param host host part of a declared URL, brackets already stripped from an
   *          IPv6 literal
   * @return the addresses the host points at, never empty
   * @throws UnknownHostException when the host cannot be resolved at all
   */
  InetAddress[] resolve(String host) throws UnknownHostException;

}

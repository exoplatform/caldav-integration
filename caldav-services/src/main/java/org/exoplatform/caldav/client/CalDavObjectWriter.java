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
package org.exoplatform.caldav.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The CalDAV door: every write is the {@link CalDavClient} method of the same
 * name, with the same headers, the same body and the same answer. Zero
 * behaviour change from the days the engine called the client directly — this
 * class exists so that the other door ({@code BlueMindImportWriter}) can be
 * chosen per server without the engine knowing there are two.
 */
@Component
public class CalDavObjectWriter implements CalendarObjectWriter {

  private final CalDavClient calDavClient;

  /**
   * The writer over the one CalDAV client.
   *
   * @param calDavClient the protocol client every request goes through
   */
  @Autowired
  public CalDavObjectWriter(CalDavClient calDavClient) {
    this.calDavClient = calDavClient;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PutResult putObject(CalDavEndpoint endpoint, String href, String icsData) {
    return calDavClient.putObject(endpoint, href, icsData);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PutResult overwriteObject(CalDavEndpoint endpoint, String href, String icsData) {
    return calDavClient.overwriteObject(endpoint, href, icsData);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PutResult updateObject(CalDavEndpoint endpoint, String href, String icsData, String ifMatch) {
    return calDavClient.updateObject(endpoint, href, icsData, ifMatch);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int deleteObject(CalDavEndpoint endpoint, String href, String ifMatch) {
    return calDavClient.deleteObject(endpoint, href, ifMatch);
  }
}

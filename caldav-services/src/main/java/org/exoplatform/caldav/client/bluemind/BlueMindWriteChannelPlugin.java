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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalendarObjectWriter;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.plugin.CalendarWriteChannelPlugin;

/**
 * Contributes BlueMind's import door for {@link WriteChannel#BLUEMIND_IMPORT}.
 *
 * <p>
 * A {@code @Service}, like any contribution from another add-on, so that the
 * host collects it by type through the Kernel/Spring bridge and never names
 * it.
 */
@Service
public class BlueMindWriteChannelPlugin implements CalendarWriteChannelPlugin {

  /** The writer over BlueMind's ICS import API. */
  private final BlueMindImportWriter blueMindImportWriter;

  /**
   * The contribution over the import writer.
   *
   * @param blueMindImportWriter the writer over BlueMind's ICS import API
   */
  @Autowired
  public BlueMindWriteChannelPlugin(BlueMindImportWriter blueMindImportWriter) {
    this.blueMindImportWriter = blueMindImportWriter;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public WriteChannel channel() {
    return WriteChannel.BLUEMIND_IMPORT;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CalendarObjectWriter writer() {
    return blueMindImportWriter;
  }

}

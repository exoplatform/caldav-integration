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
package org.exoplatform.caldav.plugin;

import org.exoplatform.caldav.client.CalendarObjectWriter;
import org.exoplatform.caldav.model.WriteChannel;

/**
 * A door, other than CalDAV, through which the copies of a server whose
 * registration declares a given {@link WriteChannel} are written and removed.
 *
 * <p>
 * <b>How it is found.</b> {@code CalendarObjectWriters} collects every bean
 * implementing this interface, across web applications: a contributor is a
 * Spring {@code @Service}, never {@code final}, living in its own add-on or in
 * this one. {@link WriteChannel#CALDAV} is built in and cannot be claimed; a
 * contribution naming it is ignored.
 *
 * <p>
 * <b>What happens without one.</b> A server whose registration declares a
 * channel nobody contributes is <em>refused</em>, never written over CalDAV
 * instead: the channel exists precisely because a CalDAV write does something
 * that server must not be asked to do. The write fails with
 * {@code CaldavPushService#WRITE_CHANNEL_UNAVAILABLE}, which the sync records
 * as a known state.
 */
public interface CalendarWriteChannelPlugin {

  /**
   * The channel this door serves.
   *
   * @return a channel other than {@link WriteChannel#CALDAV}, never null
   */
  WriteChannel channel();

  /**
   * The writer every object of a server on this channel goes through.
   *
   * @return the writer, never null
   */
  CalendarObjectWriter writer();

}

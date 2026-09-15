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
package org.exoplatform.caldav.model;

/**
 * <h2>Through which door eXo writes and removes the meeting copies of a
 * server.</h2>
 *
 * <p>
 * One decision per registration, like {@link MirrorTargetKind}, and for the
 * same reason: it is a property of the server behind the registration, not of
 * any user or any single write. A channel, not a tolerance — the quirk
 * catalogue changes what eXo <i>notices</i> about a copy, this changes
 * <i>how the copy gets there</i> — which is why it is its own field rather
 * than an entry in {@link ServerQuirk}.
 *
 * <p>
 * Why a second door exists at all (EXO-90307). A copy written over CalDAV
 * reaches BlueMind's calendar service with {@code sendNotification=true}
 * hard-coded ({@code plugins/net.bluemind.dav.server/.../proto/put/CreateEntity.java:165,170};
 * the DELETE too, {@code .../proto/delete/DeleteProtocol.java:102}), and
 * BlueMind then schedules the meeting itself: same-server invitees get it
 * twice, an answer given on BlueMind's own object never reaches eXo, and
 * external attendees get BlueMind's mail on top of eXo's. BlueMind honours no
 * {@code SCHEDULE-AGENT} and offers no administrator toggle, so there is no
 * CalDAV-side escape. Its ICS import endpoint, on the other hand, hard-codes
 * {@code sendNotification=false} on every add, modify and delete it performs
 * ({@code EventChangesMerge.java:102,238,240}), and
 * {@code IcsHook.mustSendNotification} returns on that flag before anything
 * else is looked at ({@code IcsHook.java:1099-1117}).
 */
public enum WriteChannel {

  /**
   * CalDAV {@code PUT} and {@code DELETE} on the object's own href. The
   * default, and what every deployment did before this setting existed.
   */
  CALDAV,

  /**
   * BlueMind's own REST API: the ICS import endpoint for every write
   * ({@code PUT /api/calendars/vevent/{containerUid}}, {@code IVEvent.importIcs}),
   * its calendar API for every removal
   * ({@code DELETE /api/calendars/{containerUid}/{uid}?sendNotifications=false}),
   * and a {@code calendar-multiget} REPORT afterwards to learn the version the
   * server now holds. Only meaningful on a BlueMind server, and offered to the
   * administrator as a flag so that turning it off is the whole rollback.
   */
  BLUEMIND_IMPORT;

  /**
   * Reads a stored value into a channel, tolerating anything a database can
   * hold.
   *
   * <p>
   * Never throws, for the reason {@link MirrorTargetKind#of} gives: the
   * column is written by this code and by a Liquibase default, so an
   * unexpected value means somebody edited the row by hand or a later version
   * wrote a name this one does not know — and the safe reading of both is the
   * behaviour every deployment already had. A throw on read would take down
   * every account resolving through that server, not merely route its writes
   * through the older door.
   *
   * @param stored the value read from the registration row, may be null or
   *          blank
   * @return the channel it names, or {@link #CALDAV} when it names none
   */
  public static WriteChannel of(String stored) {
    if (stored == null || stored.isBlank()) {
      return CALDAV;
    }
    for (WriteChannel channel : values()) {
      if (channel.name().equalsIgnoreCase(stored.trim())) {
        return channel;
      }
    }
    return CALDAV;
  }
}

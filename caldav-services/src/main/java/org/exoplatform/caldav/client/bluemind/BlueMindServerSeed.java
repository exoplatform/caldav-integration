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

import java.util.List;

import org.springframework.stereotype.Service;

import org.exoplatform.caldav.model.MirrorTargetKind;
import org.exoplatform.caldav.model.ServerQuirk;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.plugin.CalendarServerSeed;

/**
 * The BlueMind row a first install receives (EXO-90737): the same row the
 * host used to seed itself, now contributed by the BlueMind code rather than
 * named by the host, until the BlueMind add-on's own seed replaces it.
 *
 * <p>
 * It states no order, which sorts it after the add-on's seed; both carry the
 * identifier {@value #SEED_ID}, so when both are installed the host seeds the
 * add-on's and ignores this one.
 *
 * <p>
 * <b>What the row carries, and why.</b> It is excused for what BlueMind is
 * known to do to a copy ({@link #QUIRKS}), pointed at the account's
 * <b>main</b> calendar, and writes through BlueMind's import channel — the
 * same choices the browser's BlueMind preset makes on a declaration
 * ({@code serverPresets.js}). The main calendar rather than the model's
 * default because on this server the default has an established cost:
 * BlueMind's dedicated calendar is excluded from the account's free/busy —
 * colleagues booking around the user see eXo meeting times as free — and it
 * carries no answer buttons. A seed that disagreed with the preset an
 * administrator is about to apply to the very same row would be teaching two
 * answers to one question.
 */
@Service
public class BlueMindServerSeed implements CalendarServerSeed {

  /** The seed's identifier, the same as the BlueMind flavour's. */
  public static final String           SEED_ID     = BlueMindServerFlavour.FLAVOUR_ID;

  /** The name the BlueMind row is seeded under. */
  public static final String           SERVER_NAME = "Bluemind";

  /**
   * Address the BlueMind row is seeded with: a placeholder an administrator
   * is expected to replace with the DAV endpoint of their own BlueMind, whose
   * shape it mirrors (BlueMind serves DAV under {@code /dav/} and answers
   * there with 401 Basic realm="bm.basic.auth.v2"; the bare host only
   * redirects).
   *
   * <p>
   * An RFC 2606 {@code .invalid} name (EXO-89794): it can never resolve, so
   * the row can never be a live target, and it fails the address check — so
   * the row is seeded inactive rather than offered to users as a connector
   * that goes nowhere.
   *
   * <p>
   * Note also that BlueMind sends no CORS headers, so connecting from the
   * browser needs the portal to front it on its own origin.
   */
  public static final String           SERVER_URL  = "https://caldav.example.invalid/dav/";

  /**
   * The catalogue entries the seeded BlueMind row arrives excused for: the
   * behaviours a live account was characterised with across EXO-89716 to
   * EXO-89828, and the same four the browser's BlueMind preset ticks on a
   * declaration ({@code serverPresets.js}).
   *
   * <p>
   * <b>The two lists are the same list, and that is a constraint rather than
   * a coincidence.</b> A preset also carries a <i>summary sentence</i> naming
   * exactly what it ticks — {@code caldav.admin.servers.preset.bluemind.summary}
   * — so widening the preset is a product-copy change and not only a list
   * edit. They are held together by two assertions of the whole string, one
   * where each is produced ({@code CaldavServerServiceTest},
   * {@code serverPresets.test.js}), and by the summary pin that fails a tick
   * without a sentence and a sentence without a tick.
   *
   * <p>
   * <b>Why the entries and not their patterns.</b> The catalogue is where a
   * behaviour has its patterns, its direction and its sentence; naming the
   * entry here means the seed writes exactly what a tick of that box writes,
   * and a pattern the catalogue later widens reaches the next fresh install
   * without a second spelling to keep in step. It does <b>not</b> reach the
   * browser's BlueMind preset, which carries its own {@code QUIRKS} map of the
   * same ids to the same patterns.
   *
   * <p>
   * <b>Tolerance entries only.</b> The host files each entry into the
   * tolerance column its direction names and writes no entry that changes
   * what eXo writes ({@code OMIT}); {@code CaldavServerServiceTest} fails the
   * moment one is named here.
   */
  public static final List<ServerQuirk> QUIRKS     = List.of(ServerQuirk.DROPS_CONFERENCE,
                                                             ServerQuirk.ADDS_COMPATIBILITY_MARKERS,
                                                             ServerQuirk.ADDS_FORMATTED_DESCRIPTION,
                                                             ServerQuirk.STAMPS_DEFAULT_PRIORITY);

  /**
   * {@inheritDoc}
   */
  @Override
  public String id() {
    return SEED_ID;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String name() {
    return SERVER_NAME;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String serverUrl() {
    return SERVER_URL;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<ServerQuirk> quirks() {
    return QUIRKS;
  }

  /**
   * The account's main calendar, see the class comment.
   *
   * @return {@link MirrorTargetKind#MAIN_CALENDAR}
   */
  @Override
  public MirrorTargetKind mirrorTarget() {
    return MirrorTargetKind.MAIN_CALENDAR;
  }

  /**
   * BlueMind's own ICS import door (EXO-90307): a CalDAV write makes BlueMind
   * schedule the meeting itself.
   *
   * @return {@link WriteChannel#BLUEMIND_IMPORT}
   */
  @Override
  public WriteChannel writeChannel() {
    return WriteChannel.BLUEMIND_IMPORT;
  }

}

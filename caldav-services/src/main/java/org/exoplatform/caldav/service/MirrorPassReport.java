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

import java.util.Date;

import org.exoplatform.caldav.model.MirrorVerification;

/**
 * <h2>What the last pass over one user's copies found and moved.</h2>
 *
 * <p>
 * The two tallies together, never one of them: the destination setting of
 * EXO-89762 is applied by a relocation and then judged by a verification, and an
 * administrator asking "did my change take effect" is asking about both. Split
 * across two screens they would be two facts nobody joins up.
 *
 * <p>
 * <b>Why this exists at all.</b> Before it, the only way to know whether a
 * change of destination had landed was to read the server log — which puts the
 * measurement gate this add-on asks for ("choose the account's main calendar
 * only once copies synchronise cleanly on this server") behind a permission no
 * product owner has. A tally on screen is what turns "measured" into something
 * the person who has to make the decision can check.
 *
 * @param userIdentityId identity of the user the pass ran for
 * @param username the login of that user, or null when it could not be resolved
 * @param fullName the display name of that user, or null when it could not be
 *          resolved
 * @param at when the pass ended
 * @param verification what the comparison of the copies found and repaired
 * @param relocation what the move of the copies to a new destination did — a
 *          {@link MirrorRelocation#deferred() deferred} one on the ordinary
 *          pass, which owes no move at all
 */
public record MirrorPassReport(long userIdentityId,
                               String username,
                               String fullName,
                               Date at,
                               MirrorVerification verification,
                               MirrorRelocation relocation) {

  /**
   * The same report, with the identity of its user filled in.
   *
   * <p>
   * Resolved when the report is READ rather than when it is recorded: the pass
   * runs on every synchronisation of every connected account, the screen is
   * opened by an administrator once in a while, and a lookup per pass would be
   * a cost paid forever for a fact almost nobody looks at.
   *
   * @param resolvedUsername the login, or null
   * @param resolvedFullName the display name, or null
   * @return a copy carrying the identity
   */
  public MirrorPassReport named(String resolvedUsername, String resolvedFullName) {
    return new MirrorPassReport(userIdentityId, resolvedUsername, resolvedFullName, at, verification, relocation);
  }

  /**
   * Whether this pass actually moved copies between calendars.
   *
   * <p>
   * The ordinary pass owes no move, so its relocation is deferred and every
   * count is zero; saying "0 moved" on those would bury the accounts where a
   * destination change is genuinely still working itself out.
   *
   * @return true when the pass tried to move at least one copy
   */
  public boolean relocated() {
    return relocation != null
        && (relocation.moved() > 0 || relocation.refused() > 0 || relocation.failed() > 0 || relocation.unmovable() > 0);
  }
}

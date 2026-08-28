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

import org.exoplatform.caldav.model.MirrorTargetKind;

/**
 * How one user's meeting copies are being placed: by whose decision, whether
 * that decision is still owed, and where the copies are landing today.
 *
 * <p>
 * Three fields rather than one nullable destination, because "nowhere" has two
 * very different causes and a screen has to tell them apart. A destination that
 * is absent because the account cannot be reached is a problem to retry; a
 * destination that is absent because the user was asked to name one and has not
 * is a question to put in front of them. Collapsed into one null, the second
 * looks like the first and nobody ever answers it.
 *
 * @param kind where the registration behind this account wants copies written
 * @param choicePending true when the registration leaves the destination to the
 *          user and this user has not named one — the only state in which no
 *          copy is written and the user themselves can clear it
 * @param destination where copies are landing today, or null when there is no
 *          destination — including, but not only, when a choice is pending
 */
public record MirrorState(MirrorTargetKind kind, boolean choicePending, MirrorTarget destination) {
}

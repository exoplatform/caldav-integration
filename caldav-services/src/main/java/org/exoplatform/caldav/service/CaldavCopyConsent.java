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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.agenda.service.AgendaUserSettingsService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Whether a user has said yes to meeting copies at all, said once.
 *
 * <h2>Why this is a bean and not a private method</h2>
 *
 * <p>
 * It was a private method, on the seeding pass, and one reader was enough for
 * that. A second reader arrived with EXO-90247: deciding whether to skip an
 * invitee's copy because the server will deliver the invitation itself requires
 * knowing that the <b>organizer's</b> own copy really is being written — an
 * organizer who turned copies off puts no organizer-bearing object on the
 * server, so nothing is delivered natively and the invitee's copy must be
 * written as before. That is the same question about a different person, and
 * asking it twice in two spellings is how the two answers drift apart.
 *
 * <p>
 * It is deliberately a bean of its own rather than a method moved onto either
 * reader. The seeding pass already depends on the push service; a home on
 * either of those would make the other's dependency a cycle. This one depends
 * on agenda's settings service and on nothing else in the add-on.
 *
 * <h2>Why it is predictive and not evidential</h2>
 *
 * <p>
 * The honest-looking alternative — ask whether a copy of the event already
 * exists in the organizer's account — cannot be used at the moment the question
 * is asked. The author's own copy is pushed by their browser, which
 * <i>races</i> the creation fan-out (see {@code EventCreatedListener}), so at
 * fan-out time the row is routinely absent and an evidential check would answer
 * "no organizer copy" for exactly the meetings this decision is about. Consent
 * is what can be read reliably before the fact.
 */
@Component
public class CaldavCopyConsent {

  private static final Log LOG = ExoLogger.getLogger(CaldavCopyConsent.class);

  @Autowired
  private AgendaUserSettingsService agendaUserSettingsService;

  /**
   * Whether this user receives copies at all — the same per-account switch the
   * browser flow honours, read from agenda's settings for this add-on's
   * provider. A user who turned copies off has said no to everything the copy
   * machinery does, the seeded pending invitations included.
   *
   * <p>
   * An unreadable answer is "no". No settings readable means no consent
   * readable, and consent is the one thing a write into somebody's calendar
   * must not assume.
   *
   * @param userIdentityId identity of the user
   * @return true when the connected CalDAV account accepts copies
   */
  public boolean copiesEnabled(long userIdentityId) {
    try {
      var settings = agendaUserSettingsService.getAgendaUserSettings(userIdentityId);
      return settings != null
             && settings.getConnectedConnectors()
                        .stream()
                        .anyMatch(account -> isCaldavConnector(account.getProviderName())
                                             && account.isPushEnabled());
    } catch (RuntimeException e) {
      LOG.debug("The agenda settings of user {} could not be read; copies are treated as refused", userIdentityId, e);
      return false;
    }
  }

  /**
   * Whether a connected account's provider is one of this add-on's.
   *
   * <p>
   * A declared CalDAV server gets a provider name of its own — the base name
   * with the server's identifier appended — so only a user connected to the
   * seed registration carries the bare name. Matching the bare name alone
   * therefore read as "copies disabled" for everyone connected to a server an
   * administrator had declared, and their meetings were never copied at all,
   * silently: the pass returned before it did anything, and its own
   * diagnostics printed nothing because nothing had been considered.
   *
   * @param providerName the provider a connected account names
   * @return true when it is a CalDAV account of this add-on
   */
  private boolean isCaldavConnector(String providerName) {
    return providerName != null
        && (providerName.equals(CaldavPushService.CONNECTOR_NAME)
            || providerName.startsWith(CaldavPushService.CONNECTOR_NAME + "."));
  }
}

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.entity.CaldavShareObservationEntity;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavShareObservationStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Which of a user's own calendars colleagues can see, and how many of them
 * (EXO-90331).
 *
 * <p>
 * <b>Where the answer comes from.</b> Not from eXo remembering the grants it
 * made, and not from asking the server on every panel refresh. Both were
 * considered and both are worse: the first stores eXo's belief, so a share
 * made or removed in the server's own web client would be missed; the second
 * costs one remote access-list read per shareable calendar per refresh, which
 * is the N the Share drawer already pays once, deliberately, and which a
 * left-panel list cannot pay at all.
 *
 * <p>
 * It comes instead from the other end, for free. Every user's synchronisation
 * pass lists their whole CalDAV home and classifies each collection; a
 * collection this deployment minted for <em>another</em> of its users is
 * {@link CollectionOwnership#COLLEAGUES_EXO_CALENDAR}, recognised by the
 * anchor its slug carries ({@link CaldavOutboundService#anchorOf}), and the
 * pair behind that anchor names the owner
 * ({@link CaldavOutboundService#exportingUserOf}). So the sharee's own sweep
 * — a listing it already performs, on a schedule it already keeps — observes
 * exactly the outbound fact the owner's row needs. Persisted, the owner's mark
 * becomes a local indexed query and the server is asked nothing.
 *
 * <p>
 * <b>The two bounds of that answer</b>, stated here because this is where a
 * caller decides what to draw from it:
 * <ol>
 * <li><b>Only eXo colleagues are counted.</b> A sharee who is not a user of
 * this deployment with a connected CalDAV account never runs a pass, so
 * nothing ever lists their home and their share is in no row. That is the same
 * population EXO-90277's first limit already puts out of scope for sharing
 * from eXo; a calendar shared only with such a sharee carries no mark.</li>
 * <li><b>The mark lags by up to one synchronisation period.</b> A share
 * granted a minute ago is seen when the sharee's next pass runs — five minutes
 * by default — and so is a share revoked a minute ago. The mark is therefore a
 * state indicator and never a confirmation that a grant succeeded; the Share
 * drawer, which reads the server live, is what confirms one.</li>
 * </ol>
 * Both make the count a <b>floor</b> on a calendar's exposure. Nothing may
 * read "no mark" as "shared with nobody".
 */
@Service
public class CaldavShareObservationService {

  private static final Log                LOG = ExoLogger.getLogger(CaldavShareObservationService.class);

  @Autowired
  private CaldavShareObservationStorage   caldavShareObservationStorage;

  @Autowired
  private CaldavConnectorStorage          caldavConnectorStorage;

  @Autowired
  private AgendaCalendarService           agendaCalendarService;

  /**
   * Records what one sharee's calendar home listed of their colleagues' eXo
   * calendars, replacing whatever the last pass recorded for that home.
   *
   * <p>
   * Given the <em>whole</em> listing's worth of colleagues' calendars, never
   * one at a time: the removal of a share that has stopped being listed is
   * only expressible against a complete set, and a per-collection write could
   * add a row but never take one away. The caller must therefore have listed
   * the home successfully — a failed listing is an absence of evidence, and
   * handing it on as an empty map would erase every mark the sharee's
   * colleagues have.
   *
   * <p>
   * Absorbs its own failure. A sighting that could not be written is one the
   * next pass writes; a synchronisation must not fail because a state
   * indicator could not be updated.
   *
   * @param shareeIdentityId the eXo user whose home was listed
   * @param serverId the declared server registration, zero for an account
   *          attached before registrations existed
   * @param ownersByAnchor every colleague's eXo calendar the listing held,
   *          calendar anchor to the identity of the eXo user who owns it;
   *          empty when the home listed none
   */
  public void observed(long shareeIdentityId, long serverId, Map<String, Long> ownersByAnchor) {
    try {
      int changed = caldavShareObservationStorage.reconcile(shareeIdentityId, serverId, ownersByAnchor);
      if (changed > 0) {
        LOG.debug("The home of user {} on server {} now lists {} of their colleagues' eXo calendars; {} sighting(s) changed",
                  shareeIdentityId,
                  serverId,
                  ownersByAnchor.size(),
                  changed);
      }
    } catch (RuntimeException e) {
      LOG.warn("What the home of user {} lists of their colleagues' calendars could not be recorded;"
          + " the next pass records it", shareeIdentityId, e);
    }
  }

  /**
   * How many colleagues see each of the caller's own calendars.
   *
   * <p>
   * Keyed by agenda calendar id, which is what a row in the agenda panel knows
   * itself by, while the table is keyed by the calendar's anchor — the
   * {@code syncUid} the collection's slug carries and the pair table records.
   * The translation happens here and only in the owner's own session, over
   * their own calendars: {@code getCalendarsByOwnerIds} rather than
   * {@code getCalendars}, for the reason
   * {@link CaldavCalendarShareService#shareableCalendarIds} records — the
   * latter also reads every calendar of every space the user belongs to,
   * through an ACL check each, for a list this would discard.
   *
   * <p>
   * Two local reads and no remote request, which is the whole point of
   * observing the fact rather than asking for it. A calendar with no sighting
   * is simply absent from the answer rather than present with a zero: the
   * caller draws a mark for what is there, and the bounds above mean absence
   * is "nothing seen", not "shared with nobody".
   *
   * <p>
   * Never fails: an account that is not connected, an agenda that cannot list
   * the calendars, or anything else answers an empty map, exactly as
   * {@link CaldavCalendarShareService#shareableCalendarIds} does — a state
   * indicator that cannot be computed is one that is not drawn.
   *
   * @param userIdentityId the caller, owner of the calendars
   * @param username the caller's login
   * @return agenda calendar id to the number of colleagues who see it, empty
   *         when none of their calendars is seen by anyone
   */
  public Map<Long, Long> shareeCountsByCalendar(long userIdentityId, String username) {
    try {
      CaldavUserSetting settings = caldavConnectorStorage.getCaldavSetting(userIdentityId);
      if (!connected(settings)) {
        return Map.of();
      }
      long serverId = settings.getServerId() == null ? 0L : settings.getServerId();
      Map<String, Long> byAnchor = caldavShareObservationStorage.countShareesByAnchor(userIdentityId, serverId);
      if (byAnchor.isEmpty()) {
        return Map.of();
      }
      Map<Long, Long> byCalendar = new LinkedHashMap<>();
      for (Calendar calendar : agendaCalendarService.getCalendarsByOwnerIds(List.of(userIdentityId), username)) {
        if (calendar.getOwnerId() != userIdentityId || calendar.isDeleted() || StringUtils.isBlank(calendar.getSyncUid())) {
          continue;
        }
        Long sharees = byAnchor.get(calendar.getSyncUid());
        if (sharees != null && sharees > 0) {
          byCalendar.put(calendar.getId(), sharees);
        }
      }
      return byCalendar;
    } catch (Exception e) { // NOSONAR this answer must never fail, whatever agenda or the storage throws
      LOG.debug("How many colleagues see the calendars of user {} could not be established; none is marked", userIdentityId, e);
      return Map.of();
    }
  }

  /**
   * Forgets every sighting one user's calendar home produced.
   *
   * <p>
   * Asked when their account is disconnected or connected again. A home that
   * is no longer read confirms nothing, and a sighting nobody will ever
   * contradict again is precisely the stale mark this design must not leave
   * behind: the colleague whose calendar it named would go on being told they
   * are exposed long after the share was revoked. Forgetting is the honest
   * answer — it says "not seen" rather than "seen once, years ago" — and the
   * first pass after a reconnection restores whatever is still true.
   *
   * <p>
   * Absorbs its own failure, because the two acts calling it must succeed
   * whatever happens here.
   *
   * @param userIdentityId the eXo user whose home is no longer read
   */
  public void forgetObservationsOf(long userIdentityId) {
    try {
      int removed = caldavShareObservationStorage.forgetSharee(userIdentityId);
      if (removed > 0) {
        LOG.debug("The {} colleague calendar(s) the home of user {} listed are forgotten; their account is no longer read",
                  removed,
                  userIdentityId);
      }
    } catch (RuntimeException e) {
      LOG.warn("What the home of user {} listed of their colleagues' calendars could not be forgotten;"
          + " the marks it feeds may name a share nobody can confirm any more", userIdentityId, e);
    }
  }

  /**
   * Whether an anchor can be recorded faithfully.
   *
   * <p>
   * An anchor longer than the column, or carrying a character outside the
   * Basic Multilingual Plane which MySQL's {@code utf8mb3} column refuses or,
   * out of strict mode, truncates, is not recorded at all rather than cut down
   * into another calendar's — the same rule, for the same reason, as the
   * principal of {@code CaldavConnectionIdentityService}. Agenda mints a UUID,
   * so this rejects nothing in practice and exists for the collection whose
   * slug a server rewrote.
   *
   * @param anchor the calendar anchor the slug carried
   * @return true when it can be stored as it is
   */
  public static boolean isRecordableAnchor(String anchor) {
    return StringUtils.isNotBlank(anchor) && anchor.length() <= CaldavShareObservationEntity.ANCHOR_MAX_LENGTH
        && anchor.codePoints().allMatch(codePoint -> codePoint <= 0xFFFF);
  }

  /**
   * Whether an account is usable.
   *
   * @param settings the stored account
   * @return true when it carries credentials
   */
  private static boolean connected(CaldavUserSetting settings) {
    return settings != null && StringUtils.isNotBlank(settings.getUsername())
        && StringUtils.isNotBlank(settings.getPassword());
  }
}

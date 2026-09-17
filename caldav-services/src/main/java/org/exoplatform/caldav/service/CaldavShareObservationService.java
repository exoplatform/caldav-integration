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
import java.util.Set;
import java.util.function.Predicate;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.bluemind.BlueMindContainerNaming;
import org.exoplatform.caldav.entity.CaldavShareObservationEntity;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavShareObservationStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
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
 * It comes from two writers instead, neither of which asks the server
 * anything on the read path.
 *
 * <p>
 * <b>The grant and the revoke write it directly.</b> When eXo itself shares a
 * calendar it knows, exactly and at once, which calendar and which colleague:
 * {@link #granted} and {@link #revoked} record that as the server accepts it.
 * This is what makes the mark true immediately rather than at the next pass,
 * and it is the only writer that can be right about a revoke — the sweep can
 * only notice a revoke by a collection ceasing to be listed, which needs the
 * colleague's pass to run, and the owner cannot cause that pass.
 *
 * <p>
 * <b>The synchronisation pass reconciles.</b> Every user's pass lists their
 * whole CalDAV home and classifies each collection; a collection this
 * deployment minted for <em>another</em> of its users is
 * {@link CollectionOwnership#COLLEAGUES_EXO_CALENDAR}, recognised by the
 * anchor its slug carries ({@link CaldavOutboundService#anchorOf}), and the
 * pair behind that anchor names the owner
 * ({@link CaldavOutboundService#exportingUserOf}). That covers what the first
 * writer cannot see — a share made or removed in the calendar server's own web
 * client — and corrects drift, since a row is confirmed by the next pass that
 * still lists the collection and deleted by the first that does not.
 *
 * <p>
 * <b>The bounds of that answer</b>, stated here because this is where a
 * caller decides what to draw from it. Three of them make the count a floor:
 * <ol>
 * <li><b>An imported calendar carries a mark only where its owner can be told
 * from what the sharee's home lists.</b> A calendar the user owns on the
 * server and imported into eXo — a {@code REMOTE} pair — can be shared from
 * the Share drawer, and its collection carries no anchor this deployment
 * minted; both writers then resolve it through {@link #importedSightingOf}
 * and {@link #observableImportedAnchorOf}, one rule with two entrances, which
 * names the owner's own imported pair from the collection's path and the
 * owner the server states for it. That covers the account's default calendar
 * on an RFC 3744 server, where the collection is listed under the owner's
 * path with the owner's principal as {@code DAV:owner}, and on BlueMind a
 * {@code calendar:Default:<uid>} or {@code calendar:UserCreated:<uid>:…}
 * container, whose name carries the owner's uid. What stays out: a BlueMind
 * container whose name carries no uid (a bare uuid, an {@code exo-cal-*}
 * another deployment minted), a collection the server lists with no owner,
 * and a login two eXo users share — each is a case where the owner is not
 * knowable locally, and a guess would put the mark on the wrong row. The
 * server's own owner listing (EXO-90347) is the way to lift the first of
 * those; until then a grant on such a calendar records nothing rather than
 * a row the next pass would erase.</li>
 * <li><b>A share made outside eXo is only ever seen by a pass.</b> So it needs
 * the sharee to be a user of this deployment with a connected CalDAV account —
 * nobody else ever lists a home — and it appears, and goes away, up to one
 * synchronisation period late. A share eXo made is subject to neither: it is
 * recorded as it is made, and eXo can only share with a connected colleague in
 * the first place ({@code CaldavCalendarShareService.SHAREE_NOT_CONNECTED}).</li>
 * <li><b>The mark still confirms nothing.</b> It is a state indicator; the
 * Share drawer, which reads the server live, is what confirms who can
 * see a calendar.</li>
 * </ol>
 * All three make the count a <b>floor</b> on a calendar's exposure, and
 * nothing may read "no mark" as "shared with nobody".
 *
 * <p>
 * It is a floor only among the colleagues whose homes are still being read,
 * which is the fourth bound and the one that runs the other way: a sighting
 * nothing contradicts stands, so a suspended or removed colleague, and a home
 * whose listing comes back empty, each keep their last one. The three causes
 * and the reason none of them removes a row are set out once, on
 * {@code CaldavShareObservationEntity}; this is the caller's summary of them,
 * not a second list to keep in step.
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

  @Autowired
  private CaldavSyncStorage               caldavSyncStorage;

  @Autowired
  private CaldavConnectionIdentityService caldavConnectionIdentityService;

  /**
   * The pair states under which an imported pair stands for a calendar its
   * user still holds in eXo, bound to the collection it records — the only
   * pairs that can be the owner's own (EXO-90331).
   *
   * <p>
   * Left out, each because it stands for nothing on the eXo side: a hidden
   * share and a retired subscription are the <em>sharee's</em> records of
   * somebody else's collection ({@code CaldavDeletionService#hideShare},
   * {@code CaldavSubscriptionRetirementService}); a locally deleted, deleting
   * or orphaned pair binds a calendar that is gone. On the rig an owner's
   * {@code LOCALLY_DELETED} pair sits beside the active one at the same path
   * (pairs 6 and 8), which is why the states are named rather than the row
   * count relied on.
   */
  private static final Set<CalendarSyncStatus> HOLDING_STATES = Set.of(CalendarSyncStatus.ACTIVE,
                                                                       CalendarSyncStatus.PAUSED,
                                                                       CalendarSyncStatus.REMOTE_GONE,
                                                                       CalendarSyncStatus.REMOTE_CREATE_REFUSED);

  /**
   * An imported calendar's sighting resolved to its owner: the identity whose
   * row carries the mark, and the anchor the row is filed under (EXO-90331).
   *
   * @param ownerIdentityId the eXo user whose imported pair stands behind the
   *          collection
   * @param anchor that pair's {@code localCalendarSyncUid}, agenda's sync uid
   *          of the owner's calendar
   */
  public record ImportedSighting(long ownerIdentityId, String anchor) {
  }

  /**
   * Whose imported calendar a collection listed in a sharee's home is, and
   * under which anchor its sighting is filed — the sweep's entrance to the
   * one rule both writers share (EXO-90331).
   *
   * <p>
   * <b>The key.</b> A row is keyed by the owner's calendar anchor, agenda's
   * {@code syncUid}, which for an eXo-created calendar the collection's slug
   * carries. An imported collection carries no such slug, but the owner's
   * own pair for it does record the anchor — {@code localCalendarSyncUid},
   * the uid agenda minted when the collection was materialised
   * ({@code CaldavSyncService#materialise}) — and that is what the owner's
   * panel resolves a mark by ({@link #shareeCountsByCalendar}). So the
   * question is only ever: which pair is the owner's. Both writers ask it
   * through {@link #ownerPairAt}, and that is what makes a row the grant
   * writes one the sharee's next reconciliation keeps.
   *
   * <p>
   * <b>Whose, by the shape of the path.</b> Two servers, two ways the owner
   * is stated, and the path says which applies:
   * <ul>
   * <li><b>BlueMind</b> lists a subscription under the subscriber's own home
   * ({@code …/__uids__/<subscriber>/<container>}) and names the subscriber
   * as {@code DAV:owner}, so the server's owner says nothing; the container
   * uid does — {@code calendar:Default:<uid>} and
   * {@code calendar:UserCreated:<uid>:…} carry the owner's directory uid
   * ({@link BlueMindContainerNaming#ownerUidOf}). The owner's own pair is
   * then at the same container under the owner's home
   * ({@link BlueMindContainerNaming#hrefInHomeOf}), and the owner is the one
   * user of that pair's path whose recorded principal carries that uid.</li>
   * <li><b>An RFC 3744 server</b> (Stalwart) lists the shared collection
   * under the <em>owner's</em> path — the very path the owner's pair records
   * — and names the owner's principal as {@code DAV:owner}. The owner is the
   * one user with a pair at that path whose recorded principal is that
   * owner.</li>
   * </ul>
   * A path of BlueMind's home shape is read the BlueMind way only, even when
   * the server also states an owner: on BlueMind that owner is the viewer,
   * and reading it would name nobody rather than somebody wrong, but the
   * grant side must apply the same choice, and it cannot see the sharee's
   * listing to know which arm the sweep would take.
   *
   * <p>
   * <b>Why the recorded principal, and why exactly one.</b> Two users who
   * each imported the same third party's calendar hold two pairs at one
   * path; "any pair at this path" would make each look like the owner —
   * the mistake EXO-90347 corrected for adoption. The owner is instead the
   * user whose server identity ({@code CaldavConnectionIdentityService}) is
   * the one the server, or the container's name, states as owner. Two users
   * on one login (alice and alice2 on the rig) are both that, and then
   * nobody is named: a mark on the wrong row is worse than none. The viewer
   * is never the owner of what their own home lists as somebody else's.
   *
   * <p>
   * <b>What resolves to nobody</b>, and is thereby left out of the count: a
   * BlueMind container whose name carries no uid — a bare uuid, an
   * {@code exo-cal-*} another deployment minted — because the owner is not
   * derivable locally; the server's own owner listing (EXO-90347) is what
   * would settle it, and this method is where that answer would be read. A
   * collection an RFC server lists with no {@code DAV:owner}. A resource's
   * calendar, which no eXo user owns.
   *
   * <p>
   * Never throws: a lookup that fails names nobody, and the pass goes on.
   *
   * @param serverId the declared server registration
   * @param viewerIdentityId the eXo user whose home listed the collection
   * @param viewerPrincipal that account's own {@code current-user-principal},
   *          may be null when the server named none
   * @param collection the listed collection
   * @return the owner and the anchor, or null when no owner can be named
   */
  public ImportedSighting importedSightingOf(long serverId,
                                             long viewerIdentityId,
                                             String viewerPrincipal,
                                             CalendarCollection collection) {
    try {
      String href = CaldavSyncStorage.canonicalHref(collection.href());
      CalendarSync pair;
      if (BlueMindContainerNaming.homeUidOf(href) != null) {
        String ownerUid = BlueMindContainerNaming.ownerUidOf(href);
        if (ownerUid == null) {
          LOG.debug("Collection {} is named by a container uid that carries no owner; whose calendar it is stays unsaid",
                    collection.href());
          return null;
        }
        pair = ownerPairAt(serverId, BlueMindContainerNaming.hrefInHomeOf(href, ownerUid), namedByBlueMindUid(ownerUid));
      } else {
        String owner = collection.ownerIfAnother(viewerPrincipal);
        if (StringUtils.isBlank(owner)) {
          LOG.debug("Collection {} is listed with no owner other than the account; whose calendar it is stays unsaid",
                    collection.href());
          return null;
        }
        pair = ownerPairAt(serverId, href, namedByPrincipal(owner));
      }
      if (pair == null || pair.getUserIdentityId() == viewerIdentityId || !isRecordableAnchor(pair.getLocalCalendarSyncUid())) {
        return null;
      }
      return new ImportedSighting(pair.getUserIdentityId(), pair.getLocalCalendarSyncUid());
    } catch (RuntimeException e) {
      LOG.debug("Whose imported calendar collection {} is could not be established; nothing is noted for it",
                collection.href(),
                e);
      return null;
    }
  }

  /**
   * The anchor a grant on an imported calendar may be recorded under, or
   * null when it may not be recorded at all — the grant's entrance to the
   * rule {@link #importedSightingOf} sets out (EXO-90331).
   *
   * <p>
   * Asks what the sharee's pass would find for this collection, from what
   * the owner's side knows: the collection's own path, which on both server
   * shapes is the path the owner's pair records, and the owner's recorded
   * principal, which is what the pass compares the server's or the
   * container's owner against. On a path of BlueMind's home shape the
   * container must carry the owner's uid and sit in that uid's home — the
   * sharee's pass rebuilds the owner's path from exactly those two — spelled
   * as the container spells it, since that is the spelling the rebuilt path
   * carries. On any other path the pass reads {@code DAV:owner}, which
   * {@code CaldavCalendarShareService#requireImportedOwned} has already
   * confirmed the server states as the owner's principal. Either way the
   * pair the rule names must be this owner's, and the caller checks it is
   * the very pair it shares through.
   *
   * <p>
   * The grant writes nothing on a null, and that is the point: a row filed
   * under a key the pass cannot reproduce is deleted on the sharee's next
   * pass, and a mark that appears and vanishes with nothing the user did to
   * explain it is worse than none. Never throws, for the caller's sake: a
   * grant the server accepted is not undone because the mark could not be
   * decided.
   *
   * @param serverId the declared server registration
   * @param ownerIdentityId the eXo user sharing the calendar
   * @param href the collection's path, in any spelling
   * @return the anchor of the owner's imported pair the pass would name, or
   *         null when the pass would name nobody or somebody else
   */
  public String observableImportedAnchorOf(long serverId, long ownerIdentityId, String href) {
    try {
      String canonical = CaldavSyncStorage.canonicalHref(href);
      String principal = caldavConnectionIdentityService.principalOf(ownerIdentityId, serverId);
      if (principal == null) {
        LOG.debug("User {} has no recorded principal on server {}; a pass could not attribute collection {} to them",
                  ownerIdentityId, serverId, href);
        return null;
      }
      CalendarSync pair;
      String homeUid = BlueMindContainerNaming.homeUidOf(canonical);
      if (homeUid != null) {
        String ownerUid = BlueMindContainerNaming.ownerUidOf(canonical);
        if (ownerUid == null || !ownerUid.equals(homeUid) || !ownerUid.equalsIgnoreCase(BlueMindContainerNaming.principalUidOf(principal))) {
          LOG.debug("Collection {} is not named for user {}'s own uid in their own home; a pass could not attribute it to them",
                    href, ownerIdentityId);
          return null;
        }
        pair = ownerPairAt(serverId, canonical, namedByBlueMindUid(ownerUid));
      } else {
        pair = ownerPairAt(serverId, canonical, namedByPrincipal(principal));
      }
      if (pair == null || pair.getUserIdentityId() != ownerIdentityId || !isRecordableAnchor(pair.getLocalCalendarSyncUid())) {
        LOG.debug("Collection {} is one no pass would attribute to user {} alone; the share is not recorded", href, ownerIdentityId);
        return null;
      }
      return pair.getLocalCalendarSyncUid();
    } catch (RuntimeException e) {
      LOG.debug("Whether a pass could attribute collection {} to user {} could not be established; the share is not recorded",
                href, ownerIdentityId, e);
      return null;
    }
  }

  /**
   * The one imported pair, of the one user, that stands behind a collection
   * path and whose user the stated owner names (EXO-90331).
   *
   * <p>
   * The core both entrances share, so the two cannot drift. Reads every
   * imported pair recorded at the path on this server, keeps the ones in a
   * state where the pair still binds a calendar the user holds
   * ({@link #HOLDING_STATES}), and keeps the users among them whose recorded
   * server identity the owner test accepts. Exactly one user must remain;
   * that user's first pair — active first, then oldest, as the storage
   * orders them — is the answer. Two users is a shared login or a path two
   * accounts genuinely hold, and both are answered with nobody.
   *
   * @param serverId the declared server registration
   * @param ownerHref the canonical path the owner's pair would record
   * @param namesOwner whether a recorded principal is the stated owner's
   * @return the owner's pair, or null when no user or more than one qualifies
   */
  private CalendarSync ownerPairAt(long serverId, String ownerHref, Predicate<String> namesOwner) {
    if (StringUtils.isBlank(ownerHref)) {
      return null;
    }
    Map<Long, CalendarSync> byUser = new LinkedHashMap<>();
    for (CalendarSync candidate : caldavSyncStorage.getImportedPairsOnServer(serverId, ownerHref)) {
      if (!HOLDING_STATES.contains(candidate.getStatus()) || byUser.containsKey(candidate.getUserIdentityId())) {
        continue;
      }
      String principal = caldavConnectionIdentityService.principalOf(candidate.getUserIdentityId(), serverId);
      if (principal != null && namesOwner.test(principal)) {
        byUser.put(candidate.getUserIdentityId(), candidate);
      }
    }
    if (byUser.size() != 1) {
      if (byUser.size() > 1) {
        LOG.debug("Collection {} on server {} is held by users {} who are each its stated owner; none is named", ownerHref, serverId,
                  byUser.keySet());
      }
      return null;
    }
    return byUser.values().iterator().next();
  }

  /**
   * The owner test of an RFC 3744 listing: the recorded principal is the
   * {@code DAV:owner} the server stated, compared as canonical paths.
   *
   * @param owner the stated owner principal, in any spelling
   * @return the test
   */
  private static Predicate<String> namedByPrincipal(String owner) {
    String canonical = CaldavConnectionIdentityService.canonicalPrincipal(owner);
    return principal -> canonical != null && canonical.equals(CaldavConnectionIdentityService.canonicalPrincipal(principal));
  }

  /**
   * The owner test of a BlueMind container name: the recorded principal
   * carries the uid the container names, compared without regard to case as
   * {@code CaldavPushService} compares uids.
   *
   * @param ownerUid the uid the container carries
   * @return the test
   */
  private static Predicate<String> namedByBlueMindUid(String ownerUid) {
    return principal -> ownerUid.equalsIgnoreCase(BlueMindContainerNaming.principalUidOf(principal));
  }

  /**
   * Records what one sharee's calendar home listed of their colleagues'
   * calendars — eXo-created ones, and imported ones whose owner the rule
   * above could name — replacing whatever the last pass recorded for that
   * home.
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
   * @param ownersByAnchor every colleague's calendar the listing held,
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
   * Records a share the moment eXo itself made it, without waiting for the
   * sharee's next pass (EXO-90331).
   *
   * <p>
   * <b>Why the sweep is not enough on its own.</b> The reconciliation observes
   * a share from the sharee's end, so it can only report one after that
   * colleague's home has been listed again — up to a whole synchronisation
   * period. Measured on the rig: a grant at 21:39:54 was recorded at 21:40:01
   * by the pass that happened to follow, a revoke at 21:40:20 was not, and the
   * mark went on saying "still shared" because no pass ran afterwards. The
   * owner cannot shorten that wait either — pressing <i>Synchronise now</i>
   * runs the <em>owner's</em> pass, and an owner's home never lists their own
   * calendar as a colleague's. So for the two acts eXo performs itself the
   * observation is not something to wait for: it is already known, exactly,
   * at the moment the server accepted the write.
   *
   * <p>
   * <b>Why it does not make the sweep redundant.</b> This records what eXo
   * did; the sweep records what the server says, which also covers a share
   * made or removed in the server's own web client, and which is what removes
   * a row this method wrote when the grant behind it goes away elsewhere. The
   * two are the same table on purpose: the sweep reconciles a whole home
   * against its listing, so a row written here is confirmed by the next pass
   * that still lists the collection and deleted by the first that does not.
   * That is why the caller must only write a sighting the sweep can derive
   * again — {@code CaldavCalendarShareService#observableAnchorOf} is where
   * that condition is stated and checked, and a calendar that fails it gets no
   * mark rather than one that appears now and vanishes within the period.
   *
   * <p>
   * Absorbs its own failure: a grant succeeded on the server is not undone
   * because a state indicator could not be written, and the sharee's next pass
   * writes it.
   *
   * @param ownerIdentityId the eXo user whose calendar it is
   * @param shareeIdentityId the colleague it was just shared with
   * @param serverId the declared server registration, zero for an account
   *          attached before registrations existed
   * @param anchor the owner's calendar anchor, one the sweep derives too
   */
  public void granted(long ownerIdentityId, long shareeIdentityId, long serverId, String anchor) {
    if (!isRecordableAnchor(anchor)) {
      return;
    }
    try {
      if (caldavShareObservationStorage.record(ownerIdentityId, shareeIdentityId, serverId, anchor)) {
        LOG.debug("Calendar {} of user {} is now seen by user {} on server {}", anchor, ownerIdentityId, shareeIdentityId, serverId);
      }
    } catch (RuntimeException e) {
      LOG.warn("The share of calendar {} with user {} could not be recorded; the mark waits for that colleague's next pass",
               anchor,
               shareeIdentityId,
               e);
    }
  }

  /**
   * Removes the sighting of a share the moment eXo itself revoked it
   * (EXO-90331).
   *
   * <p>
   * The half that matters most. A mark left saying "still shared" after a
   * revoke is the one answer this feature must never give, and until this
   * existed it gave it for up to a whole synchronisation period — longer in
   * practice, since nothing forces a pass to run and the owner's own
   * <i>Synchronise now</i> does not cause one for the sharee.
   *
   * <p>
   * Unlike {@link #granted} this is not conditional on the sweep being able to
   * derive the row again, and the asymmetry is deliberate: removing a sighting
   * can only take a mark away, never invent one, so the worst a removal
   * nothing supports can do is understate an exposure the Share drawer still
   * reports correctly. Writing one has the opposite failure mode, which is why
   * it is the guarded direction.
   *
   * <p>
   * Scoped to the one colleague: a calendar shared with three people and
   * revoked from one keeps the other two sightings and the count falls to two.
   *
   * <p>
   * Absorbs its own failure, and the sharee's next pass removes what this
   * could not — the revoke having succeeded on the server, the collection is
   * gone from their home and the reconciliation drops the row.
   *
   * @param shareeIdentityId the colleague the calendar is no longer shared
   *          with
   * @param serverId the declared server registration
   * @param anchor the owner's calendar anchor
   */
  public void revoked(long shareeIdentityId, long serverId, String anchor) {
    if (!isRecordableAnchor(anchor)) {
      return;
    }
    try {
      if (caldavShareObservationStorage.forget(shareeIdentityId, serverId, anchor) > 0) {
        LOG.debug("Calendar {} is no longer seen by user {} on server {}", anchor, shareeIdentityId, serverId);
      }
    } catch (RuntimeException e) {
      LOG.warn("The revoked share of calendar {} from user {} could not be forgotten;"
          + " the mark it feeds says shared until that colleague's next pass", anchor, shareeIdentityId, e);
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

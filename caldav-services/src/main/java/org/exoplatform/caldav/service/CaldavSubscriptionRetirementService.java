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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Stops the harm a calendar the sweep materialised from a mere subscription
 * does, before the server's naming was read (EXO-90275).
 *
 * <p>
 * Until then a BlueMind resource calendar or a colleague's calendar the user
 * subscribed to became the user's own personal calendar: an ACTIVE
 * {@link SyncOrigin#REMOTE} binding, an agenda calendar the user could edit,
 * its events imported, the user's own edits pushed into somebody else's
 * calendar (rig calendar 16, acceptance calendar 33). The classification now
 * keeps new ones from being made; this retires the bindings that exist.
 *
 * <p>
 * <b>Retiring is making the binding inert, and nothing else.</b> The binding
 * becomes {@link CalendarSyncStatus#RETIRED_SUBSCRIPTION}: nothing is read
 * from the collection or written to it through it any more, and a
 * reconnection does not wake it up. <b>The calendar is not deleted</b>, and
 * that is deliberate. What it holds is mostly copies of the server's objects,
 * but eXo cannot prove it holds nothing else: agenda lists a calendar's events
 * only over a date window and only when they are confirmed, so an event the
 * user made there — a date poll, one pushed and refused, one dated outside the
 * window — could not be told from a copy, and deleting the calendar would
 * delete it with them. The state is told to the user instead, among the
 * calendars needing attention; deleting the calendar drops the binding and
 * the collection is then listed read-only under "Shared with me".
 *
 * <p>
 * <b>Nothing is written to the server.</b> The one request made is a read:
 * the owner the collection's name points at is asked its display name, and a
 * binding is retired only when that principal answers one. BlueMind accepts a
 * collection created over CalDAV under any segment, {@code calendar:…}
 * included, so the name alone could point at nobody; a principal the directory
 * does not know makes BlueMind fail the request, and the binding is then left
 * as it was. A definite refusal like that is remembered for the binding until
 * the next restart, so the question is not put to the server on every sweep; a
 * server that could not be reached, or that refused the account's credentials,
 * said nothing about the owner, and is asked again on the next pass.
 *
 * <p>
 * Said once per binding, at warn: once retired, a binding is no longer one
 * this is asked about.
 */
@Component
public class CaldavSubscriptionRetirementService {

  private static final Log  LOG           = ExoLogger.getLogger(CaldavSubscriptionRetirementService.class);

  @Autowired
  private CaldavSyncStorage caldavSyncStorage;

  @Autowired
  private CalDavClient      calDavClient;

  /**
   * The bindings whose owner the server definitely did not confirm, by id —
   * not asked again in this process.
   */
  private final Set<Long>   unconfirmed   = ConcurrentHashMap.newKeySet();

  /**
   * What retiring one binding came to.
   */
  public enum Retirement {
    /** The binding is inert; the calendar and its events are kept. */
    RETIRED,
    /** Nothing was changed: not eligible, or the owner could not be confirmed. */
    KEPT
  }

  /**
   * What the server said about the owner a collection is named after.
   */
  private enum OwnerAnswer {
    /** It answered a display name: the owner exists. */
    CONFIRMED,
    /** It answered, and not with a name: nobody the directory knows. */
    DENIED,
    /** It could not be asked: nothing is known either way. */
    UNKNOWN
  }

  /**
   * Retires a binding the sweep made for a collection that is a subscription.
   *
   * @param userIdentityId identity of the user the binding belongs to
   * @param endpoint the account's endpoint, through which the owner is asked
   * @param principal the account's own principal, as the listing named it
   * @param pair the binding, which must be an ACTIVE REMOTE pair of this user
   * @param collection the listed collection the binding is bound to
   * @param ownership whose the collection is, as the classification answered
   * @return what was done
   */
  public Retirement retire(long userIdentityId,
                           CalDavEndpoint endpoint,
                           String principal,
                           CalendarSync pair,
                           CalendarCollection collection,
                           CollectionOwnership ownership) {
    if (ownership == null || !ownership.isSubscription() || pair == null || pair.getId() == null
        || pair.getUserIdentityId() != userIdentityId || pair.getOrigin() != SyncOrigin.REMOTE
        || pair.getStatus() != CalendarSyncStatus.ACTIVE || collection == null || unconfirmed.contains(pair.getId())) {
      return Retirement.KEPT;
    }
    BlueMindContainerNaming.Subscription subscription = BlueMindContainerNaming.subscriptionOf(collection.href(), principal);
    if (subscription == null) {
      return Retirement.KEPT;
    }
    String ownerPath = BlueMindContainerNaming.principalOf(principal, subscription.ownerUid());
    OwnerAnswer answer = ask(endpoint, ownerPath);
    if (answer != OwnerAnswer.CONFIRMED) {
      if (answer == OwnerAnswer.DENIED) {
        unconfirmed.add(pair.getId());
      }
      LOG.debug("The owner {} that collection {} is named after was not confirmed ({}); binding {} is not retired",
                ownerPath,
                collection.href(),
                answer,
                pair.getId());
      return Retirement.KEPT;
    }
    pair.setStatus(CalendarSyncStatus.RETIRED_SUBSCRIPTION);
    caldavSyncStorage.savePair(pair);
    LOG.warn("The eXo calendar bound to {} (binding {}) of user {} was materialised from a {} the user only subscribed to."
        + " Its binding is retired: nothing more is read from or written to that collection, and the calendar and its"
        + " events stay in eXo untouched. Once the user deletes that calendar, the collection is listed read-only under"
        + " Shared with me",
             pair.getRemoteHref(),
             pair.getId(),
             userIdentityId,
             subscription.resource() ? "resource calendar" : "calendar of another person");
    return Retirement.RETIRED;
  }

  /**
   * Whether a principal exists on the server, by the one thing the account
   * may ask of it: its display name.
   *
   * @param endpoint the account's endpoint
   * @param principalPath the principal to ask
   * @return CONFIRMED when it answered a name; DENIED when it answered none,
   *         or failed the request as BlueMind does for a uid its directory
   *         does not hold; UNKNOWN when the server could not be reached, the
   *         credentials were refused, or the failure is not the server's
   */
  private OwnerAnswer ask(CalDavEndpoint endpoint, String principalPath) {
    try {
      return StringUtils.isNotBlank(calDavClient.readDisplayName(endpoint, principalPath)) ? OwnerAnswer.CONFIRMED
                                                                                            : OwnerAnswer.DENIED;
    } catch (CalDavUnreachableException | CalDavAuthenticationException e) {
      LOG.debug("The principal {} could not be asked for its display name", principalPath, e);
      return OwnerAnswer.UNKNOWN;
    } catch (CalDavException e) {
      LOG.debug("The principal {} refused to say its display name", principalPath, e);
      return OwnerAnswer.DENIED;
    } catch (RuntimeException e) {
      LOG.debug("Asking the principal {} for its display name failed", principalPath, e);
      return OwnerAnswer.UNKNOWN;
    }
  }
}

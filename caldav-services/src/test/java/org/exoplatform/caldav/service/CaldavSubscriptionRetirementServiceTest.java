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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.LogRecorder;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.service.CaldavSubscriptionRetirementService.Retirement;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

import ch.qos.logback.classic.Level;

/**
 * Retiring a binding the sweep materialised from a subscription (EXO-90275),
 * on the rig's shape: root's pool vehicle, agenda calendar 16, binding 17.
 *
 * <p>
 * Retiring makes the binding inert and nothing else: the calendar is not
 * deleted, and the only request made to the server is a read of the owner's
 * display name. The pins say both.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavSubscriptionRetirementServiceTest {

  private static final long   ROOT              = 1L;

  private static final String ROOT_PRINCIPAL    = "/dav/principals/__uids__/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/";

  private static final String VEHICLE           = "/dav/calendars/__uids__/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/calendar:7E3AE6F3-98DF-43D9-B071-AAB477AC2CD8/";

  private static final String VEHICLE_PRINCIPAL = "/dav/principals/__uids__/7E3AE6F3-98DF-43D9-B071-AAB477AC2CD8/";

  private static final long   PAIR              = 17L;

  @Mock
  private CaldavSyncStorage   caldavSyncStorage;

  @Mock
  private CalDavClient        calDavClient;

  @Mock
  private CalDavEndpoint      endpoint;

  @InjectMocks
  private CaldavSubscriptionRetirementService service;

  /**
   * The pool vehicle's binding becomes inert once the vehicle's principal
   * answers: saved as RETIRED_SUBSCRIPTION, said once at warn — and the one
   * request made to the server is that read.
   */
  @Test
  public void aConfirmedSubscriptionsBindingIsRetiredAndTheServerIsOnlyRead() {
    when(calDavClient.readDisplayName(endpoint, VEHICLE_PRINCIPAL)).thenReturn("Véhicule de pool 1");
    CalendarSync binding = binding17();

    Retirement outcome;
    try (LogRecorder log = new LogRecorder(CaldavSubscriptionRetirementService.class)) {
      outcome = service.retire(ROOT, endpoint, ROOT_PRINCIPAL, binding, vehicle(), CollectionOwnership.SUBSCRIBED_RESOURCE);
      assertEquals(1,
                   log.events()
                      .stream()
                      .filter(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains(VEHICLE)
                          && event.getFormattedMessage().contains("resource calendar"))
                      .count());
    }

    assertEquals(Retirement.RETIRED, outcome);
    assertEquals(CalendarSyncStatus.RETIRED_SUBSCRIPTION, binding.getStatus());
    verify(caldavSyncStorage).savePair(binding);
    verifyNoMoreInteractions(caldavSyncStorage);
    verify(calDavClient).readDisplayName(endpoint, VEHICLE_PRINCIPAL);
    verifyNoMoreInteractions(calDavClient);
  }

  /**
   * An owner the server does not know — BlueMind fails the request for a uid
   * outside its directory — or one that answers no name leaves the binding as
   * it was, for the next pass: the name alone could point at nobody.
   */
  @Test
  public void anOwnerTheServerCannotConfirmLeavesTheBindingAsItWas() {
    when(calDavClient.readDisplayName(endpoint, VEHICLE_PRINCIPAL)).thenThrow(new CalDavException("500"));
    CalendarSync binding = binding17();

    assertEquals(Retirement.KEPT,
                 service.retire(ROOT, endpoint, ROOT_PRINCIPAL, binding, vehicle(), CollectionOwnership.SUBSCRIBED_RESOURCE));
    assertEquals(CalendarSyncStatus.ACTIVE, binding.getStatus());

    doReturn(" ").when(calDavClient).readDisplayName(endpoint, VEHICLE_PRINCIPAL);
    CalendarSync another = binding17();
    another.setId(18L);
    assertEquals(Retirement.KEPT,
                 service.retire(ROOT, endpoint, ROOT_PRINCIPAL, another, vehicle(), CollectionOwnership.SUBSCRIBED_RESOURCE));
    assertEquals(CalendarSyncStatus.ACTIVE, another.getStatus());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * A definite refusal is remembered for the binding: the next sweep does not
   * put the question to the server again, which on BlueMind is a failed
   * request in its log every five minutes. A server that could not be
   * reached said nothing, and is asked again.
   */
  @Test
  public void aRefusedOwnerIsNotAskedAgainButAnUnreachableServerIs() {
    when(calDavClient.readDisplayName(endpoint, VEHICLE_PRINCIPAL)).thenThrow(new CalDavException("500"));
    CalendarSync refused = binding17();

    service.retire(ROOT, endpoint, ROOT_PRINCIPAL, refused, vehicle(), CollectionOwnership.SUBSCRIBED_RESOURCE);
    service.retire(ROOT, endpoint, ROOT_PRINCIPAL, refused, vehicle(), CollectionOwnership.SUBSCRIBED_RESOURCE);

    verify(calDavClient, times(1)).readDisplayName(endpoint, VEHICLE_PRINCIPAL);

    doThrow(new CalDavUnreachableException("down", null)).when(calDavClient).readDisplayName(endpoint, VEHICLE_PRINCIPAL);
    CalendarSync unreachable = binding17();
    unreachable.setId(19L);

    service.retire(ROOT, endpoint, ROOT_PRINCIPAL, unreachable, vehicle(), CollectionOwnership.SUBSCRIBED_RESOURCE);
    service.retire(ROOT, endpoint, ROOT_PRINCIPAL, unreachable, vehicle(), CollectionOwnership.SUBSCRIBED_RESOURCE);

    verify(calDavClient, times(3)).readDisplayName(endpoint, VEHICLE_PRINCIPAL);
    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * Only an ACTIVE REMOTE binding of the user, for a subscription, is
   * retired: a share the server's owner signal revealed, a colleague's eXo
   * calendar, a paused or already retired binding — which is what makes a
   * second pass a no-op — an eXo-made one and another user's are left alone,
   * and nobody is asked anything about them.
   */
  @Test
  public void onlyAnActiveRemoteBindingOfTheUserForASubscriptionIsRetired() {
    assertEquals(Retirement.KEPT, service.retire(ROOT, endpoint, ROOT_PRINCIPAL, binding17(), vehicle(), CollectionOwnership.SHARED));
    assertEquals(Retirement.KEPT,
                 service.retire(ROOT, endpoint, ROOT_PRINCIPAL, binding17(), vehicle(), CollectionOwnership.COLLEAGUES_EXO_CALENDAR));
    for (CalendarSyncStatus state : Set.of(CalendarSyncStatus.PAUSED, CalendarSyncStatus.RETIRED_SUBSCRIPTION)) {
      CalendarSync binding = binding17();
      binding.setStatus(state);
      assertEquals(Retirement.KEPT,
                   service.retire(ROOT, endpoint, ROOT_PRINCIPAL, binding, vehicle(), CollectionOwnership.SUBSCRIBED_RESOURCE));
    }
    CalendarSync exported = binding17();
    exported.setOrigin(SyncOrigin.EXO);
    assertEquals(Retirement.KEPT,
                 service.retire(ROOT, endpoint, ROOT_PRINCIPAL, exported, vehicle(), CollectionOwnership.SUBSCRIBED_RESOURCE));
    CalendarSync anothers = binding17();
    anothers.setUserIdentityId(2L);
    assertEquals(Retirement.KEPT,
                 service.retire(ROOT, endpoint, ROOT_PRINCIPAL, anothers, vehicle(), CollectionOwnership.SUBSCRIBED_RESOURCE));

    verifyNoInteractions(caldavSyncStorage, calDavClient);
  }

  /**
   * A collection the naming does not read — not BlueMind's shape — is not
   * retired, whatever it was classified as by the caller.
   */
  @Test
  public void aCollectionTheNamingDoesNotReadIsNotRetired() {
    CalendarCollection elsewhere = new CalendarCollection("/dav/calendars/john/calendar:room-1/", "Room", null, null, null, true, Set.of("VEVENT"));

    assertEquals(Retirement.KEPT,
                 service.retire(ROOT, endpoint, "/dav/principals/john/", binding17(), elsewhere, CollectionOwnership.SUBSCRIBED_RESOURCE));
    verify(calDavClient, never()).readDisplayName(any(), anyString());
    verify(caldavSyncStorage, never()).savePair(any());
  }

  /**
   * @return binding 17 as the sweep made it
   */
  private CalendarSync binding17() {
    CalendarSync pair = new CalendarSync();
    pair.setId(PAIR);
    pair.setUserIdentityId(ROOT);
    pair.setServerId(2L);
    pair.setRemoteHref(VEHICLE);
    pair.setLocalCalendarSyncUid("anchor-16");
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * @return the pool vehicle as root's home lists it
   */
  private CalendarCollection vehicle() {
    return new CalendarCollection(VEHICLE, "Véhicule de pool 1", null, null, null, true, Set.of("VEVENT"));
  }
}

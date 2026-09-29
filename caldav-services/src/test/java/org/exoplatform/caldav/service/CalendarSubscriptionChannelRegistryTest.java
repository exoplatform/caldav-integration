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
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavSubjectMismatchException;
import org.exoplatform.caldav.client.bluemind.BlueMindCalendarOwners;
import org.exoplatform.caldav.client.bluemind.BlueMindSubjectMismatchException;
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionChannel;
import org.exoplatform.caldav.client.bluemind.BlueMindSubscriptionClient;
import org.exoplatform.caldav.plugin.CalendarSubscription;
import org.exoplatform.caldav.plugin.NoSubscriptionChannel;

/**
 * The subscription channels as contributions (EXO-90730): with none installed
 * nothing in a listing is read as a subscription and nothing can be edited;
 * with BlueMind's installed, a BlueMind account's subscriptions are read and
 * edited through it, and an account of any other shape still gets the null
 * channel.
 */
public class CalendarSubscriptionChannelRegistryTest {

  private static final String ROOT_UID       = "751E6D1A-7FDB-49B2-B668-B569E9A5A42D";

  private static final String ROOT_PRINCIPAL = "/dav/principals/__uids__/" + ROOT_UID + "/";

  private static final String ERIC_UID       = "0C3F2B9A-1D4E-4F5A-8B6C-7D8E9F0A1B2C";

  /** Eric's default calendar, as root's home lists it once root subscribed. */
  private static final String ERICS_CALENDAR = "/dav/calendars/__uids__/" + ROOT_UID + "/calendar:Default:" + ERIC_UID + "/";

  private static final String STALWART       = "/dav/pal/root@stalwart.local/";

  /**
   * The null object reads nothing and refuses every edit and listing.
   */
  @Test
  public void theNullChannelReadsNothingAndRefusesEverything() {
    NoSubscriptionChannel none = NoSubscriptionChannel.INSTANCE;
    CalDavEndpoint endpoint = mock(CalDavEndpoint.class);

    assertEquals(NoSubscriptionChannel.ID, none.id());
    assertNull(none.userUidOf(ROOT_PRINCIPAL));
    assertNull(none.subscriptionOf(ERICS_CALENDAR, ROOT_PRINCIPAL));
    assertThrows(UnsupportedOperationException.class, () -> none.asSubscriber(endpoint, ROOT_UID, edits -> null));
    assertThrows(UnsupportedOperationException.class, () -> none.ownersOf(endpoint));
  }

  /**
   * Without BlueMind's channel, a BlueMind subscription is what the DAV
   * listing says it is: no subscription, no uid, and the owner listing is
   * refused rather than asked of anybody.
   */
  @Test
  public void withNoChannelABlueMindSubscriptionIsNotReadAsOne() {
    CalendarSubscriptionChannelRegistry registry = CalendarSubscriptionChannelRegistry.of(null);

    assertTrue(registry.isEmpty());
    assertNull(registry.subscriptionOf(ERICS_CALENDAR, ROOT_PRINCIPAL));
    assertNull(registry.userUidOf(ROOT_PRINCIPAL));
    assertSame(NoSubscriptionChannel.INSTANCE, registry.channelOf(ROOT_PRINCIPAL));
    assertSame(NoSubscriptionChannel.INSTANCE, registry.primary());
    assertThrows(UnsupportedOperationException.class, () -> registry.ownersOf(mock(CalDavEndpoint.class)));
  }

  /**
   * With BlueMind's channel, a BlueMind account's subscription is read — the
   * owner's uid and principal spelled the account's way — and an account of
   * another shape still gets the null channel.
   */
  @Test
  public void theBlueMindChannelIsPickedForABlueMindAccountAndTheNullOneOtherwise() {
    CalendarSubscriptionChannelRegistry registry = CalendarSubscriptionChannelRegistry.of(List.of(new BlueMindSubscriptionChannel(null)));

    CalendarSubscription subscription = registry.subscriptionOf(ERICS_CALENDAR, ROOT_PRINCIPAL);

    assertNotNull(subscription);
    assertEquals(ERIC_UID, subscription.ownerUid());
    assertFalse(subscription.resource());
    assertEquals("/dav/principals/__uids__/" + ERIC_UID + "/", subscription.ownerPrincipal());
    assertEquals(ROOT_UID, registry.userUidOf(ROOT_PRINCIPAL));
    assertEquals(BlueMindSubscriptionChannel.ID, registry.channelOf(ROOT_PRINCIPAL).id());
    assertSame(NoSubscriptionChannel.INSTANCE, registry.channelOf(STALWART));
    assertNull(registry.subscriptionOf(STALWART + "calendar:Default:" + ERIC_UID + "/", STALWART));
  }

  /**
   * The BlueMind channel's edits and listing are the subscription client's,
   * and its subject check still reads as the host's final refusal.
   */
  @Test
  public void theBlueMindChannelEditsAndListsThroughTheSubscriptionClient() {
    BlueMindSubscriptionClient client = mock(BlueMindSubscriptionClient.class);
    CalDavEndpoint endpoint = mock(CalDavEndpoint.class);
    BlueMindCalendarOwners owners = new BlueMindCalendarOwners(ROOT_UID, Map.of("calendar:Default:" + ROOT_UID, ROOT_UID));
    when(client.ownersOf(endpoint)).thenReturn(owners);
    when(client.asSharee(eq(endpoint), eq(ROOT_UID), any())).thenReturn("done");
    BlueMindSubscriptionChannel channel = new BlueMindSubscriptionChannel(client);

    assertSame(owners, channel.ownersOf(endpoint));
    assertEquals("done", channel.asSubscriber(endpoint, ROOT_UID, edits -> "unused"));
    verify(client).asSharee(eq(endpoint), eq(ROOT_UID), any());
    assertTrue(CalDavSubjectMismatchException.class.isAssignableFrom(BlueMindSubjectMismatchException.class),
               "the host gives up on the generic mismatch, which BlueMind's must be");
  }

  /**
   * Contributions cross the Kernel/Spring bridge only as {@code @Service}
   * beans.
   */
  @Test
  public void theRegistryAndTheBlueMindChannelAreServiceBeans() {
    assertTrue(CalendarSubscriptionChannelRegistry.class.isAnnotationPresent(Service.class));
    assertTrue(BlueMindSubscriptionChannel.class.isAnnotationPresent(Service.class));
  }
}

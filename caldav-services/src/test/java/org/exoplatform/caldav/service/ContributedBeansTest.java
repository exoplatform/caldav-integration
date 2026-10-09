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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import org.exoplatform.caldav.client.CalDavObjectWriter;
import org.exoplatform.caldav.client.CalendarObjectWriter;
import org.exoplatform.caldav.client.CalendarObjectWriters;
import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.client.SharingMechanism;
import org.exoplatform.caldav.client.bluemind.BlueMindServerFlavour;
import org.exoplatform.caldav.model.CaldavServer;
import org.exoplatform.caldav.model.WriteChannel;
import org.exoplatform.caldav.plugin.CalendarServerFlavour;
import org.exoplatform.caldav.plugin.CalendarShareChannel;
import org.exoplatform.caldav.plugin.CalendarSubscriptionChannel;
import org.exoplatform.caldav.plugin.CalendarWriteChannelPlugin;
import org.exoplatform.caldav.plugin.NoSubscriptionChannel;
import org.exoplatform.caldav.plugin.PlainServerFlavour;

/**
 * The contributions of an extension point read one by one: one whose bean
 * cannot be created is left out, and every registry then answers as if its
 * add-on were not installed, instead of throwing for every caller.
 */
public class ContributedBeansTest {

  private static final DavOptions BLUEMIND            =
                                           DavOptions.of(List.of("1, access-control, calendar-access, calendar-proxy, calendarserver-sharing"),
                                                         List.of());

  private static final String     BLUEMIND_COLLECTION = "/dav/calendars/__uids__/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/exo-cal-1/";

  /** A contribution declaring the first order. */
  @Order(1)
  public static final class First implements Runnable {

    /** Does nothing: the contribution is only read. */
    @Override
    public void run() {
      // A contribution that is only listed
    }
  }

  /** A contribution declaring the second order. */
  @Order(2)
  public static final class Second implements Runnable {

    /** Does nothing: the contribution is only read. */
    @Override
    public void run() {
      // A contribution that is only listed
    }
  }

  /**
   * A contribution whose probe fails is left out, one whose order fails too,
   * and the others are kept in their declared order.
   */
  @Test
  public void aContributionThatCannotBeCreatedIsLeftOutAndTheOthersAreSorted() {
    Runnable second = new Second();
    Runnable first = new First();
    Runnable brokenProbe = mock(Runnable.class);
    Runnable brokenOrder = mock(Runnable.class, withSettings().extraInterfaces(Ordered.class));
    when(((Ordered) brokenOrder).getOrder()).thenThrow(new BeanCreationException("broken", "the proxied bean failed"));
    ContributedBeans<Runnable> beans = new ContributedBeans<>(providerOf(second, brokenProbe, null, brokenOrder, first),
                                                              "test",
                                                              bean -> {
                                                                if (bean == brokenProbe) {
                                                                  throw new BeanCreationException("broken", "the proxied bean failed");
                                                                }
                                                                return bean;
                                                              });

    assertEquals(List.of(first, second), beans.get());
    assertEquals(List.of(first, second), beans.get(), "the same answer on every read");
  }

  /**
   * No provider reads as no contribution, and a listing that fails to create
   * one bean goes on past it, never throwing.
   */
  @Test
  public void aMissingOrFailingListingReadsAsWhatCouldBeRead() {
    assertTrue(new ContributedBeans<Runnable>(null, "test", bean -> bean).get().isEmpty());
    DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
    factory.registerBeanDefinition("broken", new RootBeanDefinition(Runnable.class, () -> {
      throw new IllegalStateException("cannot create");
    }));
    factory.registerBeanDefinition("second", new RootBeanDefinition(Second.class));
    factory.registerBeanDefinition("alsoBroken", new RootBeanDefinition(Runnable.class, () -> {
      throw new IllegalStateException("cannot create either");
    }));
    factory.registerBeanDefinition("first", new RootBeanDefinition(First.class));
    List<Runnable> read = new ContributedBeans<>(factory.getBeanProvider(Runnable.class), "test", bean -> bean).get();
    assertEquals(List.of(First.class, Second.class), read.stream().map(Object::getClass).toList());
    @SuppressWarnings("unchecked")
    ObjectProvider<Runnable> unreadable = mock(ObjectProvider.class);
    when(unreadable.stream()).thenThrow(new IllegalStateException("no bean factory"));
    assertTrue(new ContributedBeans<>(unreadable, "test", bean -> bean).get().isEmpty());
  }

  /**
   * Each registry reads a broken contribution as absent: a broken flavour
   * leaves plain CalDAV and BlueMind's own, a broken share channel leaves the
   * host's selection, a broken subscription channel the null channel, a
   * broken write channel no door.
   */
  @Test
  public void everyRegistryReadsABrokenContributionAsItsAddOnAbsent() {
    CalendarServerFlavour brokenFlavour = mock(CalendarServerFlavour.class);
    when(brokenFlavour.id()).thenThrow(new BeanCreationException("flavour", "the proxied bean failed"));
    BlueMindServerFlavour blueMind = new BlueMindServerFlavour(mock(BlueMindSessionService.class));
    CaldavServer bluemindNamed = new CaldavServer();
    bluemindNamed.setName("Bluemind");
    assertSame(PlainServerFlavour.INSTANCE, new CalendarServerFlavourRegistry(providerOf(brokenFlavour)).flavourOf(bluemindNamed));
    assertSame(blueMind, new CalendarServerFlavourRegistry(providerOf(brokenFlavour, blueMind)).flavourOf(bluemindNamed));

    CalendarShareChannel brokenShare = mock(CalendarShareChannel.class);
    when(brokenShare.mechanism()).thenThrow(new BeanCreationException("share", "the proxied bean failed"));
    CalendarShareChannelRegistry shares = new CalendarShareChannelRegistry(providerOf(brokenShare));
    assertEquals(SharingMechanism.CALENDARSERVER_SHARE, shares.mechanismOf(BLUEMIND, BLUEMIND_COLLECTION));
    assertNull(shares.channelFor(SharingMechanism.BLUEMIND_SHARE));

    CalendarSubscriptionChannel brokenSubscription = mock(CalendarSubscriptionChannel.class);
    when(brokenSubscription.id()).thenThrow(new BeanCreationException("subscription", "the proxied bean failed"));
    CalendarSubscriptionChannelRegistry subscriptions = new CalendarSubscriptionChannelRegistry(providerOf(brokenSubscription));
    assertSame(NoSubscriptionChannel.INSTANCE, subscriptions.channelOf("/dav/principals/__uids__/x/"));
    assertTrue(subscriptions.isEmpty());

    CalendarWriteChannelPlugin brokenWriter = mock(CalendarWriteChannelPlugin.class);
    when(brokenWriter.channel()).thenThrow(new BeanCreationException("writer", "the proxied bean failed"));
    CalendarObjectWriters writers = new CalendarObjectWriters(mock(CaldavServerService.class),
                                                              mock(CalDavObjectWriter.class),
                                                              providerOf(brokenWriter));
    assertFalse(writers.serves(WriteChannel.BLUEMIND_IMPORT));
    assertTrue(writers.serves(WriteChannel.CALDAV));
  }

  /**
   * A write-channel contribution that cannot be created at a first read is
   * found at the next: the resolver reads the contributions on each use, as
   * the other registries do, instead of keeping an empty first reading for
   * the node's lifetime.
   */
  @Test
  public void aWriteChannelContributionBrokenAtAFirstReadIsFoundAtTheNext() {
    CalendarWriteChannelPlugin writer = mock(CalendarWriteChannelPlugin.class);
    when(writer.channel()).thenThrow(new BeanCreationException("writer", "the proxied bean failed"))
                          .thenReturn(WriteChannel.BLUEMIND_IMPORT);
    when(writer.writer()).thenReturn(mock(CalendarObjectWriter.class));
    CalendarObjectWriters writers = new CalendarObjectWriters(mock(CaldavServerService.class),
                                                              mock(CalDavObjectWriter.class),
                                                              providerOf(writer));
    assertFalse(writers.serves(WriteChannel.BLUEMIND_IMPORT));
    assertTrue(writers.serves(WriteChannel.BLUEMIND_IMPORT));
  }

  /**
   * A provider listing the given beans, in that order, as the bean factory
   * would stream them.
   *
   * @param beans the beans, nulls included
   * @param <T> the contract
   * @return the provider
   */
  @SafeVarargs
  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T... beans) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.stream()).thenAnswer(invocation -> Stream.of(beans));
    return provider;
  }
}

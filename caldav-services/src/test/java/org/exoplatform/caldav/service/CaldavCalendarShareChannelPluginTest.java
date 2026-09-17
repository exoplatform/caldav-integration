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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.exoplatform.agenda.constant.CalendarShareSource;
import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.CalendarShare;
import org.exoplatform.agenda.model.ChannelDelivery;
import org.exoplatform.agenda.model.ExternalShare;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.model.CalendarShares;
import org.exoplatform.caldav.model.CalendarShares.CalendarSharee;
import org.exoplatform.caldav.model.CalendarShares.PublishedLinkMode;
import org.exoplatform.caldav.model.CalendarShares.ShareAccess;
import org.exoplatform.caldav.model.CalendarShares.ShareUser;
import org.exoplatform.caldav.model.CalendarShares.ShareeKind;
import org.exoplatform.caldav.service.CaldavCalendarShareService.SharedCollection;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * What agenda's share service gets from this add-on's channel (EXO-90357):
 * a grant on the owner's server for a share agenda recorded, with the server
 * and the collection on the answer; "not applicable" when the server has
 * nothing to do with the calendar; a named failure agenda can word and the
 * owner can retry otherwise; the server's own grants agenda has no record
 * of; and never an exception, since agenda keeps its record whatever this
 * channel says.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CaldavCalendarShareChannelPluginTest {

  private static final long                ALICE      = 5L;

  private static final long                BOB        = 9L;

  private static final long                CAROL      = 10L;

  private static final long                CALENDAR   = 12L;

  private static final String              COLLECTION = "/dav/cal/alice%40stalwart.local/exo-cal-9f1c/";

  @Mock
  private CaldavCalendarShareService       shareService;

  @Mock
  private AgendaCalendarService            agendaCalendarService;

  @Mock
  private IdentityManager                  identityManager;

  @InjectMocks
  private CaldavCalendarShareChannelPlugin plugin;

  /**
   * Alice owns calendar 12, exported to server 1; bob and carol are users.
   *
   * @throws Exception never
   */
  @BeforeEach
  void setUp() throws Exception {
    Calendar calendar = new Calendar();
    calendar.setId(CALENDAR);
    calendar.setOwnerId(ALICE);
    when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(calendar);
    when(identityManager.getIdentity(String.valueOf(BOB))).thenReturn(user("bob"));
    when(identityManager.getIdentity(String.valueOf(CAROL))).thenReturn(user("carol"));
    when(shareService.sharedCollectionOf(ALICE, "alice", CALENDAR)).thenReturn(new SharedCollection(1L, COLLECTION));
  }

  /**
   * The ordinary delivery: the share service grants bob read access as
   * alice, and the answer names the server and the collection, which is
   * what the sharee's own listing of the server is told apart by.
   *
   * @throws Exception never
   */
  @Test
  public void aGrantTheServerTookIsDeliveredWithItsServerAndCollection() throws Exception {
    when(shareService.grant(ALICE, "alice", CALENDAR, "bob")).thenReturn(new CalendarShares(CALENDAR, List.of()));

    ChannelDelivery delivery = plugin.deliver(share(BOB), "alice");

    assertEquals(ChannelDelivery.Status.DELIVERED, delivery.getStatus());
    assertEquals("caldav:1", delivery.getChannelId());
    assertEquals(COLLECTION, delivery.getDeliveryRef());
    assertEquals("caldav", plugin.id(), "the bare id, which agenda matches by prefix");
  }

  /**
   * An owner with no account, a calendar that is not on the server and a
   * server that offers no sharing are none of this channel's business: the
   * share stays in eXo only, with nothing to retry.
   *
   * @throws Exception never
   */
  @Test
  public void aShareTheServerHasNothingToDoWithIsNotApplicable() throws Exception {
    doThrow(new CaldavShareException(CaldavCalendarShareService.NOT_CONNECTED)).when(shareService).sharedCollectionOf(ALICE, "alice", CALENDAR);
    assertEquals(ChannelDelivery.Status.NOT_APPLICABLE, plugin.deliver(share(BOB), "alice").getStatus());

    doThrow(new IllegalArgumentException(CaldavCalendarShareService.CALENDAR_NOT_ON_SERVER)).when(shareService).sharedCollectionOf(ALICE, "alice", CALENDAR);
    assertEquals(ChannelDelivery.Status.NOT_APPLICABLE, plugin.deliver(share(BOB), "alice").getStatus());

    doReturn(new SharedCollection(1L, COLLECTION)).when(shareService).sharedCollectionOf(ALICE, "alice", CALENDAR);
    doThrow(new CaldavShareException(CaldavCalendarShareService.NOT_SUPPORTED)).when(shareService).grant(ALICE, "alice", CALENDAR, "bob");
    assertEquals(ChannelDelivery.Status.NOT_APPLICABLE, plugin.deliver(share(BOB), "alice").getStatus());
  }

  /**
   * Every other refusal is a failure agenda words by its code, in the shape
   * its bundle knows: a colleague without an account on the server, a server
   * that cannot be reached, a colleague holding more than reading.
   *
   * @throws Exception never
   */
  @Test
  public void aRefusalIsAFailureNamedInAgendasShape() throws Exception {
    doThrow(new IllegalArgumentException(CaldavCalendarShareService.SHAREE_NOT_CONNECTED)).when(shareService).grant(ALICE, "alice", CALENDAR, "bob");
    ChannelDelivery delivery = plugin.deliver(share(BOB), "alice");
    assertEquals(ChannelDelivery.Status.FAILED, delivery.getStatus());
    assertEquals("SHAREE_NOT_CONNECTED", delivery.getFailureCode());

    doThrow(new CaldavShareException(CaldavCalendarShareService.SERVER_UNAVAILABLE)).when(shareService).grant(ALICE, "alice", CALENDAR, "bob");
    assertEquals("SERVER_UNREACHABLE", plugin.deliver(share(BOB), "alice").getFailureCode());

    doThrow(new IllegalArgumentException(CaldavCalendarShareService.NOT_READ_ONLY)).when(shareService).grant(ALICE, "alice", CALENDAR, "bob");
    assertEquals("NOT_READ_ONLY", plugin.deliver(share(BOB), "alice").getFailureCode());

    assertEquals("FOREIGN_ACCESS_NOT_PRESERVED", CaldavCalendarShareChannelPlugin.codeOf(CaldavCalendarShareService.FOREIGN_ACCESS_NOT_PRESERVED));
    assertEquals("DELIVERY_FAILED", CaldavCalendarShareChannelPlugin.codeOf(null));
  }

  /**
   * A colleague whose identity is gone cannot be granted anything: a failure,
   * and the server is never asked.
   */
  @Test
  public void aShareeWhoseIdentityIsGoneFails() throws Exception {
    when(identityManager.getIdentity(String.valueOf(BOB))).thenReturn(null);

    ChannelDelivery delivery = plugin.deliver(share(BOB), "alice");

    assertEquals(ChannelDelivery.Status.FAILED, delivery.getStatus());
    assertEquals("SHAREE_UNKNOWN", delivery.getFailureCode());
    verify(shareService, never()).grant(anyLong(), anyString(), anyLong(), anyString());
  }

  /**
   * Withdrawing revokes on the server; a revoke the server refuses is
   * reported as not withdrawn, and agenda deletes its record anyway.
   *
   * @throws Exception never
   */
  @Test
  public void withdrawingRevokesOnTheServer() throws Exception {
    when(shareService.revoke(ALICE, "alice", CALENDAR, "bob")).thenReturn(new CalendarShares(CALENDAR, List.of()));
    assertTrue(plugin.withdraw(share(BOB), "alice"));
    verify(shareService).revoke(ALICE, "alice", CALENDAR, "bob");

    doThrow(new CaldavShareException(CaldavCalendarShareService.SERVER_UNAVAILABLE)).when(shareService).revoke(ALICE, "alice", CALENDAR, "bob");
    assertFalse(plugin.withdraw(share(BOB), "alice"));
  }

  /**
   * The server's grants agenda has no record of: a colleague granted from
   * another client is listed by login with the collection agenda records the
   * adopted share with, removable when the server lets eXo remove it; a
   * colleague agenda already recorded is left out; someone outside eXo (with
   * the address their principal names), everyone and a published link are
   * listed as they are, never removable from here.
   *
   * @throws Exception never
   */
  @Test
  public void theServersOwnGrantsAreListedApartFromAgendasRecords() throws Exception {
    CalendarSharee bob = new CalendarSharee("/dav/pal/bob", ShareeKind.EXO_USERS, List.of(new ShareUser(BOB, "bob", "Bob Builder", null)), null, ShareAccess.READ, true);
    CalendarSharee carol = new CalendarSharee("/dav/pal/carol", ShareeKind.EXO_USERS, List.of(new ShareUser(CAROL, "carol", "Carol", null)), null, ShareAccess.MORE, false);
    CalendarSharee outside = new CalendarSharee("/dav/pal/x%40y.org", ShareeKind.OUTSIDE_EXO, List.of(), "Xavier", ShareAccess.MORE, false);
    CalendarSharee everyone = new CalendarSharee("{DAV:}all", ShareeKind.EVERYONE, List.of(), null, ShareAccess.READ, false);
    CalendarSharee link = new CalendarSharee("published-link:PRIVATE", ShareeKind.PUBLISHED_LINK, List.of(), null, ShareAccess.READ, false, PublishedLinkMode.PRIVATE);
    when(shareService.listShares(ALICE, "alice", CALENDAR)).thenReturn(new CalendarShares(CALENDAR, List.of(bob, carol, outside, everyone, link)));

    List<ExternalShare> external = plugin.listExternalShares(CALENDAR, "alice", List.of(CAROL));

    assertEquals(4, external.size());
    ExternalShare bobRow = external.get(0);
    assertEquals("caldav:1", bobRow.getChannelId());
    assertEquals("bob", bobRow.getExternalId(), "removed by login, as the share service revokes");
    assertEquals("EXO_USER", bobRow.getKind());
    assertEquals(BOB, bobRow.getShareeIdentityId());
    assertEquals("Bob Builder", bobRow.getDisplayName());
    assertTrue(bobRow.isRemovable());
    assertTrue(bobRow.isReadOnly());
    assertEquals(COLLECTION, bobRow.getDeliveryRef(), "what agenda records the adopted share with");
    assertNull(bobRow.getEmail());
    assertEquals("OUTSIDE_EXO", external.get(1).getKind());
    assertEquals("Xavier", external.get(1).getDisplayName());
    assertEquals("x@y.org", external.get(1).getEmail(), "the address the principal names, decoded");
    assertFalse(external.get(1).isReadOnly());
    assertFalse(external.get(1).isRemovable());
    assertNull(external.get(1).getDeliveryRef());
    assertEquals("EVERYONE", external.get(2).getKind());
    assertEquals("{DAV:}all", external.get(2).getDisplayName());
    assertEquals("PUBLISHED_LINK", external.get(3).getKind());
    assertEquals("Private", external.get(3).getDisplayName());
    assertEquals(0, external.get(3).getShareeIdentityId());
  }

  /**
   * A calendar whose list cannot be read — no account, not on the server,
   * server down — has no external shares, and the drawer does not fail.
   *
   * @throws Exception never
   */
  @Test
  public void aListThatCannotBeReadIsEmpty() throws Exception {
    when(shareService.listShares(ALICE, "alice", CALENDAR)).thenThrow(new CaldavShareException(CaldavCalendarShareService.SERVER_UNAVAILABLE));
    assertEquals(List.of(), plugin.listExternalShares(CALENDAR, "alice", List.of()));

    doThrow(new CaldavShareException(CaldavCalendarShareService.NOT_CONNECTED)).when(shareService).sharedCollectionOf(ALICE, "alice", CALENDAR);
    assertEquals(List.of(), plugin.listExternalShares(CALENDAR, "alice", List.of()));
  }

  /**
   * Removing an external share revokes by the login the listing gave.
   *
   * @throws Exception never
   */
  @Test
  public void removingAnExternalShareRevokesByLogin() throws Exception {
    when(shareService.revoke(ALICE, "alice", CALENDAR, "bob")).thenReturn(new CalendarShares(CALENDAR, List.of()));
    assertTrue(plugin.removeExternalShare(CALENDAR, "bob", "alice"));

    doThrow(new IllegalArgumentException(CaldavCalendarShareService.NOT_READ_ONLY)).when(shareService).revoke(ALICE, "alice", CALENDAR, "bob");
    assertFalse(plugin.removeExternalShare(CALENDAR, "bob", "alice"));
    assertFalse(plugin.removeExternalShare(CALENDAR, "", "alice"));
  }

  /**
   * Whether the calendar holds the owner's meeting copies is the share
   * service's answer, and no when it cannot be told.
   *
   * @throws Exception never
   */
  @Test
  public void meetingCopiesAreTheShareServicesAnswerOrNo() throws Exception {
    when(shareService.holdsMeetingCopies(ALICE, "alice", CALENDAR)).thenReturn(true);
    assertTrue(plugin.holdsMeetingCopies(CALENDAR, "alice"));

    doThrow(new ObjectNotFoundException("gone")).when(shareService).holdsMeetingCopies(ALICE, "alice", CALENDAR);
    assertFalse(plugin.holdsMeetingCopies(CALENDAR, "alice"));

    when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(null);
    assertFalse(plugin.holdsMeetingCopies(CALENDAR, "alice"));
  }

  /**
   * A share of a calendar that is gone, or with no owner login, is nothing
   * this channel can carry.
   */
  @Test
  public void aShareOfAGoneCalendarOrWithoutAnOwnerIsNotCarried() {
    assertEquals(ChannelDelivery.Status.NOT_APPLICABLE, plugin.deliver(share(BOB), " ").getStatus());
    assertFalse(plugin.withdraw(share(BOB), null));

    when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(null);
    ChannelDelivery delivery = plugin.deliver(share(BOB), "alice");
    assertEquals(ChannelDelivery.Status.FAILED, delivery.getStatus());
    assertEquals("CALENDAR_NOT_FOUND", delivery.getFailureCode());
  }

  /**
   * Alice's share of calendar 12 with a colleague, as agenda records it.
   *
   * @param shareeId the colleague
   * @return the record
   */
  private static CalendarShare share(long shareeId) {
    return new CalendarShare(1, CALENDAR, shareeId, ALICE, 1000, CalendarShareSource.EXO, null, null, false, null);
  }

  /**
   * An enabled user identity.
   *
   * @param username the login
   * @return the identity
   */
  private static Identity user(String username) {
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, username);
    identity.setId(username);
    identity.setEnable(true);
    return identity;
  }
}

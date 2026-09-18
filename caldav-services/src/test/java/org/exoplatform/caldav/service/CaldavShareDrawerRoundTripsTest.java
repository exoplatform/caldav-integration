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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.exoplatform.agenda.model.Calendar;
import org.exoplatform.agenda.model.ChannelShares;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.caldav.client.AccessControlEntry;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CollectionAcl;
import org.exoplatform.caldav.client.DavOptions;
import org.exoplatform.caldav.client.bluemind.BlueMindAclClient;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * What one opening of agenda's Share drawer costs the owner's calendar server
 * (EXO-90385) — the count, not the feeling.
 *
 * <h2>Why a count is the deliverable</h2>
 *
 * <p>
 * The drawer took three to six seconds to open on a calendar hosted on a
 * remote CalDAV server, and the cost was latency rather than volume: it did
 * not grow with the number of sharees, calendars or users. Measured on the
 * rig, one round trip to BlueMind costs 0.57 to 0.99 s and one to a local
 * Stalwart about 2 ms — so what the drawer costs is the <em>number</em> of
 * sequential asks, which is what this test pins.
 *
 * <h2>What is counted</h2>
 *
 * <p>
 * Every call this add-on makes that a server has to answer: the CalDAV client
 * (minus {@link CalDavClient#endpoint}, which mints an endpoint locally from
 * the registration and talks to nobody), BlueMind's REST client, and the
 * push's {@code mirrorDestination}. That last one counts as <b>one ask and
 * three round trips</b>: it walks {@code discoverPrincipal}, the principal's
 * {@code calendar-home-set} and a listing of that home, and more when the
 * account's destination is its main calendar. It is a mock here, so the test
 * counts the ask; the report counts the walk.
 *
 * <h2>The numbers</h2>
 *
 * <p>
 * Before EXO-90385, on an RFC 3744 server, one {@code GET /calendars/{id}/shares}
 * cost <b>5 asks</b>: {@code capabilities}, {@code readAcl},
 * {@code discoverPrincipal}, and {@code mirrorDestination} <em>twice</em> —
 * once for the listing's own warning flag, once more because agenda asked
 * {@code holdsMeetingCopies} as a separate question. Plus one
 * {@code readDisplayName} per sharee who is no eXo user. It is <b>3</b> now:
 * the principal comes from eXo's record of the connection on the read path,
 * and the flag travels with the shares instead of being derived again.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CaldavShareDrawerRoundTripsTest {

  private static final long    ALICE             = 5L;

  private static final long    BOB               = 9L;

  private static final long    STALWART          = 1L;

  private static final long    CALENDAR          = 12L;

  private static final String  ANCHOR            = "9f1c2d3e-4b5a-6c7d-8e9f-0a1b2c3d4e5f";

  private static final String  COLLECTION        = "/dav/cal/alice%40stalwart.local/exo-cal-" + ANCHOR + "/";

  private static final String  ALICE_PRINCIPAL   = "/dav/pal/alice@stalwart.local";

  private static final String  BOB_PRINCIPAL     = "/dav/pal/bob@stalwart.local";

  private static final String  STRANGER_PRINCIPAL = "/dav/pal/stranger@stalwart.local";

  private static final String  STALWART_DAV      = "1, 2, 3, access-control, calendar-access, addressbook";

  /** What one opening of the drawer asks the server for, after EXO-90385. */
  private static final Map<String, Integer> ONE_OPENING = Map.of("capabilities", 1, "readAcl", 1, "mirrorDestination", 1);

  @Mock
  private AgendaCalendarService             agendaCalendarService;

  @Mock
  private CaldavConnectorStorage            caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage                 caldavSyncStorage;

  @Mock
  private CalDavClient                      calDavClient;

  @Mock
  private CaldavConnectionIdentityService   caldavConnectionIdentityService;

  @Mock
  private IdentityManager                   identityManager;

  @Mock
  private CalDavEndpoint                    endpoint;

  @Mock
  private BlueMindAclClient                 blueMindAclClient;

  @Mock
  private CaldavPushService                 caldavPushService;

  @Mock
  private CaldavShareSubscriptionService    caldavShareSubscriptionService;

  @Mock
  private CaldavServerOwnerService          caldavServerOwnerService;

  @Mock
  private CaldavServerService               caldavServerService;

  private CaldavCalendarShareService        service;

  private CaldavCalendarShareChannelPlugin  plugin;

  /**
   * Alice (identity 5) owns calendar 12, exported to Stalwart (server 1) as
   * {@code exo-cal-<anchor>} and shared read-only with bob, who is connected
   * to the same server. Her principal is recorded, as it is for any account
   * whose synchronisation has run once, and the copies of her eXo meetings go
   * elsewhere.
   *
   * @throws Exception when the plugin's collaborators cannot be set
   */
  @BeforeEach
  public void rig() throws Exception {
    // The add-on's single definition of "connected" lives in CaldavServerService
    // (EXO-90358); alice's account is a username and a password, the shape this
    // rig was written against
    lenient().when(caldavServerService.isConnected(org.mockito.ArgumentMatchers.any()))
             .thenAnswer(call -> call.getArgument(0) != null);
    service = new CaldavCalendarShareService(agendaCalendarService,
                                             caldavConnectorStorage,
                                             caldavSyncStorage,
                                             calDavClient,
                                             caldavConnectionIdentityService,
                                             identityManager,
                                             blueMindAclClient,
                                             caldavPushService,
                                             caldavShareSubscriptionService,
                                             caldavServerOwnerService,
                                             caldavServerService);
    plugin = new CaldavCalendarShareChannelPlugin();
    set(plugin, "caldavCalendarShareService", service);
    set(plugin, "agendaCalendarService", agendaCalendarService);
    set(plugin, "identityManager", identityManager);
    lenient().when(agendaCalendarService.getCalendarById(CALENDAR)).thenReturn(calendar());
    lenient().when(caldavConnectorStorage.getCaldavSetting(ALICE)).thenReturn(connected());
    lenient().when(caldavSyncStorage.getPairByLocalCalendar(ALICE, STALWART, ANCHOR)).thenReturn(exoPair());
    lenient().when(calDavClient.endpoint(STALWART, "alice")).thenReturn(endpoint);
    lenient().when(calDavClient.capabilities(endpoint, COLLECTION))
             .thenReturn(DavOptions.of(List.of(STALWART_DAV), List.of("OPTIONS, PROPFIND, REPORT, ACL")));
    lenient().when(calDavClient.readAcl(endpoint, COLLECTION))
             .thenReturn(CollectionAcl.of(List.of(AccessControlEntry.readGrantTo(BOB_PRINCIPAL)), Set.of()));
    lenient().when(calDavClient.discoverPrincipal(endpoint)).thenReturn(ALICE_PRINCIPAL + "/");
    lenient().when(caldavConnectionIdentityService.principalOf(ALICE, STALWART)).thenReturn(ALICE_PRINCIPAL);
    lenient().when(caldavConnectionIdentityService.usersConnectedAs(STALWART, BOB_PRINCIPAL)).thenReturn(List.of(BOB));
    lenient().when(identityManager.getIdentity(BOB)).thenReturn(user(BOB, "bob", "Bob Test"));
    lenient().when(caldavPushService.mirrorDestination(ALICE, "alice"))
             .thenReturn(new MirrorTarget("/dav/cal/alice@stalwart.local/exo-meetings/", false, "eXo meetings"));
  }

  /**
   * <b>The count.</b> One opening of the drawer — what agenda's
   * {@code GET /calendars/{id}/shares} asks this channel, through
   * {@code AgendaCalendarShareServiceImpl#getChannelShares} — asks the server
   * three times: what the collection advertises, its access list, and where
   * the meeting copies go. Not four, and not five as it did before
   * EXO-90385: the caller's principal is no longer discovered on this path,
   * and the meeting-copies flag is no longer derived a second time.
   */
  @Test
  public void oneOpeningOfTheDrawerAsksTheServerThreeTimes() {
    ChannelShares answer = plugin.listShares(CALENDAR, "alice", List.of());

    assertEquals(ONE_OPENING, asks(), "what one opening of the Share drawer costs the server");
    assertEquals(1, answer.shares().size(), "bob's grant, which agenda has no record of");
    assertEquals(BOB, answer.shares().get(0).getShareeIdentityId());
    assertFalse(answer.meetingCopies(), "the copies go to the mirror, not to this calendar");
  }

  /**
   * <b>The target is resolved once.</b> The calendar, the account and the pair
   * binding them are read once per opening, not three times as they were when
   * the channel asked {@code sharedCollectionOf}, {@code listShares} and
   * {@code holdsMeetingCopies} in turn. These are database reads rather than
   * remote ones, but they are the same resolution and the three-way split is
   * what made the third server conversation possible.
   */
  @Test
  public void theTargetIsResolvedOncePerOpening() {
    plugin.listShares(CALENDAR, "alice", List.of());

    assertEquals(1,
                 countOf(caldavSyncStorage, "getPairByLocalCalendar"),
                 "the pair binding the calendar to its collection, resolved once");
    assertEquals(1, countOf(caldavConnectorStorage, "getCaldavSetting"), "the owner's account, read once");
  }

  /**
   * The name of a sharee no eXo user is connected as is the one call that
   * grows with the list, and it is unchanged: one {@code readDisplayName} for
   * that row and none for bob, whom eXo can name itself.
   */
  @Test
  public void onlyAShareeOutsideExoCostsARowOfItsOwn() {
    lenient().when(calDavClient.readAcl(endpoint, COLLECTION))
             .thenReturn(CollectionAcl.of(List.of(AccessControlEntry.readGrantTo(BOB_PRINCIPAL),
                                                  AccessControlEntry.readGrantTo(STRANGER_PRINCIPAL)),
                                          Set.of()));
    lenient().when(calDavClient.readDisplayName(endpoint, STRANGER_PRINCIPAL)).thenReturn("A Stranger");

    ChannelShares answer = plugin.listShares(CALENDAR, "alice", List.of());

    assertEquals(2, answer.shares().size());
    assertEquals(1, countOf(calDavClient, "readDisplayName"), "one name asked, for the sharee eXo cannot name");
    assertEquals(4, asks().values().stream().mapToInt(Integer::intValue).sum(), "the three asks plus that one name");
  }

  /**
   * A server whose access list cannot be read still owes the owner the
   * warning: the list is empty and the meeting-copies flag is asked on its
   * own, as it was before EXO-90385. A missed warning exposes the owner's
   * meetings; a false one costs a click.
   */
  @Test
  public void anUnreadableListStillAnswersTheMeetingCopiesWarning() {
    lenient().when(calDavClient.readAcl(endpoint, COLLECTION)).thenThrow(new CalDavException("down"));
    lenient().when(caldavPushService.mirrorDestination(ALICE, "alice")).thenReturn(new MirrorTarget(COLLECTION, false, "Alice"));

    ChannelShares answer = plugin.listShares(CALENDAR, "alice", List.of());

    assertTrue(answer.shares().isEmpty(), "nothing could be read, so nothing is listed");
    assertTrue(answer.meetingCopies(), "and the warning is still answered");
  }

  /**
   * The default of the SPI method — what a channel written before EXO-90385
   * gets — still makes the two calls it stands for, and answers the same
   * thing. It is what the override is measured against: the destination of the
   * meeting copies is walked twice there and once here, which is the second of
   * the two asks EXO-90385 removed (the first being the caller's principal,
   * pinned in {@code CaldavCalendarShareServiceTest}).
   */
  @Test
  public void theSpiDefaultStillMakesTheTwoCallsItStandsFor() {
    CalendarShareChannelPluginDefault fallback = new CalendarShareChannelPluginDefault(plugin);

    ChannelShares answer = fallback.listShares(CALENDAR, "alice", List.of());

    assertEquals(1, answer.shares().size());
    assertFalse(answer.meetingCopies());
    assertEquals(Map.of("capabilities", 1, "readAcl", 1, "mirrorDestination", 2),
                 asks(),
                 "where the meeting copies go, asked twice — what the override spares");
  }

  /**
   * A channel that does not override {@code listShares}: the SPI's own
   * default, delegating to this add-on's two single-purpose methods.
   *
   * @param delegate the channel whose two methods the default calls
   */
  private record CalendarShareChannelPluginDefault(CaldavCalendarShareChannelPlugin delegate)
      implements org.exoplatform.agenda.plugin.CalendarShareChannelPlugin {

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
      return delegate.id();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public org.exoplatform.agenda.model.ChannelDelivery deliver(org.exoplatform.agenda.model.CalendarShare share,
                                                                String ownerUsername) {
      return delegate.deliver(share, ownerUsername);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean withdraw(org.exoplatform.agenda.model.CalendarShare share, String ownerUsername) {
      return delegate.withdraw(share, ownerUsername);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<org.exoplatform.agenda.model.ExternalShare> listExternalShares(long calendarId,
                                                                               String ownerUsername,
                                                                               List<Long> recordedShareeIds) {
      return delegate.listExternalShares(calendarId, ownerUsername, recordedShareeIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeExternalShare(long calendarId, String externalId, String ownerUsername) {
      return delegate.removeExternalShare(calendarId, externalId, ownerUsername);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean holdsMeetingCopies(long calendarId, String ownerUsername) {
      return delegate.holdsMeetingCopies(calendarId, ownerUsername);
    }
  }

  /**
   * Every call made so far that a server has to answer, by name.
   *
   * @return the counts, in the order the calls were first made
   */
  private Map<String, Integer> asks() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Object mock : List.of(calDavClient, blueMindAclClient, caldavPushService)) {
      for (Invocation invocation : mockingDetails(mock).getInvocations()) {
        String name = invocation.getMethod().getName();
        // The endpoint is minted from the registration, locally: no server is
        // asked, and counting it would make a local resolution look remote
        if (!"endpoint".equals(name)) {
          counts.merge(name, 1, Integer::sum);
        }
      }
    }
    return counts;
  }

  /**
   * How many times one method of a mock was called.
   *
   * @param mock the mock
   * @param method the method's name
   * @return the number of calls
   */
  private static int countOf(Object mock, String method) {
    return (int) mockingDetails(mock).getInvocations()
                                     .stream()
                                     .filter(invocation -> invocation.getMethod().getName().equals(method))
                                     .count();
  }

  /**
   * Sets a field the container would inject.
   *
   * @param target the bean
   * @param name the field
   * @param value what to put in it
   * @throws Exception when there is no such field
   */
  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true); // NOSONAR a test wiring what the container injects
    field.set(target, value);
  }

  /**
   * Alice's calendar.
   *
   * @return the calendar
   */
  private static Calendar calendar() {
    Calendar calendar = new Calendar();
    calendar.setId(CALENDAR);
    calendar.setOwnerId(ALICE);
    calendar.setSyncUid(ANCHOR);
    return calendar;
  }

  /**
   * Alice's connected account.
   *
   * @return the settings
   */
  private static CaldavUserSetting connected() {
    CaldavUserSetting settings = new CaldavUserSetting();
    settings.setUsername("alice@stalwart.local");
    settings.setPassword("secret");
    settings.setServerId(STALWART);
    return settings;
  }

  /**
   * The pair binding alice's calendar to the collection eXo created for it.
   *
   * @return the pair
   */
  private static CalendarSync exoPair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(3L);
    pair.setUserIdentityId(ALICE);
    pair.setServerId(STALWART);
    pair.setLocalCalendarSyncUid(ANCHOR);
    pair.setRemoteHref(COLLECTION);
    pair.setOrigin(SyncOrigin.EXO);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * An enabled eXo user with a profile.
   *
   * @param id the identity id
   * @param login the login
   * @param fullName the full name
   * @return the identity
   */
  private static Identity user(long id, String login, String fullName) {
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, login);
    identity.setId(String.valueOf(id));
    Profile profile = new Profile(identity);
    profile.setProperty(Profile.FULL_NAME, fullName);
    identity.setProfile(profile);
    return identity;
  }
}

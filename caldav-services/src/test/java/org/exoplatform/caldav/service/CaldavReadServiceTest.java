/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarHome;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.ics.IcsReader;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.RemoteCalendar;
import org.exoplatform.caldav.model.RemoteCalendarsRead;
import org.exoplatform.caldav.model.RemoteEventsRead;
import org.exoplatform.caldav.model.RemoteIcsEvent;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The read half, and above all what it does when part of it fails.
 *
 * <p>
 * The browser degraded per calendar — one collection a server refused did not
 * blank the agenda, because every calendar was fetched in its own settled
 * promise. Moving the loop into a single server-side method makes it very easy
 * to lose that property without noticing: one uncaught exception and the user
 * sees an empty month instead of a missing calendar. Most of what follows
 * exists to pin that it did not happen.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavReadServiceTest {

  private static final long          USER   = 42L;

  private static final long          SERVER = 7L;

  private static final String        HOME   = "/dav/calendars/john/";

  /** The account's own principal, as the server names it in the discovery walk. */
  private static final String        PRINCIPAL = "/dav/principals/john/";

  /** The eXo login the credentials provider resolves the DAV account from. */
  private static final String        LOGIN  = "john";

  private static final Instant       FROM   = Instant.parse("2026-10-01T00:00:00Z");

  private static final Instant       TO     = Instant.parse("2026-11-30T00:00:00Z");

  @Mock
  private CalDavClient               calDavClient;

  @Mock
  private CaldavConnectorStorage     caldavConnectorStorage;

  @Mock
  private IcsReader                  icsReader;

  @Mock
  private CaldavSyncStorage          caldavSyncStorage;

  @Mock
  private CaldavOutboundService      caldavOutboundService;

  @Mock
  private CalDavEndpoint             endpoint;

  @Mock
  private IdentityManager            identityManager;

  @InjectMocks
  private CaldavReadService          service;

  @BeforeEach
  public void connectAnAccount() {
    // The owner is named by the real service over this test's mocks, so that
    // a listing is pinned end to end — classification, owner, wire shape —
    // rather than against a mock that answers whatever the test wrote
    // (EXO-90237). Set by hand: @InjectMocks wires mocks, and this one is not.
    ReflectionTestUtils.setField(service,
                                 "caldavCalendarOwnerService",
                                 new CaldavCalendarOwnerService(caldavOutboundService, identityManager, calDavClient));
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, "john")).thenReturn(endpoint);
    lenient().when(calDavClient.discoverHome(any())).thenReturn(new CalendarHome(PRINCIPAL, HOME));
    // Nothing bound by default: these tests are about what the shim serves,
    // not about what eXo has taken over.
    lenient().when(caldavSyncStorage.getPairs(anyLong(), anyLong())).thenReturn(List.of());
    // The classification is run for real on the mocked outbound service: it
    // reads only its arguments and the one question it asks of the deployment
    // (isMintedByThisDeployment), which the tests below stub per case. A mock
    // answering null for an enum would otherwise fail every listing.
    lenient().when(caldavOutboundService.ownershipOf(anyLong(), any(), any(), any())).thenCallRealMethod();
  }

  @Test
  public void aCalendarThatFailsCostsOnlyItsOwnEvents() {
    // The property the browser got for free from Promise.allSettled, and the
    // one a server-side loop loses by default. A user with three calendars
    // and one broken server must not see an empty agenda.
    givenCalendars(calendar("/dav/calendars/john/a/", "A"), calendar("/dav/calendars/john/b/", "B"));
    when(calDavClient.calendarQuery(any(), eq("/dav/calendars/john/a/"), any(), any()))
                                                                                                                .thenThrow(new CalDavException("refused"));
    when(calDavClient.calendarQuery(any(), eq("/dav/calendars/john/b/"), any(), any()))
                                                                                                                .thenReturn(List.of(object("BEGIN:VCALENDAR")));
    when(icsReader.read(anyString(), any(), any())).thenReturn(List.of(occurrence("kept")));

    RemoteEventsRead read = service.readEvents(USER, LOGIN, FROM, TO);

    assertEquals(1, read.events().size());
    assertEquals("kept", read.events().get(0).getUid());
    // Degrading is not the same as hiding. The calendar that refused is named,
    // so the view can own up to a partial week instead of drawing it as a
    // complete one.
    assertEquals(List.of("/dav/calendars/john/a/"), read.failedCalendars());
    // The account itself answered — it listed its collections — so a banner
    // saying the whole account is unreachable would be a lie.
    assertFalse(read.failed());
  }

  /**
   * Every calendar answered and none of them held anything: the flags stay
   * down, so a view draws a genuinely empty week without a failure banner.
   */
  @Test
  public void anAccountThatAnsweredNothingIsNotReportedAsFailed() {
    givenCalendars(calendar("/dav/calendars/john/a/", "A"));
    when(calDavClient.calendarQuery(any(), anyString(), any(), any())).thenReturn(List.of());

    RemoteEventsRead read = service.readEvents(USER, LOGIN, FROM, TO);

    assertTrue(read.events().isEmpty());
    assertFalse(read.failed());
    assertTrue(read.failedCalendars().isEmpty());
  }

  @Test
  public void oneUnreadableObjectDoesNotCostTheWholeCalendar() {
    // Some clients write objects no parser accepts. Losing one meeting beats
    // losing every meeting that happens to share its collection.
    givenCalendars(calendar("/dav/calendars/john/a/", "A"));
    when(calDavClient.calendarQuery(any(), anyString(), any(), any()))
                                                                                               .thenReturn(List.of(object("BROKEN"),
                                                                                                                   object("BEGIN:VCALENDAR")));
    when(icsReader.read(eq("BROKEN"), any(), any())).thenThrow(new IllegalStateException("unparseable"));
    when(icsReader.read(eq("BEGIN:VCALENDAR"), any(), any())).thenReturn(List.of(occurrence("kept")));

    RemoteEventsRead read = service.readEvents(USER, LOGIN, FROM, TO);

    assertEquals(1, read.events().size());
    // Deliberately not escalated to the calendar's failure flag. An object no
    // parser accepts is a data defect, not a reachability one: it does not
    // heal, so raising the flag would pin a "could not be read" banner on an
    // agenda that is otherwise correct, for ever. The WARN is where it lives.
    assertTrue(read.failedCalendars().isEmpty());
    assertFalse(read.failed());
  }

  @Test
  public void rejectedCredentialsStillLeaveTheAnswerUsable() {
    // Every calendar will fail the same way, and the cause is a stale password
    // rather than a broken calendar — but the request still answers, because
    // an exception here would blank an agenda the user can otherwise still
    // read from other connectors.
    givenCalendars(calendar("/dav/calendars/john/a/", "A"));
    when(calDavClient.calendarQuery(any(), anyString(), any(), any()))
                                                                                               .thenThrow(new CalDavAuthenticationException("refused"));

    RemoteEventsRead read = service.readEvents(USER, LOGIN, FROM, TO);

    assertTrue(read.events().isEmpty());
    // Usable, but not passed off as an empty calendar: a stale password is
    // exactly the case where the user has something to do about it.
    assertEquals(List.of("/dav/calendars/john/a/"), read.failedCalendars());
  }

  @Test
  public void oneReportPerCalendarAndNoMore() {
    // The shape this replaces was one request per object, from the page. If
    // this ever becomes one per event again, it will be here.
    givenCalendars(calendar("/dav/calendars/john/a/", "A"),
                   calendar("/dav/calendars/john/b/", "B"),
                   calendar("/dav/calendars/john/c/", "C"));
    when(calDavClient.calendarQuery(any(), anyString(), any(), any())).thenReturn(List.of());

    service.readEvents(USER, LOGIN, FROM, TO);

    verify(calDavClient, times(3)).calendarQuery(any(), anyString(), any(), any());
  }

  @Test
  public void everyOccurrenceKnowsWhichCalendarItCameFrom() {
    givenCalendars(calendar("/dav/calendars/john/a/", "A"));
    when(calDavClient.calendarQuery(any(), anyString(), any(), any()))
                                                                                               .thenReturn(List.of(object("BEGIN:VCALENDAR")));
    when(icsReader.read(anyString(), any(), any())).thenReturn(List.of(occurrence("one")));

    RemoteIcsEvent event = service.readEvents(USER, LOGIN, FROM, TO).events().get(0);

    assertEquals("/dav/calendars/john/a/", event.getCalendarId());
    assertTrue(event.getColor().startsWith("#"), "every event carries a usable colour");
  }

  @Test
  public void aServerPublishedColourIsHonoured() {
    givenCalendars(new CalendarCollection("/dav/calendars/john/a/", "A", null, null, "#D688DBFF", true));

    List<RemoteCalendar> calendars = service.listCalendars(USER, LOGIN).calendars();

    // BlueMind publishes #RRGGBBAA; the alpha is dropped, the colour is kept.
    assertEquals("#D688DB", calendars.get(0).getColor());
  }

  @Test
  public void aCalendarIsIdentifiedByItsHrefNotItsName() {
    givenCalendars(calendar("/dav/calendars/john/a/", "Work"));

    RemoteCalendar calendar = service.listCalendars(USER, LOGIN).calendars().get(0);

    // Renaming a calendar in a client must not detach what eXo associated
    // with it, and nothing stops two collections sharing a name.
    assertEquals("/dav/calendars/john/a/", calendar.getId());
    assertEquals("Work", calendar.getName());
  }

  @Test
  public void aReadOnlyCollectionIsReportedAsSuch() {
    givenCalendars(new CalendarCollection("/dav/calendars/john/shared/", "Shared", null, null, null, false));

    assertTrue(service.listCalendars(USER, LOGIN).calendars().get(0).isReadOnly());
  }

  @Test
  public void aWritableCollectionIsNot() {
    givenCalendars(calendar("/dav/calendars/john/a/", "A"));

    assertFalse(service.listCalendars(USER, LOGIN).calendars().get(0).isReadOnly());
  }

  @Test
  public void anAccountThatIsNotConnectedReadsNothing() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    assertTrue(service.readEvents(USER, LOGIN, FROM, TO).events().isEmpty());
    assertTrue(service.listCalendars(USER, LOGIN).calendars().isEmpty());
    // Empty, and not a failure. There is no account to have failed, and a user
    // who connected nothing must not be told their calendar server is down.
    assertFalse(service.readEvents(USER, LOGIN, FROM, TO).failed());
    assertFalse(service.listCalendars(USER, LOGIN).failed());
    // Never a request: an unconnected account has no credentials to send, and
    // sending none would prompt a Basic challenge on the user's own browser.
    verify(calDavClient, never()).calendarQuery(any(), anyString(), any(), any());
  }

  @Test
  public void anImpossiblePeriodIsNotAskedFor() {
    // A reversed or absent window would have the server enumerate whatever the
    // calendar chooses to return, which is not what the caller asked for.
    assertTrue(service.readEvents(USER, LOGIN, TO, FROM).events().isEmpty());
    assertTrue(service.readEvents(USER, LOGIN, null, TO).events().isEmpty());
    // A window nobody could ask about is not an account that failed.
    assertFalse(service.readEvents(USER, LOGIN, TO, FROM).failed());
    verify(calDavClient, never()).calendarQuery(any(), anyString(), any(), any());
  }

  /**
   * The pin this ticket exists for, on the events endpoint.
   *
   * <p>
   * Measured live: the calendar server was stopped, the platform logged that
   * it could not be reached, and the browser was handed a cheerful empty list
   * it drew as an empty week. The request must still answer — an exception
   * here blanks an agenda the user can otherwise read — but the answer has to
   * say that nothing was read, or the log stays the only place the failure
   * exists.
   */
  @Test
  public void aServerThatCannotBeListedAnswersEmptyAndSaysItFailed() {
    when(calDavClient.discoverHome(any())).thenThrow(new CalDavException("unreachable"));

    RemoteEventsRead read = service.readEvents(USER, LOGIN, FROM, TO);

    assertTrue(read.events().isEmpty());
    assertTrue(read.failed());
  }

  /**
   * The same pin on the calendars endpoint: an account whose listing failed
   * must not be mistaken for an account that holds no calendar.
   */
  @Test
  public void aServerThatCannotBeListedSaysSoOnTheCalendarsToo() {
    when(calDavClient.discoverHome(any())).thenThrow(new CalDavException("unreachable"));

    RemoteCalendarsRead read = service.listCalendars(USER, LOGIN);

    assertTrue(read.calendars().isEmpty());
    assertTrue(read.failed());
  }

  /**
   * An account that lists its calendars normally reports no failure, so the
   * flag above means something when it is raised.
   */
  @Test
  public void anAccountThatListsItsCalendarsReportsNoFailure() {
    givenCalendars(calendar("/dav/calendars/john/a/", "A"));

    RemoteCalendarsRead read = service.listCalendars(USER, LOGIN);

    assertEquals(1, read.calendars().size());
    assertFalse(read.failed());
  }

  @Test
  public void theMirrorIsNotAmongTheCalendarsRead() {
    // Its events are copies of events eXo already shows. Read back, each one
    // is drawn next to the original — the same meeting twice, at the same
    // hour, which reads as a broken sync rather than as a display rule.
    givenCalendars(calendar("/dav/calendars/john/personal/", "Personal"),
                   calendar("/dav/calendars/john/exo-meetings/", "eXo Meetings"));

    List<RemoteCalendar> calendars = service.listCalendars(USER, LOGIN).calendars();

    assertEquals(1, calendars.size());
    assertEquals("/dav/calendars/john/personal/", calendars.get(0).getId());
  }

  @Test
  public void theMirrorIsNotReadEither() {
    givenCalendars(calendar("/dav/calendars/john/exo-meetings/", "eXo Meetings"));

    assertTrue(service.readEvents(USER, LOGIN, FROM, TO).events().isEmpty());
    // Never even asked for: the copies are not events to display, and fetching
    // them only to drop them costs a REPORT per read.
    verify(calDavClient, never()).calendarQuery(any(), anyString(), any(), any());
  }

  @Test
  public void aCollectionExoAlreadyHoldsIsNoLongerServedHere() {
    // The shim being retired, one collection at a time. Its events live in an
    // eXo calendar now, so serving them here as well shows the user every
    // meeting twice — once under Remote and once under their own calendar.
    givenCalendars(calendar("/dav/calendars/john/work/", "Work"));
    givenBoundCollections("/dav/calendars/john/work");

    assertTrue(service.readEvents(USER, LOGIN, FROM, TO).events().isEmpty());
    assertTrue(service.listCalendars(USER, LOGIN).calendars().isEmpty());
    // Not fetched only to be dropped: a retired collection costs no REPORT.
    verify(calDavClient, never()).calendarQuery(any(), anyString(), any(), any());
  }

  @Test
  public void aCollectionWithNoBindingIsStillServed() {
    // The safety net. A materialisation that has not happened yet, or one that
    // failed, must leave the user seeing their events rather than silently
    // losing them between the two halves.
    givenCalendars(calendar("/dav/calendars/john/work/", "Work"));

    assertEquals(1, service.listCalendars(USER, LOGIN).calendars().size());
  }

  @Test
  public void aTombstonedCollectionStaysHidden() {
    // The user deleted the eXo calendar, and the dialog that asked them
    // promised eXo would "simply stop showing it". Putting the collection back
    // under Remote would break that promise in the plainest way.
    givenCalendars(calendar("/dav/calendars/john/private/", "Private"));
    givenBoundCollections("/dav/calendars/john/private");

    assertTrue(service.listCalendars(USER, LOGIN).calendars().isEmpty());
  }

  @Test
  public void aTaskListIsNotOfferedAsARemoteCalendar() {
    // A CalDAV home publishes the account's task list beside its calendars,
    // and it answers a PROPFIND exactly as a calendar would. Materialisation
    // has always refused it; this listing did not, so the Remote section
    // stayed alive on an account whose only unbound collection was a task
    // list — a section showing something that can never hold an event.
    givenCalendars(new CalendarCollection("/dav/calendars/john/tasks/",
                                          "Mes taches",
                                          null,
                                          null,
                                          null,
                                          true,
                                          java.util.Set.of("VTODO")));

    assertTrue(service.listCalendars(USER, LOGIN).calendars().isEmpty());
  }

  @Test
  public void aCollectionExoMadeAndNoLongerTracksIsNotOffered() {
    // eXo pushes a calendar out under a path it derives. If the binding is
    // then lost — a database restored or reset while the account keeps what
    // was pushed to it — the collection is still there, and the sync refuses
    // to materialise its own creations. Listing it offered the user something
    // that could never become a calendar.
    givenCalendars(calendar("/dav/cal/john/exo-cal-3b4fca2c-8563-4ff4-8a7c-26cc731aec68/", "Work"));

    assertTrue(service.listCalendars(USER, LOGIN).calendars().isEmpty());
  }

  @Test
  public void aCollectionDeclaringNoComponentSetIsStillACalendar() {
    // RFC 4791 5.2.3 makes the property optional, and its absence means every
    // component is supported. Reading silence as "no events" would empty the
    // Remote section on every server that does not publish it.
    givenCalendars(calendar("/dav/calendars/john/private/", "Private"));

    assertEquals(1, service.listCalendars(USER, LOGIN).calendars().size());
  }

  // ------------------------------------ a colleague's calendar in the home, EXO-90235

  /** A colleague's principal, as the server names it. */
  private static final String        ALICE  = "/dav/principals/alice/";

  /** The colleague's calendar, listed at her path inside the user's own home. */
  private static final String        ALICES = "/dav/calendars/alice/default/";

  /**
   * A calendar the server says belongs to somebody else is offered read-only,
   * even when the privilege set would allow a write: the same predicate the
   * sweep refuses to materialise on, so the list never offers as writable a
   * calendar the sweep will never make the user's own.
   */
  @Test
  public void aCalendarAColleagueSharedIsListedReadOnlyEvenWhenWritable() {
    givenCalendars(owned(ALICES, "Alice", ALICE, true, true));

    List<RemoteCalendar> calendars = service.listCalendars(USER, LOGIN).calendars();

    assertEquals(1, calendars.size());
    assertEquals(ALICES, calendars.get(0).getId());
    assertTrue(calendars.get(0).isReadOnly());
  }

  /**
   * The events of a shared calendar are served: the sweep never binds it, so
   * it is unbound, and an unbound collection is what this path serves. This
   * is the only way its events reach the agenda once the sweep stops
   * materialising it.
   */
  @Test
  public void theEventsOfACalendarAColleagueSharedAreServed() {
    givenCalendars(owned(ALICES, "Alice", ALICE, true, false));
    when(calDavClient.calendarQuery(any(), eq(ALICES), any(), any())).thenReturn(List.of(object("BEGIN:VCALENDAR")));
    when(icsReader.read(anyString(), any(), any())).thenReturn(List.of(occurrence("alices-event")));

    RemoteEventsRead read = service.readEvents(USER, LOGIN, FROM, TO);

    assertEquals(1, read.events().size());
    assertEquals(ALICES, read.events().get(0).getCalendarId());
    assertFalse(read.failed());
  }

  /**
   * The user's own calendar, named with them as owner and write granted, is
   * not read-only — the negative half, on the list.
   */
  @Test
  public void theUsersOwnCalendarIsNotReadOnlyWhenTheServerNamesThemAsOwner() {
    givenCalendars(owned("/dav/calendars/john/a/", "A", PRINCIPAL, true, true));

    assertFalse(service.listCalendars(USER, LOGIN).calendars().get(0).isReadOnly());
  }

  /**
   * @param href the collection path
   * @param name its display name
   * @param owner the owner the server named, or null
   * @param privilegesAnswered whether the server answered a privilege set
   * @param writable whether that set grants write
   * @return a listed calendar with those ownership facts
   */
  private CalendarCollection owned(String href, String name, String owner, boolean privilegesAnswered, boolean writable) {
    return new CalendarCollection(href, name, null, null, null, writable, java.util.Set.of("VEVENT"), owner, privilegesAnswered);
  }

  // ------------------------------------ a colleague's eXo calendar in the home, EXO-90234

  /** The anchor of the colleague's eXo calendar, CAL2 on the rig (task 90234). */
  private static final String        CAL2_ANCHOR         = "959b5529-ea4c-4ae4-a793-a2c201c3af9f";

  /** CAL2 as BlueMind lists it to the sharee after subscribing: under their own home. */
  private static final String        CAL2_UNDER_OWN_HOME = HOME + "exo-cal-" + CAL2_ANCHOR + "/";

  /** The same colleague's eXo calendar as Stalwart lists a share: at her path. */
  private static final String        CAL2_AT_ALICES_PATH = "/dav/calendars/alice/exo-cal-" + CAL2_ANCHOR + "/";

  /**
   * The defect on the list side (EXO-90234): a colleague's eXo calendar the
   * user subscribed to on BlueMind was dropped on its prefix, while its
   * events were served — a calendar the user could see the events of but
   * never the calendar. It is now listed, read-only, on this deployment's
   * word alone: the server names the user as owner and grants the full set.
   */
  @Test
  public void aColleaguesExoCalendarSubscribedOnBlueMindIsListedReadOnly() {
    givenCalendars(owned(CAL2_UNDER_OWN_HOME, "CAL2", PRINCIPAL, true, true));
    when(caldavOutboundService.isMintedByThisDeployment(SERVER, CaldavSyncStorage.canonicalHref(CAL2_UNDER_OWN_HOME))).thenReturn(true);

    List<RemoteCalendar> calendars = service.listCalendars(USER, LOGIN).calendars();

    assertEquals(1, calendars.size());
    assertEquals(CAL2_UNDER_OWN_HOME, calendars.get(0).getId());
    assertEquals("CAL2", calendars.get(0).getName());
    assertTrue(calendars.get(0).isReadOnly(), "writable by the server's word, read-only by this deployment's");
  }

  /**
   * The same calendar as Stalwart lists it — at her path, her as owner,
   * read-only: listed read-only there too, so the two servers show the same
   * thing for the same share.
   */
  @Test
  public void aColleaguesExoCalendarSharedOnStalwartIsListedReadOnly() {
    givenCalendars(owned(CAL2_AT_ALICES_PATH, "CAL2", ALICE, true, false));
    when(caldavOutboundService.isMintedByThisDeployment(SERVER, CaldavSyncStorage.canonicalHref(CAL2_AT_ALICES_PATH))).thenReturn(true);

    List<RemoteCalendar> calendars = service.listCalendars(USER, LOGIN).calendars();

    assertEquals(1, calendars.size());
    assertEquals(CAL2_AT_ALICES_PATH, calendars.get(0).getId());
    assertTrue(calendars.get(0).isReadOnly());
  }

  /**
   * The events of the colleague's eXo calendar are served, as they were
   * before — this is the one path of the three that already agreed with the
   * fix, and it must keep agreeing: the calendar is now listed, and a listed
   * calendar with no events would be the defect the other way round.
   */
  @Test
  public void theEventsOfAColleaguesExoCalendarAreServed() {
    givenCalendars(owned(CAL2_UNDER_OWN_HOME, "CAL2", PRINCIPAL, true, true));
    when(calDavClient.calendarQuery(any(), eq(CAL2_UNDER_OWN_HOME), any(), any())).thenReturn(List.of(object("BEGIN:VCALENDAR")));
    when(icsReader.read(anyString(), any(), any())).thenReturn(List.of(occurrence("CAL2BM")));

    RemoteEventsRead read = service.readEvents(USER, LOGIN, FROM, TO);

    assertEquals(1, read.events().size());
    assertEquals(CAL2_UNDER_OWN_HOME, read.events().get(0).getCalendarId());
    assertFalse(read.failed());
    // The read-through serves every unbound collection and asks nobody whose
    // it is: the classification is the list's and the sweep's, not this path's.
    verify(caldavOutboundService, never()).ownershipOf(anyLong(), any(), any(), any());
  }

  // ------------------------------------ who shared it, EXO-90237

  /**
   * CAL2 as eric sees it on BlueMind: shared — distinct from read-only — and
   * owned by root, named by identity, login and full name from the pair this
   * deployment holds. The server, which names eric as owner, is not asked
   * for a name.
   */
  @Test
  public void aColleaguesExoCalendarOnBlueMindIsSharedAndNamesTheColleague() {
    givenCalendars(owned(CAL2_UNDER_OWN_HOME, "CAL2", PRINCIPAL, true, true));
    when(caldavOutboundService.isMintedByThisDeployment(SERVER, CaldavSyncStorage.canonicalHref(CAL2_UNDER_OWN_HOME))).thenReturn(true);
    when(caldavOutboundService.exportingUserOf(SERVER, CaldavSyncStorage.canonicalHref(CAL2_UNDER_OWN_HOME))).thenReturn(1L);
    when(identityManager.getIdentity(1L)).thenReturn(user("1", "root", "Root Root"));

    RemoteCalendar cal2 = service.listCalendars(USER, LOGIN).calendars().get(0);

    assertTrue(cal2.isReadOnly());
    assertTrue(cal2.isShared());
    assertEquals(1L, cal2.getOwnerIdentityId());
    assertEquals("root", cal2.getOwnerUsername());
    assertEquals("Root Root", cal2.getOwnerDisplayName());
    verify(calDavClient, never()).readDisplayName(any(), anyString());
  }

  /**
   * The same calendar as Stalwart lists it — at her path, her as owner — is
   * still named from the pair, not from the principal: the deployment's word
   * is the more useful one, and it costs no PROPFIND.
   */
  @Test
  public void aColleaguesExoCalendarOnStalwartIsNamedFromThePairNotThePrincipal() {
    givenCalendars(owned(CAL2_AT_ALICES_PATH, "CAL2", ALICE, true, false));
    when(caldavOutboundService.isMintedByThisDeployment(SERVER, CaldavSyncStorage.canonicalHref(CAL2_AT_ALICES_PATH))).thenReturn(true);
    when(caldavOutboundService.exportingUserOf(SERVER, CaldavSyncStorage.canonicalHref(CAL2_AT_ALICES_PATH))).thenReturn(1L);
    when(identityManager.getIdentity(1L)).thenReturn(user("1", "root", "Root Root"));

    RemoteCalendar cal2 = service.listCalendars(USER, LOGIN).calendars().get(0);

    assertTrue(cal2.isShared());
    assertEquals(1L, cal2.getOwnerIdentityId());
    assertEquals("Root Root", cal2.getOwnerDisplayName());
    verify(calDavClient, never()).readDisplayName(any(), anyString());
  }

  /**
   * Alice's default as bob sees it on Stalwart: shared, named "Alice" from
   * her principal's display name, and no identity — nothing maps a DAV
   * principal to an eXo user.
   */
  @Test
  public void aCalendarAColleagueSharedOnStalwartIsSharedAndNamedByHerPrincipal() {
    givenCalendars(owned(ALICES, "Alice", ALICE, true, false));
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("Alice");

    RemoteCalendar alices = service.listCalendars(USER, LOGIN).calendars().get(0);

    assertTrue(alices.isReadOnly());
    assertTrue(alices.isShared());
    assertNull(alices.getOwnerIdentityId());
    assertNull(alices.getOwnerUsername());
    assertEquals("Alice", alices.getOwnerDisplayName());
    // The overload production calls: on a mock the interface's default-method
    // chain is not walked, so verifying the String overload would pass whatever
    // the service did.
    verify(identityManager, never()).getIdentity(anyLong());
  }

  /**
   * A read-only calendar of the user's own is not a share, and nobody is
   * named. The shape is the one where the two flags part: a server that
   * answers no privilege set — Google's — leaves the collection unwritable
   * without having said it is anybody else's (EXO-90235's silence rule), so
   * it is read-only and not shared. (A privilege set that withholds write
   * <em>is</em> the server saying the collection is somebody else's, and
   * that shape is a share by design — see
   * {@link #aCalendarAColleagueSharedIsListedReadOnlyEvenWhenWritable}.)
   */
  @Test
  public void aReadOnlyCalendarOfTheUsersOwnIsNotShared() {
    givenCalendars(new CalendarCollection("/dav/calendars/john/holidays/", "Holidays", null, null, null, false));

    RemoteCalendar own = service.listCalendars(USER, LOGIN).calendars().get(0);

    assertTrue(own.isReadOnly());
    assertFalse(own.isShared());
    assertNull(own.getOwnerIdentityId());
    assertNull(own.getOwnerUsername());
    assertNull(own.getOwnerDisplayName());
    verify(calDavClient, never()).readDisplayName(any(), anyString());
  }

  /**
   * A calendar under the user's own home that the server will not let them
   * write, naming them as owner: a share by the privilege signal alone, and
   * nobody is named for it — "shared by yourself" would be the one wrong
   * answer, and the user's own principal is never asked its name.
   */
  @Test
  public void aShareByPrivilegeAloneWhoseOwnerIsTheUserNamesNobody() {
    givenCalendars(owned("/dav/calendars/john/locked/", "Locked", PRINCIPAL, true, false));

    RemoteCalendar locked = service.listCalendars(USER, LOGIN).calendars().get(0);

    assertTrue(locked.isReadOnly());
    assertTrue(locked.isShared(), "the server withheld write: somebody else's by its word (EXO-90235)");
    assertNull(locked.getOwnerIdentityId());
    assertNull(locked.getOwnerUsername());
    assertNull(locked.getOwnerDisplayName());
    verify(calDavClient, never()).readDisplayName(any(), anyString());
  }

  /**
   * A colleague the registry no longer knows leaves the share a share, with
   * nobody named — never an error, never the viewer.
   */
  @Test
  public void aColleagueTheRegistryNoLongerKnowsLeavesTheShareUnnamed() {
    givenCalendars(owned(CAL2_UNDER_OWN_HOME, "CAL2", PRINCIPAL, true, true));
    when(caldavOutboundService.isMintedByThisDeployment(SERVER, CaldavSyncStorage.canonicalHref(CAL2_UNDER_OWN_HOME))).thenReturn(true);
    when(caldavOutboundService.exportingUserOf(SERVER, CaldavSyncStorage.canonicalHref(CAL2_UNDER_OWN_HOME))).thenReturn(1L);
    when(identityManager.getIdentity(1L)).thenReturn(null);

    RemoteCalendarsRead read = service.listCalendars(USER, LOGIN);
    RemoteCalendar cal2 = read.calendars().get(0);

    assertFalse(read.failed());
    assertTrue(cal2.isShared());
    assertNull(cal2.getOwnerIdentityId());
    assertNull(cal2.getOwnerUsername());
    assertNull(cal2.getOwnerDisplayName());
  }

  /**
   * One colleague sharing three calendars is asked her name once per
   * listing, and a second listing asks again: the memo lives and dies with
   * the request.
   */
  @Test
  public void oneColleagueSharingThreeCalendarsIsAskedHerNameOncePerListing() {
    givenCalendars(owned(ALICES, "Default", ALICE, true, false),
                   owned("/dav/calendars/alice/work/", "Work", ALICE, true, false),
                   owned("/dav/calendars/alice/home/", "Home", ALICE, true, false));
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenReturn("Alice");

    List<RemoteCalendar> calendars = service.listCalendars(USER, LOGIN).calendars();

    assertEquals(3, calendars.size());
    assertTrue(calendars.stream().allMatch(calendar -> "Alice".equals(calendar.getOwnerDisplayName())));
    verify(calDavClient, times(1)).readDisplayName(endpoint, ALICE);

    service.listCalendars(USER, LOGIN);
    verify(calDavClient, times(2)).readDisplayName(endpoint, ALICE);
  }

  /**
   * A principal that cannot be asked names the share by its path, and the
   * listing answers as if nothing had failed — an owner's name is never
   * worth the user's calendars.
   */
  @Test
  public void aPrincipalThatCannotBeAskedIsNamedByItsPathAndTheListingStillAnswers() {
    givenCalendars(owned(ALICES, "Alice", ALICE, true, false));
    when(calDavClient.readDisplayName(endpoint, ALICE)).thenThrow(new CalDavException("refused"));

    RemoteCalendarsRead read = service.listCalendars(USER, LOGIN);

    assertFalse(read.failed());
    assertEquals(1, read.calendars().size());
    assertTrue(read.calendars().get(0).isShared());
    assertEquals("alice", read.calendars().get(0).getOwnerDisplayName());
  }

  /**
   * @param id the identity id
   * @param login the eXo login
   * @param fullName the profile's full name
   * @return a user identity
   */
  private Identity user(String id, String login, String fullName) {
    Identity identity = new Identity(id);
    identity.setRemoteId(login);
    Profile profile = new Profile(identity);
    profile.setProperty(Profile.FULL_NAME, fullName);
    identity.setProfile(profile);
    return identity;
  }

  /**
   * The user's own exported calendar, met under a path none of their pairs
   * record: not listed, as before, and recognised from their own EXO pair
   * without the account-wide question.
   */
  @Test
  public void theUsersOwnExportedCalendarUnderAnotherPathIsStillNotListed() {
    CalendarSync mine = new CalendarSync();
    mine.setUserIdentityId(USER);
    mine.setServerId(SERVER);
    mine.setLocalCalendarSyncUid(CAL2_ANCHOR);
    mine.setRemoteHref(CAL2_UNDER_OWN_HOME);
    mine.setOrigin(SyncOrigin.EXO);
    when(caldavSyncStorage.getPairs(anyLong(), anyLong())).thenReturn(List.of(mine));
    givenCalendars(owned("/dav/calendars/publish/exo-cal-" + CAL2_ANCHOR + "/", "CAL2", PRINCIPAL, true, true));

    assertTrue(service.listCalendars(USER, LOGIN).calendars().isEmpty());
    verify(caldavOutboundService, never()).isMintedByThisDeployment(anyLong(), anyString());
  }

  /**
   * Another eXo deployment's calendar — its anchor known to no pair here —
   * is still not listed: the sweep adopts it on its next pass (EXO-90226),
   * and it is then excluded by its binding. The BlueMind facts are on it,
   * the same ones a colleague's share carries there, so what separates the
   * two on this list is the pair table's answer alone.
   */
  @Test
  public void anotherDeploymentsExoCalendarIsStillNotListed() {
    String href = "/dav/calendars/john/exo-cal-fd3fe75f-58f9-49e5-93d0-85f63b24a807/";
    givenCalendars(owned(href, "Perso", PRINCIPAL, true, true));
    when(caldavOutboundService.isMintedByThisDeployment(SERVER, CaldavSyncStorage.canonicalHref(href))).thenReturn(false);

    assertTrue(service.listCalendars(USER, LOGIN).calendars().isEmpty());
  }

  /**
   * The prefix with nothing after it names no calendar — eXo never mints
   * such a slug — and the list reads it exactly as the sweep does: an
   * ordinary collection, listed here and materialised there. Pinned because
   * the list used to carry its own spelling of "eXo-made" that dropped this
   * shape while the sweep kept it; one predicate
   * ({@code CaldavOutboundService.isExoCreated}) now serves both, and this
   * is the one input on which the two spellings disagreed.
   */
  @Test
  public void aBareExoPrefixNamesNoCalendarAndIsListedLikeAnyOther() {
    givenCalendars(calendar("/dav/calendars/john/exo-cal-/", "Nameless"));

    List<RemoteCalendar> calendars = service.listCalendars(USER, LOGIN).calendars();

    assertEquals(1, calendars.size());
    assertEquals("/dav/calendars/john/exo-cal-/", calendars.get(0).getId());
    verify(caldavOutboundService, never()).isMintedByThisDeployment(anyLong(), anyString());
  }

  /**
   * The dedicated mirror is excluded by its path before anything is
   * classified, a colleague's as much as the user's own.
   */
  @Test
  public void aColleaguesDedicatedMirrorIsExcludedByItsPathAndNeverClassified() {
    givenCalendars(owned("/dav/calendars/alice/exo-meetings/", "eXo Meetings", ALICE, true, false));

    assertTrue(service.listCalendars(USER, LOGIN).calendars().isEmpty());
    verify(caldavOutboundService, never()).ownershipOf(anyLong(), any(), any(), any());
  }

  @Test
  public void oneBoundCollectionDoesNotHideTheOthers() {
    givenCalendars(calendar("/dav/calendars/john/work/", "Work"),
                   calendar("/dav/calendars/john/family/", "Family"));
    givenBoundCollections("/dav/calendars/john/work");

    assertEquals(1, service.listCalendars(USER, LOGIN).calendars().size());
    assertEquals("Family", service.listCalendars(USER, LOGIN).calendars().get(0).getName());
  }

  // ------------------------------------ a share the user hid, EXO-90239

  /**
   * A hidden share is a pair, and a pair of any state drops its collection
   * from the list and keeps its events unserved — the mechanism hiding rests
   * on. Pinned on the status itself, not through the generic helper: a
   * filter that started reading the status would pass every older test and
   * put the hidden calendar straight back on the user's screen.
   */
  @Test
  public void aHiddenShareLeavesTheListAndItsEventsAreNotServed() {
    givenCalendars(owned(ALICES, "Alice", ALICE, true, false), calendar("/dav/calendars/john/work/", "Work"));
    givenHiddenShare(ALICES);

    List<RemoteCalendar> calendars = service.listCalendars(USER, LOGIN).calendars();
    RemoteEventsRead read = service.readEvents(USER, LOGIN, FROM, TO);

    assertEquals(1, calendars.size());
    assertEquals("/dav/calendars/john/work/", calendars.get(0).getId());
    assertFalse(read.failed());
    verify(calDavClient, never()).calendarQuery(any(), eq(ALICES), any(), any());
    verify(calDavClient).calendarQuery(any(), eq("/dav/calendars/john/work/"), any(), any());
  }

  /**
   * The hidden-calendars listing asks about bound collections, which the
   * calendar list can never describe: a hidden share is named, classified a
   * share and given its owner exactly as the list would have, while the
   * list itself no longer holds it.
   */
  @Test
  public void aBoundShareIsDescribedAsTheListWouldHaveListedIt() {
    givenCalendars(owned(ALICES, "Alice", ALICE, true, false), calendar("/dav/calendars/john/work/", "Work"));
    givenHiddenShare(ALICES);
    when(calDavClient.readDisplayName(any(), eq(ALICE))).thenReturn("Alice Martin");

    RemoteCalendarsRead described = service.describeCollections(USER, LOGIN, Set.of(CaldavSyncStorage.canonicalHref(ALICES)));

    assertTrue(service.listCalendars(USER, LOGIN).calendars().stream().noneMatch(c -> ALICES.equals(c.getId())),
               "the list drops it");
    assertFalse(described.failed());
    assertEquals(1, described.calendars().size());
    RemoteCalendar alices = described.calendars().get(0);
    assertEquals(ALICES, alices.getId());
    assertEquals("Alice", alices.getName());
    assertTrue(alices.isShared());
    assertTrue(alices.isReadOnly());
    assertEquals("Alice Martin", alices.getOwnerDisplayName());
  }

  /**
   * A colleague's eXo calendar hidden on BlueMind is named from the pair this
   * deployment holds, as the list names it — identity, login, full name.
   */
  @Test
  public void aBoundColleaguesExoCalendarIsDescribedWithTheColleague() {
    givenCalendars(owned(CAL2_UNDER_OWN_HOME, "CAL2", PRINCIPAL, true, true));
    givenHiddenShare(CAL2_UNDER_OWN_HOME);
    when(caldavOutboundService.isMintedByThisDeployment(SERVER, CaldavSyncStorage.canonicalHref(CAL2_UNDER_OWN_HOME))).thenReturn(true);
    when(caldavOutboundService.exportingUserOf(SERVER, CaldavSyncStorage.canonicalHref(CAL2_UNDER_OWN_HOME))).thenReturn(1L);
    when(identityManager.getIdentity(1L)).thenReturn(user("1", "root", "Root Root"));

    RemoteCalendarsRead described = service.describeCollections(USER,
                                                                LOGIN,
                                                                Set.of(CaldavSyncStorage.canonicalHref(CAL2_UNDER_OWN_HOME)));

    assertEquals(1, described.calendars().size());
    assertTrue(described.calendars().get(0).isShared());
    assertEquals("Root Root", described.calendars().get(0).getOwnerDisplayName());
    assertEquals(1L, described.calendars().get(0).getOwnerIdentityId());
  }

  /**
   * Only what was asked about, and only what the server still lists: a path
   * the account no longer holds is simply absent from an unfailed answer,
   * and the other collections of the account are not described uninvited.
   */
  @Test
  public void describingNamesOnlyWhatWasAskedAndTheServerStillLists() {
    givenCalendars(calendar("/dav/calendars/john/work/", "Work"), calendar("/dav/calendars/john/private/", "Private"));

    RemoteCalendarsRead described = service.describeCollections(USER,
                                                                LOGIN,
                                                                Set.of("/dav/calendars/john/private", ALICES));

    assertFalse(described.failed());
    assertEquals(1, described.calendars().size());
    assertEquals("/dav/calendars/john/private/", described.calendars().get(0).getId());
  }

  /**
   * An account that cannot be listed says so, rather than answering an empty
   * list a caller would read as "every hidden calendar is gone".
   */
  @Test
  public void describingSaysWhenTheAccountCouldNotBeListed() {
    when(calDavClient.discoverHome(any())).thenThrow(new CalDavException("down"));

    RemoteCalendarsRead described = service.describeCollections(USER, LOGIN, Set.of(ALICES));

    assertTrue(described.failed());
    assertTrue(described.calendars().isEmpty());
  }

  /**
   * Nothing asked, nothing read: the common case of a user with nothing
   * hidden must not cost a round trip.
   */
  @Test
  public void describingNothingAsksTheServerNothing() {
    assertTrue(service.describeCollections(USER, LOGIN, Set.of()).calendars().isEmpty());

    verify(calDavClient, never()).discoverHome(any());
  }

  /**
   * @param href the collection the user hid
   */
  private void givenHiddenShare(String href) {
    CalendarSync pair = new CalendarSync();
    pair.setId(12L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(CaldavSyncStorage.canonicalHref(href));
    pair.setOrigin(SyncOrigin.REMOTE);
    pair.setStatus(CalendarSyncStatus.HIDDEN_SHARE);
    when(caldavSyncStorage.getPairs(anyLong(), anyLong())).thenReturn(List.of(pair));
  }

  /**
   * @param hrefs the collections eXo already accounts for
   */
  private void givenBoundCollections(String... hrefs) {
    List<CalendarSync> pairs = new java.util.ArrayList<>();
    for (String href : hrefs) {
      CalendarSync pair = new CalendarSync();
      pair.setUserIdentityId(USER);
      pair.setServerId(SERVER);
      pair.setRemoteHref(href);
      pairs.add(pair);
    }
    when(caldavSyncStorage.getPairs(anyLong(), anyLong())).thenReturn(pairs);
  }

  @Test
  public void aMirrorRecordedUnderAnotherNameIsStillExcluded() {
    // An adopted mirror is an ordinary calendar the user already had, so its
    // path carries no slug to recognise. The stored href is what identifies
    // it — compared canonically, because the one saved while the browser spoke
    // through the relay is rooted at /caldav/rest/dav/{id} and would never
    // compare equal to the collection's own path.
    CaldavUserSetting adopted = settings();
    adopted.setMirrorCalendarHref("/caldav/rest/dav/7/dav/calendars/john/personal/");
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(adopted);
    givenCalendars(calendar("/dav/calendars/john/personal/", "Personal"),
                   calendar("/dav/calendars/john/work/", "Work"));

    List<RemoteCalendar> calendars = service.listCalendars(USER, LOGIN).calendars();

    assertEquals(1, calendars.size());
    assertEquals("/dav/calendars/john/work/", calendars.get(0).getId());
  }

  /**
   * The account's calendars, as the server lists them.
   *
   * @param collections what the listing answers
   */
  private void givenCalendars(CalendarCollection... collections) {
    when(calDavClient.listCalendars(any(), eq(HOME))).thenReturn(List.of(collections));
  }

  /**
   * A writable calendar publishing no colour.
   *
   * @param href the collection href
   * @param name its display name
   * @return the collection
   */
  private CalendarCollection calendar(String href, String name) {
    return new CalendarCollection(href, name, null, null, null, true);
  }

  /**
   * A calendar object as a server returns it.
   *
   * @param data its iCalendar body
   * @return the object
   */
  private CalendarObject object(String data) {
    return new CalendarObject("/dav/calendars/john/a/one.ics", "\"etag\"", data);
  }

  /**
   * One occurrence as the read engine produces it.
   *
   * @param uid its iCalendar UID
   * @return the occurrence
   */
  private RemoteIcsEvent occurrence(String uid) {
    RemoteIcsEvent event = new RemoteIcsEvent();
    event.setUid(uid);
    return event;
  }

  /**
   * @return a connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername("john");
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    return setting;
  }
}

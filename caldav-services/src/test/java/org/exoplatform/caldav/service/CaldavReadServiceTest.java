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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.ics.IcsReader;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.RemoteCalendar;
import org.exoplatform.caldav.model.RemoteIcsEvent;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

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
  private CalDavEndpoint             endpoint;

  @InjectMocks
  private CaldavReadService          service;

  @BeforeEach
  public void connectAnAccount() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, "john")).thenReturn(endpoint);
    lenient().when(calDavClient.discoverCalendarHome(any(), anyString(), anyString())).thenReturn(HOME);
    // Nothing bound by default: these tests are about what the shim serves,
    // not about what eXo has taken over.
    lenient().when(caldavSyncStorage.getPairs(anyLong(), anyLong())).thenReturn(List.of());
  }

  @Test
  public void aCalendarThatFailsCostsOnlyItsOwnEvents() {
    // The property the browser got for free from Promise.allSettled, and the
    // one a server-side loop loses by default. A user with three calendars
    // and one broken server must not see an empty agenda.
    givenCalendars(calendar("/dav/calendars/john/a/", "A"), calendar("/dav/calendars/john/b/", "B"));
    when(calDavClient.calendarQuery(any(), eq("/dav/calendars/john/a/"), any(), any(), anyString(), anyString()))
                                                                                                                .thenThrow(new CalDavException("refused"));
    when(calDavClient.calendarQuery(any(), eq("/dav/calendars/john/b/"), any(), any(), anyString(), anyString()))
                                                                                                                .thenReturn(List.of(object("BEGIN:VCALENDAR")));
    when(icsReader.read(anyString(), any(), any())).thenReturn(List.of(occurrence("kept")));

    List<RemoteIcsEvent> events = service.readEvents(USER, FROM, TO);

    assertEquals(1, events.size());
    assertEquals("kept", events.get(0).getUid());
  }

  @Test
  public void oneUnreadableObjectDoesNotCostTheWholeCalendar() {
    // Some clients write objects no parser accepts. Losing one meeting beats
    // losing every meeting that happens to share its collection.
    givenCalendars(calendar("/dav/calendars/john/a/", "A"));
    when(calDavClient.calendarQuery(any(), anyString(), any(), any(), anyString(), anyString()))
                                                                                               .thenReturn(List.of(object("BROKEN"),
                                                                                                                   object("BEGIN:VCALENDAR")));
    when(icsReader.read(eq("BROKEN"), any(), any())).thenThrow(new IllegalStateException("unparseable"));
    when(icsReader.read(eq("BEGIN:VCALENDAR"), any(), any())).thenReturn(List.of(occurrence("kept")));

    assertEquals(1, service.readEvents(USER, FROM, TO).size());
  }

  @Test
  public void rejectedCredentialsStillLeaveTheAnswerUsable() {
    // Every calendar will fail the same way, and the cause is a stale password
    // rather than a broken calendar — but the request still answers, because
    // an exception here would blank an agenda the user can otherwise still
    // read from other connectors.
    givenCalendars(calendar("/dav/calendars/john/a/", "A"));
    when(calDavClient.calendarQuery(any(), anyString(), any(), any(), anyString(), anyString()))
                                                                                               .thenThrow(new CalDavAuthenticationException("refused"));

    assertTrue(service.readEvents(USER, FROM, TO).isEmpty());
  }

  @Test
  public void oneReportPerCalendarAndNoMore() {
    // The shape this replaces was one request per object, from the page. If
    // this ever becomes one per event again, it will be here.
    givenCalendars(calendar("/dav/calendars/john/a/", "A"),
                   calendar("/dav/calendars/john/b/", "B"),
                   calendar("/dav/calendars/john/c/", "C"));
    when(calDavClient.calendarQuery(any(), anyString(), any(), any(), anyString(), anyString())).thenReturn(List.of());

    service.readEvents(USER, FROM, TO);

    verify(calDavClient, times(3)).calendarQuery(any(), anyString(), any(), any(), anyString(), anyString());
  }

  @Test
  public void everyOccurrenceKnowsWhichCalendarItCameFrom() {
    givenCalendars(calendar("/dav/calendars/john/a/", "A"));
    when(calDavClient.calendarQuery(any(), anyString(), any(), any(), anyString(), anyString()))
                                                                                               .thenReturn(List.of(object("BEGIN:VCALENDAR")));
    when(icsReader.read(anyString(), any(), any())).thenReturn(List.of(occurrence("one")));

    RemoteIcsEvent event = service.readEvents(USER, FROM, TO).get(0);

    assertEquals("/dav/calendars/john/a/", event.getCalendarId());
    assertTrue(event.getColor().startsWith("#"), "every event carries a usable colour");
  }

  @Test
  public void aServerPublishedColourIsHonoured() {
    givenCalendars(new CalendarCollection("/dav/calendars/john/a/", "A", null, null, "#D688DBFF", true));

    List<RemoteCalendar> calendars = service.listCalendars(USER);

    // BlueMind publishes #RRGGBBAA; the alpha is dropped, the colour is kept.
    assertEquals("#D688DB", calendars.get(0).getColor());
  }

  @Test
  public void aCalendarIsIdentifiedByItsHrefNotItsName() {
    givenCalendars(calendar("/dav/calendars/john/a/", "Work"));

    RemoteCalendar calendar = service.listCalendars(USER).get(0);

    // Renaming a calendar in a client must not detach what eXo associated
    // with it, and nothing stops two collections sharing a name.
    assertEquals("/dav/calendars/john/a/", calendar.getId());
    assertEquals("Work", calendar.getName());
  }

  @Test
  public void aReadOnlyCollectionIsReportedAsSuch() {
    givenCalendars(new CalendarCollection("/dav/calendars/john/shared/", "Shared", null, null, null, false));

    assertTrue(service.listCalendars(USER).get(0).isReadOnly());
  }

  @Test
  public void aWritableCollectionIsNot() {
    givenCalendars(calendar("/dav/calendars/john/a/", "A"));

    assertFalse(service.listCalendars(USER).get(0).isReadOnly());
  }

  @Test
  public void anAccountThatIsNotConnectedReadsNothing() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    assertTrue(service.readEvents(USER, FROM, TO).isEmpty());
    assertTrue(service.listCalendars(USER).isEmpty());
    // Never a request: an unconnected account has no credentials to send, and
    // sending none would prompt a Basic challenge on the user's own browser.
    verify(calDavClient, never()).calendarQuery(any(), anyString(), any(), any(), anyString(), anyString());
  }

  @Test
  public void anImpossiblePeriodIsNotAskedFor() {
    // A reversed or absent window would have the server enumerate whatever the
    // calendar chooses to return, which is not what the caller asked for.
    assertTrue(service.readEvents(USER, TO, FROM).isEmpty());
    assertTrue(service.readEvents(USER, null, TO).isEmpty());
    verify(calDavClient, never()).calendarQuery(any(), anyString(), any(), any(), anyString(), anyString());
  }

  @Test
  public void aServerThatCannotBeListedAnswersEmptyRatherThanFailing() {
    when(calDavClient.discoverCalendarHome(any(), anyString(), anyString())).thenThrow(new CalDavException("unreachable"));

    assertTrue(service.readEvents(USER, FROM, TO).isEmpty());
  }

  @Test
  public void theMirrorIsNotAmongTheCalendarsRead() {
    // Its events are copies of events eXo already shows. Read back, each one
    // is drawn next to the original — the same meeting twice, at the same
    // hour, which reads as a broken sync rather than as a display rule.
    givenCalendars(calendar("/dav/calendars/john/personal/", "Personal"),
                   calendar("/dav/calendars/john/exo-meetings/", "eXo Meetings"));

    List<RemoteCalendar> calendars = service.listCalendars(USER);

    assertEquals(1, calendars.size());
    assertEquals("/dav/calendars/john/personal/", calendars.get(0).getId());
  }

  @Test
  public void theMirrorIsNotReadEither() {
    givenCalendars(calendar("/dav/calendars/john/exo-meetings/", "eXo Meetings"));

    assertTrue(service.readEvents(USER, FROM, TO).isEmpty());
    // Never even asked for: the copies are not events to display, and fetching
    // them only to drop them costs a REPORT per read.
    verify(calDavClient, never()).calendarQuery(any(), anyString(), any(), any(), anyString(), anyString());
  }

  @Test
  public void aCollectionExoAlreadyHoldsIsNoLongerServedHere() {
    // The shim being retired, one collection at a time. Its events live in an
    // eXo calendar now, so serving them here as well shows the user every
    // meeting twice — once under Remote and once under their own calendar.
    givenCalendars(calendar("/dav/calendars/john/work/", "Work"));
    givenBoundCollections("/dav/calendars/john/work");

    assertTrue(service.readEvents(USER, FROM, TO).isEmpty());
    assertTrue(service.listCalendars(USER).isEmpty());
    // Not fetched only to be dropped: a retired collection costs no REPORT.
    verify(calDavClient, never()).calendarQuery(any(), anyString(), any(), any(), anyString(), anyString());
  }

  @Test
  public void aCollectionWithNoBindingIsStillServed() {
    // The safety net. A materialisation that has not happened yet, or one that
    // failed, must leave the user seeing their events rather than silently
    // losing them between the two halves.
    givenCalendars(calendar("/dav/calendars/john/work/", "Work"));

    assertEquals(1, service.listCalendars(USER).size());
  }

  @Test
  public void aTombstonedCollectionStaysHidden() {
    // The user deleted the eXo calendar, and the dialog that asked them
    // promised eXo would "simply stop showing it". Putting the collection back
    // under Remote would break that promise in the plainest way.
    givenCalendars(calendar("/dav/calendars/john/private/", "Private"));
    givenBoundCollections("/dav/calendars/john/private");

    assertTrue(service.listCalendars(USER).isEmpty());
  }

  @Test
  public void oneBoundCollectionDoesNotHideTheOthers() {
    givenCalendars(calendar("/dav/calendars/john/work/", "Work"),
                   calendar("/dav/calendars/john/family/", "Family"));
    givenBoundCollections("/dav/calendars/john/work");

    assertEquals(1, service.listCalendars(USER).size());
    assertEquals("Family", service.listCalendars(USER).get(0).getName());
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

    List<RemoteCalendar> calendars = service.listCalendars(USER);

    assertEquals(1, calendars.size());
    assertEquals("/dav/calendars/john/work/", calendars.get(0).getId());
  }

  /**
   * The account's calendars, as the server lists them.
   *
   * @param collections what the listing answers
   */
  private void givenCalendars(CalendarCollection... collections) {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(collections));
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

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.ics.IcsMerger;
import org.exoplatform.caldav.ics.IcsWriter;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * What moving the push server-side is actually for.
 *
 * <p>
 * The load-bearing test here is {@link #aCreatedMirrorIsProvenByReadingBack()}
 * — that a mirror calendar reported as created really exists. Nobody could
 * write that assertion while the work happened in a page, and its absence is
 * how a server answering 201 while creating nothing went undiagnosed for
 * three rounds. The rest pins the failure codes the browser still renders, and
 * the conditional-write discipline that turns a concurrent edit into something
 * the caller can act on instead of a silent overwrite.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavPushServiceTest {

  private static final long          USER   = 42L;

  private static final long          SERVER = 7L;

  private static final String        HOME   = "/dav/calendars/john/";

  private static final String        MIRROR = "/dav/calendars/john/exo-meetings/";

  /** The same collection as the storage records it: no trailing slash. */
  private static final String        CANONICAL_MIRROR = "/dav/calendars/john/exo-meetings";

  @Mock
  private CalDavClient               calDavClient;

  @Mock
  private CaldavConnectorStorage     caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage          caldavSyncStorage;

  @Mock
  private IcsWriter                  icsWriter;

  @Mock
  private IcsMerger                  icsMerger;

  @Mock
  private CalDavEndpoint             endpoint;

  @InjectMocks
  private CaldavPushService          service;

  @BeforeEach
  public void connectAnAccount() {
    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(calDavClient.endpoint(SERVER, "john")).thenReturn(endpoint);
    lenient().when(calDavClient.discoverCalendarHome(any(), anyString(), anyString())).thenReturn(HOME);
    lenient().when(icsWriter.write(any())).thenReturn("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");
  }

  @Test
  public void anExistingMirrorIsReusedRatherThanRecreated() {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar(MIRROR,
                                                                                                            "eXo Meetings")));

    MirrorTarget target = service.ensureMirror(USER);

    assertEquals(MIRROR, target.href());
    assertFalse(target.adopted());
    // Asking twice for the same calendar must ask for the same collection: a
    // second MKCALENDAR is how a reconnecting user collected a new calendar
    // on the server every time.
    verify(calDavClient, never()).mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  public void aCreatedMirrorIsProvenByReadingBack() {
    // The assertion this whole PR exists to make possible.
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(),
                                                                                          List.of(calendar(MIRROR,
                                                                                                           "eXo Meetings")));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    MirrorTarget target = service.ensureMirror(USER);

    assertEquals(MIRROR, target.href());
    assertFalse(target.adopted());
    verify(caldavConnectorStorage).saveMirrorCalendarHref(MIRROR, USER);
  }

  @Test
  public void aTwoZeroOneThatCreatedNothingIsNotSuccess() {
    // BlueMind answers 201 while creating nothing when a request omits the
    // supported component set. Believing the status cost three rounds of
    // wrong diagnosis; the read-back is what decides.
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString()))
                                                                              .thenReturn(List.of(calendar("/dav/calendars/john/personal/",
                                                                                                           "Personal")));
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(201,
                                                                                                                                   List.of()));

    MirrorTarget target = service.ensureMirror(USER);

    // Not reported as created — adopted instead, and said so.
    assertTrue(target.adopted());
    assertEquals("/dav/calendars/john/personal/", target.href());
    assertEquals("Personal", target.name());
  }

  @Test
  public void anAccountWithNoCalendarAtAllIsARefusal() {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of());
    when(calDavClient.mkCalendar(any(), anyString(), anyString(), any(), anyString(), anyString()))
                                                                                                  .thenReturn(new MkCalendarResult(403,
                                                                                                                                   List.of()));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.ensureMirror(USER));

    assertEquals(CaldavPushService.CREATION_REFUSED, failure.getCode());
  }

  @Test
  public void aFirstPushIsConditionalOnTheObjectNotExisting() {
    givenAMirror();
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(201,
                                                                                                                    "\"etag-1\"",
                                                                                                                    null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync mapping = service.pushEvent(USER, event("evt-1"));

    ArgumentCaptor<String> href = ArgumentCaptor.forClass(String.class);
    verify(calDavClient).putObject(any(), href.capture(), anyString(), anyString(), anyString());
    // The filename convention the browser push has always used, so objects
    // written before the migration are found rather than duplicated.
    assertEquals(MIRROR + "evt-1.ics", href.getValue());
    assertEquals("\"etag-1\"", mapping.getEtag());
    assertNotNull(mapping.getPushedHash());
    verify(calDavClient, never()).updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  public void aLaterPushMergesIntoWhatTheServerHolds() {
    givenAMirror();
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-1"))).thenReturn(mapped("\"etag-1\""));
    when(calDavClient.fetchObject(any(), anyString(), anyString(), anyString()))
                                                                                .thenReturn(new CalendarObject(MIRROR + "evt-1.ics",
                                                                                                               "\"etag-1\"",
                                                                                                               "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"));
    when(icsMerger.merge(anyString(), anyString(), eq(false))).thenReturn("MERGED");
    when(calDavClient.updateObject(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                                                                                                           .thenReturn(new PutResult(204,
                                                                                                                                     "\"etag-2\"",
                                                                                                                                     null));
    when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync mapping = service.pushEvent(USER, event("evt-1"));

    // Merged, never replaced: another client's overrides live in the same
    // object, and a wholesale replacement would destroy them.
    verify(calDavClient).updateObject(any(), anyString(), eq("MERGED"), eq("\"etag-1\""), anyString(), anyString());
    assertEquals("\"etag-2\"", mapping.getEtag());
  }

  @Test
  public void aConcurrentEditSurfacesAsAConflict() {
    givenAMirror();
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenReturn(new PutResult(412,
                                                                                                                    null,
                                                                                                                    null));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.pushEvent(USER, event("evt-1")));

    assertEquals(CaldavPushService.CONFLICT, failure.getCode());
    // Never retried blindly — deciding what to do about a concurrent edit is
    // the caller's, and that is the whole point of the conditional write.
    verify(caldavSyncStorage, never()).saveObject(any());
  }

  @Test
  public void rejectedCredentialsKeepTheirOwnCode() {
    givenAMirror();
    when(calDavClient.putObject(any(), anyString(), anyString(), anyString(), anyString()))
                                                                                          .thenThrow(new CalDavAuthenticationException("refused"));

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.pushEvent(USER, event("evt-1")));

    assertEquals(CaldavPushService.CREDENTIALS, failure.getCode());
  }

  @Test
  public void anAccountThatIsNotConnectedCannotBePushedTo() {
    when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(null);

    CaldavPushException failure = assertThrows(CaldavPushException.class, () -> service.pushEvent(USER, event("evt-1")));

    assertEquals(CaldavPushService.NOT_CONNECTED, failure.getCode());
  }

  @Test
  public void deletingSomethingThatIsAlreadyGoneSucceeds() {
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
    when(caldavSyncStorage.getObjectByUid(anyLong(), eq("evt-gone"))).thenReturn(null);

    service.deleteEvent(USER, "evt-gone");

    // The end state the caller asked for is the end state that holds.
    verify(calDavClient, never()).deleteObject(any(), anyString(), any(), anyString(), anyString());
  }

  /**
   * A mirror pair and collection already in place.
   */
  private void givenAMirror() {
    when(calDavClient.listCalendars(any(), eq(HOME), anyString(), anyString())).thenReturn(List.of(calendar(MIRROR,
                                                                                                            "eXo Meetings")));
    when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(pair()));
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

  /**
   * @param href the collection href
   * @param name its display name
   * @return a listed calendar
   */
  private CalendarCollection calendar(String href, String name) {
    return new CalendarCollection(href, name, null, null, null, false);
  }

  /**
   * @return the mirror pair, already bound
   */
  private CalendarSync pair() {
    CalendarSync pair = new CalendarSync();
    pair.setId(1L);
    pair.setUserIdentityId(USER);
    pair.setServerId(SERVER);
    pair.setRemoteHref(CANONICAL_MIRROR);
    pair.setOrigin(SyncOrigin.MIRROR);
    pair.setStatus(CalendarSyncStatus.ACTIVE);
    return pair;
  }

  /**
   * @param etag the tag last seen
   * @return an object already pushed once
   */
  private ObjectSync mapped(String etag) {
    ObjectSync mapping = new ObjectSync();
    mapping.setId(5L);
    mapping.setCalendarSyncId(1L);
    mapping.setIcsUid("evt-1");
    mapping.setRemoteHref(MIRROR + "evt-1.ics");
    mapping.setEtag(etag);
    return mapping;
  }

  /**
   * @param uid the iCalendar UID
   * @return an event to push
   */
  private IcsEvent event(String uid) {
    return IcsEvent.builder()
                   .uid(uid)
                   .summary("Steering point")
                   .start(Instant.parse("2026-09-08T09:00:00Z"))
                   .end(Instant.parse("2026-09-08T10:00:00Z"))
                   .timeZoneId("Europe/Paris")
                   .build();
  }
}

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
package org.exoplatform.caldav.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.exoplatform.caldav.dao.CaldavCalendarSyncDAO;
import org.exoplatform.caldav.dao.CaldavObjectSyncDAO;
import org.exoplatform.caldav.entity.CaldavCalendarSyncEntity;
import org.exoplatform.caldav.entity.CaldavObjectSyncEntity;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;

/**
 * What this storage has to guarantee is narrow and load-bearing: an href is
 * stored canonical, always, whoever wrote it. Every binding in the engine is
 * recovered by comparing hrefs, so a single path that skips normalisation does
 * not fail loudly — it silently fails to find a pair that exists, and the
 * engine creates a duplicate collection instead. These tests pin the
 * normalisation itself and the two write paths that must apply it.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavSyncStorageTest {

  private static final long     USER   = 42L;

  private static final long     SERVER = 7L;

  @Mock
  private CaldavCalendarSyncDAO calendarSyncDAO;

  @Mock
  private CaldavObjectSyncDAO   objectSyncDAO;

  @InjectMocks
  private CaldavSyncStorage     storage;

  @Test
  public void canonicalHrefDropsTheTrailingSlash() {
    assertEquals("/dav/calendars/john/work", CaldavSyncStorage.canonicalHref("/dav/calendars/john/work/"));
    assertEquals("/dav/calendars/john/work", CaldavSyncStorage.canonicalHref("/dav/calendars/john/work"));
  }

  @Test
  public void canonicalHrefDecodesPercentEscapes() {
    // The same collection, as a server writes it and as a client writes it.
    assertEquals(CaldavSyncStorage.canonicalHref("/dav/calendars/john%40acme.com/work/"),
                 CaldavSyncStorage.canonicalHref("/dav/calendars/john@acme.com/work"));
  }

  @Test
  public void canonicalHrefKeepsOnlyThePath() {
    // Reached through the relay or directly, it is one collection.
    assertEquals("/dav/calendars/john/work",
                 CaldavSyncStorage.canonicalHref("https://caldav.example.invalid/dav/calendars/john/work/"));
  }

  @Test
  public void canonicalHrefLeavesAnUnparseableValueUsable() {
    // Normalisation, not validation: refusing an odd href would lose the
    // binding rather than protect it.
    String odd = "not a url at all/";
    assertEquals("not a url at all", CaldavSyncStorage.canonicalHref(odd));
    assertNull(CaldavSyncStorage.canonicalHref(null));
  }

  @Test
  public void canonicalHrefStripsTheRelayRoot() {
    // The browser addressed servers through /caldav/rest/dav/{id} and stored
    // hrefs carrying that prefix; the server addresses the collection itself.
    // Both name one collection, and treating them as two does not fail loudly
    // — it silently fails to recognise a calendar.
    assertEquals("/dav/calendars/john/work",
                 CaldavSyncStorage.canonicalHref("/caldav/rest/dav/7/dav/calendars/john/work/"));
    assertEquals(CaldavSyncStorage.canonicalHref("/dav/calendars/john/work"),
                 CaldavSyncStorage.canonicalHref("https://exo.test/caldav/rest/dav/12/dav/calendars/john/work/"));
  }

  @Test
  public void savePairStoresTheHrefCanonical() {
    when(calendarSyncDAO.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    storage.savePair(pair(null, "/cal/john/work", "uid-1", SyncOrigin.EXO));

    ArgumentCaptor<CaldavCalendarSyncEntity> saved = ArgumentCaptor.forClass(CaldavCalendarSyncEntity.class);
    verify(calendarSyncDAO).save(saved.capture());
    assertEquals("/cal/john/work", saved.getValue().getRemoteHref());
  }

  @Test
  public void savePairNormalisesWhatTheCallerDidNot() {
    when(calendarSyncDAO.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    storage.savePair(pair(null, "https://caldav.example.invalid/cal/john%20doe/work/", "uid-2", SyncOrigin.REMOTE));

    ArgumentCaptor<CaldavCalendarSyncEntity> saved = ArgumentCaptor.forClass(CaldavCalendarSyncEntity.class);
    verify(calendarSyncDAO).save(saved.capture());
    assertEquals("/cal/john doe/work", saved.getValue().getRemoteHref());
  }

  @Test
  public void saveObjectNormalisesItsHrefToo() {
    when(objectSyncDAO.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectSync object = new ObjectSync(null, 3L, 9L, "uid@acme", "/cal/john/work/uid%40acme.ics", null, null, new Date());
    storage.saveObject(object);

    ArgumentCaptor<CaldavObjectSyncEntity> saved = ArgumentCaptor.forClass(CaldavObjectSyncEntity.class);
    verify(objectSyncDAO).save(saved.capture());
    assertEquals("/cal/john/work/uid@acme.ics", saved.getValue().getRemoteHref());
  }

  @Test
  public void getPairByRemoteHrefMatchesAcrossSpellings() {
    CaldavCalendarSyncEntity stored = entity(1L, "/cal/john@acme.com/work", "uid-1", SyncOrigin.EXO);
    when(calendarSyncDAO.findByUserIdentityIdAndServerId(USER, SERVER)).thenReturn(List.of(stored));

    // The very lookup that, done on raw strings, would miss and make the
    // engine create a second collection.
    CalendarSync found = storage.getPairByRemoteHref(USER,
                                                      SERVER,
                                                      "https://caldav.example.invalid/cal/john%40acme.com/work/");

    assertNotNull(found);
    assertEquals(1L, found.getId());
  }

  @Test
  public void getPairByRemoteHrefAnswersNullRatherThanGuessing() {
    when(calendarSyncDAO.findByUserIdentityIdAndServerId(USER, SERVER)).thenReturn(List.of(entity(1L,
                                                                                                  "/cal/john/work",
                                                                                                  "uid-1",
                                                                                                  SyncOrigin.EXO)));

    assertNull(storage.getPairByRemoteHref(USER, SERVER, "/cal/john/private"));
    assertNull(storage.getPairByRemoteHref(USER, SERVER, " "));
  }

  @Test
  public void getPairByLocalCalendarReadsThroughTheAnchor() {
    when(calendarSyncDAO.findByUserIdentityIdAndServerIdAndLocalCalendarSyncUid(USER, SERVER, "uid-1"))
                                                                                                       .thenReturn(Optional.of(entity(5L,
                                                                                                                                      "/cal/john/work",
                                                                                                                                      "uid-1",
                                                                                                                                      SyncOrigin.EXO)));

    assertEquals(5L, storage.getPairByLocalCalendar(USER, SERVER, "uid-1").getId());
    assertNull(storage.getPairByLocalCalendar(USER, SERVER, "absent"));
  }

  @Test
  public void getPairsByOriginShowsDuplicatesRatherThanHidingThem() {
    // The mirror pair should be single, but its anchor is null and no unique
    // index covers NULL rows. Returning a list is what lets the service see
    // that something went wrong instead of silently working on the first row.
    when(calendarSyncDAO.findByUserIdentityIdAndServerIdAndOrigin(USER, SERVER, SyncOrigin.MIRROR))
                                                                                                  .thenReturn(List.of(entity(1L,
                                                                                                                             "/cal/john/exo-meetings",
                                                                                                                             null,
                                                                                                                             SyncOrigin.MIRROR),
                                                                                                                      entity(2L,
                                                                                                                             "/cal/john/exo-meetings-2",
                                                                                                                             null,
                                                                                                                             SyncOrigin.MIRROR)));

    List<CalendarSync> mirrors = storage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR);

    assertEquals(2, mirrors.size());
  }

  @Test
  public void isEventMappedIsWhatMakesTheBackfillRepeatable() {
    when(objectSyncDAO.existsByLocalEventId(11L)).thenReturn(true);

    assertTrue(storage.isEventMapped(11L));
    verify(objectSyncDAO).existsByLocalEventId(11L);
  }

  /**
   * A pair DTO with the fields these tests care about and defaults elsewhere.
   *
   * @param id technical identifier, or null for a new pair
   * @param href the remote collection href, in any spelling
   * @param anchor agenda's calendar sync uid, or null for a mirror pair
   * @param origin which side created the collection
   * @return the pair
   */
  private CalendarSync pair(Long id, String href, String anchor, SyncOrigin origin) {
    return new CalendarSync(id, USER, SERVER, anchor, href, origin, null, null, CalendarSyncStatus.ACTIVE, null, null, 0);
  }

  /**
   * A pair entity already stored, therefore already canonical.
   *
   * @param id technical identifier
   * @param href the canonical remote collection href
   * @param anchor agenda's calendar sync uid, or null for a mirror pair
   * @param origin which side created the collection
   * @return the entity
   */
  private CaldavCalendarSyncEntity entity(long id, String href, String anchor, SyncOrigin origin) {
    return new CaldavCalendarSyncEntity(id,
                                        USER,
                                        SERVER,
                                        anchor,
                                        href,
                                        origin,
                                        null,
                                        null,
                                        CalendarSyncStatus.ACTIVE,
                                        null,
                                        null,
                                        0);
  }

  @Test
  public void everyPairOfAnAccountIsMappedOnTheWayOut() {
    when(calendarSyncDAO.findByUserIdentityIdAndServerId(USER, SERVER)).thenReturn(List.of(entity(1L, "/cal/a", "anchor-a", SyncOrigin.REMOTE), entity(2L, "/cal/b", "anchor-b", SyncOrigin.REMOTE)));

    assertEquals(2, storage.getPairs(USER, SERVER).size());
  }

  @Test
  public void oneBindingIsReadByItsIdentifier() {
    when(calendarSyncDAO.findById(9L)).thenReturn(Optional.of(entity(9L, "/cal/a", "anchor-a", SyncOrigin.REMOTE)));

    assertNotNull(storage.getPair(9L));
  }

  @Test
  public void aBindingThatIsNotThereReadsAsNull() {
    // Rather than an empty Optional the callers would each have to unwrap, or
    // an exception on a path where absence is an ordinary answer.
    when(calendarSyncDAO.findById(9L)).thenReturn(Optional.empty());

    assertNull(storage.getPair(9L));
  }

  @Test
  public void deletingABindingGoesStraightToTheDao() {
    storage.deletePair(9L);

    verify(calendarSyncDAO).deleteById(9L);
  }

  @Test
  public void theDuePageIsOrderedByHowLongItHasWaited() {
    // The sweep takes the head of this page, so the order is the whole point:
    // sorted any other way it would keep refreshing the same accounts and
    // never reach the ones that have waited longest.
    when(calendarSyncDAO.findDue(eq(CalendarSyncStatus.ACTIVE), any(), any(Pageable.class)))
                                                                                           .thenReturn(new PageImpl<>(List.of(entity(1L, "/cal/a", "anchor-a", SyncOrigin.REMOTE))));

    assertEquals(1, storage.getDuePairs(CalendarSyncStatus.ACTIVE, new Date(), 0, 50).getContent().size());

    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(calendarSyncDAO).findDue(eq(CalendarSyncStatus.ACTIVE), any(), page.capture());
    assertEquals(Sort.by(Sort.Direction.ASC, "lastSyncEnd"), page.getValue().getSort());
  }

  @Test
  public void anObjectIsReadByItsIcalendarUid() {
    when(objectSyncDAO.findByCalendarSyncIdAndIcsUid(3L, "uid-1")).thenReturn(Optional.of(objectEntity(1L)));

    assertNotNull(storage.getObjectByUid(3L, "uid-1"));
  }

  @Test
  public void anObjectIsReadByTheExoEventItStandsFor() {
    when(objectSyncDAO.findByCalendarSyncIdAndLocalEventId(3L, 7L)).thenReturn(Optional.of(objectEntity(1L)));

    assertNotNull(storage.getObjectByEvent(3L, 7L));
  }

  @Test
  public void theObjectsOfABindingComeBackAsAPage() {
    when(objectSyncDAO.findByCalendarSyncId(eq(3L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(objectEntity(1L))));

    assertEquals(1, storage.getObjects(3L, 0, 100).getContent().size());
  }

  /**
   * The lookup an edit fans out from: every copy of one meeting, across every
   * user's collections. Asked of the mapping table rather than of agenda's
   * attendee list, because it is the only source that still answers after eXo
   * has destroyed the event — and the only one that says who <i>already</i> has
   * a copy.
   */
  @Test
  public void everyCopyOfOneEventComesBackAcrossEveryUsersCollections() {
    when(objectSyncDAO.findByLocalEventId(eq(7L), any(Pageable.class)))
                                                                      .thenReturn(new PageImpl<>(List.of(objectEntity(1L),
                                                                                                         objectEntity(2L))));

    assertEquals(2, storage.getObjectsByEvent(7L, 0, 50).getContent().size());
  }

  @Test
  public void theObjectsOfABindingCanBeCounted() {
    when(objectSyncDAO.countByCalendarSyncId(3L)).thenReturn(4L);

    assertEquals(4L, storage.countObjects(3L));
  }

  @Test
  public void everyObjectOfABindingGoesAtOnce() {
    when(objectSyncDAO.deleteByCalendarSyncId(3L)).thenReturn(4);

    assertEquals(4, storage.deleteObjects(3L));
  }

  @Test
  public void oneObjectGoesByItsIdentifier() {
    storage.deleteObject(5L);

    verify(objectSyncDAO).deleteById(5L);
  }

  @Test
  public void aBlankHrefIsHandedBackUntouched() {
    // Null and empty are answers, not paths to canonicalise: a binding with no
    // href is a state the callers already handle, and inventing a path here
    // would hide it.
    assertNull(CaldavSyncStorage.canonicalHref(null));
    assertEquals("", CaldavSyncStorage.canonicalHref(""));
  }


  /**
   * @param id the mapping's identifier
   * @return one object mapping as stored
   */
  private CaldavObjectSyncEntity objectEntity(long id) {
    CaldavObjectSyncEntity entity = new CaldavObjectSyncEntity();
    entity.setId(id);
    entity.setCalendarSyncId(3L);
    entity.setIcsUid("uid-1");
    entity.setLocalEventId(7L);
    return entity;
  }

}

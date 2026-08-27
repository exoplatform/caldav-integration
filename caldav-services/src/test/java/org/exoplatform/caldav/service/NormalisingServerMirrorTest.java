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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.agenda.model.Event;
import org.exoplatform.agenda.model.RemoteEvent;
import org.exoplatform.agenda.service.AgendaCalendarService;
import org.exoplatform.agenda.service.AgendaEventService;
import org.exoplatform.agenda.service.AgendaRemoteEventService;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalendarCollection;
import org.exoplatform.caldav.client.CalendarObject;
import org.exoplatform.caldav.client.MkCalendarResult;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.client.ServerCapabilities;
import org.exoplatform.caldav.client.SyncCollectionResult;
import org.exoplatform.caldav.ics.IcsMerger;
import org.exoplatform.caldav.ics.IcsWriter;
import org.exoplatform.caldav.model.CaldavUserSetting;
import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.CalendarSyncStatus;
import org.exoplatform.caldav.model.IcsEvent;
import org.exoplatform.caldav.model.MirrorVerification;
import org.exoplatform.caldav.model.ObjectSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.storage.CaldavConnectorStorage;
import org.exoplatform.caldav.storage.CaldavSyncStorage;

/**
 * The push and the verification pass, run against a server that does not keep
 * the bytes it is given.
 *
 * <p>
 * This is EXO-89716 in a test. On BlueMind every copy eXo pushed was judged
 * altered on every pass, repaired, judged altered again, and after
 * {@code maxRepairs} rounds abandoned — after which eXo could never write to it
 * again and the meeting went quietly stale in the user's calendar. Nobody was
 * misbehaving: BlueMind parses an object into its own model and re-serialises
 * it, and eXo was comparing what the server stored against a digest of what eXo
 * had <i>sent</i>. The same code against byte-stable Stalwart was silent.
 *
 * <p>
 * The two services are the real ones here, wired to each other, because the
 * defect lived in neither of them alone: the push recorded the wrong baseline
 * and the pass drew the correct conclusion from it. A mock of either half would
 * have agreed with itself. What is faked is the server — twice: one that
 * re-serialises what it stores, and one that keeps the bytes verbatim, so the
 * claim "this changes nothing for a byte-stable server" is a test rather than a
 * sentence.
 */
@ExtendWith(MockitoExtension.class)
public class NormalisingServerMirrorTest {

  private static final long                    USER    = 42L;

  private static final long                    SERVER  = 7L;

  private static final long                    EVENT   = 500L;

  private static final String                  LOGIN   = "john";

  private static final String                  HOME    = "/dav/calendars/john/";

  private static final String                  MIRROR  = "/dav/calendars/john/exo-meetings/";

  private static final String                  HREF    = MIRROR + "evt-1.ics";

  /** What eXo writes: its own PRODID, its own property order. */
  private static final String                  EXO_ICS = "BEGIN:VCALENDAR\r\n"
      + "VERSION:2.0\r\n"
      + "PRODID:-//eXo//Agenda//EN\r\n"
      + "BEGIN:VEVENT\r\n"
      + "UID:evt-1\r\n"
      + "SUMMARY:Sprint review\r\n"
      + "DTSTART:20260901T090000Z\r\n"
      + "DTEND:20260901T100000Z\r\n"
      + "END:VEVENT\r\n"
      + "END:VCALENDAR\r\n";

  @Mock
  private CaldavConnectorStorage               caldavConnectorStorage;

  @Mock
  private CaldavSyncStorage                    caldavSyncStorage;

  @Mock
  private IcsWriter                            icsWriter;

  @Mock
  private IcsMerger                            icsMerger;

  @Mock
  private AgendaEventService                   agendaEventService;

  @Mock
  private AgendaEventIcsMapper                 agendaEventIcsMapper;

  @Mock
  private AgendaRemoteEventService             agendaRemoteEventService;

  @Mock
  private AgendaCalendarService                agendaCalendarService;

  private FakeCalDavServer                     server;

  private CaldavPushService                    push;

  private CaldavMirrorVerificationService      verification;

  /** The mapping rows, as a database would hold them. */
  private final Map<Long, ObjectSync>          rows    = new LinkedHashMap<>();

  /** The mirror binding, the only pair this account has. */
  private CalendarSync                         mirror;

  /**
   * Wires the two real services to a fake normalising server and an in-memory
   * mapping table.
   */
  @BeforeEach
  public void connectAnAccountOnANormalisingServer() {
    server = new FakeCalDavServer(true);
    push = new CaldavPushService();
    verification = new CaldavMirrorVerificationService();
    inject(push);
    inject(verification);
    ReflectionTestUtils.setField(push, "icsWriter", icsWriter);
    ReflectionTestUtils.setField(push, "icsMerger", icsMerger);
    ReflectionTestUtils.setField(push, "agendaEventService", agendaEventService);
    ReflectionTestUtils.setField(push, "agendaEventIcsMapper", agendaEventIcsMapper);
    ReflectionTestUtils.setField(push, "agendaRemoteEventService", agendaRemoteEventService);
    ReflectionTestUtils.setField(push, "agendaCalendarService", agendaCalendarService);
    ReflectionTestUtils.setField(verification, "caldavPushService", push);
    ReflectionTestUtils.setField(verification, "maxRepairs", 3);

    mirror = new CalendarSync();
    mirror.setId(3L);
    mirror.setUserIdentityId(USER);
    mirror.setServerId(SERVER);
    mirror.setRemoteHref(CaldavSyncStorage.canonicalHref(MIRROR));
    mirror.setOrigin(SyncOrigin.MIRROR);
    mirror.setStatus(CalendarSyncStatus.ACTIVE);

    lenient().when(caldavConnectorStorage.getCaldavSetting(USER)).thenReturn(settings());
    lenient().when(caldavSyncStorage.getPairsByOrigin(USER, SERVER, SyncOrigin.MIRROR)).thenReturn(List.of(mirror));
    lenient().when(caldavSyncStorage.getPairs(USER, SERVER)).thenReturn(List.of(mirror));
    lenient().when(icsWriter.write(any())).thenReturn(EXO_ICS);
    // A repair rewrites through the merge, so what the merger answers is what
    // is written: eXo's own text, which is the case the merge collapses to
    // when the server holds nothing another client authored.
    lenient().when(icsMerger.merge(anyString(), anyString(), anyBoolean())).thenReturn(EXO_ICS);
    givenAnInMemoryMappingTable();
  }

  @Test
  public void aServerThatReSerialisesWhatItStoresDoesNotMakeEveryCopyLookTamperedWith() {
    // The bug, stated as the rig stated it: four consecutive identical passes,
    // "19 checked, 0 missing, 19 altered, 0 re-pushed, 19 abandoned". One
    // object is enough to reproduce it; the count only multiplied it.
    push.writeInto(USER, mirror, event(), EVENT);
    // What the server holds is not what eXo sent, and that is legitimate.
    assertNotEquals(EXO_ICS, server.stored(HREF));

    for (int pass = 0; pass < 4; pass++) {
      MirrorVerification result = verification.verify(USER);

      assertEquals(1, result.checked(), "pass " + pass);
      assertEquals(0, result.altered(), "pass " + pass);
      assertEquals(0, result.repaired(), "pass " + pass);
      assertEquals(0, result.abandoned(), "pass " + pass);
    }
  }

  @Test
  public void aByteStableServerIsUnaffected() {
    // Stalwart. It was already healthy, and the read-back must not make it
    // anything else: the baseline it yields is the same bytes eXo sent.
    server = new FakeCalDavServer(false);
    inject(push);
    inject(verification);

    ObjectSync mapping = push.writeInto(USER, mirror, event(), EVENT);

    assertEquals(EXO_ICS, server.stored(HREF));
    assertEquals(0, verification.verify(USER).altered());
    assertEquals(server.etag(HREF), mapping.getEtag());
  }

  @Test
  public void anEditMadeInExoReachesTheCopyOnANormalisingServer() {
    // The ticket's second acceptance criterion, and the half a digest alone
    // does not cover. An ordinary update is guarded by If-Match with the
    // version eXo recorded; record the version the write answered rather than
    // the one the server settled on, and every later edit is refused with a
    // 412 the user never sees. The meeting moves in eXo and not in their
    // calendar.
    push.writeInto(USER, mirror, event(), EVENT);
    when(icsWriter.write(any())).thenReturn(EXO_ICS.replace("090000Z", "150000Z"));
    when(icsMerger.merge(anyString(), anyString(), anyBoolean())).thenReturn(EXO_ICS.replace("090000Z", "150000Z"));

    push.writeInto(USER, mirror, event(), EVENT);

    assertTrue(server.stored(HREF).contains("DTSTART:20260901T150000Z"), server.stored(HREF));
  }

  @Test
  public void aGenuineClientEditIsStillCalledAlteredAndStillRepaired() {
    // The whole point of the pass, and the thing a fix must not buy its
    // silence with. Somebody opened the copy on their phone and changed it;
    // the mirror is eXo's projection, so eXo writes it back.
    push.writeInto(USER, mirror, event(), EVENT);
    givenTheRepairCanRebuildTheEvent();

    server.editedByAClient(HREF, EXO_ICS.replace("Sprint review", "Sprint review (moved to the pub)"));
    MirrorVerification result = verification.verify(USER);

    assertEquals(1, result.altered());
    assertEquals(1, result.repaired());
    assertEquals(0, result.abandoned());
    // And the repair converges: the copy the server now holds is eXo's again,
    // so the next pass has nothing to say. Before the fix this second pass
    // reported "altered" once more, which is how an object reached maxRepairs.
    assertEquals(0, verification.verify(USER).altered());
  }

  @Test
  public void aCopyAbandonedBeforeTheFixHealsOnTheFirstPassAfterIt() {
    // The installed base. A row written by the old code carries a digest of
    // what eXo sent, and the repair counter that abandoned it lived in memory
    // — so deploying this fix restarts the JVM and forgives the counter, but
    // the stale digest is still in the database. It costs exactly one repair.
    ObjectSync legacy = push.writeInto(USER, mirror, event(), EVENT);
    // Exactly what the old code left behind: a digest of the bytes eXo sent,
    // and a version the server has since moved past — which is the state that
    // sent BlueMind's copies round the repair loop to abandonment.
    legacy.setPushedHash(digestOf(EXO_ICS));
    legacy.setEtag("\"v0\"");
    caldavSyncStorage.saveObject(legacy);
    givenTheRepairCanRebuildTheEvent();

    MirrorVerification first = verification.verify(USER);
    MirrorVerification second = verification.verify(USER);

    assertEquals(1, first.altered());
    assertEquals(1, first.repaired());
    assertEquals(0, second.altered());
    assertEquals(0, second.abandoned());
  }

  @Test
  public void anEtagThatMovedOverBytesThatDidNotIsRecordedRatherThanPaidForForEver() {
    // A server touching its own metadata. The bytes are ours, so the version
    // it now publishes names our copy: recording it stops the next pass paying
    // another fetch to reach the same conclusion, and — the reason it matters
    // — stops the next ordinary update carrying an If-Match the server has
    // already left behind, which it would refuse.
    ObjectSync mapping = push.writeInto(USER, mirror, event(), EVENT);
    String recorded = mapping.getEtag();
    server.touchedItsOwnMetadata(HREF);

    assertEquals(0, verification.verify(USER).altered());
    assertNotEquals(recorded, rows.get(mapping.getId()).getEtag());
    assertEquals(server.etag(HREF), rows.get(mapping.getId()).getEtag());
    // Cheap from now on: the listing and the row agree, so nothing is fetched.
    int before = server.fetches();
    verification.verify(USER);
    assertEquals(before, server.fetches());
  }

  @Test
  public void aServerThatCannotBeReadBackLeavesThePushSucceeding() {
    // A read-back that fails says nothing about a write that succeeded. The
    // sent bytes stand in, which is exactly what happened before this existed.
    server.refuseReads();

    ObjectSync mapping = push.writeInto(USER, mirror, event(), EVENT);

    assertEquals(digestOf(EXO_ICS), mapping.getPushedHash());
    assertTrue(rows.containsKey(mapping.getId()));
  }

  /**
   * Points a service's collaborators at this test's fakes.
   *
   * @param service the push or the verification service
   */
  private void inject(Object service) {
    ReflectionTestUtils.setField(service, "calDavClient", server);
    ReflectionTestUtils.setField(service, "caldavConnectorStorage", caldavConnectorStorage);
    ReflectionTestUtils.setField(service, "caldavSyncStorage", caldavSyncStorage);
  }

  /**
   * Makes the mapping-table mock behave like a table: rows come back as they
   * were last written, which is what a verification pass reads.
   */
  private void givenAnInMemoryMappingTable() {
    AtomicLong sequence = new AtomicLong(1000L);
    lenient().when(caldavSyncStorage.saveObject(any())).thenAnswer(invocation -> {
      ObjectSync object = invocation.getArgument(0);
      if (object.getId() == null || object.getId() <= 0) {
        object.setId(sequence.incrementAndGet());
      }
      rows.put(object.getId(), object);
      return object;
    });
    lenient().when(caldavSyncStorage.getObjectByUid(anyLong(), anyString())).thenAnswer(invocation -> {
      String uid = invocation.getArgument(1);
      return rows.values().stream().filter(row -> uid.equals(row.getIcsUid())).findFirst().orElse(null);
    });
    lenient().when(caldavSyncStorage.getObjectByEvent(anyLong(), anyLong())).thenAnswer(invocation -> {
      long eventId = invocation.getArgument(1);
      return rows.values()
                 .stream()
                 .filter(row -> row.getLocalEventId() != null && row.getLocalEventId() == eventId)
                 .findFirst()
                 .orElse(null);
    });
    lenient().when(caldavSyncStorage.getObjects(anyLong(), anyInt(), anyInt()))
             .thenAnswer(invocation -> {
               int page = invocation.getArgument(1);
               return new PageImpl<>(page == 0 ? new ArrayList<>(rows.values()) : List.of());
             });
  }

  /**
   * Lets the repair rebuild the event from agenda, the way a live repair does.
   */
  private void givenTheRepairCanRebuildTheEvent() {
    Event event = new Event();
    event.setId(EVENT);
    event.setCalendarId(11L);
    try {
      when(agendaEventService.getEventById(EVENT, null, USER)).thenReturn(event);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
    when(agendaRemoteEventService.findRemoteEvent(EVENT, USER)).thenReturn(remoteEvent());
    when(agendaEventIcsMapper.toIcsEvent(any(), anyString(), any(), anyLong())).thenReturn(event());
    when(agendaCalendarService.getCalendarById(11L)).thenReturn(null);
  }

  /**
   * @return the identifier agenda already holds for this event's object
   */
  private RemoteEvent remoteEvent() {
    RemoteEvent remote = new RemoteEvent();
    remote.setEventId(EVENT);
    remote.setIdentityId(USER);
    remote.setRemoteId("evt-1");
    return remote;
  }

  /**
   * @return the event as the writer receives it
   */
  private IcsEvent event() {
    IcsEvent event = new IcsEvent();
    event.setUid("evt-1");
    event.setSummary("Sprint review");
    return event;
  }

  /**
   * @return the connected account
   */
  private CaldavUserSetting settings() {
    CaldavUserSetting setting = new CaldavUserSetting();
    setting.setUsername(LOGIN);
    setting.setPassword("secret");
    setting.setServerId(SERVER);
    return setting;
  }

  /**
   * @param ics an iCalendar object
   * @return the digest the push records for it
   */
  private String digestOf(String ics) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
                                .formatHex(digest.digest(ics.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A CalDAV server that either keeps the bytes it is handed or re-serialises
   * them, and says which of the two it is at construction.
   *
   * <p>
   * Faked rather than mocked on purpose: the defect is a disagreement between
   * what one call wrote and what a later call reads, and a mock has no memory
   * to disagree with itself in.
   */
  private static final class FakeCalDavServer implements CalDavClient {

    /** Whether this server re-serialises what it stores, as BlueMind does. */
    private final boolean             normalising;

    /** The objects it holds, by href. */
    private final Map<String, String> objects = new LinkedHashMap<>();

    /** The version it publishes for each of them. */
    private final Map<String, String> etags   = new LinkedHashMap<>();

    /** Bumped on every write, so no two versions are alike. */
    private int                       version;

    /** How many GETs it has served, which is what the read-back costs. */
    private int                       fetches;

    /** Whether reads are refused, standing for an unreachable server. */
    private boolean                   readsRefused;

    /**
     * @param normalising true to re-serialise stored objects
     */
    private FakeCalDavServer(boolean normalising) {
      this.normalising = normalising;
    }

    /**
     * @param href the object's path
     * @return the iCalendar text this server holds for it
     */
    private String stored(String href) {
      return objects.get(href);
    }

    /**
     * @param href the object's path
     * @return the version it publishes for it
     */
    private String etag(String href) {
      return etags.get(href);
    }

    /**
     * @return how many GETs this server has served
     */
    private int fetches() {
      return fetches;
    }

    /** Makes every read fail, standing for a server that cannot be reached. */
    private void refuseReads() {
      readsRefused = true;
    }

    /**
     * Rewrites an object the way another client would: new bytes, new version,
     * and no normalisation, because this text is the client's own.
     *
     * @param href the object's path
     * @param ics what the client left there
     */
    private void editedByAClient(String href, String ics) {
      objects.put(href, ics);
      etags.put(href, "\"v" + ++version + "\"");
    }

    /**
     * Moves an object's version without touching a byte of it, the way a
     * server does when it changes its own metadata.
     *
     * @param href the object's path
     */
    private void touchedItsOwnMetadata(String href) {
      etags.put(href, "\"v" + ++version + "\"");
    }

    /**
     * What this server actually stores for what it was given.
     *
     * <p>
     * A stand-in for BlueMind's parse-and-re-serialise: the same meeting, a
     * different document — its own PRODID and the event's properties in its
     * own order. Semantically equal, textually not, which is the only
     * property this test needs it to have.
     *
     * @param ics the text that was PUT
     * @return the text this server keeps
     */
    private String store(String ics) {
      if (!normalising) {
        return ics;
      }
      List<String> lines = Arrays.asList(ics.split("\r\n"));
      List<String> properties = lines.stream()
                                     .filter(line -> !line.startsWith("BEGIN:") && !line.startsWith("END:")
                                         && !line.startsWith("PRODID:"))
                                     .sorted()
                                     .collect(Collectors.toList());
      return "BEGIN:VCALENDAR\r\nPRODID:-//FakeMind//Calendar//EN\r\nBEGIN:VEVENT\r\n"
          + String.join("\r\n", properties) + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    /**
     * Stores an object and publishes a version for it.
     *
     * <p>
     * A normalising server answers the write with the version of the document
     * it was <i>handed</i>, then keeps its own re-serialisation under the next
     * version — so the ETag the PUT returned names a representation the server
     * does not hold. That is not an embellishment: it is what made the live
     * defect visible at all. The verification pass short-circuits on an
     * unchanged ETag, so a server whose version never moved would hide a
     * mismatched digest instead of reporting it, and the rig reported "19
     * altered" precisely because BlueMind's version had moved. A byte-stable
     * server has nothing to re-serialise and publishes one version.
     *
     * @param href the object's path
     * @param ics the text that was PUT
     * @param status the status to answer
     * @return the write's result
     */
    private PutResult accept(String href, String ics, int status) {
      String answered = "\"v" + ++version + "\"";
      objects.put(href, store(ics));
      etags.put(href, normalising ? "\"v" + ++version + "\"" : answered);
      return new PutResult(status, answered, null);
    }

    @Override
    public CalDavEndpoint endpoint(Long serverId, String davUsername) {
      // Null on purpose: this fake is addressed by href alone, and every
      // method below ignores the endpoint. Minting a real one would only add
      // a registry the test has no use for.
      return null;
    }

    @Override
    public String discoverCalendarHome(CalDavEndpoint endpoint, String username, String password) {
      return HOME;
    }

    @Override
    public List<CalendarCollection> listCalendars(CalDavEndpoint endpoint,
                                                  String homeHref,
                                                  String username,
                                                  String password) {
      return List.of(new CalendarCollection(MIRROR, "eXo Meetings", null, null, null, true));
    }

    @Override
    public CalendarCollection readCalendar(CalDavEndpoint endpoint, String href, String username, String password) {
      return null;
    }

    @Override
    public String getCtag(CalDavEndpoint endpoint, String href, String username, String password) {
      return null;
    }

    @Override
    public Map<String, String> listResourceEtags(CalDavEndpoint endpoint,
                                                 String collectionHref,
                                                 String username,
                                                 String password) {
      return new LinkedHashMap<>(etags);
    }

    @Override
    public List<CalendarObject> calendarQuery(CalDavEndpoint endpoint,
                                              String collectionHref,
                                              Instant start,
                                              Instant end,
                                              String username,
                                              String password) {
      return List.of();
    }

    @Override
    public List<CalendarObject> multiget(CalDavEndpoint endpoint,
                                         String collectionHref,
                                         List<String> hrefs,
                                         String username,
                                         String password) {
      return List.of();
    }

    @Override
    public SyncCollectionResult syncCollection(CalDavEndpoint endpoint,
                                               String collectionHref,
                                               String syncToken,
                                               String username,
                                               String password) {
      throw new UnsupportedOperationException("not part of this test");
    }

    @Override
    public ServerCapabilities probeCapabilities(CalDavEndpoint endpoint,
                                                String collectionHref,
                                                String username,
                                                String password) {
      throw new UnsupportedOperationException("not part of this test");
    }

    @Override
    public CalendarObject fetchObject(CalDavEndpoint endpoint, String href, String username, String password) {
      fetches++;
      if (readsRefused) {
        throw new IllegalStateException("the server cannot be reached");
      }
      String ics = objects.get(href);
      return ics == null ? null : new CalendarObject(href, etags.get(href), ics);
    }

    @Override
    public PutResult putObject(CalDavEndpoint endpoint, String href, String icsData, String username, String password) {
      // If-None-Match: * — a create, and only a create.
      return objects.containsKey(href) ? new PutResult(412, null, null) : accept(href, icsData, 201);
    }

    @Override
    public PutResult overwriteObject(CalDavEndpoint endpoint,
                                     String href,
                                     String icsData,
                                     String username,
                                     String password) {
      return accept(href, icsData, 200);
    }

    @Override
    public PutResult updateObject(CalDavEndpoint endpoint,
                                  String href,
                                  String icsData,
                                  String ifMatch,
                                  String username,
                                  String password) {
      // If-Match — refused when the caller's version is not the current one,
      // which is how a stale recorded ETag stops an eXo edit from landing.
      return ifMatch != null && ifMatch.equals(etags.get(href)) ? accept(href, icsData, 204)
                                                                : new PutResult(412, null, null);
    }

    @Override
    public int deleteObject(CalDavEndpoint endpoint, String href, String ifMatch, String username, String password) {
      objects.remove(href);
      etags.remove(href);
      return 204;
    }

    @Override
    public MkCalendarResult mkCalendar(CalDavEndpoint endpoint,
                                       String href,
                                       String displayName,
                                       String color,
                                       String username,
                                       String password) {
      return new MkCalendarResult(201, List.of());
    }

    @Override
    public int deleteCollection(CalDavEndpoint endpoint, CalendarSync pair, String username, String password) {
      return 204;
    }
  }
}

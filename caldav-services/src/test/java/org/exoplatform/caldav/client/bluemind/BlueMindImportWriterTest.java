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
package org.exoplatform.caldav.client.bluemind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.client.TestEndpoints;
import org.exoplatform.caldav.client.bluemind.BlueMindCalendarImportClient.ImportReport;

/**
 * The BlueMind door (EXO-90307), against a mocked import API and a mocked
 * CalDAV client for its reads: what it sends, what it takes out, what it
 * reads back, and which CalDAV writes it never issues.
 */
public class BlueMindImportWriterTest {

  private static final String OWNER_UID  = "751E6D1A-3F2B-4C8D-9E01-2A3B4C5D6E7F";

  private static final String CONTAINER  = "calendar:Default:" + OWNER_UID;

  private static final String COLLECTION = "/dav/calendars/__uids__/" + OWNER_UID + "/" + CONTAINER + "/";

  private static final String HREF       = COLLECTION + "evt-1.ics";

  /** The document as {@code IcsWriter} renders it, {@code LAST-MODIFIED} and a folded line included. */
  private static final String ICS        = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:evt-1\r\n"
      + "DTSTAMP:20260901T080000Z\r\nCREATED:20260801T080000Z\r\nLAST-MODIFIED:20260901T080000Z\r\n"
      + "SUMMARY:Sprint review\r\nDESCRIPTION:A long description that the writer folded onto\r\n  a second line\r\n"
      + "END:VEVENT\r\nEND:VCALENDAR\r\n";

  /**
   * The version the collection listing — and the Depth:0 read — publish for
   * the object: raw, constant (GetTag.java:46-56); the values a rig capture
   * answered for one object on 2026-09-16
   * ({@code bluemind-propfind-object-depth0-getetag.captured.xml}).
   */
  private static final String STORED_ETAG   = "bmdav_3980966296_0";

  /**
   * The version the multiget REPORT publishes for the same object: quoted
   * base64 of the token at its item version (CalendarMultigetExecutor.java:123),
   * as captured the same day ({@code bluemind-report-multiget-one-href-getetag.captured.xml}).
   */
  private static final String MULTIGET_ETAG = "\"Ym1kYXZfMzk4MDk2NjI5Nl8x\"";

  /** A Depth:0 token hashed from another spelling of the path — the shape hypothesis failing. */
  private static final String OTHER_TOKEN   = "bmdav_1130583920_0";

  private final BlueMindCalendarImportClient importClient = mock(BlueMindCalendarImportClient.class);

  private final CalDavClient                 calDavClient = mock(CalDavClient.class);

  private final CalDavEndpoint               endpoint     =
                                                      TestEndpoints.endpoint(7L,
                                                                             URI.create("https://bm.example.com/dav/"),
                                                                             "personal",
                                                                             "francois");

  private BlueMindImportWriter               writer;

  /**
   * A writer whose import always reports the series applied.
   */
  @BeforeEach
  void aWriterOverAnImportThatApplies() {
    writer = new BlueMindImportWriter(importClient, calDavClient);
    when(importClient.importIcs(any(), anyString(), anyString())).thenReturn(new ImportReport(List.of("evt-1"), 1));
  }

  /**
   * <b>Trap 1.</b> {@code LAST-MODIFIED} leaves the document on this door and
   * nothing else does: BlueMind drops an import whose {@code LAST-MODIFIED} is
   * not after the stored one ({@code ICSImportTask.java:171-179}), and eXo's
   * value does not move on an answer.
   */
  @Test
  void lastModifiedIsTakenOutOfTheImportedDocumentAndNothingElseIs() {
    givenNothingStoredThenStored();

    writer.putObject(endpoint, HREF, ICS);

    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(importClient).importIcs(eq(endpoint), eq(CONTAINER), body.capture());
    assertFalse(body.getValue().toUpperCase().contains("LAST-MODIFIED"), body.getValue());
    assertEquals(ICS.replace("LAST-MODIFIED:20260901T080000Z\r\n", ""), body.getValue());
  }

  /**
   * A folded {@code LAST-MODIFIED} goes with its continuation line; a property
   * that merely starts with the same letters stays; the line ending of the
   * document is kept.
   */
  @Test
  void theWholeFoldedPropertyGoesAndLookalikesStay() {
    String document = "BEGIN:VEVENT\nLAST-MODIFIED;X-P=1:20260901T080000Z\n  more\nLAST-MODIFIED-EXTRA:kept\nX-LAST-MODIFIED:kept\nEND:VEVENT\n";

    assertEquals("BEGIN:VEVENT\nLAST-MODIFIED-EXTRA:kept\nX-LAST-MODIFIED:kept\nEND:VEVENT\n",
                 BlueMindImportWriter.withoutLastModified(document));
    assertEquals("BEGIN:VEVENT\r\nEND:VEVENT\r\n", BlueMindImportWriter.withoutLastModified("BEGIN:VEVENT\r\nEND:VEVENT\r\n"));
  }

  /**
   * <b>Trap 4.</b> The import answers no ETag, so the version eXo records is
   * read back in the collection's {@code Depth: 1} listing's shape — the very
   * channel the verification pass lists with and adopts from — verbatim, raw
   * token included; never from a {@code calendar-multiget} REPORT or a GET,
   * whose shapes the pass would overwrite on its next round.
   * Here the single-object reads answer nothing conclusive, so both reads are
   * the listing itself.
   */
  @Test
  void theVersionIsReadBackFromTheCollectionListingAfterTheImport() {
    givenNothingStoredThenStored();

    PutResult result = writer.putObject(endpoint, HREF, ICS);

    assertEquals(201, result.status());
    assertEquals(STORED_ETAG, result.etag());
    verify(calDavClient, times(2)).listResourceEtags(endpoint, COLLECTION);
    // The create's own precondition is absence, the listing's word alone:
    // the single-object channel is asked once, after the import, never
    // before it.
    verify(calDavClient, times(1)).multigetEtags(endpoint, COLLECTION, List.of(HREF));
    verify(calDavClient, never()).multiget(any(), anyString(), any());
    verify(calDavClient, never()).fetchObject(any(), anyString());
  }

  /**
   * <b>The settled path.</b> Once the {@code Depth: 0} read has
   * agreed with the listing on this collection — one listing, paid on the
   * first conclusive read — a conditional write on it is two single-object
   * requests before and two after, and lists the collection never again.
   */
  @Test
  void aWriteOnAnAgreedCollectionListsTheCollectionNoMore() {
    givenStored();
    givenPresent(STORED_ETAG);
    when(importClient.deleteEvent(endpoint, CONTAINER, "evt-1")).thenReturn(204);

    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, STORED_ETAG).status());
    verify(calDavClient, times(1)).listResourceEtags(endpoint, COLLECTION);

    PutResult second = writer.updateObject(endpoint, HREF, ICS, STORED_ETAG);

    assertEquals(200, second.status());
    assertEquals(STORED_ETAG, second.etag());
    verify(calDavClient, times(1)).listResourceEtags(endpoint, COLLECTION);
    verify(calDavClient, times(4)).multigetEtags(endpoint, COLLECTION, List.of(HREF));
    verify(calDavClient, times(4)).readEtag(endpoint, HREF);
    assertEquals(204, writer.deleteObject(endpoint, HREF, STORED_ETAG));
    verify(calDavClient, times(1)).listResourceEtags(endpoint, COLLECTION);
  }

  /**
   * <b>The recorded version.</b> The version a write answers is
   * the listing's shape whatever the {@code Depth: 0} read says: on a
   * collection where the two agreed it is the token both publish; on one
   * where they differed, the listing's value is recorded and the
   * single-object channel is not asked again on that collection.
   */
  @Test
  void theRecordedVersionIsTheListingShapedTokenUnderEitherVerdict() {
    givenStored();
    givenPresent(OTHER_TOKEN);

    assertEquals(STORED_ETAG, writer.overwriteObject(endpoint, HREF, ICS).etag(), "the listing's value, not the Depth:0 one");
    assertEquals(STORED_ETAG, writer.overwriteObject(endpoint, HREF, ICS).etag());
    verify(calDavClient, times(2)).listResourceEtags(endpoint, COLLECTION);
    verify(calDavClient, times(1)).multigetEtags(endpoint, COLLECTION, List.of(HREF));
    verify(calDavClient, times(1)).readEtag(endpoint, HREF);
    verify(calDavClient, never()).multiget(any(), anyString(), any());
  }

  /**
   * <b>Every inconclusive shape.</b> No presence from the
   * multiget, no {@code getetag} at {@code Depth: 0}, a blank one, a server
   * error on either read: each falls back to the listing and the write goes
   * on with the listing's answer.
   */
  @Test
  void eachInconclusiveSingleObjectAnswerFallsBackToTheListing() {
    givenStored();

    when(calDavClient.multigetEtags(endpoint, COLLECTION, List.of(HREF))).thenReturn(Map.of());
    when(calDavClient.readEtag(endpoint, HREF)).thenReturn(STORED_ETAG);
    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, STORED_ETAG).status());
    verify(calDavClient, never()).readEtag(endpoint, HREF);
    verify(calDavClient, times(2)).listResourceEtags(endpoint, COLLECTION);

    when(calDavClient.multigetEtags(endpoint, COLLECTION, List.of(HREF))).thenReturn(Map.of(HREF, MULTIGET_ETAG));
    when(calDavClient.readEtag(endpoint, HREF)).thenReturn(null);
    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, STORED_ETAG).status());
    verify(calDavClient, times(4)).listResourceEtags(endpoint, COLLECTION);

    when(calDavClient.readEtag(endpoint, HREF)).thenReturn(" ");
    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, STORED_ETAG).status());
    verify(calDavClient, times(6)).listResourceEtags(endpoint, COLLECTION);

    when(calDavClient.readEtag(endpoint, HREF)).thenThrow(new CalDavException("The calendar server answered 500 for PROPFIND"));
    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, STORED_ETAG).status());
    verify(calDavClient, times(8)).listResourceEtags(endpoint, COLLECTION);

    when(calDavClient.multigetEtags(endpoint, COLLECTION, List.of(HREF))).thenThrow(new CalDavException("The calendar server answered 500 for REPORT"));
    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, STORED_ETAG).status());
    verify(calDavClient, times(10)).listResourceEtags(endpoint, COLLECTION);
  }

  /**
   * <b>The exact href.</b> Presence counts only under the exact
   * spelling eXo sent: the server's response href is its own construction of
   * the object's path, the one its listing hashes from, so an object answered
   * under another spelling (a leaf eXo percent-encodes and the server does
   * not) would hash apart at {@code Depth: 0} — the listing is read instead
   * and the {@code Depth: 0} read is not asked, on an agreed collection too.
   */
  @Test
  void presenceUnderAnotherSpellingOfTheHrefIsNotPresence() {
    givenStored();
    givenPresent(STORED_ETAG);
    writer.updateObject(endpoint, HREF, ICS, STORED_ETAG);
    verify(calDavClient, times(1)).listResourceEtags(endpoint, COLLECTION);

    String encoded = COLLECTION + "evt%201.ics";
    String decoded = COLLECTION + "evt 1.ics";
    when(calDavClient.multigetEtags(endpoint, COLLECTION, List.of(encoded))).thenReturn(Map.of(decoded, MULTIGET_ETAG));
    when(calDavClient.readEtag(endpoint, encoded)).thenReturn("bmdav_777_0");
    when(calDavClient.listResourceEtags(endpoint, COLLECTION)).thenReturn(Map.of(decoded, "bmdav_888_0"));
    when(importClient.importIcs(any(), anyString(), anyString())).thenReturn(new ImportReport(List.of("evt 1"), 1));

    PutResult result = writer.updateObject(endpoint, encoded, ICS.replace("UID:evt-1", "UID:evt 1"), "bmdav_888_0");

    assertEquals(200, result.status());
    assertEquals("bmdav_888_0", result.etag(), "the listing's value, the Depth:0 read never asked");
    verify(calDavClient, never()).readEtag(endpoint, encoded);
    verify(calDavClient, times(3)).listResourceEtags(endpoint, COLLECTION);
  }

  /**
   * A refused credential is not an inconclusive answer: it is thrown, never
   * fallen back from, because the listing would meet the same refusal.
   */
  @Test
  void aCredentialRefusalOnTheSingleObjectReadIsThrownNotFallenBackFrom() {
    givenStored();
    when(calDavClient.multigetEtags(endpoint, COLLECTION, List.of(HREF))).thenThrow(new CalDavAuthenticationException("refused"));

    assertThrows(CalDavAuthenticationException.class, () -> writer.updateObject(endpoint, HREF, ICS, STORED_ETAG));

    verify(calDavClient, never()).listResourceEtags(endpoint, COLLECTION);
    verify(importClient, never()).importIcs(any(), anyString(), anyString());
  }

  /**
   * <b>A missing object under both hypotheses.</b> Whether a
   * {@code Depth: 0} on a missing href answers 404 (no version) or a node
   * minted from the path (a version for nothing — BlueMind,
   * {@code DavStore.java:474-502}), the object is absent: the create goes
   * through, the update is 412 with no version, the conditional removal is
   * 404 — and the {@code Depth: 0} read is never even asked, because
   * presence was not affirmed first.
   */
  @Test
  void aMissingObjectIsAbsentWhetherDepthZeroAnswers404OrAPathMintedNode() {
    for (String depthZero : new String[] { null, OTHER_TOKEN }) {
      writer = new BlueMindImportWriter(importClient, calDavClient);
      givenNothingStored();
      when(calDavClient.multigetEtags(endpoint, COLLECTION, List.of(HREF))).thenReturn(Map.of());
      when(calDavClient.readEtag(endpoint, HREF)).thenReturn(depthZero);

      PutResult update = writer.updateObject(endpoint, HREF, ICS, STORED_ETAG);
      assertTrue(update.preconditionFailed());
      assertNull(update.etag());
      assertEquals(404, writer.deleteObject(endpoint, HREF, STORED_ETAG));

      givenNothingStoredThenStored();
      PutResult created = writer.putObject(endpoint, HREF, ICS);
      assertEquals(201, created.status());
      assertEquals(STORED_ETAG, created.etag());
    }
    verify(calDavClient, never()).readEtag(endpoint, HREF);
  }

  /**
   * <b>No verdict on a listing that lacks the object.</b> The
   * multiget affirming an object the listing does not carry — a race, or a
   * listing that failed on the server — decides nothing about the two
   * channels' agreement: the listing's answer stands for that call, and the
   * next call on the collection is still allowed to settle the verdict.
   */
  @Test
  void aListingThatLacksTheObjectRecordsNoVerdict() {
    givenNothingStored();
    givenPresent(STORED_ETAG);

    PutResult gone = writer.updateObject(endpoint, HREF, ICS, STORED_ETAG);
    assertTrue(gone.preconditionFailed());
    assertNull(gone.etag());

    givenStored();
    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, STORED_ETAG).status());
    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, STORED_ETAG).status());
    verify(calDavClient, times(2)).listResourceEtags(endpoint, COLLECTION);
    verify(calDavClient, times(5)).multigetEtags(endpoint, COLLECTION, List.of(HREF));
  }

  /**
   * <b>A refusal is the listing's word.</b> On an agreed
   * collection, a {@code Depth: 0} version that differs from the one the
   * caller conditions on does not refuse by itself: the listing is read, and
   * it decides — a 412 carrying the listing's value when it disagrees too,
   * an accepted write when it agrees after all; and a listing that
   * contradicts the token revokes the collection's verdict, so nothing of
   * the contradicted shape is ever recorded.
   */
  @Test
  void aRefusalIsAlwaysConfirmedAgainstTheListing() {
    givenStored();
    givenPresent(STORED_ETAG);
    writer.updateObject(endpoint, HREF, ICS, STORED_ETAG);
    verify(calDavClient, times(1)).listResourceEtags(endpoint, COLLECTION);

    PutResult refused = writer.updateObject(endpoint, HREF, ICS, "\"older\"");
    assertTrue(refused.preconditionFailed());
    assertEquals(STORED_ETAG, refused.etag());
    verify(calDavClient, times(2)).listResourceEtags(endpoint, COLLECTION);

    when(calDavClient.readEtag(endpoint, HREF)).thenReturn(OTHER_TOKEN);
    PutResult accepted = writer.updateObject(endpoint, HREF, ICS, STORED_ETAG);
    assertEquals(200, accepted.status());
    assertEquals(STORED_ETAG, accepted.etag(), "the version recorded after the write is the listing's, not the contradicted token");
    // The listing contradicted the token, so the verdict is revoked: the
    // write-back and the next write read the listing again, and the
    // single-object channel is left alone on this collection.
    verify(calDavClient, times(4)).listResourceEtags(endpoint, COLLECTION);
    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, STORED_ETAG).status());
    verify(calDavClient, times(6)).listResourceEtags(endpoint, COLLECTION);
    verify(calDavClient, times(4)).multigetEtags(endpoint, COLLECTION, List.of(HREF));
    verify(importClient, times(3)).importIcs(any(), anyString(), anyString());
  }

  /**
   * <b>The scenario itself.</b> After a pass, the row holds the
   * value the listing publishes; the next update conditions on it, and it must
   * be accepted — quoted or weak spellings of the same token included, which
   * is the tolerance the pass's own comparison has and no more.
   */
  @Test
  void theValueThePassAdoptedIsAcceptedAsThePrecondition() {
    givenStored();

    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, STORED_ETAG).status());
    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, "\"" + STORED_ETAG + "\"").status());
    assertEquals(200, writer.updateObject(endpoint, HREF, ICS, "W/\"" + STORED_ETAG + "\"").status());
    assertTrue(writer.updateObject(endpoint, HREF, ICS, "\"Ym1kYXZfMTIzXzQ1\"").preconditionFailed(),
               "a token from another channel is not decoded into this one");
    assertTrue(BlueMindImportWriter.sameVersion("\"a\"", "a"));
    assertFalse(BlueMindImportWriter.sameVersion("a", null));
  }

  /**
   * <b>Trap 5, at the door.</b> No CalDAV PUT and no CalDAV DELETE, whatever
   * the write shape.
   */
  @Test
  void noCalDavWriteIsEverIssued() {
    givenNothingStoredThenStored();
    writer.putObject(endpoint, HREF, ICS);
    givenStored();
    writer.updateObject(endpoint, HREF, ICS, STORED_ETAG);
    writer.overwriteObject(endpoint, HREF, ICS);
    writer.deleteObject(endpoint, HREF, STORED_ETAG);
    writer.deleteObject(endpoint, HREF, null);

    verify(calDavClient, never()).putObject(any(), anyString(), anyString());
    verify(calDavClient, never()).updateObject(any(), anyString(), anyString(), anyString());
    verify(calDavClient, never()).overwriteObject(any(), anyString(), anyString());
    verify(calDavClient, never()).deleteObject(any(), anyString(), any());
    verify(importClient, org.mockito.Mockito.times(2)).deleteEvent(endpoint, CONTAINER, "evt-1");
  }

  /**
   * Create-only, emulated: an object the REPORT already lists is answered 412
   * with its version and nothing is imported.
   */
  @Test
  void aCreateOverAnExistingObjectIsRefusedLikeIfNoneMatch() {
    givenStored();

    PutResult result = writer.putObject(endpoint, HREF, ICS);

    assertTrue(result.preconditionFailed());
    assertEquals(STORED_ETAG, result.etag());
    verify(importClient, never()).importIcs(any(), anyString(), anyString());
  }

  /**
   * Replace-if-unchanged, emulated: another version, or no object, is 412; the
   * version read is what the update was conditioned on and nothing is
   * imported. A blank precondition is refused as the CalDAV client refuses it.
   */
  @Test
  void anUpdateOverAMovedOrMissingObjectIsRefusedLikeIfMatch() {
    givenStored();
    assertTrue(writer.updateObject(endpoint, HREF, ICS, "\"older\"").preconditionFailed());
    givenNothingStored();
    PutResult gone = writer.updateObject(endpoint, HREF, ICS, STORED_ETAG);
    assertTrue(gone.preconditionFailed());
    assertNull(gone.etag());
    verify(importClient, never()).importIcs(any(), anyString(), anyString());
    assertThrows(IllegalArgumentException.class, () -> writer.updateObject(endpoint, HREF, ICS, " "));
  }

  /**
   * A conditional removal: gone is 404 and nothing is sent; another version
   * is 412 and nothing is sent; the same version is removed with the status
   * BlueMind's calendar API answered.
   */
  @Test
  void aConditionalRemovalIsEmulatedOnTheSameRead() {
    when(importClient.deleteEvent(endpoint, CONTAINER, "evt-1")).thenReturn(204);
    givenNothingStored();
    assertEquals(404, writer.deleteObject(endpoint, HREF, STORED_ETAG));
    givenStored();
    assertEquals(PutResult.PRECONDITION_FAILED, writer.deleteObject(endpoint, HREF, "\"older\""));
    verify(importClient, never()).deleteEvent(any(), anyString(), anyString());
    assertEquals(204, writer.deleteObject(endpoint, HREF, STORED_ETAG));
  }

  /**
   * <b>Trap 3, at the door.</b> A task that ended without the series among
   * what it applied — the unhandled shape, or a document the importer could
   * not read — is a failed write, never a recorded version.
   */
  @Test
  void aSeriesTheImporterDidNotApplyIsAFailedWrite() {
    givenNothingStored();
    when(importClient.importIcs(any(), anyString(), anyString())).thenReturn(new ImportReport(List.of(), 1));

    CalDavException refused = assertThrows(CalDavException.class, () -> writer.putObject(endpoint, HREF, ICS));

    assertTrue(refused.getMessage().contains("did not import evt-1"), refused.getMessage());
  }

  /**
   * An import the task reports applied but that the REPORT does not list
   * afterwards is a failed write too: eXo records a version only for an object
   * it has seen at the href it will address later.
   */
  @Test
  void anImportTheServerDoesNotListAfterwardsIsAFailedWrite() {
    givenNothingStored();

    CalDavException refused = assertThrows(CalDavException.class, () -> writer.overwriteObject(endpoint, HREF, ICS));

    assertTrue(refused.getMessage().contains("lists nothing at " + HREF), refused.getMessage());
  }

  /**
   * The container is the collection's last segment decoded, and the item uid
   * the file name without {@code .ics} — BlueMind's own CalDAV rule
   * ({@code PutProtocol.java:83}) — whether the href arrives raw or encoded.
   */
  @Test
  void theContainerAndTheItemUidAreReadOffTheHref() {
    String encoded = "/dav/calendars/__uids__/" + OWNER_UID + "/calendar%3ADefault%3A" + OWNER_UID + "/evt%201.ics";

    assertEquals(CONTAINER, BlueMindImportWriter.containerUidOf(HREF));
    assertEquals(CONTAINER, BlueMindImportWriter.containerUidOf(encoded));
    assertEquals("evt-1", BlueMindImportWriter.uidOf(HREF));
    assertEquals("evt 1", BlueMindImportWriter.uidOf(encoded));
    assertEquals(COLLECTION, BlueMindImportWriter.collectionOf(HREF));
    assertEquals("evt-1", BlueMindImportWriter.uidInside(ICS));
  }

  /**
   * A long UID folded across lines is read whole, as the import reports it:
   * read off its first physical line it would never match, and that copy's
   * write would fail on every sweep.
   */
  @Test
  void aFoldedUidIsReadWhole() {
    String folded = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:040000008200E00074C5B7101A82E0080000000\r\n 0A1B2C3D4E5F@organiser.example.test\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

    assertEquals("040000008200E00074C5B7101A82E00800000000A1B2C3D4E5F@organiser.example.test",
                 BlueMindImportWriter.uidInside(folded));
    assertEquals("evt-1", BlueMindImportWriter.uidInside("BEGIN:VEVENT\nUID:evt-\n\t1\nEND:VEVENT"));
  }

  /**
   * The collection listing carries the object — and the container itself, as
   * BlueMind lists it — so the write finds it there afterwards.
   */
  private void givenStored() {
    when(calDavClient.listResourceEtags(endpoint, COLLECTION)).thenReturn(listingWith(HREF));
  }

  /**
   * The single-object channel affirms the object: the one-href multiget
   * answers it under the REPORT channel's version, and the {@code Depth: 0}
   * read answers the given token.
   *
   * @param depthZeroToken what the {@code Depth: 0} PROPFIND answers
   */
  private void givenPresent(String depthZeroToken) {
    when(calDavClient.multigetEtags(endpoint, COLLECTION, List.of(HREF))).thenReturn(Map.of(HREF, MULTIGET_ETAG));
    when(calDavClient.readEtag(endpoint, HREF)).thenReturn(depthZeroToken);
  }

  /**
   * The listing carries nothing at the href.
   */
  private void givenNothingStored() {
    when(calDavClient.listResourceEtags(endpoint, COLLECTION)).thenReturn(listingWith(null));
  }

  /**
   * Nothing before the import, the object after it.
   */
  private void givenNothingStoredThenStored() {
    when(calDavClient.listResourceEtags(endpoint, COLLECTION)).thenReturn(listingWith(null), listingWith(HREF));
  }

  /**
   * A BlueMind-shaped listing: the container with its own token, a neighbour
   * object, and optionally the object under test.
   *
   * @param href the object's href, or null to leave it out
   * @return the listing as {@code listResourceEtags} answers it
   */
  private static Map<String, String> listingWith(String href) {
    Map<String, String> listing = new java.util.LinkedHashMap<>();
    listing.put(COLLECTION, "bmdav_4158572241_1757971200000");
    listing.put(COLLECTION + "f909181a-04fc-486d-aa6a-136586320ae1.ics", "bmdav_2859517047_0");
    if (href != null) {
      listing.put(href, STORED_ETAG);
    }
    return listing;
  }
}

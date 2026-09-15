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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

  /** The version the collection listing publishes for the object: raw, constant (GetTag.java:46-56). */
  private static final String STORED_ETAG = "bmdav_851210693_0";

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
   * read back from the collection's {@code Depth: 1} listing — the very
   * channel the verification pass lists with and adopts from — verbatim, raw
   * token included; never from a {@code calendar-multiget} REPORT or a GET,
   * whose shapes the pass would overwrite on its next round (review F1).
   */
  @Test
  void theVersionIsReadBackFromTheCollectionListingAfterTheImport() {
    givenNothingStoredThenStored();

    PutResult result = writer.putObject(endpoint, HREF, ICS);

    assertEquals(201, result.status());
    assertEquals(STORED_ETAG, result.etag());
    verify(calDavClient, org.mockito.Mockito.times(2)).listResourceEtags(endpoint, COLLECTION);
    verify(calDavClient, never()).multiget(any(), anyString(), any());
    verify(calDavClient, never()).fetchObject(any(), anyString());
  }

  /**
   * <b>Review F1, the scenario itself.</b> After a pass, the row holds the
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
   * The collection listing carries the object — and the container itself, as
   * BlueMind lists it — so the write finds it there afterwards.
   */
  private void givenStored() {
    when(calDavClient.listResourceEtags(endpoint, COLLECTION)).thenReturn(listingWith(HREF));
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

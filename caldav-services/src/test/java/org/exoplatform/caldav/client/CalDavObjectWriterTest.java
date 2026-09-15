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
package org.exoplatform.caldav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * The CalDAV door changes nothing: each write is the client method of the
 * same name, the document passed through byte for byte — {@code LAST-MODIFIED}
 * included, which the other door takes out.
 */
public class CalDavObjectWriterTest {

  private static final String ICS = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:evt-1\r\nLAST-MODIFIED:20260901T080000Z\r\n"
      + "SUMMARY:Sprint review\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

  private final CalDavClient       client   = mock(CalDavClient.class);

  private final CalDavObjectWriter writer   = new CalDavObjectWriter(client);

  private final CalDavEndpoint     endpoint = TestEndpoints.endpoint(1L, URI.create("https://dav.example/dav/"), "personal", "john");

  /**
   * The four writes, each delegated once with the very same arguments and
   * the client's own answer handed back.
   */
  @Test
  void everyWriteIsTheClientsOwnWithTheDocumentVerbatim() {
    PutResult created = new PutResult(201, "\"v1\"", null);
    PutResult replaced = new PutResult(200, "\"v2\"", null);
    PutResult forced = new PutResult(204, null, null);
    when(client.putObject(endpoint, "/dav/cal/evt-1.ics", ICS)).thenReturn(created);
    when(client.updateObject(endpoint, "/dav/cal/evt-1.ics", ICS, "\"v1\"")).thenReturn(replaced);
    when(client.overwriteObject(endpoint, "/dav/cal/evt-1.ics", ICS)).thenReturn(forced);
    when(client.deleteObject(endpoint, "/dav/cal/evt-1.ics", "\"v2\"")).thenReturn(204);

    assertSame(created, writer.putObject(endpoint, "/dav/cal/evt-1.ics", ICS));
    assertSame(replaced, writer.updateObject(endpoint, "/dav/cal/evt-1.ics", ICS, "\"v1\""));
    assertSame(forced, writer.overwriteObject(endpoint, "/dav/cal/evt-1.ics", ICS));
    assertEquals(204, writer.deleteObject(endpoint, "/dav/cal/evt-1.ics", "\"v2\""));

    verify(client).putObject(endpoint, "/dav/cal/evt-1.ics", ICS);
    verify(client).updateObject(endpoint, "/dav/cal/evt-1.ics", ICS, "\"v1\"");
    verify(client).overwriteObject(endpoint, "/dav/cal/evt-1.ics", ICS);
    verify(client).deleteObject(endpoint, "/dav/cal/evt-1.ics", "\"v2\"");
    verifyNoMoreInteractions(client);
  }
}

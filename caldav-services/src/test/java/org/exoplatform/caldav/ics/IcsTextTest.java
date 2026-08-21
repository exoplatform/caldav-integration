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
package org.exoplatform.caldav.ics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import org.exoplatform.caldav.model.IcsReminder;

/**
 * The three conversions no library can make for us: what a fragment of HTML
 * means as plain text, what an agenda response means as a PARTSTAT, and what a
 * reminder means in minutes. Each has a wrong answer that would ship quietly —
 * markup leaking into a phone's calendar, an invalid token a strict reader
 * rejects, an alarm at the wrong hour — so each is pinned here rather than
 * left to the golden corpus, which only exercises the cases it happens to
 * contain.
 */
public class IcsTextTest {

  @Test
  public void htmlBecomesTextWithItsLineBreaksKept() {
    assertEquals("First line\nSecond line", IcsText.htmlToText("First line<br>Second line"));
    assertEquals("Paragraph one\n\nParagraph two", IcsText.htmlToText("<p>Paragraph one</p>\n<p>Paragraph two</p>"));
  }

  @Test
  public void htmlEntitiesResolveAfterTheMarkupIsGone() {
    // Unescaping first would turn an escaped angle bracket into a tag that the
    // tag removal then eats, losing the text it wrapped.
    assertEquals("a < b & c > d", IcsText.htmlToText("<p>a &lt; b &amp; c &gt; d</p>"));
  }

  @Test
  public void runsOfBlankLinesCollapse() {
    assertEquals("One\n\nTwo", IcsText.htmlToText("One<br><br><br><br>Two"));
  }

  @Test
  public void emptyDescriptionsAreEmpty() {
    assertEquals("", IcsText.htmlToText(null));
    assertEquals("", IcsText.htmlToText("   "));
  }

  @Test
  public void agendaResponsesMapOntoRfcTokens() {
    assertEquals("ACCEPTED", IcsText.partStat("ACCEPTED"));
    assertEquals("DECLINED", IcsText.partStat("declined"));
    assertEquals("TENTATIVE", IcsText.partStat("Tentative"));
  }

  @Test
  public void anUnknownResponseBecomesTheRfcDefault() {
    // Never an invalid token: a strict reader rejects the whole property, and
    // with it the attendee.
    assertEquals("NEEDS-ACTION", IcsText.partStat("NEEDS_ACTION"));
    assertEquals("NEEDS-ACTION", IcsText.partStat("whatever"));
    assertEquals("NEEDS-ACTION", IcsText.partStat(null));
  }

  @Test
  public void remindersConvertToMinutes() {
    assertEquals(10L, IcsText.reminderMinutes(new IcsReminder(10, "MINUTE")));
    assertEquals(120L, IcsText.reminderMinutes(new IcsReminder(2, "hour")));
    assertEquals(1440L, IcsText.reminderMinutes(new IcsReminder(1, "DAY")));
    assertEquals(10080L, IcsText.reminderMinutes(new IcsReminder(1, "week")));
  }

  @Test
  public void anUnknownUnitCountsAsMinutes() {
    assertEquals(5L, IcsText.reminderMinutes(new IcsReminder(5, null)));
    assertEquals(5L, IcsText.reminderMinutes(new IcsReminder(5, "fortnight")));
  }

  @Test
  public void anUnusableReminderIsNoAlarmAtAll() {
    // Better silent than alarming at a time nobody asked for.
    assertNull(IcsText.reminderMinutes(new IcsReminder(-1, "MINUTE")));
    assertNull(IcsText.reminderMinutes(null));
  }

  @Test
  public void bothShapesAgendaSuppliesParseAsInstants() {
    assertEquals(Instant.parse("2026-09-08T09:00:00Z"), IcsText.parseInstant("2026-09-08T09:00:00.000Z"));
    assertEquals(Instant.parse("2026-09-08T00:00:00Z"), IcsText.parseInstant("2026-09-08"));
    assertNull(IcsText.parseInstant("not a date"));
    assertNull(IcsText.parseInstant(null));
  }
}

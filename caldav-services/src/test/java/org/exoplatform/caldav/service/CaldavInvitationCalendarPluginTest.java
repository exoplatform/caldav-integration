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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;

import org.exoplatform.agenda.constant.EventAttendeeResponse;
import org.exoplatform.caldav.model.HeldMailInvitation;
import org.exoplatform.caldav.model.LandedMailInvitation;
import org.exoplatform.caldav.model.MailInvitation;
import org.exoplatform.emailConnector.model.HeldInvitation;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.emailConnector.model.InvitationProbe;
import org.exoplatform.emailConnector.model.LandedInvitation;
import org.exoplatform.emailConnector.plugin.InvitationCalendarPlugin;

/**
 * The bean the mail reader finds across add-ons: a named, non-final
 * {@code @Service} conditional on the reader's SPI class, translating the
 * reader's records into this add-on's and back, and carrying the call, nothing
 * else (EXO-90848).
 */
@ExtendWith(MockitoExtension.class)
public class CaldavInvitationCalendarPluginTest {

  private static final String            ICS        = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n";

  private static final InvitationLanding ANSWERED   = new InvitationLanding("john",
                                                                            "john@acme.com",
                                                                            "REQUEST",
                                                                            "weekly-sync@google.com",
                                                                            null,
                                                                            2,
                                                                            InvitationAnswer.TENTATIVE,
                                                                            ICS);

  private static final InvitationLanding ADDED      = new InvitationLanding("john",
                                                                            "john@acme.com",
                                                                            "PUBLISH",
                                                                            "weekly-sync@google.com",
                                                                            null,
                                                                            0,
                                                                            null,
                                                                            ICS);

  private static final MailInvitation    INVITATION = new MailInvitation("john",
                                                                         "john@acme.com",
                                                                         "REQUEST",
                                                                         "weekly-sync@google.com",
                                                                         2,
                                                                         EventAttendeeResponse.TENTATIVE,
                                                                         ICS);

  private static final MailInvitation    PUBLISHED  = new MailInvitation("john",
                                                                         "john@acme.com",
                                                                         "PUBLISH",
                                                                         "weekly-sync@google.com",
                                                                         0,
                                                                         null,
                                                                         ICS);

  @Mock
  private CaldavInvitationLandingService caldavInvitationLandingService;

  @InjectMocks
  private CaldavInvitationCalendarPlugin plugin;

  /**
   * The reader's record becomes this add-on's, field for field — an answer
   * given or none — and what this add-on did becomes the reader's record; the
   * failure is the landing service's.
   */
  @Test
  public void theCallIsTranslatedBothWaysAndCarriedAsIs() {
    when(caldavInvitationLandingService.land(INVITATION)).thenReturn(new LandedMailInvitation(77L, "/portal/dw/agenda?eventId=77", false, false));
    LandedInvitation landed = plugin.land(ANSWERED);
    assertEquals(77L, landed.eventId());
    assertEquals("/portal/dw/agenda?eventId=77", landed.link());
    assertFalse(landed.removed());
    assertFalse(landed.alreadyHeld());

    when(caldavInvitationLandingService.land(PUBLISHED)).thenReturn(new LandedMailInvitation(78L, null, true, false));
    assertTrue(plugin.land(ADDED).removed());
    when(caldavInvitationLandingService.land(PUBLISHED)).thenReturn(new LandedMailInvitation(79L, "/portal/dw/agenda?eventId=79", false, true));
    assertTrue(plugin.land(ADDED).alreadyHeld());

    when(caldavInvitationLandingService.land(INVITATION)).thenReturn(null);
    assertNull(plugin.land(ANSWERED));

    when(caldavInvitationLandingService.land(INVITATION)).thenThrow(new IllegalStateException("refused"));
    assertThrows(IllegalStateException.class, () -> plugin.land(ANSWERED));

    when(caldavInvitationLandingService.holdsCalendarFor("john")).thenReturn(true);
    assertTrue(plugin.holdsCalendarFor("john"));
  }

  /**
   * The reader's question is handed over field for field, and the copy held
   * comes back with the answer by its name, NEEDS_ACTION told as none
   * (EXO-90873); a failure to read it is the landing service's.
   */
  @Test
  public void whatIsHeldIsTranslatedBothWays() {
    InvitationProbe probe = new InvitationProbe("john", "john@acme.com", "weekly-sync@google.com", null, "olivia@partner.example");
    when(caldavInvitationLandingService.held("john", "john@acme.com", "weekly-sync@google.com", null, "olivia@partner.example"))
                                                                                                                             .thenReturn(new HeldMailInvitation(77L,
                                                                                                                                                                "/portal/dw/agenda?eventId=77",
                                                                                                                                                                EventAttendeeResponse.DECLINED,
                                                                                                                                                                4));
    assertEquals(new HeldInvitation(77L, "/portal/dw/agenda?eventId=77", InvitationAnswer.DECLINED, 4), plugin.held(probe));

    when(caldavInvitationLandingService.held("john", "john@acme.com", "weekly-sync@google.com", null, "olivia@partner.example"))
                                                                                                                             .thenReturn(new HeldMailInvitation(77L, null, EventAttendeeResponse.NEEDS_ACTION, 0),
                                                                                                                                         new HeldMailInvitation(77L, null, null, 0),
                                                                                                                                         null);
    assertNull(plugin.held(probe).answer());
    assertNull(plugin.held(probe).answer());
    assertNull(plugin.held(probe));

    when(caldavInvitationLandingService.held("john", "john@acme.com", "weekly-sync@google.com", null, "olivia@partner.example"))
                                                                                                                             .thenThrow(new IllegalStateException("unreadable"));
    assertThrows(IllegalStateException.class, () -> plugin.held(probe));
  }

  /**
   * What the bridge exports and what keeps a server without the mail reader
   * booting: the annotations are the wiring, and a call-by-hand test cannot
   * see them go — this reads them. The mutant it kills is the annotation
   * removed, the name dropped, or the class made final (proxied by the bridge).
   */
  @Test
  public void theBeanIsExportedByNameAndConditionalOnTheReader() {
    Service service = CaldavInvitationCalendarPlugin.class.getAnnotation(Service.class);
    assertEquals("caldavInvitationCalendarPlugin", service == null ? null : service.value());
    ConditionalOnClass conditional = CaldavInvitationCalendarPlugin.class.getAnnotation(ConditionalOnClass.class);
    assertEquals(InvitationCalendarPlugin.class, conditional == null ? null : conditional.value()[0]);
    assertFalse(Modifier.isFinal(CaldavInvitationCalendarPlugin.class.getModifiers()));
  }

  /**
   * No bean but this one names a type of the reader's in a method signature:
   * Spring introspects every method of every bean it creates, and an
   * unconditional bean naming the reader's records would fail this add-on's
   * context on a server without the reader. Pinned on the landing service,
   * the one bean this plugin hands the call to.
   */
  @Test
  public void onlyTheConditionalBeanNamesTheReadersTypes() {
    for (Method method : CaldavInvitationLandingService.class.getDeclaredMethods()) {
      for (Class<?> parameter : method.getParameterTypes()) {
        assertFalse(parameter.getName().startsWith("org.exoplatform.emailConnector"),
                    method.getName() + " names " + parameter.getName());
      }
      assertFalse(method.getReturnType().getName().startsWith("org.exoplatform.emailConnector"), method.getName());
    }
    assertEquals(List.of(), List.of(CaldavInvitationLandingService.class.getAnnotationsByType(ConditionalOnClass.class)),
                 "unconditional on purpose: it must be safe to create without the reader");
  }
}

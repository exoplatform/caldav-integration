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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.emailConnector.plugin.InvitationCalendarPlugin;

/**
 * The bean the mail reader finds across add-ons: a named, non-final
 * {@code @Service} conditional on the reader's SPI class, carrying the call and
 * nothing else (EXO-90848).
 */
@ExtendWith(MockitoExtension.class)
public class CaldavInvitationCalendarPluginTest {

  private static final InvitationLanding LANDING = new InvitationLanding("john",
                                                                         "john@acme.com",
                                                                         "weekly-sync@google.com",
                                                                         null,
                                                                         2,
                                                                         InvitationAnswer.ACCEPTED,
                                                                         "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");

  @Mock
  private CaldavInvitationLandingService caldavInvitationLandingService;

  @InjectMocks
  private CaldavInvitationCalendarPlugin plugin;

  /**
   * The answer, and the failure, are the landing service's.
   */
  @Test
  public void theCallIsCarriedAsIs() {
    when(caldavInvitationLandingService.land(LANDING)).thenReturn(true);
    assertTrue(plugin.land(LANDING));

    when(caldavInvitationLandingService.land(LANDING)).thenReturn(false);
    assertFalse(plugin.land(LANDING));

    when(caldavInvitationLandingService.land(LANDING)).thenThrow(new IllegalStateException("refused"));
    assertThrows(IllegalStateException.class, () -> plugin.land(LANDING));
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
}

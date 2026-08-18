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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.caldav.storage.CaldavConnectorStorage;

/**
 * The mirror calendar href is the identity of the collection every pushed
 * meeting is written to, so an empty one is worth refusing before it reaches
 * storage: a setting saved blank cannot be told apart from an account that
 * never chose a mirror, and the connector would silently start writing to
 * whichever collection it found first.
 */
@ExtendWith(MockitoExtension.class)
public class CaldavConnectorServiceImplTest {

  private static final long              USER_IDENTITY_ID = 42L;

  @Mock
  private CaldavConnectorStorage         caldavConnectorStorage;

  @InjectMocks
  private CaldavConnectorServiceImpl     caldavConnectorService;

  /**
   * An href reaches storage as it was given, for the user it was given for.
   */
  @Test
  public void shouldSaveMirrorCalendarHref() {
    caldavConnectorService.saveMirrorCalendarHref("/dav/root/mirror/", USER_IDENTITY_ID);

    verify(caldavConnectorStorage).saveMirrorCalendarHref("/dav/root/mirror/", USER_IDENTITY_ID);
  }

  /**
   * A blank href is refused with the message code the REST layer turns into a
   * 400, and nothing is written.
   */
  @Test
  public void shouldRefuseBlankMirrorCalendarHref() {
    IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                                                  () -> caldavConnectorService.saveMirrorCalendarHref("   ", USER_IDENTITY_ID));
    assertEquals("caldav.mirrorCalendarHrefMandatory", blank.getMessage());

    IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                                                    () -> caldavConnectorService.saveMirrorCalendarHref(null, USER_IDENTITY_ID));
    assertEquals("caldav.mirrorCalendarHrefMandatory", missing.getMessage());

    verifyNoInteractions(caldavConnectorStorage);
  }
}

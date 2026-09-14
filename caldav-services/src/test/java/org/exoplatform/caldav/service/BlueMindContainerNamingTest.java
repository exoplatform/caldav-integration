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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Whose a BlueMind collection is, by the container uid it is named by
 * (EXO-90275).
 *
 * <p>
 * Fixtures, and what each one is:
 * <ul>
 * <li>{@code bluemind-propfind-home-depth1-with-owner.xml} — the hrefs of
 * the live capture of 2026-08-20 (the owner element is derived, and not read
 * here): the account's own home, main calendar {@code calendar:Default:<own
 * uid>}, a calendar of its own created over CalDAV under a bare uuid, its task
 * list, and the scheduling collections. Every one of them must stay the
 * account's own.</li>
 * <li>{@code bluemind-propfind-home-depth1-sharee-subscribed.xml} — DERIVED
 * from the live observation of 2026-09-13 (EXO-90234): the subscribed pool
 * vehicle {@code calendar:7E3AE6F3-…} beside the account's own calendars.</li>
 * <li>The rig shapes of 2026-09-14 (root's pool vehicle, a colleague's
 * main calendar under root's home) — taken from the rig database's recorded
 * hrefs.</li>
 * <li>DERIVED from BlueMind's source, never observed on the wire: the
 * {@code calendar:UserCreated:<owner uid>:<uuid>} shape
 * ({@code UserCalendarService#getUserCreatedCalendarUid}), the uid-less
 * {@code calendar:UserCreated:<uuid>} ({@code ICalendarUids#userCreatedCalendar}
 * given a bare seed), and a percent-encoded spelling.</li>
 * </ul>
 */
public class BlueMindContainerNamingTest {

  /** The account of both captured fixtures. */
  private static final String FIXTURE_PRINCIPAL = "/dav/principals/__uids__/9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13/";

  private static final String FIXTURE_HOME      = "/dav/calendars/__uids__/9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13/";

  /** Root on the rig: BlueMind account FRANCOIS. */
  private static final String ROOT_PRINCIPAL    = "/dav/principals/__uids__/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/";

  private static final String ROOT_HOME         = "/dav/calendars/__uids__/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/";

  /** The pool vehicle root subscribed to — rig calendar 16, pair 17. */
  private static final String POOL_VEHICLE      = ROOT_HOME + "calendar:7E3AE6F3-98DF-43D9-B071-AAB477AC2CD8/";

  /** Meyer's uid on the rig, whose home also lists the pool vehicle. */
  private static final String MEYER_UID         = "4C60FEDD-2B1A-4C3D-9E8F-1A2B3C4D5E6F";

  /**
   * Every href of the captured own listing is the account's own: its main
   * calendar names its own uid, and nothing else there has a container shape
   * that names anybody.
   */
  @Test
  public void everyCollectionOfTheCapturedOwnListingIsTheAccountsOwn() throws Exception {
    List<String> hrefs = hrefsOf("bluemind-propfind-home-depth1-with-owner.xml");

    assertTrue(hrefs.contains(FIXTURE_HOME + "calendar:Default:9F3C1A20-4D5E-4B7A-8C61-2E0D7A4B9C13/"), "the main calendar is there");
    assertTrue(hrefs.contains(FIXTURE_HOME + "3B8E5C71-06A4-4F92-9D18-5C7E1B2A64F0/"), "and a bare-uuid calendar of its own");
    for (String href : hrefs) {
      assertNull(BlueMindContainerNaming.subscriptionOf(href, FIXTURE_PRINCIPAL), href);
    }
  }

  /**
   * In the subscribed listing, the pool vehicle is a resource subscription
   * named by its own uid, and nothing else is one — the colleague's eXo
   * calendar is the deployment's witness to speak for, not this rule's.
   */
  @Test
  public void thePoolVehicleOfTheSubscribedListingIsAResourceAndNothingElseIs() throws Exception {
    List<String> subscriptions = new ArrayList<>();
    for (String href : hrefsOf("bluemind-propfind-home-depth1-sharee-subscribed.xml")) {
      BlueMindContainerNaming.Subscription subscription = BlueMindContainerNaming.subscriptionOf(href, FIXTURE_PRINCIPAL);
      if (subscription != null) {
        subscriptions.add(href);
        assertTrue(subscription.resource(), href);
        assertEquals("7E3AE6F3-5B2C-4D1E-9A8F-6C0B3D2E1F4A", subscription.ownerUid());
      }
    }
    assertEquals(List.of(FIXTURE_HOME + "calendar:7E3AE6F3-5B2C-4D1E-9A8F-6C0B3D2E1F4A/"), subscriptions);
  }

  /** Rig calendar 16: root's pool vehicle is a resource. */
  @Test
  public void theRigsPoolVehicleIsAResourceSubscription() {
    BlueMindContainerNaming.Subscription subscription = BlueMindContainerNaming.subscriptionOf(POOL_VEHICLE, ROOT_PRINCIPAL);

    assertNotNull(subscription);
    assertTrue(subscription.resource());
    assertEquals("7E3AE6F3-98DF-43D9-B071-AAB477AC2CD8", subscription.ownerUid());
  }

  /**
   * A colleague's main calendar under the user's home — the acceptance
   * shape of calendar 33 — is a person's, named by her uid; the user's own
   * main calendar is not a subscription, and neither is it when the server
   * spells the uid in another case.
   */
  @Test
  public void aColleaguesMainCalendarIsAPersonsAndTheUsersOwnIsNot() {
    BlueMindContainerNaming.Subscription colleague = BlueMindContainerNaming.subscriptionOf(ROOT_HOME + "calendar:Default:" + MEYER_UID
        + "/", ROOT_PRINCIPAL);

    assertNotNull(colleague);
    assertFalse(colleague.resource());
    assertEquals(MEYER_UID, colleague.ownerUid());
    assertNull(BlueMindContainerNaming.subscriptionOf(ROOT_HOME + "calendar:Default:751E6D1A-7FDB-49B2-B668-B569E9A5A42D/",
                                                      ROOT_PRINCIPAL));
    assertNull(BlueMindContainerNaming.subscriptionOf(ROOT_HOME + "calendar:default:751e6d1a-7fdb-49b2-b668-b569e9a5a42d/",
                                                      ROOT_PRINCIPAL),
               "compared as BlueMind uids are, without regard to case");
  }

  /**
   * DERIVED from BlueMind's source: a calendar a user created carries its
   * owner's uid, so a colleague's is a person's and the user's own is not; a
   * created calendar whose seed names nobody concludes nothing.
   */
  @Test
  public void aCreatedCalendarIsTheOwnerItNames() {
    BlueMindContainerNaming.Subscription colleague = BlueMindContainerNaming.subscriptionOf(ROOT_HOME + "calendar:UserCreated:"
        + MEYER_UID + ":0b9e2f5c-6a1d-4c7e-8f3a-2d4b6c8e0a1f/", ROOT_PRINCIPAL);

    assertNotNull(colleague);
    assertFalse(colleague.resource());
    assertEquals(MEYER_UID, colleague.ownerUid());
    assertNull(BlueMindContainerNaming.subscriptionOf(ROOT_HOME
        + "calendar:UserCreated:751E6D1A-7FDB-49B2-B668-B569E9A5A42D:0b9e2f5c-6a1d-4c7e-8f3a-2d4b6c8e0a1f/", ROOT_PRINCIPAL));
    assertNull(BlueMindContainerNaming.subscriptionOf(ROOT_HOME + "calendar:UserCreated:0b9e2f5c-6a1d-4c7e-8f3a-2d4b6c8e0a1f/",
                                                      ROOT_PRINCIPAL),
               "a bare seed names no owner");
  }

  /**
   * The shapes no rule reads: eXo's own slug, a bare uuid, a Stalwart path,
   * a task list, an empty container — and a percent-encoded spelling, which
   * is decoded like any other href before the rule reads it.
   */
  @Test
  public void shapesThatNameNobodyAreLeftAloneAndAnEncodedSpellingIsDecoded() {
    for (String href : List.of(ROOT_HOME + "exo-cal-959b5529-ea4c-4ae4-a793-a2c201c3af9f/",
                               ROOT_HOME + "3B8E5C71-06A4-4F92-9D18-5C7E1B2A64F0/",
                               "/dav/cal/alice%40stalwart.local/default/",
                               ROOT_HOME + "todolist:default_751E6D1A-7FDB-49B2-B668-B569E9A5A42D/",
                               ROOT_HOME + "calendar:/",
                               ROOT_HOME + "calendar:Default:/",
                               ROOT_HOME)) {
      assertNull(BlueMindContainerNaming.subscriptionOf(href, ROOT_PRINCIPAL), href);
    }
    BlueMindContainerNaming.Subscription encoded = BlueMindContainerNaming.subscriptionOf(ROOT_HOME + "calendar%3ADefault%3A" + MEYER_UID
        + "/", ROOT_PRINCIPAL);
    assertNotNull(encoded);
    assertEquals(MEYER_UID, encoded.ownerUid());
  }

  /**
   * The rule is read on BlueMind's shape only: a {@code calendar:} segment on
   * an account whose principal and home are not spelled {@code __uids__/<uid>},
   * or in a home that is not the account's own, names nobody.
   */
  @Test
  public void theRuleIsReadOnlyOnAnAccountOfBlueMindsShapeInItsOwnHome() {
    assertNull(BlueMindContainerNaming.subscriptionOf("/dav/calendars/john/calendar:room-1/", "/dav/principals/john/"),
               "an account of another shape");
    assertNull(BlueMindContainerNaming.subscriptionOf("/dav/calendars/users/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/calendar:room-1/",
                                                      ROOT_PRINCIPAL),
               "a home not spelled __uids__");
    assertNull(BlueMindContainerNaming.subscriptionOf("/dav/calendars/__uids__/" + MEYER_UID + "/calendar:room-1/", ROOT_PRINCIPAL),
               "another account's home");
    assertNull(BlueMindContainerNaming.subscriptionOf(POOL_VEHICLE, "/dav/principals/users/751E6D1A-7FDB-49B2-B668-B569E9A5A42D/"),
               "a principal not spelled __uids__");
    assertNotNull(BlueMindContainerNaming.subscriptionOf(POOL_VEHICLE, ROOT_PRINCIPAL), "the control");
  }

  /**
   * A server that names no principal switches the rule off: an owner that
   * cannot be compared is not evidence of a subscription.
   */
  @Test
  public void noPrincipalMeansNoSubscription() {
    assertNull(BlueMindContainerNaming.subscriptionOf(POOL_VEHICLE, null));
    assertNull(BlueMindContainerNaming.subscriptionOf(POOL_VEHICLE, " "));
  }

  /**
   * The owner's principal is the account's with the uid replaced — the
   * spelling BlueMind builds every principal in.
   */
  @Test
  public void theOwnersPrincipalIsTheAccountsWithTheUidReplaced() {
    assertEquals("/dav/principals/__uids__/" + MEYER_UID + "/", BlueMindContainerNaming.principalOf(ROOT_PRINCIPAL, MEYER_UID));
    assertEquals("/dav/principals/__uids__/" + MEYER_UID + "/",
                 BlueMindContainerNaming.principalOf("/dav/principals/__uids__/751E6D1A-7FDB-49B2-B668-B569E9A5A42D", MEYER_UID),
                 "whether or not the account's principal ends with a slash");
  }

  /**
   * The hrefs a captured multistatus holds, in order.
   *
   * @param name the transcript's file name
   * @return every {@code d:href} of it
   * @throws IOException when the transcript cannot be read
   */
  private List<String> hrefsOf(String name) throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream("caldav/transcripts/" + name)) {
      String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      Matcher matcher = Pattern.compile("<d:response><d:href>([^<]+)</d:href>").matcher(content);
      List<String> hrefs = new ArrayList<>();
      while (matcher.find()) {
        hrefs.add(matcher.group(1));
      }
      assertFalse(hrefs.isEmpty(), name + " holds responses");
      return hrefs;
    }
  }
}

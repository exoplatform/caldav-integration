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

import java.net.URI;
import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalDavNotFoundException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.bluemind.BlueMindRestSession.Answer;
import org.exoplatform.caldav.client.bluemind.BlueMindRestSession.Session;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Edits a colleague's own BlueMind subscriptions through BlueMind's REST API,
 * as that colleague (EXO-90277).
 *
 * <p>
 * <b>Why a subscription at all.</b> A {@code CS:share} writes an access entry
 * and nothing else ({@code SharingProtocol.java}); BlueMind's DAV home lists
 * <em>subscriptions</em>, not readable containers ({@code DavStore.java},
 * {@code getCalendarDavResource}), and so does its webmail, whose "Add"
 * modal subscribes a readable-but-unsubscribed container with
 * {@code offlineSync=true} ({@code SubscribeOtherContainersModal.vue}). So a
 * calendar shared from eXo is invisible to the colleague — in eXo and in
 * BlueMind — until somebody subscribes them, and this client does what their
 * "Add" click would.
 *
 * <p>
 * <b>The call.</b>
 * {@code POST /api/users/{domainUid}/subscriptions/{subject}/_subscribe} with a
 * JSON array of {@code {containerUid, offlineSync, automount}}
 * ({@code parent/user/net.bluemind.user.api/.../IUserSubscription.java},
 * {@code ContainerSubscription.java}); the reverse is {@code _unsubscribe}
 * with an array of container uids. The service checks
 * {@code ROLE_MANAGE_USER_SUBSCRIPTIONS} or {@code ROLE_SELF} on the subject
 * in the container's domain ({@code UserSubscriptionService.java}) — the
 * subject's own session, same domain — and no access check: subscribing is a
 * per-user display preference, readable or not. A subscription that already
 * exists is updated, not refused, so the call is idempotent. The answer is a
 * 200 with an empty body; a fault is {@code {errorCode, errorType, message}}
 * — 403 for {@code PERMISSION_DENIED}, and for an unknown container
 * {@code NOT_FOUND} ({@code subscriptionToContainer}), which
 * {@code ResponseBuilder.replyServerFault} sends as a 500 at BlueMind master
 * and which this client also accepts as a 404, the shape an earlier reading
 * predicted; a live capture settles it, and both are the same absence here.
 *
 * <p>
 * <b>Whose session, and the one check that guards it.</b> The session is
 * opened with the <em>colleague's</em> stored credentials — the endpoint is
 * minted for their eXo login — because only they may edit their
 * subscriptions. The {@code {domainUid}} in the path is not stored anywhere in
 * eXo; it comes from the login answer, as does {@code authUser.uid}, and the
 * client refuses <em>before any POST</em> when that uid is not the one it was
 * asked to edit ({@link BlueMindSubjectMismatchException}): a principal
 * recorded from a stale discovery, or a colleague reconnected as somebody
 * else, must never make eXo edit another account with a password it holds.
 * The session is still closed by {@link BlueMindRestSession#call}.
 *
 * <p>
 * Same server, the colleague's credentials from storage, one session per job,
 * nothing secret in a message: {@link BlueMindRestSession}'s rules, shared
 * with every other REST conversation this add-on holds with BlueMind.
 */
@Component
public class BlueMindSubscriptionClient {

  /**
   * Whether the subscribed calendar also synchronises to the colleague's
   * devices: BlueMind's own webmail default, and the PO's choice — the
   * calendar reaches their phone as a hand subscription would.
   */
  public static final boolean OFFLINE_SYNC = true;

  /** Whether the subscription is mounted in the colleague's views at once. */
  public static final boolean AUTOMOUNT    = true;

  private static final Log    LOG          = ExoLogger.getLogger(BlueMindSubscriptionClient.class);

  private final BlueMindRestSession session;

  private final JsonMapper          mapper = JsonMapper.builder().build();

  /**
   * The edits one open session, checked to be the colleague's own, can make.
   */
  public interface Subscriptions {

    /**
     * Subscribes the colleague to a container, with {@link #OFFLINE_SYNC} and
     * {@link #AUTOMOUNT}. A no-op on the server when they already are.
     *
     * @param containerUid the container uid, the last segment of the
     *          calendar collection's path
     * @throws CalDavAuthenticationException when BlueMind refuses the session
     * @throws CalDavForbiddenException when BlueMind refuses the edit
     * @throws CalDavNotFoundException when BlueMind does not hold the container
     * @throws CalDavUnreachableException when the server cannot be reached
     * @throws CalDavException when the answer is anything else
     */
    void subscribe(String containerUid);

    /**
     * Unsubscribes the colleague from a container. BlueMind removes the
     * subscription row whether or not the container still exists
     * ({@code UserSubscriptionService.unsubscribe}: "unsub anyway").
     *
     * @param containerUid the container uid
     * @throws CalDavAuthenticationException when BlueMind refuses the session
     * @throws CalDavForbiddenException when BlueMind refuses the edit
     * @throws CalDavNotFoundException when BlueMind answers not found
     * @throws CalDavUnreachableException when the server cannot be reached
     * @throws CalDavException when the answer is anything else
     */
    void unsubscribe(String containerUid);
  }

  /**
   * The client Spring builds, over the shared session.
   *
   * @param session the REST session every BlueMind conversation shares
   */
  @Autowired
  public BlueMindSubscriptionClient(BlueMindRestSession session) {
    this.session = session;
  }

  /**
   * The seam the tests use: a session over a handed-in transport.
   *
   * @param httpClient the transport
   * @param caldavCredentialsResolver the credentials seam
   */
  BlueMindSubscriptionClient(HttpClient httpClient, CaldavCredentialsResolver caldavCredentialsResolver) {
    this(new BlueMindRestSession(httpClient, caldavCredentialsResolver));
  }

  /**
   * Runs edits of one colleague's subscriptions in one session opened as
   * them, after checking the session is theirs.
   *
   * <p>
   * One session for several edits is what the drain uses: a colleague owed
   * three subscriptions costs one login, not three.
   *
   * @param <T> what the job produces
   * @param shareeEndpoint the colleague's DAV endpoint, minted from the
   *          registry for <em>their</em> eXo login
   * @param shareeUid the directory entry uid eXo recorded for them, the
   *          segment of their principal
   * @param job the edits to make
   * @return what the job produced
   * @throws IllegalArgumentException when the uid is blank
   * @throws UnsupportedOperationException when the colleague's configured
   *           credentials are not a login and password
   * @throws CalDavAuthenticationException when BlueMind refuses the login
   * @throws BlueMindSubjectMismatchException when BlueMind authenticated
   *           somebody other than the recorded uid; nothing was sent
   * @throws CalDavUnreachableException when the server cannot be reached
   * @throws CalDavException when the login answer names no domain, or a call
   *           answers anything else
   */
  public <T> T asSharee(CalDavEndpoint shareeEndpoint, String shareeUid, Function<Subscriptions, T> job) {
    if (StringUtils.isBlank(shareeUid)) {
      throw new IllegalArgumentException("The sharee's directory entry uid is required");
    }
    return session.call(shareeEndpoint, open -> {
      if (!shareeUid.equals(open.userUid())) {
        throw new BlueMindSubjectMismatchException("The calendar server authenticated the session as entry "
            + StringUtils.defaultString(open.userUid(), "(none)") + ", not as the recorded entry " + shareeUid
            + "; its subscriptions are not edited");
      }
      if (StringUtils.isBlank(open.domainUid())) {
        throw new CalDavException("The calendar server named no domain for the session of entry " + shareeUid
            + "; its subscriptions are not edited");
      }
      String base = "/api/users/" + BlueMindRestSession.encodeSegment(open.domainUid()) + "/subscriptions/"
          + BlueMindRestSession.encodeSegment(shareeUid);
      return job.apply(new Subscriptions() {

        /**
         * {@inheritDoc}
         */
        @Override
        public void subscribe(String containerUid) {
          requireContainer(containerUid);
          String body = mapper.writeValueAsString(List.of(Map.of("containerUid",
                                                                 containerUid,
                                                                 "offlineSync",
                                                                 OFFLINE_SYNC,
                                                                 "automount",
                                                                 AUTOMOUNT)));
          post(open, base + "/_subscribe", body);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void unsubscribe(String containerUid) {
          requireContainer(containerUid);
          post(open, base + "/_unsubscribe", mapper.writeValueAsString(List.of(containerUid)));
        }
      });
    });
  }

  /**
   * Subscribes one colleague to one container, in a session of its own.
   *
   * @param shareeEndpoint the colleague's DAV endpoint, minted for their eXo
   *          login
   * @param shareeUid the directory entry uid eXo recorded for them
   * @param containerUid the container uid
   * @throws CalDavException as {@link #asSharee} and
   *           {@link Subscriptions#subscribe} throw
   */
  public void subscribe(CalDavEndpoint shareeEndpoint, String shareeUid, String containerUid) {
    requireContainer(containerUid);
    asSharee(shareeEndpoint, shareeUid, edits -> {
      edits.subscribe(containerUid);
      return null;
    });
  }

  /**
   * Unsubscribes one colleague from one container, in a session of its own.
   *
   * @param shareeEndpoint the colleague's DAV endpoint, minted for their eXo
   *          login
   * @param shareeUid the directory entry uid eXo recorded for them
   * @param containerUid the container uid
   * @throws CalDavException as {@link #asSharee} and
   *           {@link Subscriptions#unsubscribe} throw
   */
  public void unsubscribe(CalDavEndpoint shareeEndpoint, String shareeUid, String containerUid) {
    requireContainer(containerUid);
    asSharee(shareeEndpoint, shareeUid, edits -> {
      edits.unsubscribe(containerUid);
      return null;
    });
  }

  /**
   * Whose each calendar in an account's view is, read as the account itself
   * (EXO-90347).
   *
   * <p>
   * {@code GET /api/users/{domainUid}/subscriptions/{uid}?type=calendar}
   * ({@code IUserSubscription.listSubscriptions}), in a session opened with
   * the <em>account's</em> stored credentials, addressed to the uid BlueMind
   * authenticated that session as — never a uid a caller names, so the
   * listing read is by construction the account's own. The answer is a
   * JSON array of {@code ContainerSubscriptionDescriptor}, of which two
   * members are read: {@code containerUid} and {@code owner}; an entry
   * lacking either is skipped. Captured live on 2026-09-16
   * ({@code bluemind-rest-subscriptions-after-subscribe.captured.json}): the
   * account's own calendars carry its uid as owner, a colleague's shared
   * calendar carries the colleague's, a pool vehicle carries the resource's.
   *
   * <p>
   * The same session rules as every other call here: same server, the
   * account's credentials from storage, one session per call, closed
   * afterwards, nothing secret in a message.
   *
   * @param accountEndpoint the account's DAV endpoint, minted from the
   *          registry for the user's eXo login
   * @return the authenticated uid and the owner of every listed calendar
   * @throws UnsupportedOperationException when the account's configured
   *           credentials are not a login and password
   * @throws CalDavAuthenticationException when BlueMind refuses the login or
   *           the session
   * @throws CalDavForbiddenException when BlueMind refuses the listing
   * @throws CalDavUnreachableException when the server cannot be reached
   * @throws CalDavException when the login names no uid or no domain, or the
   *           answer is anything but a JSON array
   */
  public BlueMindCalendarOwners ownersOf(CalDavEndpoint accountEndpoint) {
    return session.call(accountEndpoint, open -> {
      if (StringUtils.isBlank(open.userUid()) || StringUtils.isBlank(open.domainUid())) {
        throw new CalDavException("The calendar server named no entry or no domain for the account's session; its calendar"
            + " owners are not read");
      }
      // Read once, before the request, and answered from that one reading: a
      // call that meets a refused session opens another one mid-flight
      // (EXO-90397), so asking the session again afterwards could name an
      // entry other than the one whose subscriptions were actually asked for.
      String entryUid = open.userUid();
      String path = "/api/users/" + BlueMindRestSession.encodeSegment(open.domainUid()) + "/subscriptions/"
          + BlueMindRestSession.encodeSegment(entryUid) + "?type=calendar";
      URI named = open.named(path);
      Answer answer = open.get(path);
      int status = answer.status();
      if (status == 401) {
        throw new CalDavAuthenticationException("The calendar server refused the session for GET " + named);
      }
      if (status == 403) {
        throw new CalDavForbiddenException("The calendar server refused GET " + named + " (403); this account may not read"
            + " its subscriptions");
      }
      if (status < 200 || status >= 300) {
        String code = faultCode(answer, named);
        throw new CalDavException("The calendar server answered " + status + (code == null ? "" : " (" + code + ")") + " for GET "
            + named);
      }
      JsonNode listing = session.parse(answer.body(), named);
      if (!listing.isArray()) {
        throw new CalDavException("The calendar server answered something that is not a subscription list for GET " + named);
      }
      Map<String, String> owners = new HashMap<>();
      for (JsonNode entry : listing) {
        String containerUid = BlueMindRestSession.textOf(entry, "containerUid");
        String owner = BlueMindRestSession.textOf(entry, "owner");
        if (containerUid != null && owner != null) {
          owners.put(containerUid, owner);
        }
      }
      return new BlueMindCalendarOwners(entryUid, owners);
    });
  }

  /**
   * One POST of an edit, its answer read as BlueMind's service answers.
   *
   * @param open the session
   * @param path the path under the root
   * @param body the JSON body
   */
  private void post(Session open, String path, String body) {
    URI named = open.named(path);
    Answer answer = open.post(path, body, BlueMindRestSession.JSON_MEDIA_TYPE);
    int status = answer.status();
    if (status == 401) {
      throw new CalDavAuthenticationException("The calendar server refused the session for POST " + named);
    }
    if (status == 403) {
      throw new CalDavForbiddenException("The calendar server refused POST " + named + " (403); this account may not edit"
          + " these subscriptions");
    }
    String code = status >= 200 && status < 300 ? null : faultCode(answer, named);
    if (status == 404 || (status == 500 && "NOT_FOUND".equals(code))) {
      throw new CalDavNotFoundException("The calendar server does not hold what POST " + named + " addressed (" + status
          + (code == null ? "" : " " + code) + ")");
    }
    if (status < 200 || status >= 300) {
      throw new CalDavException("The calendar server answered " + status + (code == null ? "" : " (" + code + ")") + " for POST "
          + named);
    }
  }

  /**
   * The {@code errorCode} of a fault body, when the body is one.
   *
   * <p>
   * <b>The code, and nothing else of the body.</b> A fault also carries a
   * {@code message}, which is free text BlueMind chose — and the rule this
   * client keeps, that no secret reaches a line, cannot be a rule here if
   * what it covers is decided by another vendor's string. The code is a
   * closed vocabulary ({@code ErrorCode}), so it is the part that can be both
   * useful and safe; the whole body remains available to an operator through
   * a capture, which is where a fault text belongs. The same rule already
   * governed the exception messages this method feeds.
   *
   * <p>
   * Stated for <em>this</em> client deliberately: {@code BlueMindCalendarImportClient}'s
   * own {@code faultCode} still logs BlueMind's text, and argues for it in
   * its comment. Narrowing that one is a separate call — this client is the
   * one that authenticates as somebody else, which is why it takes the
   * stricter reading of the same rule.
   *
   * @param answer the answer
   * @param named the request, for the debug line
   * @return the code, or null when the body is not a fault
   */
  private String faultCode(Answer answer, URI named) {
    if (StringUtils.isBlank(answer.body())) {
      return null;
    }
    try {
      JsonNode fault = session.parse(answer.body(), named);
      String code = BlueMindRestSession.textOf(fault, "errorCode");
      if (code != null) {
        LOG.debug("The calendar server answered {} for {}: {}", answer.status(), named, code);
      }
      return code;
    } catch (CalDavException e) {
      return null;
    }
  }

  /**
   * Refuses a blank container uid before anything is sent.
   *
   * @param containerUid the uid
   */
  private static void requireContainer(String containerUid) {
    if (StringUtils.isBlank(containerUid)) {
      throw new IllegalArgumentException("A container uid is required");
    }
  }
}

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
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavForbiddenException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.bluemind.BlueMindRestSession.Answer;
import org.exoplatform.caldav.provider.CaldavCredentialsResolver;

/**
 * Reads the access control list of a BlueMind calendar through BlueMind's own
 * REST API — the one place its sharing state can be read (EXO-90253).
 *
 * <p>
 * BlueMind's DAV server answers a {@code CS:share} with 200 whatever it did
 * and publishes no sharee list over DAV ({@code CS:invite} is a stub:
 * {@code plugins/net.bluemind.dav.server/.../proto/props/calendarserver/Invite.java}),
 * so the only proof that a grant was applied is the container's access list,
 * {@code GET /api/containers/_manage/{containerUid}/_acl}
 * ({@code parent/core/net.bluemind.core.container.api/.../IContainerManagement.java}),
 * which the container's owner may read
 * ({@code ContainerPermissionResolver.java}: the owner holds every verb).
 *
 * <p>
 * The session — same server, the owner's stored credentials, log in, read,
 * always log out — is {@link BlueMindRestSession}'s, shared with every other
 * REST conversation this add-on holds with BlueMind.
 */
@Component
public class BlueMindAclClient {

  /** The header BlueMind's REST API reads a session key from. */
  public static final String API_KEY_HEADER = BlueMindRestSession.API_KEY_HEADER;

  private final BlueMindRestSession session;

  /**
   * One entry of a BlueMind container's access list.
   *
   * @param subject the directory entry uid it applies to — a user's uid is the
   *          segment of their DAV principal
   *          {@code /dav/principals/__uids__/<uid>/}
   * @param verb the BlueMind verb, {@code Read}, {@code Write}, {@code Manage}…
   */
  public record BlueMindAce(String subject, String verb) {
  }

  /**
   * The client Spring builds, over the shared session.
   *
   * @param session the REST session every BlueMind conversation shares
   */
  @Autowired
  public BlueMindAclClient(BlueMindRestSession session) {
    this.session = session;
  }

  /**
   * The seam the tests use: a session over a handed-in transport.
   *
   * @param httpClient the transport
   * @param caldavCredentialsResolver the credentials seam
   */
  BlueMindAclClient(HttpClient httpClient, CaldavCredentialsResolver caldavCredentialsResolver) {
    this(new BlueMindRestSession(httpClient, caldavCredentialsResolver));
  }

  /**
   * A container's access list, read as its owner in one short REST session.
   *
   * @param endpoint the owner's DAV endpoint, minted from the registry
   * @param containerUid the container uid, the last segment of the calendar
   *          collection's path
   * @return the entries, in the order BlueMind lists them
   * @throws UnsupportedOperationException when the configured credentials are
   *           not a login and password
   * @throws CalDavAuthenticationException when BlueMind refuses the login
   * @throws CalDavForbiddenException when the account may not read the list
   * @throws CalDavUnreachableException when the server cannot be reached
   * @throws CalDavException when the answer is anything else
   */
  public List<BlueMindAce> readAcl(CalDavEndpoint endpoint, String containerUid) {
    try (Session open = open(endpoint)) {
      return open.readAcl(containerUid);
    }
  }

  /**
   * Opens a REST session as the owner, for as many reads as one service call
   * needs; {@link Session#close()} logs it out. The exceptions are those of
   * {@link #readAcl(CalDavEndpoint, String)} for the login half.
   *
   * @param endpoint the owner's DAV endpoint, minted from the registry
   * @return the session, to close
   */
  public Session open(CalDavEndpoint endpoint) {
    return new Session(session.open(endpoint));
  }

  /**
   * One authenticated conversation with BlueMind's REST API, reading access
   * lists. Not a {@code record}: the key must not be printed.
   */
  public class Session implements AutoCloseable {

    private final BlueMindRestSession.Session rest;

    Session(BlueMindRestSession.Session rest) {
      this.rest = rest;
    }

    /**
     * A container's access list, read in this session.
     *
     * @param containerUid the container uid
     * @return the entries, in the order BlueMind lists them
     * @throws CalDavAuthenticationException when BlueMind no longer accepts the
     *           session
     * @throws CalDavForbiddenException when the account may not read the list
     * @throws CalDavUnreachableException when the server cannot be reached
     * @throws CalDavException when the answer is anything else
     */
    public List<BlueMindAce> readAcl(String containerUid) {
      if (StringUtils.isBlank(containerUid)) {
        throw new IllegalArgumentException("A container uid is required");
      }
      String path = "/api/containers/_manage/" + BlueMindRestSession.encodeSegment(containerUid) + "/_acl";
      URI uri = rest.named(path);
      Answer answer = rest.get(path);
      if (answer.status() == 401) {
        throw new CalDavAuthenticationException("The calendar server refused the session for GET " + uri);
      }
      if (answer.status() == 403) {
        throw new CalDavForbiddenException("The calendar server refused to list the access of " + uri);
      }
      if (answer.status() != 200) {
        throw new CalDavException("The calendar server answered " + answer.status() + " for GET " + uri);
      }
      return acesOf(answer.body(), uri);
    }

    /**
     * Logs the session out; a failure is only noted.
     */
    @Override
    public void close() {
      rest.close();
    }

    @Override
    public String toString() {
      return rest.toString();
    }
  }

  /**
   * Whether the owner's configured credentials are a login and password this
   * API can take, without calling the server: what decides whether sharing
   * on BlueMind is offered at all, so that a registration whose provider
   * produces a token never shows an action every click of which would be
   * refused.
   *
   * @param endpoint the owner's endpoint, minted from the registry
   * @return true when the provider produces a Basic login and password
   */
  public boolean acceptsCredentials(CalDavEndpoint endpoint) {
    return session.acceptsCredentials(endpoint);
  }

  /**
   * The access list in an answer: a JSON array of {@code subject} and
   * {@code verb}. Anything else is refused rather than read partly, since a
   * grant is confirmed on it.
   *
   * @param body the answer's body
   * @param uri the request, for the message
   * @return the entries
   */
  private List<BlueMindAce> acesOf(String body, URI uri) {
    JsonNode list = session.parse(body, uri);
    if (!list.isArray()) {
      throw new CalDavException("The calendar server answered no access list for GET " + uri);
    }
    List<BlueMindAce> aces = new ArrayList<>();
    for (JsonNode entry : list) {
      String subject = BlueMindRestSession.textOf(entry, "subject");
      String verb = BlueMindRestSession.textOf(entry, "verb");
      if (StringUtils.isBlank(subject) || StringUtils.isBlank(verb)) {
        throw new CalDavException("The calendar server answered an access entry without subject or verb for GET " + uri);
      }
      aces.add(new BlueMindAce(subject, verb));
    }
    return aces;
  }
}

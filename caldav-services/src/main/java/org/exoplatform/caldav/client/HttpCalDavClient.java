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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.exoplatform.caldav.model.CalendarSync;
import org.exoplatform.caldav.model.SyncOrigin;
import org.exoplatform.caldav.service.CaldavServerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * CalDAV over the JDK's own HTTP client, modelled line for line on
 * email-connector's {@code HttpCardDavClient} — the hand-written client that
 * already works against the same BlueMind that defeated the browser — with
 * the disciplines the relay established carried over:
 * <ul>
 * <li><b>registry-only targets</b> — endpoints are minted from the server
 * registry alone ({@link #endpoint(Long, String)}), hrefs resolve against
 * that endpoint's authority, and a foreign-host href is refused;</li>
 * <li><b>no redirects</b> — a declared server must not be able to bounce
 * this client's credentialed requests to another host, so the transport
 * never follows a 3xx (which also rules the well-known discovery flow out
 * by design: discovery starts at the registered base path instead);</li>
 * <li><b>explicit timeouts and a bounded body read</b>, both
 * deployment-tunable;</li>
 * <li><b>no auth material anywhere near a log or an exception</b> — every
 * message names the method and the URI, never a header.</li>
 * </ul>
 * The XML is parsed with doctypes and external entities disabled: the
 * payload comes from whatever server an administrator declared, which is
 * still attacker-influenced input, and a stock parser would happily read
 * files off this machine on its behalf. And every property is read through
 * its <b>propstat status</b>: BlueMind answers 207 with failing propstats
 * where a naive client reads 2xx as success — the false-success class that
 * shipped a bug the week this client was designed.
 */
@Component
public class HttpCalDavClient implements CalDavClient {

  private static final Log            LOG                       = ExoLogger.getLogger(HttpCalDavClient.class);

  private static final String         DAV_NS                    = "DAV:";

  private static final String         CALDAV_NS                 = "urn:ietf:params:xml:ns:caldav";

  /** getctag is a CalendarServer extension, in its own namespace, not DAV's. */
  private static final String         CALENDARSERVER_NS         = "http://calendarserver.org/ns/";

  /** calendar-color is an Apple extension, in its own namespace too. */
  private static final String         APPLE_NS                  = "http://apple.com/ns/ical/";

  /** Element wrapping one resource inside a multistatus body. */
  private static final String         RESPONSE_ELEMENT          = "response";

  /** Element carrying an HTTP status line, at response or propstat level. */
  private static final String         STATUS_ELEMENT            = "status";

  /** Element carrying the RFC 6578 synchronisation token. */
  private static final String         SYNC_TOKEN_ELEMENT        = "sync-token";

  /** Collection-level change tag: a cheap "did anything move here" probe. */
  private static final String         GETCTAG_PROPERTY          = "getctag";

  /** Per-object entity tag, the conditional-write discipline rests on it. */
  private static final String         GETETAG_PROPERTY          = "getetag";

  /** Request header scoping how deep PROPFIND and REPORT descend. */
  private static final String         DEPTH_HEADER              = "Depth";

  /** Request header carrying the Basic credentials of the calendar account. */
  private static final String         AUTHORIZATION_HEADER      = "Authorization";

  /** Deployment property naming the connect timeout, in seconds. */
  private static final String         CONNECT_TIMEOUT_PROPERTY  = "exo.agenda.caldav.client.connectTimeoutSeconds";

  /** Deployment property naming the per-request timeout, in seconds. */
  private static final String         REQUEST_TIMEOUT_PROPERTY  = "exo.agenda.caldav.client.requestTimeoutSeconds";

  /** Deployment property capping response body sizes, in bytes. */
  private static final String         MAX_BODY_BYTES_PROPERTY   = "exo.agenda.caldav.client.maxBodyBytes";

  private static final int            DEFAULT_CONNECT_TIMEOUT   = 10;

  private static final int            DEFAULT_REQUEST_TIMEOUT   = 30;

  private static final long           DEFAULT_MAX_BODY_BYTES    = 20L * 1024 * 1024;

  /**
   * The DAV usernames this client will place into a URL path: the same set
   * the relay's probe accepts, so the two entry points refuse the same
   * inputs.
   */
  private static final Pattern        USERNAME_PATTERN          = Pattern.compile("[A-Za-z0-9._%+@\\-]+");

  /**
   * The characters a server-absolute DAV path may carry: the RFC 3986 path
   * character set, percent-encoding included — the relay's own gate,
   * applied here to every href before a URI is built, so no crafted href
   * can smuggle a different authority or a dot-segment.
   */
  private static final Pattern        SAFE_PATH_PATTERN         = Pattern.compile("/[A-Za-z0-9._~%!$&'()*+,;=:@/\\-]*");

  /** UTC timestamps in the shape a time-range filter requires. */
  private static final DateTimeFormatter TIME_RANGE_FORMAT      =
                                                            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                                                                             .withZone(ZoneOffset.UTC);

  private static final String         PROPFIND_PRINCIPAL        = """
      <?xml version="1.0" encoding="utf-8"?>
      <d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>""";

  private static final String         PROPFIND_HOME             = """
      <?xml version="1.0" encoding="utf-8"?>
      <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
        <d:prop><c:calendar-home-set/></d:prop>
      </d:propfind>""";

  /**
   * The full property set the sync engine binds a pair on — asked in one
   * PROPFIND whether listing a home or re-reading one collection, so both
   * paths answer the same {@link CalendarCollection}.
   */
  private static final String         PROPFIND_COLLECTION       = """
      <?xml version="1.0" encoding="utf-8"?>
      <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:a="http://apple.com/ns/ical/">
        <d:prop>
          <d:displayname/>
          <d:resourcetype/>
          <cs:getctag/>
          <d:sync-token/>
          <d:supported-report-set/>
          <c:supported-calendar-component-set/>
          <d:current-user-privilege-set/>
          <a:calendar-color/>
        </d:prop>
      </d:propfind>""";

  private static final String         PROPFIND_ETAGS            = """
      <?xml version="1.0" encoding="utf-8"?>
      <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>""";

  private static final String         PROPFIND_CAPABILITIES     = """
      <?xml version="1.0" encoding="utf-8"?>
      <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
        <d:prop><d:supported-report-set/><cs:getctag/><d:sync-token/></d:prop>
      </d:propfind>""";

  private final HttpClient            httpClient;

  private final CaldavServerService   caldavServerService;

  /**
   * The client Spring builds. Redirects are NEVER followed: the target host
   * comes from the registry, and a compromised or misconfigured registered
   * server must not be able to bounce credentialed requests anywhere else.
   *
   * @param caldavServerService the registry every endpoint is resolved from
   */
  @Autowired
  public HttpCalDavClient(CaldavServerService caldavServerService) {
    this(HttpClient.newBuilder()
                   .connectTimeout(Duration.ofSeconds(intProperty(CONNECT_TIMEOUT_PROPERTY, DEFAULT_CONNECT_TIMEOUT)))
                   .followRedirects(HttpClient.Redirect.NEVER)
                   .build(),
         caldavServerService);
  }

  /**
   * The seam the tests use: a client whose transport is handed in, so the
   * protocol can be exercised against canned answers without a server.
   *
   * @param httpClient the transport to send on
   * @param caldavServerService the registry every endpoint is resolved from
   */
  HttpCalDavClient(HttpClient httpClient, CaldavServerService caldavServerService) {
    this.httpClient = httpClient;
    this.caldavServerService = caldavServerService;
  }

  @Override
  public CalDavEndpoint endpoint(Long serverId, String davUsername) {
    String url = caldavServerService.resolveServerUrl(serverId);
    if (StringUtils.isBlank(url)) {
      // The registry seeds itself from this property at startup, so reading
      // it here only matters for a deployment whose seeding has not run yet;
      // it is the same admin-controlled fallback resolveServerUrl documents.
      url = System.getProperty(CaldavServerService.CALDAV_SERVER_URL_PROPERTY);
    }
    if (StringUtils.isBlank(url)) {
      throw new CalDavException("No CalDAV server is declared to talk to");
    }
    if (url.contains("{username}")) {
      if (StringUtils.isBlank(davUsername) || !USERNAME_PATTERN.matcher(davUsername).matches()) {
        throw new CalDavException("The CalDAV username cannot be part of a URL path");
      }
      url = url.replace("{username}", URLEncoder.encode(davUsername, StandardCharsets.UTF_8));
    }
    URI base = uri(url.trim());
    if (!Strings.CI.equalsAny(base.getScheme(), "http", "https") || StringUtils.isBlank(base.getHost())) {
      throw new CalDavException("The declared CalDAV server URL is not a usable http(s) URL");
    }
    return new CalDavEndpoint(serverId, base);
  }

  @Override
  public String discoverCalendarHome(CalDavEndpoint endpoint, String username, String password) {
    Element response = firstResponse(propfind(endpoint, endpoint.getBasePath(), PROPFIND_PRINCIPAL, "0", username, password));
    String principal = response == null ? null : hrefWithin(response, DAV_NS, "current-user-principal");
    if (StringUtils.isBlank(principal)) {
      throw new CalDavException("The server did not say who the current user is");
    }
    response = firstResponse(propfind(endpoint, asPath(endpoint, principal), PROPFIND_HOME, "0", username, password));
    String home = response == null ? null : hrefWithin(response, CALDAV_NS, "calendar-home-set");
    if (StringUtils.isBlank(home)) {
      throw new CalDavException("The server did not say where the calendars are");
    }
    return asPath(endpoint, home);
  }

  @Override
  public List<CalendarCollection> listCalendars(CalDavEndpoint endpoint, String homeHref, String username, String password) {
    Element multistatus = propfind(endpoint, homeHref, PROPFIND_COLLECTION, "1", username, password);
    List<CalendarCollection> calendars = new ArrayList<>();
    for (Element response : childElements(multistatus, DAV_NS, RESPONSE_ELEMENT)) {
      CalendarCollection calendar = toCalendar(endpoint, response);
      if (calendar != null) {
        calendars.add(calendar);
      }
    }
    return calendars;
  }

  @Override
  public CalendarCollection readCalendar(CalDavEndpoint endpoint, String href, String username, String password) {
    Element response = firstResponse(propfind(endpoint, href, PROPFIND_COLLECTION, "0", username, password));
    return response == null ? null : toCalendar(endpoint, response);
  }

  @Override
  public String getCtag(CalDavEndpoint endpoint, String href, String username, String password) {
    Element response = firstResponse(propfind(endpoint, href, PROPFIND_COLLECTION, "0", username, password));
    return response == null ? null : grantedText(response, CALENDARSERVER_NS, GETCTAG_PROPERTY);
  }

  @Override
  public Map<String, String> listResourceEtags(CalDavEndpoint endpoint,
                                               String collectionHref,
                                               String username,
                                               String password) {
    Element multistatus = propfind(endpoint, collectionHref, PROPFIND_ETAGS, "1", username, password);
    Map<String, String> etags = new LinkedHashMap<>();
    for (Element response : childElements(multistatus, DAV_NS, RESPONSE_ELEMENT)) {
      String href = responsePath(endpoint, response);
      String etag = grantedText(response, DAV_NS, GETETAG_PROPERTY);
      // The collection itself comes back in a Depth:1 listing and carries no
      // etag; so does any other member that is not a calendar object. Both
      // are skipped by that one rule rather than by guessing from the path.
      if (StringUtils.isNotBlank(href) && StringUtils.isNotBlank(etag)) {
        etags.put(href, etag);
      }
    }
    return etags;
  }

  @Override
  public List<CalendarObject> calendarQuery(CalDavEndpoint endpoint,
                                            String collectionHref,
                                            Instant start,
                                            Instant end,
                                            String username,
                                            String password) {
    StringBuilder timeRange = new StringBuilder();
    if (start != null || end != null) {
      timeRange.append("<c:time-range");
      if (start != null) {
        timeRange.append(" start=\"").append(TIME_RANGE_FORMAT.format(start)).append('"');
      }
      if (end != null) {
        timeRange.append(" end=\"").append(TIME_RANGE_FORMAT.format(end)).append('"');
      }
      timeRange.append("/>");
    }
    String body = """
        <?xml version="1.0" encoding="utf-8"?>
        <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
          <d:prop><d:getetag/><c:calendar-data/></d:prop>
          <c:filter>
            <c:comp-filter name="VCALENDAR">
              <c:comp-filter name="VEVENT">%s</c:comp-filter>
            </c:comp-filter>
          </c:filter>
        </c:calendar-query>""".formatted(timeRange);
    return readObjects(endpoint, report(endpoint, collectionHref, body, "1", username, password));
  }

  @Override
  public List<CalendarObject> multiget(CalDavEndpoint endpoint,
                                       String collectionHref,
                                       List<String> hrefs,
                                       String username,
                                       String password) {
    if (hrefs == null || hrefs.isEmpty()) {
      return List.of();
    }
    StringBuilder body = new StringBuilder("""
        <?xml version="1.0" encoding="utf-8"?>
        <c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
          <d:prop><d:getetag/><c:calendar-data/></d:prop>
        """);
    hrefs.forEach(href -> body.append("  <d:href>").append(escape(asPath(endpoint, href))).append("</d:href>\n"));
    body.append("</c:calendar-multiget>");
    return readObjects(endpoint, report(endpoint, collectionHref, body.toString(), "1", username, password));
  }

  @Override
  public SyncCollectionResult syncCollection(CalDavEndpoint endpoint,
                                             String collectionHref,
                                             String syncToken,
                                             String username,
                                             String password) {
    String body = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:sync-collection xmlns:d="DAV:">
          <d:sync-token>%s</d:sync-token>
          <d:sync-level>1</d:sync-level>
          <d:prop><d:getetag/></d:prop>
        </d:sync-collection>""".formatted(escape(StringUtils.defaultString(syncToken)));
    HttpRequest request = request(endpoint, collectionHref, "REPORT", body, username, password).header(DEPTH_HEADER, "0").build();
    DavResponse response = exchange(request);
    // Token invalidation is the routine tier-1 downgrade, answered before the
    // generic status policy so a 403 carrying the valid-sync-token
    // precondition is never misread as a credential refusal.
    if (response.status() == 507
        || (response.status() == 403 && Strings.CS.contains(response.body(), "valid-sync-token"))) {
      return SyncCollectionResult.invalidToken();
    }
    checkReadStatus(response, request);
    Element multistatus = parse(response.body(), request.uri());
    List<CalendarObject> changed = new ArrayList<>();
    List<String> deleted = new ArrayList<>();
    for (Element item : childElements(multistatus, DAV_NS, RESPONSE_ELEMENT)) {
      String href = responsePath(endpoint, item);
      if (StringUtils.isBlank(href)) {
        continue;
      }
      int itemStatus = directStatusOf(item);
      if (itemStatus == 404) {
        deleted.add(href);
      } else {
        String etag = grantedText(item, DAV_NS, GETETAG_PROPERTY);
        if (StringUtils.isNotBlank(etag)) {
          changed.add(new CalendarObject(href, etag, null));
        }
      }
    }
    String newToken = null;
    List<Element> tokens = childElements(multistatus, DAV_NS, SYNC_TOKEN_ELEMENT);
    if (!tokens.isEmpty()) {
      newToken = StringUtils.trimToNull(tokens.get(0).getTextContent());
    }
    return new SyncCollectionResult(true, newToken, changed, deleted);
  }

  @Override
  public ServerCapabilities probeCapabilities(CalDavEndpoint endpoint,
                                              String collectionHref,
                                              String username,
                                              String password) {
    HttpRequest request = request(endpoint,
                                  collectionHref,
                                  "PROPFIND",
                                  PROPFIND_CAPABILITIES,
                                  username,
                                  password).header(DEPTH_HEADER, "0").build();
    DavResponse response = exchange(request);
    checkReadStatus(response, request);
    Element multistatus = parse(response.body(), request.uri());
    Element first = firstResponse(multistatus);
    boolean multigetAdvertised = false;
    boolean queryAdvertised = false;
    boolean syncAdvertised = false;
    boolean hasCtag = false;
    boolean hasSyncToken = false;
    if (first != null) {
      for (Element prop : grantedProps(first)) {
        List<Element> reports = descendants(prop, DAV_NS, "supported-report-set");
        if (!reports.isEmpty()) {
          Element reportSet = reports.get(0);
          multigetAdvertised = !descendants(reportSet, CALDAV_NS, "calendar-multiget").isEmpty();
          queryAdvertised = !descendants(reportSet, CALDAV_NS, "calendar-query").isEmpty();
          syncAdvertised = !descendants(reportSet, DAV_NS, "sync-collection").isEmpty();
        }
        hasCtag = hasCtag || !descendants(prop, CALENDARSERVER_NS, GETCTAG_PROPERTY).isEmpty();
        hasSyncToken = hasSyncToken || StringUtils.isNotBlank(textOf(prop, DAV_NS, SYNC_TOKEN_ELEMENT));
      }
    }
    return new ServerCapabilities(syncAdvertised && hasSyncToken,
                                  multigetAdvertised,
                                  queryAdvertised,
                                  hasCtag,
                                  response.header("dav"));
  }

  @Override
  public CalendarObject fetchObject(CalDavEndpoint endpoint, String href, String username, String password) {
    HttpRequest request = HttpRequest.newBuilder(target(endpoint, href))
                                     .timeout(requestTimeout())
                                     .header(AUTHORIZATION_HEADER, basicAuth(username, password))
                                     .GET()
                                     .build();
    DavResponse response = exchange(request);
    int status = response.status();
    if (status == 404 || status == 410) {
      // The object is gone, which for a conflict re-read is a fact to
      // report — the engine reconciles the row — not a failure to log.
      return null;
    }
    if (status >= 500) {
      // BlueMind answers 500 — not 404 — for an .ics that is simply not
      // there, so treating a server error as fatal made every first push of
      // an event fail before a single byte was written. Reporting "absent"
      // is safe rather than optimistic: the creating write keeps its
      // If-None-Match: *, so if the object did exist after all, the server
      // answers 412 and the caller sees a conflict — the worst case is a
      // refused write, never a silently overwritten one.
      LOG.warn("The CalDAV server failed to say whether the object exists ({} for GET {}), treating it as absent",
               status,
               request.uri());
      return null;
    }
    checkAuthStatus(status, true, request);
    if (status != 200) {
      throw refusal(response.status(), request);
    }
    // The etag exactly as sent, quotes and all, like everywhere else in this
    // client: it goes straight back out as an If-Match, which only works
    // verbatim — RFC 9110 compares an If-Match strongly, so a tag this
    // client tidied up on the way in could let a conditional write through
    // that the server meant to refuse.
    return new CalendarObject(asPath(endpoint, href), response.header("etag"), response.body());
  }

  @Override
  public PutResult putObject(CalDavEndpoint endpoint, String href, String icsData, String username, String password) {
    return put(endpoint, href, icsData, "If-None-Match", "*", username, password);
  }

  @Override
  public PutResult updateObject(CalDavEndpoint endpoint,
                                String href,
                                String icsData,
                                String ifMatch,
                                String username,
                                String password) {
    if (StringUtils.isBlank(ifMatch)) {
      // Refused rather than sent unconditional: an unconditional PUT
      // overwrites silently, which is the exact class of loss the
      // conditional-write doctrine exists to make impossible.
      throw new IllegalArgumentException("An update needs the ETag its read answered; an unconditional overwrite is refused");
    }
    return put(endpoint, href, icsData, "If-Match", ifMatch, username, password);
  }

  @Override
  public int deleteObject(CalDavEndpoint endpoint, String href, String ifMatch, String username, String password) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(target(endpoint, href))
                                             .timeout(requestTimeout())
                                             .header(AUTHORIZATION_HEADER, basicAuth(username, password))
                                             .DELETE();
    if (StringUtils.isNotBlank(ifMatch)) {
      builder.header("If-Match", ifMatch);
    } else {
      LOG.debug("CalDAV DELETE sent without a precondition");
    }
    HttpRequest request = builder.build();
    DavResponse response = exchange(request);
    int status = response.status();
    checkAuthStatus(status, false, request);
    // 200/204 deleted; 404/410 already gone — absent is absent, a fact the
    // caller consumes, and what makes a retried delete idempotent; 412 the
    // precondition protecting somebody else's change.
    if (status != 200 && status != 204 && status != 404 && status != 410 && status != PutResult.PRECONDITION_FAILED) {
      throw refusal(status, request);
    }
    return status;
  }

  @Override
  public MkCalendarResult mkCalendar(CalDavEndpoint endpoint,
                                     String href,
                                     String displayName,
                                     String color,
                                     String username,
                                     String password) {
    // The component set is never optional: BlueMind derives the created
    // collection's KIND from the <c:comp> elements, and a request without
    // them fails that derivation internally, swallows the failure and still
    // answers 201 — claiming a creation that never happened (proven live
    // 2026-08-20: displayname-only body → 201 and the collection absent
    // from the next Depth:1 listing; the same body plus this property →
    // 201 and the collection listed). VEVENT alone on purpose: the mirror
    // carries meeting copies only, and BlueMind maps VTODO to a different
    // container kind (its accounts hold a separate todolist: collection),
    // so declaring both would leave the kind derivation ambiguous.
    StringBuilder props = new StringBuilder("<d:displayname>").append(escape(displayName)).append("</d:displayname>");
    props.append("<c:supported-calendar-component-set><c:comp name=\"VEVENT\"/></c:supported-calendar-component-set>");
    if (StringUtils.isNotBlank(color)) {
      props.append("<a:calendar-color>").append(escape(color)).append("</a:calendar-color>");
    }
    String body = """
        <?xml version="1.0" encoding="utf-8"?>
        <c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
          <d:set><d:prop>%s</d:prop></d:set>
        </c:mkcalendar>""".formatted(props);
    HttpRequest request = request(endpoint, href, "MKCALENDAR", body, username, password).build();
    DavResponse response = exchange(request);
    // 401/407 only: a 403 on this write verb IS the refusal — BlueMind
    // refuses MKCALENDAR outright with credentials that are perfectly fine,
    // and classifying that as an auth failure would pause the account.
    checkAuthStatus(response.status(), false, request);
    return new MkCalendarResult(response.status(), failedPropstatStatuses(response.body(), request.uri()));
  }

  /**
   * The one PUT both writes share; only the precondition header differs —
   * {@code If-None-Match: *} to insist on creating, {@code If-Match: etag}
   * to insist on replacing what was just read and nothing newer.
   *
   * @param endpoint the declared server
   * @param href the object's server-absolute path
   * @param icsData the iCalendar text to store
   * @param preconditionHeader which precondition header to send
   * @param preconditionValue its value, never blank here
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the status and, when the server sent them, the stored version
   *         and the object's real location
   */
  private PutResult put(CalDavEndpoint endpoint,
                        String href,
                        String icsData,
                        String preconditionHeader,
                        String preconditionValue,
                        String username,
                        String password) {
    HttpRequest request = HttpRequest.newBuilder(target(endpoint, href))
                                     .timeout(requestTimeout())
                                     // An iCalendar object, not DAV XML: request() is not reused
                                     // because its Content-Type belongs to PROPFIND/REPORT bodies,
                                     // and a server told an .ics is application/xml may refuse it.
                                     .header("Content-Type", "text/calendar; charset=utf-8")
                                     .header(AUTHORIZATION_HEADER, basicAuth(username, password))
                                     .header(preconditionHeader, preconditionValue)
                                     .method("PUT", BodyPublishers.ofString(icsData, StandardCharsets.UTF_8))
                                     .build();
    DavResponse response = exchange(request);
    int status = response.status();
    checkAuthStatus(status, false, request);
    // 201 is the created object, 204 and 200 are how some servers
    // acknowledge instead. 412 is the precondition refused — an answer the
    // caller must be able to tell apart from an error, because under
    // If-None-Match:* it means "already exists" and under If-Match it means
    // "somebody changed it first" — facts either way, not faults.
    if (status != 200 && status != 201 && status != 204 && status != PutResult.PRECONDITION_FAILED) {
      throw refusal(status, request);
    }
    // The etag exactly as sent, quotes and all: whatever this client hands
    // back may travel out again as a precondition, and a precondition only
    // means what the server said if it is byte for byte what the server
    // said. What it does NOT promise is that this shape matches the one a
    // PROPFIND listing answers for the same version — reconciling the two
    // is the sync engine's job, not this client's.
    //
    // The Location matters as much as the etag: BlueMind's CardDAV twin
    // stores objects under a path of its own choosing, and this header is
    // the only same-round-trip way to learn the entry's real name.
    String location = response.header("location");
    return new PutResult(status,
                         response.header("etag"),
                         location == null ? null : resolveLocation(request.uri(), location));
  }

  /**
   * Turns one multistatus response element into a calendar collection, or
   * null when its granted resourcetype does not say calendar — the type is
   * read, never guessed from the path.
   *
   * @param endpoint the declared server, for href resolution
   * @param response one response element
   * @return the collection, or null when this member is not a calendar
   */
  private CalendarCollection toCalendar(CalDavEndpoint endpoint, Element response) {
    boolean isCalendar = false;
    boolean writable = false;
    for (Element prop : grantedProps(response)) {
      List<Element> types = descendants(prop, DAV_NS, "resourcetype");
      if (!types.isEmpty() && !descendants(types.get(0), CALDAV_NS, "calendar").isEmpty()) {
        isCalendar = true;
      }
      List<Element> privileges = descendants(prop, DAV_NS, "current-user-privilege-set");
      if (!privileges.isEmpty()) {
        Element set = privileges.get(0);
        writable = !descendants(set, DAV_NS, "write").isEmpty() || !descendants(set, DAV_NS, "all").isEmpty()
            || !descendants(set, DAV_NS, "bind").isEmpty();
      }
    }
    if (!isCalendar) {
      return null;
    }
    return new CalendarCollection(responsePath(endpoint, response),
                                  grantedText(response, DAV_NS, "displayname"),
                                  grantedText(response, CALENDARSERVER_NS, GETCTAG_PROPERTY),
                                  grantedText(response, DAV_NS, SYNC_TOKEN_ELEMENT),
                                  grantedText(response, APPLE_NS, "calendar-color"),
                                  writable,
                                  supportedComponents(response));
  }

  /**
   * The component types a collection declares it holds.
   * <p>
   * Read from {@code supported-calendar-component-set}, whose {@code comp}
   * children name the types. An absent property yields an empty set, which
   * {@link CalendarCollection#holdsEvents()} reads as "the server did not say"
   * rather than "nothing" — the distinction RFC 4791 draws and the one that
   * decides whether a task list is mistaken for a calendar.
   *
   * @param response one multistatus response element
   * @return the declared component names, upper-cased; empty when undeclared
   */
  private Set<String> supportedComponents(Element response) {
    Set<String> components = new HashSet<>();
    for (Element prop : grantedProps(response)) {
      for (Element set : descendants(prop, CALDAV_NS, "supported-calendar-component-set")) {
        for (Element comp : descendants(set, CALDAV_NS, "comp")) {
          String name = comp.getAttribute("name");
          if (StringUtils.isNotBlank(name)) {
            components.add(name.trim().toUpperCase());
          }
        }
      }
    }
    return components;
  }

  /**
   * The objects of a multiget or calendar-query multistatus: href, granted
   * etag and granted calendar data per response.
   *
   * @param endpoint the declared server, for href resolution
   * @param multistatus the parsed REPORT answer
   * @return the objects that carried calendar data
   */
  private List<CalendarObject> readObjects(CalDavEndpoint endpoint, Element multistatus) {
    List<CalendarObject> objects = new ArrayList<>();
    for (Element response : childElements(multistatus, DAV_NS, RESPONSE_ELEMENT)) {
      String href = responsePath(endpoint, response);
      String data = grantedText(response, CALDAV_NS, "calendar-data");
      if (StringUtils.isNotBlank(href) && StringUtils.isNotBlank(data)) {
        objects.add(new CalendarObject(href, grantedText(response, DAV_NS, GETETAG_PROPERTY), data));
      }
    }
    return objects;
  }

  /**
   * Sends a PROPFIND and parses the multistatus it answers.
   *
   * @param endpoint the declared server
   * @param href the target's server-absolute path
   * @param body the request body
   * @param depth the Depth header value
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the multistatus element
   */
  private Element propfind(CalDavEndpoint endpoint, String href, String body, String depth, String username, String password) {
    HttpRequest request = request(endpoint, href, "PROPFIND", body, username, password).header(DEPTH_HEADER, depth).build();
    DavResponse response = exchange(request);
    checkReadStatus(response, request);
    return parse(response.body(), request.uri());
  }

  /**
   * Sends a REPORT and parses the multistatus it answers.
   *
   * @param endpoint the declared server
   * @param href the target's server-absolute path
   * @param body the request body
   * @param depth the Depth header value
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the multistatus element
   */
  private Element report(CalDavEndpoint endpoint, String href, String body, String depth, String username, String password) {
    HttpRequest request = request(endpoint, href, "REPORT", body, username, password).header(DEPTH_HEADER, depth).build();
    DavResponse response = exchange(request);
    checkReadStatus(response, request);
    return parse(response.body(), request.uri());
  }

  /**
   * Builds a request with the headers every DAV XML call needs.
   *
   * @param endpoint the declared server
   * @param href the target's server-absolute path
   * @param method PROPFIND, REPORT or MKCALENDAR
   * @param body the XML body
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the builder, so the caller can add its Depth
   */
  private HttpRequest.Builder request(CalDavEndpoint endpoint,
                                      String href,
                                      String method,
                                      String body,
                                      String username,
                                      String password) {
    return HttpRequest.newBuilder(target(endpoint, href))
                      .timeout(requestTimeout())
                      .header("Content-Type", "application/xml; charset=utf-8")
                      .header(AUTHORIZATION_HEADER, basicAuth(username, password))
                      .method(method, BodyPublishers.ofString(body, StandardCharsets.UTF_8));
  }

  /**
   * Where a request may actually go: the endpoint's own authority, always.
   * A server-absolute path is admitted through the same character gate as
   * the relay's; an absolute URL is admitted only when it names the
   * endpoint's host — anything else is refused before a socket is opened,
   * because following it would aim the user's credentials at a host no
   * administrator declared.
   *
   * @param endpoint the declared server
   * @param href the target's path, or an absolute URL on the same host
   * @return the URI to send to
   */
  private URI target(CalDavEndpoint endpoint, String href) {
    String path = StringUtils.defaultIfBlank(StringUtils.trimToNull(href), endpoint.getBasePath());
    if (Strings.CI.startsWith(path, "http")) {
      URI absolute = uri(path);
      if (!sameAuthority(absolute, endpoint.getBaseUri())) {
        throw new CalDavException("Refusing to address " + absolute.getHost() + ", which is not the declared CalDAV server");
      }
      path = StringUtils.defaultIfBlank(absolute.getRawPath(), "/")
          + (absolute.getRawQuery() == null ? "" : "?" + absolute.getRawQuery());
    }
    if (!SAFE_PATH_PATTERN.matcher(StringUtils.substringBefore(path, "?")).matches() || path.contains("..")) {
      throw new CalDavException("Not a usable DAV resource path");
    }
    URI base = endpoint.getBaseUri();
    return uri(base.getScheme() + "://" + base.getRawAuthority() + path);
  }

  /**
   * Sends a request and answers status, headers and a bounded body —
   * transport concerns only, no status policy, so each verb applies its
   * own accepted set on top.
   *
   * @param request the request to send
   * @return the response, body read up to the configured cap
   */
  private DavResponse exchange(HttpRequest request) {
    try {
      HttpResponse<InputStream> response = httpClient.send(request, BodyHandlers.ofInputStream());
      long max = longProperty(MAX_BODY_BYTES_PROPERTY, DEFAULT_MAX_BODY_BYTES);
      byte[] bytes;
      try (InputStream stream = response.body()) {
        bytes = stream.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
      }
      if (bytes.length > max) {
        throw new CalDavException(String.format("The calendar server answered more than %s bytes for %s %s",
                                                max,
                                                request.method(),
                                                request.uri()));
      }
      int status = response.statusCode();
      if (status >= 300 && status < 400) {
        // Never followed: a redirect from a declared server is how a
        // compromised registration would bounce credentialed requests to
        // another host. Surfaced as a plain failure naming the server.
        throw new CalDavException(String.format("The calendar server answered a redirect (%s) for %s %s, which this client never follows",
                                                status,
                                                request.method(),
                                                request.uri()));
      }
      return new DavResponse(status, response, new String(bytes, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new CalDavException("The calendar server could not be reached at " + request.uri(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CalDavException("Interrupted while talking to " + request.uri(), e);
    }
  }

  /**
   * The status policy of the read verbs: credentials classified first
   * (403 included — the BlueMind shape), then 207 Multi-Status as the
   * normal answer, 200 tolerated because some servers answer it for a
   * single-resource PROPFIND.
   *
   * @param response the exchanged response
   * @param request the request it answers, for the error message
   */
  private void checkReadStatus(DavResponse response, HttpRequest request) {
    int status = response.status();
    checkAuthStatus(status, true, request);
    if (status != 207 && status != 200) {
      throw refusal(status, request);
    }
  }

  /**
   * Classifies a credential refusal, per verb family: 401 and 407 always;
   * 403 only on the read verbs, where BlueMind is verified live to answer
   * it for refused Basic auth — on a write verb a 403 legitimately means
   * "this resource may not be written" and must stay a plain refusal.
   *
   * @param status the answered status
   * @param readVerb whether the request was PROPFIND, REPORT or GET
   * @param request the request, for the error message
   */
  private void checkAuthStatus(int status, boolean readVerb, HttpRequest request) {
    if (status == 401 || status == 407 || (readVerb && status == 403)) {
      throw new CalDavAuthenticationException(String.format("The calendar server refused the credentials (%s) for %s %s",
                                                            status,
                                                            request.method(),
                                                            request.uri()));
    }
  }

  /**
   * The one exception an unaccepted status becomes — method and URI only,
   * never a header, so no auth material can ever reach a log through it.
   *
   * @param status the answered status
   * @param request the request it answers
   * @return the exception to throw
   */
  private CalDavException refusal(int status, HttpRequest request) {
    return new CalDavException(String.format("The calendar server answered %s for %s %s",
                                             status,
                                             request.method(),
                                             request.uri()));
  }

  /**
   * Parses a DAV XML document. Doctypes and external entities are disabled:
   * this XML comes from a server an administrator declared, which is still
   * attacker-influenced input, and a stock parser would fetch whatever that
   * server's entities pointed at, including files on this machine.
   *
   * @param xml the response body
   * @param uri the URI it came from, for the error message
   * @return the document's root element
   */
  private Element parse(String xml, URI uri) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setNamespaceAware(true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      // A BOM or leading whitespace before the declaration is a shape real
      // servers do emit, and the parser refuses it; neither changes what the
      // document says.
      String document = StringUtils.stripStart(xml, "\uFEFF \t\r\n");
      return builder.parse(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new CalDavException("The calendar server answered something that is not CalDAV XML, from " + uri, e);
    }
  }

  /**
   * Every non-2xx status carried at propstat level in a body, for the
   * MKCALENDAR result. An unparseable or empty body simply contributes
   * none: at this layer the raw status plus these codes is the whole
   * answer, and deciding what they mean is the caller's read-back to make.
   *
   * @param body the response body, possibly empty or not XML
   * @param uri the URI it came from, for the debug log
   * @return the failing propstat statuses, possibly empty
   */
  private List<Integer> failedPropstatStatuses(String body, URI uri) {
    List<Integer> failed = new ArrayList<>();
    if (StringUtils.isBlank(body)) {
      return failed;
    }
    Element root;
    try {
      root = parse(body, uri);
    } catch (CalDavException e) {
      LOG.debug("MKCALENDAR answer from {} carries no readable XML body", uri, e);
      return failed;
    }
    for (Element propstat : descendants(root, DAV_NS, "propstat")) {
      int status = statusCodeOf(textOf(propstat, DAV_NS, STATUS_ELEMENT));
      if (status > 0 && (status < 200 || status >= 300)) {
        failed.add(status);
      }
    }
    return failed;
  }

  /**
   * The prop elements of a response whose propstat status granted them —
   * the one gate every property in this client is read through. BlueMind
   * answers 207 with failing propstats where a naive client reads 2xx as
   * success; Stalwart interleaves a 404 propstat (an absent calendar-color)
   * with the 200 one in the same response — both verified live — so a
   * property outside a 2xx propstat does not exist as far as this client is
   * concerned.
   *
   * @param response one multistatus response element
   * @return the granted prop elements, possibly empty
   */
  private List<Element> grantedProps(Element response) {
    List<Element> granted = new ArrayList<>();
    for (Element propstat : childElements(response, DAV_NS, "propstat")) {
      int status = statusCodeOf(textOf(propstat, DAV_NS, STATUS_ELEMENT));
      if (status >= 200 && status < 300) {
        granted.addAll(childElements(propstat, DAV_NS, "prop"));
      }
    }
    return granted;
  }

  /**
   * The granted text of a named property — read only inside 2xx propstats,
   * never from the response at large.
   *
   * @param response one multistatus response element
   * @param namespace the property namespace
   * @param name the property local name
   * @return the trimmed text, or null when absent or not granted
   */
  private String grantedText(Element response, String namespace, String name) {
    for (Element prop : grantedProps(response)) {
      String text = textOf(prop, namespace, name);
      if (text != null) {
        return text;
      }
    }
    return null;
  }

  /**
   * The status a response carries directly (no propstat) — the shape a
   * sync-collection REPORT reports deletions in.
   *
   * @param response one multistatus response element
   * @return the status code, or 0 when the response carries none directly
   */
  private int directStatusOf(Element response) {
    List<Element> statuses = childElements(response, DAV_NS, STATUS_ELEMENT);
    return statuses.isEmpty() ? 0 : statusCodeOf(StringUtils.trimToNull(statuses.get(0).getTextContent()));
  }

  /**
   * The code of an {@code HTTP/1.1 404 Not Found} status line.
   *
   * @param statusLine the status line text, or null
   * @return the code, or 0 when the line is absent or unreadable
   */
  private int statusCodeOf(String statusLine) {
    if (StringUtils.isBlank(statusLine)) {
      return 0;
    }
    String[] parts = statusLine.trim().split("\\s+");
    for (String part : parts) {
      if (part.length() == 3 && StringUtils.isNumeric(part)) {
        return Integer.parseInt(part);
      }
    }
    return 0;
  }

  /**
   * The href of one multistatus response, as a server-absolute path. A
   * foreign-host href skips its entry — warned and ignored rather than
   * failing the whole listing, so one hostile or misconfigured member
   * cannot take the sync down; the entry stays unreachable either way,
   * because {@link #target} would refuse it again at request time.
   *
   * @param endpoint the declared server, for absolute-href folding
   * @param response one response element
   * @return the path, or null when the response carries no usable href
   */
  private String responsePath(CalDavEndpoint endpoint, Element response) {
    List<Element> hrefs = childElements(response, DAV_NS, "href");
    if (hrefs.isEmpty()) {
      return null;
    }
    try {
      return asPath(endpoint, StringUtils.trimToNull(hrefs.get(0).getTextContent()));
    } catch (CalDavException e) {
      LOG.warn("A listing from the CalDAV server names a foreign host; the entry is ignored: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Folds an href the server answered into a server-absolute path — the one
   * shape this client answers hrefs in, and accepts them back in. BlueMind
   * advertises path-only, server-absolute hrefs rooted at {@code /dav/};
   * those pass through unchanged. An absolute URL naming the endpoint's own
   * host is folded onto its path; one naming a foreign host is refused,
   * because a path that would later aim credentials at an undeclared host
   * must not survive this method.
   *
   * @param endpoint the declared server
   * @param href the href text the server answered, or a caller-held path
   * @return the server-absolute raw path, or null when the href was blank
   */
  private String asPath(CalDavEndpoint endpoint, String href) {
    String trimmed = StringUtils.trimToNull(href);
    if (trimmed == null) {
      return null;
    }
    if (Strings.CI.startsWith(trimmed, "http")) {
      URI absolute = uri(trimmed);
      if (!sameAuthority(absolute, endpoint.getBaseUri())) {
        throw new CalDavException("Refusing an href naming " + absolute.getHost()
            + ", which is not the declared CalDAV server");
      }
      return StringUtils.defaultIfBlank(absolute.getRawPath(), "/");
    }
    return trimmed;
  }

  /**
   * Resolves a Location header against the request URL — servers answer
   * paths as often as URLs, and the caller needs one absolute shape.
   *
   * @param requestUri the URL the request went to
   * @param location the Location header value
   * @return the absolute form of the location
   */
  private String resolveLocation(URI requestUri, String location) {
    try {
      return requestUri.resolve(location).toString();
    } catch (IllegalArgumentException e) {
      LOG.debug("Unresolvable Location header answered by {}", requestUri, e);
      return location;
    }
  }

  /**
   * Whether two URIs name the same server: same scheme, same host ignoring
   * case, same effective port — the scheme's default when none is written.
   *
   * @param first one URI
   * @param second the other URI
   * @return true when both name the same authority
   */
  private boolean sameAuthority(URI first, URI second) {
    return Strings.CI.equals(first.getScheme(), second.getScheme())
        && Strings.CI.equals(first.getHost(), second.getHost())
        && effectivePort(first) == effectivePort(second);
  }

  /**
   * The port a URI actually addresses: the written one, else the scheme's
   * default.
   *
   * @param uri the URI to read
   * @return the effective port
   */
  private int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return Strings.CI.equals(uri.getScheme(), "https") ? 443 : 80;
  }

  /**
   * The first response element of a multistatus, or null when there is none.
   *
   * @param multistatus the parsed document root
   * @return the first response, or null
   */
  private Element firstResponse(Element multistatus) {
    List<Element> responses = childElements(multistatus, DAV_NS, RESPONSE_ELEMENT);
    return responses.isEmpty() ? null : responses.get(0);
  }

  /**
   * The text of the first descendant with this name, trimmed, or null.
   *
   * @param parent the element to search under
   * @param namespace the element namespace
   * @param name the local name
   * @return the text, or null when absent or empty
   */
  private String textOf(Element parent, String namespace, String name) {
    List<Element> found = descendants(parent, namespace, name);
    return found.isEmpty() ? null : StringUtils.trimToNull(found.get(0).getTextContent());
  }

  /**
   * The href nested inside a named element — the shape both discovery
   * steps answer in.
   *
   * @param parent the element to search under
   * @param namespace the wrapper's namespace
   * @param name the wrapper's local name
   * @return the href, or null
   */
  private String hrefWithin(Element parent, String namespace, String name) {
    List<Element> wrappers = descendants(parent, namespace, name);
    return wrappers.isEmpty() ? null : textOf(wrappers.get(0), DAV_NS, "href");
  }

  /**
   * Every descendant element with this qualified name.
   *
   * @param parent the element to search under
   * @param namespace the element namespace
   * @param name the local name
   * @return the matches, in document order
   */
  private List<Element> descendants(Element parent, String namespace, String name) {
    List<Element> found = new ArrayList<>();
    NodeList nodes = parent.getElementsByTagNameNS(namespace, name);
    for (int i = 0; i < nodes.getLength(); i++) {
      found.add((Element) nodes.item(i));
    }
    return found;
  }

  /**
   * The direct children with this qualified name — used wherever nesting
   * matters: response elements must not be gathered from nested documents,
   * and a response's own status must not be confused with a propstat's.
   *
   * @param parent the element whose children to read
   * @param namespace the element namespace
   * @param name the local name
   * @return the matching children
   */
  private List<Element> childElements(Element parent, String namespace, String name) {
    List<Element> found = new ArrayList<>();
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE && namespace.equals(child.getNamespaceURI())
          && name.equals(child.getLocalName())) {
        found.add((Element) child);
      }
    }
    return found;
  }

  /**
   * Parses a URL, refusing an unusable one in the terms the caller
   * understands.
   *
   * @param url the URL to parse
   * @return the URI
   */
  private URI uri(String url) {
    try {
      return new URI(url);
    } catch (URISyntaxException e) {
      throw new CalDavException("Not a usable CalDAV URL: " + url, e);
    }
  }

  /**
   * The Basic credentials header. Sent on every request rather than waiting
   * to be challenged — which is what DAV servers expect, saves a round trip,
   * and matters doubly against BlueMind, whose refusals do not even carry a
   * {@code WWW-Authenticate} challenge to react to.
   *
   * @param username the account
   * @param password its password
   * @return the header value
   */
  private String basicAuth(String username, String password) {
    String pair = StringUtils.defaultString(username) + ":" + StringUtils.defaultString(password);
    return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Escapes text going into a request body. Hrefs come from the server, but
   * they travel back out in the multiget, and a path with an ampersand
   * would otherwise produce XML the server rejects.
   *
   * @param text the text to escape
   * @return the escaped text
   */
  private String escape(String text) {
    return StringUtils.defaultString(text)
                      .replace("&", "&amp;")
                      .replace("<", "&lt;")
                      .replace(">", "&gt;");
  }

  /**
   * The per-request timeout, re-read per call so a deployment can tune it
   * without a restart.
   *
   * @return the timeout to send with
   */
  private Duration requestTimeout() {
    return Duration.ofSeconds(intProperty(REQUEST_TIMEOUT_PROPERTY, DEFAULT_REQUEST_TIMEOUT));
  }

  /**
   * An integer deployment property, else its default.
   *
   * @param name property name
   * @param defaultValue value when unset or unreadable
   * @return the configured value
   */
  private static int intProperty(String name, int defaultValue) {
    try {
      return Integer.parseInt(System.getProperty(name, String.valueOf(defaultValue)));
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * A long deployment property, else its default.
   *
   * @param name property name
   * @param defaultValue value when unset or unreadable
   * @return the configured value
   */
  private static long longProperty(String name, long defaultValue) {
    try {
      return Long.parseLong(System.getProperty(name, String.valueOf(defaultValue)));
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * One exchanged response: status, headers and the bounded body — what the
   * per-verb status policies are applied to.
   *
   * @param status the HTTP status
   * @param response the raw response, for header access
   * @param body the body, read up to the configured cap
   */
  private record DavResponse(int status, HttpResponse<InputStream> response, String body) {

    /**
     * One response header, trimmed to null.
     *
     * @param name the header name, case-insensitive
     * @return the first value, or null
     */
    String header(String name) {
      return StringUtils.trimToNull(response.headers().firstValue(name).orElse(null));
    }
  }

  /** The prefix eXo derives every personal collection's path from. */
  private static final String                COLLECTION_PREFIX = "exo-cal-";

  @Override
  public int deleteCollection(CalDavEndpoint endpoint, CalendarSync pair, String username, String password) {
    String href = authorisedTarget(pair);
    HttpRequest request = HttpRequest.newBuilder(target(endpoint, href))
                                     .timeout(requestTimeout())
                                     .header(AUTHORIZATION_HEADER, basicAuth(username, password))
                                     .DELETE()
                                     .build();
    DavResponse response = exchange(request);
    int status = response.status();
    checkAuthStatus(status, false, request);
    // 200/204 deleted; 404/410 already gone — absent is absent, which is what
    // makes a repeated deletion idempotent rather than an error to explain.
    if (status != 200 && status != 204 && status != 404 && status != 410) {
      throw refusal(status, request);
    }
    return status;
  }

  /**
   * The collection a pair authorises deleting, or a refusal.
   *
   * <p>
   * Both conditions have to hold, and they guard different mistakes. ORIGIN
   * says eXo created this collection, so removing it destroys nothing the user
   * built elsewhere. The path check says <i>this</i> pair's collection and not
   * some other one: a pair whose href drifted — a bad migration, a hand-edited
   * row, a bug in binding — would otherwise aim a deletion at whatever it now
   * points to.
   *
   * <p>
   * An IllegalArgumentException rather than a checked refusal, deliberately.
   * There is no correct way for a caller to recover from asking to delete a
   * collection it may not delete; the only correct outcome is that the request
   * was never built.
   *
   * @param pair the binding offered as authorisation
   * @return the collection href to address
   */
  private String authorisedTarget(CalendarSync pair) {
    if (pair == null || pair.getOrigin() != SyncOrigin.EXO) {
      throw new IllegalArgumentException("Only a collection eXo created may be deleted; this pair is "
          + (pair == null ? "absent" : String.valueOf(pair.getOrigin())));
    }
    if (StringUtils.isBlank(pair.getLocalCalendarSyncUid())) {
      throw new IllegalArgumentException("A pair with no calendar anchor authorises no deletion");
    }
    String href = StringUtils.stripEnd(StringUtils.trimToEmpty(pair.getRemoteHref()), "/");
    String expected = COLLECTION_PREFIX + pair.getLocalCalendarSyncUid();
    if (!href.endsWith("/" + expected)) {
      throw new IllegalArgumentException("The pair's collection " + href + " is not the one eXo derives for calendar "
          + pair.getLocalCalendarSyncUid());
    }
    return href + "/";
  }
}

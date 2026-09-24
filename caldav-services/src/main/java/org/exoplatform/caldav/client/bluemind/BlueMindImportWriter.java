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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.CalDavAuthenticationException;
import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
import org.exoplatform.caldav.client.CalDavUnreachableException;
import org.exoplatform.caldav.client.CalendarObjectWriter;
import org.exoplatform.caldav.client.PutResult;
import org.exoplatform.caldav.client.bluemind.BlueMindCalendarImportClient.ImportReport;
import org.exoplatform.caldav.storage.CaldavSyncStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The BlueMind door: every write is an ICS import, every removal a REST
 * delete with notifications off, and the version eXo records afterwards is
 * the one the collection's own listing publishes (EXO-90307).
 *
 * <p>
 * <b>Which version, and why that one.</b> The import answers a task, not an
 * ETag, and the engine drives every later conditional write off the ETag it
 * recorded. There is exactly one channel that value may come from: the
 * {@code Depth: 1} PROPFIND the verification pass lists the collection with
 * ({@code CalDavClient#listResourceEtags}), because that pass compares the
 * listing against the row and, once the content is confirmed equal, adopts
 * the listing's value into the row ({@code CaldavMirrorVerificationService#adoptVersion}:
 * "the version the server publishes means the one the collection listing
 * publishes"). A precondition read from any other channel — a
 * {@code calendar-multiget} REPORT, a GET, the PUT's own answer — would be
 * compared against a row the pass has meanwhile rewritten in the listing's
 * shape, and on BlueMind the shapes differ: the REPORT renders
 * {@code SyncTokens.getEtag(path, version)}, quoted base64
 * ({@code CalendarMultigetExecutor.java:123}), while the listing renders a
 * child {@code .ics} through {@code GetTag.fetch} as the raw token
 * {@code bmdav_<lnum>_0} ({@code GetTag.java:46-56}, "unsupported ressource
 * type", timestamp 0). Compared across channels, every update after the first
 * pass would be refused for ever. So this door reads the precondition from
 * the listing, records what the listing publishes after the write, and the
 * row, the pass and the emulation speak one shape.
 *
 * <p>
 * <b>What that buys, and what it does not.</b> On BlueMind the listed value
 * of an object never moves, so the emulated {@code If-Match} refuses nothing
 * a client changed — which is exactly what BlueMind's own CalDAV {@code PUT}
 * does with a real {@code If-Match}: its DAV server reads only
 * {@code If-None-Match} ({@code PutProtocol.java:65},
 * {@code putQuery.setCreate(r.headers().contains("If-None-Match"))}) and no
 * {@code If-Match} reader exists anywhere under
 * {@code plugins/net.bluemind.dav.server}. So the engine never relied on it
 * there: the verification pass's content comparison and answer adoption are
 * the guard on this server, through either door. What the listing does
 * answer reliably is <i>existence</i>: an object absent from it is absent,
 * which is what create-only needs. The check and the write are two calls, so
 * a client writing between them is not refused; that is the race the repair
 * path already accepts on every server.
 *
 * <p>
 * <b>Reading one object rather than the whole calendar.</b> Read through the listing alone,
 * every conditional write cost one {@code Depth: 1} ETag-only PROPFIND of
 * the <i>whole</i> collection before it and one after it; on BlueMind the
 * destination is the main calendar, so every push, answer, exclusion or
 * removal listed the user's entire calendar twice, and BlueMind logged one
 * INFO line per child each time ({@code GetTag.java:53-54}). This door now
 * asks two single-object questions instead, and asks the listing only when
 * their answers are not conclusive:
 * <ul>
 * <li><i>Is the object there?</i> — a one-href {@code calendar-multiget}
 * REPORT asking {@code getetag} only ({@code CalDavClient#multigetEtags}).
 * BlueMind answers one response per item its store returned
 * ({@code CalendarMultigetExecutor.java:82,114-130}) and none for a uid it
 * does not hold, so a response for the href is presence — taken only when
 * its href is byte-equal to the one eXo sent: the server spells it as
 * {@code <REPORT path> + uid + ".ics"} ({@code :117}), the construction the
 * listing hashes from ({@code DavStore.java:402}), so equality means the
 * {@code Depth: 0} request path below hashes like the listing's child for
 * <i>this</i> object, whatever the collection-wide verdict. Its version is
 * the REPORT channel's, quoted base64 ({@code :123}), and is never recorded. No
 * response is <i>not</i> taken as absence: a failed lookup answers the same
 * empty document ({@code :83-86}), so absence is only ever the listing's
 * word — which is why {@link #putObject}, whose accepting answer <i>is</i>
 * absence, reads the listing directly and asks nothing else. Both multiget
 * shapes were observed on the rig on 2026-09-16
 * ({@code bluemind-report-multiget-one-href-getetag.captured.xml}: one
 * response, {@code "Ym1kYXZfMzk4MDk2NjI5Nl8x"};
 * {@code bluemind-report-multiget-missing-href.captured.xml}: an empty
 * multistatus).</li>
 * <li><i>Which version does the listing publish for it?</i> — a
 * {@code Depth: 0} PROPFIND of {@code getetag} on the href
 * ({@code CalDavClient#readEtag}). BlueMind resolves it through the same
 * {@code ds.from(path)} ({@code PropFindProtocol.java:69}) and hashes
 * {@code dr.getPath()} ({@code GetTag.java:47,56}, {@code SyncTokens.java:42}),
 * so the token equals the listing's iff the request path equals
 * {@code containerPath + uid + ".ics"} as {@code addEvents} builds it
 * ({@code DavStore.java:402}) — a hypothesis about spelling, observed to
 * hold on the rig on 2026-09-16 for one object of the main calendar
 * ({@code bluemind-propfind-object-depth0-getetag.captured.xml} against
 * {@code bluemind-propfind-collection-depth1-getetag.captured.xml}, the same
 * {@code bmdav_3980966296_0}), and still verified live per collection below
 * because one object on one server is an observation, not a proof. This
 * read cannot answer existence either: the router assumes an
 * {@code .ics} node exists whenever its path is well formed
 * ({@code MethodRouter.java:162-175}, {@code DavStore.java:474-502}, the
 * {@code default} "assume yes"), {@code ResType.VSTUFF} mints the node from
 * the path with no lookup, and {@code Depth: 0} adds only that node
 * ({@code DavStore.java:355-357}); a missing object is answered 207 with a
 * token — observed on the rig the same day on a probe href that did not
 * exist ({@code bluemind-propfind-missing-object-depth0.captured.xml},
 * {@code bmdav_3856992451_0}). So it is asked only once presence is
 * established.</li>
 * </ul>
 * <b>Nothing here rests on the spelling hypothesis.</b> The first time the
 * {@code Depth: 0} read answers on a collection, its token is compared with
 * the {@code Depth: 1} listing's for the same href, the way the pass compares
 * ({@link #sameVersion}); the verdict is kept per collection for the life of
 * the process ({@link #depthZeroAgreesWithListing}) and stated once at INFO.
 * Agreement makes later writes on that collection single-object reads only;
 * disagreement keeps them on the listing, at today's cost. And a refusal is
 * never pronounced on the single-object channel alone: when its version
 * differs from the one the caller conditions on, the listing is read and its
 * value compared instead ({@link #versionOf}); a listing that contradicts
 * the token there revokes the verdict as well — so even a verdict that went
 * stale can cost a listing, never a wrong 412 and never a recorded version
 * of the wrong shape. Every fall-back is taken at DEBUG. Request count per conditional write on the settled path: two
 * single-object requests before and two after, none listing the collection;
 * a create still lists once, before the import.
 *
 * <p>
 * <b>Switching a server onto this door.</b> A row written over CalDAV holds
 * the PUT's ETag, another shape again. Its first conditional write through
 * this door is refused once, the engine settles it as a conflict, and the
 * next verification pass — finding the listing's value differs from the row —
 * fetches, compares against what eXo renders now, repairs the edit in and
 * adopts the listing's value. One refusal, self-healing, and only on the
 * transition.
 *
 * <p>
 * <b>What is taken out of the document.</b> {@code LAST-MODIFIED}, and only
 * on this door. BlueMind's importer applies a series only when the file's
 * {@code LAST-MODIFIED} is null, later than the stored one, or its EXDATEs
 * changed ({@code ICSImportTask.java:171-179}; the file's value becomes
 * {@code updated} at {@code VEventServiceHelper.java:381-382}); eXo renders
 * it from the event's own modification date, which an answer does not move,
 * so a copy rewritten to carry an answer would be silently dropped. Without
 * the property the import always applies. BlueMind renders the stored
 * object's {@code LAST-MODIFIED} from its own item timestamp
 * ({@code VEventServiceHelper.java:151}) and {@code IcsEquivalence} ignores
 * that property on every server, so the verification pass sees no drift.
 *
 * <p>
 * <b>A bounded quirk to know about.</b> When an import changes the start or
 * end of a series, BlueMind's merge empties the stored exceptions and
 * EXDATEs before re-adding the file's ({@code EventChangesMerge.java:111-113}
 * — from source, not yet observed on a server); a second import of the same
 * document restores them. The verification pass performs that second import
 * when it compares the copy and finds it short of what eXo renders, and it
 * compares only when the listing's value moved — which on BlueMind it does
 * not — or in a settings round. Until the rig says otherwise, treat a
 * date-changing edit of a series with exceptions as one to watch.
 */
@Component
public class BlueMindImportWriter implements CalendarObjectWriter {

  private static final Log LOG           = ExoLogger.getLogger(BlueMindImportWriter.class);

  /** The property taken out of every document sent through this door. */
  static final String      LAST_MODIFIED = "LAST-MODIFIED";

  private final BlueMindCalendarImportClient importClient;

  private final CalDavClient                 calDavClient;

  /**
   * Per collection — keyed by server and canonical collection path — whether
   * the {@code Depth: 0} read of an object's version agreed with the
   * {@code Depth: 1} listing's for the same href when both were read once.
   * Absent until an object of the collection has been read both ways; the
   * verdict lives as long as the process.
   */
  private final Map<String, Boolean>         depthZeroAgreesWithListing = new ConcurrentHashMap<>();

  /**
   * The writer over the import API and the CalDAV client its reads use.
   *
   * @param importClient BlueMind's calendar REST API
   * @param calDavClient the CalDAV client, for the reads that learn whether
   *          an object is stored and under which listed version
   */
  @Autowired
  public BlueMindImportWriter(BlueMindCalendarImportClient importClient, CalDavClient calDavClient) {
    this.importClient = importClient;
    this.calDavClient = calDavClient;
  }

  /**
   * {@inheritDoc}
   *
   * <p>
   * Create-only, emulated: an object the listing already carries at the href
   * is answered 412 with its version, the way {@code If-None-Match: *} would.
   * The listing is read directly here: the answer that lets a create proceed
   * is absence, and absence is the listing's word alone.
   */
  @Override
  public PutResult putObject(CalDavEndpoint endpoint, String href, String icsData) {
    String existing = listed(endpoint, href);
    if (existing != null) {
      return new PutResult(PutResult.PRECONDITION_FAILED, existing, null);
    }
    return new PutResult(201, write(endpoint, href, icsData), null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PutResult overwriteObject(CalDavEndpoint endpoint, String href, String icsData) {
    return new PutResult(200, write(endpoint, href, icsData), null);
  }

  /**
   * {@inheritDoc}
   *
   * <p>
   * Replace-if-unchanged, emulated: an object the listing does not carry, or
   * carries under another version than {@code ifMatch}, is answered 412, the
   * way {@code If-Match} would. Versions compare the way the verification
   * pass compares them — quoting and a weak marker aside.
   */
  @Override
  public PutResult updateObject(CalDavEndpoint endpoint, String href, String icsData, String ifMatch) {
    if (StringUtils.isBlank(ifMatch)) {
      throw new IllegalArgumentException("An update needs the ETag its read answered; an unconditional overwrite is refused");
    }
    String existing = versionOf(endpoint, href, ifMatch);
    if (!sameVersion(ifMatch, existing)) {
      return new PutResult(PutResult.PRECONDITION_FAILED, existing, null);
    }
    return new PutResult(200, write(endpoint, href, icsData), null);
  }

  /**
   * {@inheritDoc}
   *
   * <p>
   * Conditional when a version is given, emulated the same way: gone is 404,
   * another version is 412. The removal itself carries
   * {@code sendNotifications=false}.
   */
  @Override
  public int deleteObject(CalDavEndpoint endpoint, String href, String ifMatch) {
    if (StringUtils.isNotBlank(ifMatch)) {
      String existing = versionOf(endpoint, href, ifMatch);
      if (existing == null) {
        return 404;
      }
      if (!sameVersion(ifMatch, existing)) {
        return PutResult.PRECONDITION_FAILED;
      }
    }
    String containerUid = containerUidOf(href);
    int status = importClient.deleteEvent(endpoint, containerUid, uidOf(href));
    LOG.debug("Copy {} removed from BlueMind through the calendar API with notifications off (container {}, {})",
              href,
              containerUid,
              status);
    return status;
  }

  /**
   * Imports the document and reads back the version the listing now publishes
   * for the href — through the single-object reads when they are conclusive
   * on this collection, through the listing otherwise.
   *
   * @param endpoint the account's endpoint
   * @param href the object's path
   * @param icsData the document eXo rendered
   * @return the listed version
   * @throws CalDavException when BlueMind did not report the series written,
   *           or lists nothing at the href afterwards
   */
  private String write(CalDavEndpoint endpoint, String href, String icsData) {
    String containerUid = containerUidOf(href);
    String uid = StringUtils.defaultIfBlank(uidInside(icsData), uidOf(href));
    ImportReport report = importClient.importIcs(endpoint, containerUid, withoutLastModified(icsData));
    if (!report.imported(uid)) {
      // Not written, whatever the task said: a series the importer left
      // unhandled, or a document it could not read at all (total 0). Said as
      // a failed write so the sweep retries it, never recorded as a version.
      throw new CalDavException("The calendar server did not import " + uid + " into " + containerUid + " (" + report.uids().size()
          + " of " + report.total() + " series applied)");
    }
    String stored = versionOf(endpoint, href, null);
    if (stored == null) {
      throw new CalDavException("The calendar server reported " + uid + " imported into " + containerUid
          + " but lists nothing at " + href);
    }
    LOG.debug("Copy {} written to BlueMind through the ICS import channel (container {}, {} of {} series applied)",
              href,
              containerUid,
              report.uids().size(),
              report.total());
    return stored;
  }

  /**
   * The version the collection's listing publishes for one href, read the
   * way the caller wants it read: through the single-object channel when it
   * is conclusive on this collection and agrees with {@code expected}, and
   * through the {@code Depth: 1} listing in every other case — the channel
   * not being conclusive, the collection having no verdict yet or a negative
   * one, or the channel's version differing from the one the caller
   * conditions on, in which case the listing's own value is what is compared
   * and returned, so a refusal never rests on the single-object channel
   * alone. The listing is read at most once per call.
   *
   * @param endpoint the account's endpoint
   * @param href the object's path
   * @param expected the version the caller conditions on, or null when it
   *          conditions on nothing
   * @return the version in the listing's shape, or null when the object is
   *         not there
   */
  private String versionOf(CalDavEndpoint endpoint, String href, String expected) {
    String collection = collectionOf(href);
    String key = endpoint.getServerId() + " " + CaldavSyncStorage.canonicalHref(collection);
    Boolean agrees = depthZeroAgreesWithListing.get(key);
    if (Boolean.FALSE.equals(agrees)) {
      return listed(endpoint, href);
    }
    String token = singleObjectToken(endpoint, href, collection);
    if (token == null) {
      return listed(endpoint, href);
    }
    if (agrees == null) {
      // The first conclusive read on this collection: both channels, once,
      // and the listing's own value is what this call answers.
      String fromListing = listed(endpoint, href);
      settle(key, collection, token, fromListing);
      return fromListing;
    }
    if (expected != null && !sameVersion(expected, token)) {
      LOG.debug("The single-object read of {} names version {} where {} was expected; the collection listing has the last word",
                href,
                token,
                expected);
      String fromListing = listed(endpoint, href);
      // A listing that contradicts the token contradicts the verdict too: the
      // channels no longer agree here, so this collection goes back to the
      // listing for the rest of the process.
      if (fromListing != null && !sameVersion(fromListing, token)) {
        settle(key, collection, token, fromListing);
      }
      return fromListing;
    }
    return token;
  }

  /**
   * The object's version through the single-object channel — the one-href
   * multiget for presence, then the {@code Depth: 0} PROPFIND for the value —
   * or null when that channel is not conclusive here: presence not affirmed
   * under the exact spelling eXo sends, no {@code getetag} granted, or a
   * server error on either read. Reads nothing else; the listing is the
   * caller's to read.
   *
   * @param endpoint the account's endpoint
   * @param href the object's path
   * @param collection the object's collection, slash-terminated
   * @return the token the {@code Depth: 0} read granted, or null
   */
  private String singleObjectToken(CalDavEndpoint endpoint, String href, String collection) {
    String token;
    try {
      Map<String, String> present = calDavClient.multigetEtags(endpoint, collection, List.of(href));
      // Presence under the server's OWN spelling of the path, byte for byte:
      // BlueMind renders the response href as <REPORT request path> + uid +
      // ".ics" (CalendarMultigetExecutor.java:117), the very construction the
      // listing hashes its child token from (DavStore.java:402). A key equal
      // to the href eXo sends means the Depth:0 request path is that same
      // string; a key spelled otherwise (a percent-encoded leaf, say) means
      // the two would hash apart, and the listing is read instead.
      if (StringUtils.isBlank(present.get(href))) {
        LOG.debug("The one-href multiget of {} answered no version under that spelling; the collection listing is read instead",
                  href);
        return null;
      }
      token = calDavClient.readEtag(endpoint, href);
    } catch (CalDavAuthenticationException | CalDavUnreachableException e) {
      // Every later step would meet the same refusal, the listing included.
      throw e;
    } catch (CalDavException e) {
      LOG.debug("The single-object read of {} failed ({}); the collection listing is read instead", href, e.getMessage());
      return null;
    }
    if (StringUtils.isBlank(token)) {
      LOG.debug("The Depth:0 read of {} granted no getetag; the collection listing is read instead", href);
      return null;
    }
    return token;
  }

  /**
   * Records, for one collection, whether the {@code Depth: 0} token and the
   * listing's value name the same version — stated once at INFO, because it
   * is the answer to the question the whole single-object channel hangs on.
   * A listing that does not carry the object decides nothing: a race, or a
   * listing that failed on the server ({@code DavStore.java:406-408}), says
   * nothing about the channels' agreement, and no verdict is recorded.
   *
   * @param key the verdict's key, server and canonical collection
   * @param collection the collection, for the log line
   * @param token what the {@code Depth: 0} read granted
   * @param fromListing what the listing publishes for the same object, may
   *          be null
   */
  private void settle(String key, String collection, String token, String fromListing) {
    if (fromListing == null) {
      LOG.debug("The listing of {} does not carry an object the multiget answered; no verdict on the Depth:0 read", collection);
      return;
    }
    boolean agrees = sameVersion(fromListing, token);
    depthZeroAgreesWithListing.put(key, agrees);
    LOG.info("On BlueMind collection {} the Depth:0 getetag {} the collection listing's ({} against {}); {}",
             collection,
             agrees ? "agrees with" : "differs from",
             token,
             fromListing,
             agrees ? "single-object reads serve this collection from now on" : "the collection listing serves it");
  }

  /**
   * The version the collection's listing publishes for one href, or null when
   * the listing does not carry the object — the same {@code Depth: 1}
   * PROPFIND the verification pass reads, so the value has the shape the pass
   * adopts.
   *
   * @param endpoint the account's endpoint
   * @param href the object's path
   * @return the listed version, verbatim, or null
   */
  private String listed(CalDavEndpoint endpoint, String href) {
    return valueAt(calDavClient.listResourceEtags(endpoint, collectionOf(href)), href);
  }

  /**
   * The value a server-answered map holds for one href, whatever spelling
   * the server gave the key — canonical paths compared case-insensitively.
   *
   * @param answered object path to version, as the server spelled the paths
   * @param href the object's path as eXo spells it
   * @return the first non-blank value at that object, or null
   */
  private static String valueAt(Map<String, String> answered, String href) {
    String wanted = CaldavSyncStorage.canonicalHref(href);
    return answered.entrySet()
                   .stream()
                   .filter(entry -> StringUtils.equalsIgnoreCase(CaldavSyncStorage.canonicalHref(entry.getKey()), wanted))
                   .map(Map.Entry::getValue)
                   .filter(StringUtils::isNotBlank)
                   .findFirst()
                   .orElse(null);
  }

  /**
   * Whether two versions name the same one, quoting and a weak marker aside —
   * the comparison {@code CaldavMirrorVerificationService#normalise} makes,
   * and no more: a server's private encoding is never decoded.
   *
   * @param expected the version the caller conditions on
   * @param listed the version the listing publishes, may be null
   * @return true when both name one version
   */
  static boolean sameVersion(String expected, String listed) {
    return listed != null && StringUtils.equals(normalise(expected), normalise(listed));
  }

  /**
   * A version without its quotes and weak marker.
   *
   * @param etag the version as sent
   * @return the bare token
   */
  private static String normalise(String etag) {
    return StringUtils.removeStart(StringUtils.strip(etag, "\""), "W/").replace("\"", "");
  }

  /**
   * The document without its {@code LAST-MODIFIED} lines, continuation lines
   * included, line endings otherwise untouched.
   *
   * @param icsData the document as rendered
   * @return the document to import
   */
  static String withoutLastModified(String icsData) {
    if (icsData == null || !icsData.toUpperCase(Locale.ROOT).contains(LAST_MODIFIED)) {
      return icsData;
    }
    String separator = icsData.contains("\r\n") ? "\r\n" : "\n";
    StringBuilder kept = new StringBuilder(icsData.length());
    boolean dropping = false;
    for (String line : icsData.split("\r?\n", -1)) {
      if (dropping && (line.startsWith(" ") || line.startsWith("\t"))) {
        continue;
      }
      dropping = isProperty(line, LAST_MODIFIED);
      if (!dropping) {
        if (kept.length() > 0) {
          kept.append(separator);
        }
        kept.append(line);
      }
    }
    return kept.toString();
  }

  /**
   * The {@code UID} the document itself names, or null when it names none.
   *
   * <p>
   * Read on the unfolded document: a long UID folded across lines (RFC 5545
   * §3.1) is one value, and read off its first physical line it would never
   * match the uid the import reports.
   *
   * @param icsData the document
   * @return the first UID value, trimmed
   */
  static String uidInside(String icsData) {
    if (icsData == null) {
      return null;
    }
    for (String line : icsData.replaceAll("\r?\n[ \t]", "").split("\r?\n")) {
      if (isProperty(line, "UID")) {
        return StringUtils.trimToNull(StringUtils.substringAfter(line, ":"));
      }
    }
    return null;
  }

  /**
   * The BlueMind container the object lives in: the collection's last path
   * segment, decoded.
   *
   * @param href the object's path
   * @return the container uid
   */
  static String containerUidOf(String href) {
    String collection = CaldavSyncStorage.canonicalHref(collectionOf(href));
    return StringUtils.substringAfterLast(collection, "/");
  }

  /**
   * The item uid the object is stored under: its file name without
   * {@code .ics}, decoded — the same rule BlueMind's own CalDAV PUT applies
   * ({@code PutProtocol.java:83}).
   *
   * @param href the object's path
   * @return the item uid
   */
  static String uidOf(String href) {
    String name = StringUtils.substringAfterLast(CaldavSyncStorage.canonicalHref(href), "/");
    return StringUtils.removeEndIgnoreCase(name, ".ics");
  }

  /**
   * The collection an object's href sits in, slash-terminated as a collection
   * is addressed: the pass lists it the same way, because BlueMind ignores the
   * slashless spelling without answering or redirecting and the call spent its
   * whole timeout ({@code CaldavMirrorVerificationService#verify}, the comment
   * on its {@code listResourceEtags} call).
   *
   * @param href the object's path
   * @return the collection's path
   */
  static String collectionOf(String href) {
    String trimmed = StringUtils.stripEnd(href.trim(), "/");
    return StringUtils.substringBeforeLast(trimmed, "/") + "/";
  }

  /**
   * Whether a content line starts a property of the given name — followed by
   * a colon or a parameter, never merely by more letters.
   *
   * @param line the unfolded content line
   * @param name the property name, upper case
   * @return true when the line is that property
   */
  private static boolean isProperty(String line, String name) {
    if (!StringUtils.startsWithIgnoreCase(line, name) || line.length() <= name.length()) {
      return false;
    }
    char next = line.charAt(name.length());
    return next == ':' || next == ';';
  }
}

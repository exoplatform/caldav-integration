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

import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.caldav.client.CalDavClient;
import org.exoplatform.caldav.client.CalDavEndpoint;
import org.exoplatform.caldav.client.CalDavException;
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
 * does with a real {@code If-Match} (the rig note at
 * {@code CaldavMirrorVerificationService#adoptVersion}), and why the engine
 * never relied on it there: the verification pass's content comparison and
 * answer adoption are the guard on this server, through either door. What
 * the listing does answer reliably is <i>existence</i>: an object absent from
 * it is absent, which is what create-only needs. The check and the write are
 * two calls, so a client writing between them is not refused; that is the
 * race the repair path already accepts on every server.
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
   * The writer over the import API and the CalDAV client its reads use.
   *
   * @param importClient BlueMind's calendar REST API
   * @param calDavClient the CalDAV client, for the {@code Depth: 1} listing
   *          that learns the stored version in the pass's own shape
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
    String existing = listed(endpoint, href);
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
      String existing = listed(endpoint, href);
      if (existing == null) {
        return 404;
      }
      if (!sameVersion(ifMatch, existing)) {
        return PutResult.PRECONDITION_FAILED;
      }
    }
    String containerUid = containerUidOf(href);
    int status = importClient.deleteEvent(endpoint, containerUid, uidOf(href));
    LOG.info("Copy {} removed from BlueMind through the calendar API with notifications off (container {}, {})",
             href,
             containerUid,
             status);
    return status;
  }

  /**
   * Imports the document and reads back the version the listing now publishes
   * for the href.
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
    String stored = listed(endpoint, href);
    if (stored == null) {
      throw new CalDavException("The calendar server reported " + uid + " imported into " + containerUid
          + " but lists nothing at " + href);
    }
    LOG.info("Copy {} written to BlueMind through the ICS import channel (container {}, {} of {} series applied)",
             href,
             containerUid,
             report.uids().size(),
             report.total());
    return stored;
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
    String wanted = CaldavSyncStorage.canonicalHref(href);
    Map<String, String> listing = calDavClient.listResourceEtags(endpoint, collectionOf(href));
    return listing.entrySet()
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
   * @param icsData the document
   * @return the first UID value, trimmed
   */
  static String uidInside(String icsData) {
    if (icsData == null) {
      return null;
    }
    for (String line : icsData.split("\r?\n")) {
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
   * is addressed — BlueMind ignores the slashless form without answering.
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

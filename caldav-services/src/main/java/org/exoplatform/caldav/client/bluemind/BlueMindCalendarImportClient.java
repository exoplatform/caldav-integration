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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.exoplatform.caldav.client.bluemind.BlueMindRestSession.Answer;
import org.exoplatform.caldav.client.bluemind.BlueMindRestSession.Session;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Writes and removes calendar objects through BlueMind's own calendar REST
 * API — the door whose every change carries {@code sendNotification=false}
 * (EXO-90307).
 *
 * <p>
 * What is called, and why it is safe to call (verified in BlueMind's source,
 * {@code bluemind-public} master):
 * <ul>
 * <li><b>Import</b>: {@code PUT /api/calendars/vevent/{containerUid}}, the
 * raw ICS as the body ({@code IVEvent.java:31,53}; a {@code Stream}
 * parameter is read as the raw body whatever the media type,
 * {@code DefaultBodyParameterCodecs.StreamBodyCodec}). The service runs
 * {@code MultipleCalendarICSImport} in {@code Mode.IMPORT}
 * ({@code VEventService.java:114-116}) — never {@code Mode.SYNC}, which would
 * delete everything the file does not carry ({@code ICSImportTask.java:90-103}).
 * Each series is matched by ICS UID, merged, and applied with
 * {@code sendNotification=false} on the add, the modify and the delete of an
 * attendee-side series ({@code EventChangesMerge.java:102,238,240}), so
 * {@code IcsHook.mustSendNotification} returns before any REQUEST, UPDATE,
 * CANCEL or REPLY is built ({@code IcsHook.java:1099-1117}).</li>
 * <li><b>Its answer is a task</b>: a {@code TaskRef} whose status is read at
 * {@code GET /api/tasks/{id}} ({@code ITask.java}) until
 * {@code state.ended}. The task's result is the first {@code end} the monitor
 * received ({@code TaskMonitor.java:85-92} ignores a second one), and in
 * import mode that is an {@code ImportStats} — {@code uids} of what was added
 * or updated, {@code total} of everything the file carried, unhandled
 * included ({@code ICSImportTask.java:83-89}). A UID missing from
 * {@code uids} was <i>not</i> written, whatever the HTTP status said.</li>
 * <li><b>Removal</b>: {@code DELETE /api/calendars/{containerUid}/{uid}?sendNotifications=false}
 * ({@code ICalendar.java:125-128}).</li>
 * <li><b>Faults</b> are {@code {errorCode, errorType, message}} bodies: 500,
 * or 403 for {@code PERMISSION_DENIED} ({@code ResponseBuilder.java:70-76});
 * a dead session is 401 ({@code RestResponse.invalidSession}).</li>
 * </ul>
 *
 * <p>
 * Bounded waiting, never a hang: the task is polled at a fixed interval until
 * it ends or a deadline passes, and a task still running at the deadline is a
 * failed write — the caller's sweep retries it, and the import is idempotent
 * by UID so a retry over a late success changes nothing.
 */
@Component
public class BlueMindCalendarImportClient {

  /** The deployment property bounding the wait for one import task, in seconds. */
  public static final String IMPORT_TIMEOUT_PROPERTY = "exo.agenda.caldav.bluemind.import.timeoutSeconds";

  /** The wait when the property says nothing. */
  static final Duration      DEFAULT_IMPORT_TIMEOUT  = Duration.ofSeconds(30);

  /** How often a running task is asked again. */
  static final Duration      POLL_INTERVAL           = Duration.ofMillis(250);

  /** The media type the import body is sent as; BlueMind reads the raw bytes. */
  static final String        ICS_MEDIA_TYPE          = "text/calendar; charset=utf-8";

  private static final Log   LOG                     = ExoLogger.getLogger(BlueMindCalendarImportClient.class);

  private final BlueMindRestSession session;

  private final Sleeper             sleeper;

  private final Clock               clock;

  private final Duration            importTimeout;

  /**
   * How the poll loop waits between two status reads — a seam, so a test can
   * drive the deadline without spending the time.
   */
  @FunctionalInterface
  public interface Sleeper {

    /**
     * Waits for the duration.
     *
     * @param duration how long
     * @throws InterruptedException when the thread is interrupted meanwhile
     */
    void sleep(Duration duration) throws InterruptedException;
  }

  /**
   * What one import task reported when it ended.
   *
   * @param uids the ICS UIDs BlueMind added or updated
   * @param total how many series the file carried, the unhandled ones
   *          included
   */
  public record ImportReport(List<String> uids, int total) {

    /**
     * Whether one series was written.
     *
     * @param uid the ICS UID
     * @return true when BlueMind reports it added or updated
     */
    public boolean imported(String uid) {
      return uid != null && uids.stream().anyMatch(uid::equalsIgnoreCase);
    }
  }

  /**
   * The client Spring builds: real sleeps, the system clock, the configured
   * deadline.
   *
   * @param session the REST session every BlueMind conversation shares
   */
  @Autowired
  public BlueMindCalendarImportClient(BlueMindRestSession session) {
    this(session, Thread::sleep, Clock.systemUTC(), configuredTimeout());
  }

  /**
   * The seam the tests use.
   *
   * @param session the REST session
   * @param sleeper how the poll loop waits
   * @param clock what the deadline is measured on
   * @param importTimeout how long one import task may run
   */
  BlueMindCalendarImportClient(BlueMindRestSession session, Sleeper sleeper, Clock clock, Duration importTimeout) {
    this.session = session;
    this.sleeper = sleeper;
    this.clock = clock;
    this.importTimeout = importTimeout;
  }

  /**
   * Imports one ICS document into a calendar container and waits for BlueMind
   * to finish applying it.
   *
   * @param endpoint the account's DAV endpoint, minted from the registry
   * @param containerUid the calendar container, the last segment of the
   *          collection's path
   * @param ics the iCalendar text, exactly as it should be applied
   * @return what the task reported
   * @throws IllegalArgumentException when the container or the document is
   *           blank
   * @throws CalDavAuthenticationException when BlueMind refuses the login or
   *           the session
   * @throws CalDavForbiddenException when the account may not write the
   *           container
   * @throws CalDavException when the import is refused, ends in error, or does
   *           not end within the deadline
   */
  public ImportReport importIcs(CalDavEndpoint endpoint, String containerUid, String ics) {
    if (StringUtils.isBlank(containerUid)) {
      throw new IllegalArgumentException("A container uid is required");
    }
    if (StringUtils.isBlank(ics)) {
      throw new IllegalArgumentException("An iCalendar document is required");
    }
    String path = "/api/calendars/vevent/" + BlueMindRestSession.encodeSegment(containerUid);
    return session.call(endpoint, open -> {
      URI named = open.named(path);
      Answer answer = open.put(path, ics, ICS_MEDIA_TYPE);
      refuse(answer, "PUT", named);
      String taskId = BlueMindRestSession.textOf(session.parse(answer.body(), named), "id");
      if (StringUtils.isBlank(taskId)) {
        throw new CalDavException("The calendar server answered no task for PUT " + named);
      }
      return await(open, taskId, named);
    });
  }

  /**
   * Removes one series from a calendar container without notifying anybody.
   *
   * @param endpoint the account's DAV endpoint, minted from the registry
   * @param containerUid the calendar container
   * @param uid the item uid, which is the ICS UID
   * @return 204 when removed — or when BlueMind holds no such item, which its
   *         calendar service only logs ({@code CalendarService.java:466-471});
   *         404 when the container itself is unknown
   *         ({@code CalendarServiceBaseFactory.java:60})
   * @throws IllegalArgumentException when the container or the uid is blank
   * @throws CalDavAuthenticationException when BlueMind refuses the login or
   *           the session
   * @throws CalDavForbiddenException when the account may not write the
   *           container
   * @throws CalDavException when the removal is refused for any other reason
   */
  public int deleteEvent(CalDavEndpoint endpoint, String containerUid, String uid) {
    if (StringUtils.isBlank(containerUid) || StringUtils.isBlank(uid)) {
      throw new IllegalArgumentException("A container uid and an item uid are required");
    }
    String path = "/api/calendars/" + BlueMindRestSession.encodeSegment(containerUid) + "/"
        + BlueMindRestSession.encodeSegment(uid) + "?sendNotifications=false";
    return session.call(endpoint, open -> {
      URI named = open.named(path);
      Answer answer = open.delete(path);
      if (answer.status() == 500 && "NOT_FOUND".equals(faultCode(answer, named))) {
        return 404;
      }
      refuse(answer, "DELETE", named);
      return 204;
    });
  }

  /**
   * Polls one task until it ends or the deadline passes.
   *
   * @param open the session
   * @param taskId the task
   * @param named the import request, for messages
   * @return what the task reported
   * @throws CalDavException when the task ends in error or does not end in
   *           time
   */
  private ImportReport await(Session open, String taskId, URI named) {
    Instant deadline = clock.instant().plus(importTimeout);
    String path = "/api/tasks/" + BlueMindRestSession.encodeSegment(taskId);
    URI status = open.named(path);
    while (true) {
      Answer answer = open.get(path);
      refuse(answer, "GET", status);
      JsonNode task = session.parse(answer.body(), status);
      String state = BlueMindRestSession.textOf(task, "state");
      if ("Success".equals(state)) {
        return reportOf(BlueMindRestSession.textOf(task, "result"), named);
      }
      if ("InError".equals(state)) {
        LOG.debug("The import task {} ended in error: {}", taskId, BlueMindRestSession.textOf(task, "lastLogEntry"));
        throw new CalDavException("The calendar server could not apply the import for PUT " + named);
      }
      if (!clock.instant().isBefore(deadline)) {
        throw new CalDavException("The calendar server did not finish the import for PUT " + named + " within " + importTimeout);
      }
      try {
        sleeper.sleep(POLL_INTERVAL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CalDavException("Interrupted while waiting for the import for PUT " + named, e);
      }
    }
  }

  /**
   * The {@code ImportStats} a finished task carries as its result.
   *
   * @param result the task's result text, a JSON document
   * @param named the import request, for messages
   * @return the report
   * @throws CalDavException when the result is not an import report
   */
  private ImportReport reportOf(String result, URI named) {
    if (StringUtils.isBlank(result)) {
      throw new CalDavException("The calendar server reported no import result for PUT " + named);
    }
    JsonNode stats = session.parse(result, named);
    JsonNode uids = stats.get("uids");
    List<String> imported = new ArrayList<>();
    if (uids != null && uids.isArray()) {
      uids.forEach(uid -> imported.add(uid.asText()));
    }
    JsonNode total = stats.get("total");
    return new ImportReport(List.copyOf(imported), total == null || !total.isNumber() ? imported.size() : total.asInt());
  }

  /**
   * Turns a refusal into the failure the DAV side would raise for the same
   * status, and lets a success through.
   *
   * @param answer the answer
   * @param method the method, for messages
   * @param named the request, for messages
   * @throws CalDavAuthenticationException on 401
   * @throws CalDavForbiddenException on 403
   * @throws CalDavException on anything that is not a 2xx
   */
  private void refuse(Answer answer, String method, URI named) {
    int status = answer.status();
    if (status == 401) {
      throw new CalDavAuthenticationException("The calendar server refused the session for " + method + " " + named);
    }
    if (status == 403) {
      throw new CalDavForbiddenException("The calendar server refused " + method + " " + named + " (403); this account may not"
          + " write that calendar");
    }
    if (status < 200 || status >= 300) {
      String code = faultCode(answer, named);
      throw new CalDavException("The calendar server answered " + status + (code == null ? "" : " (" + code + ")") + " for "
          + method + " " + named);
    }
  }

  /**
   * The {@code errorCode} of a fault body, when the body is one.
   *
   * <p>
   * Only the code is carried into a message: the fault's {@code message} is
   * BlueMind's own exception text, which this add-on does not control and
   * therefore never quotes in an exception. It is logged at debug for whoever
   * is investigating.
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
        LOG.debug("The calendar server answered {} for {}: {} {}",
                  answer.status(),
                  named,
                  code,
                  BlueMindRestSession.textOf(fault, "message"));
      }
      return code;
    } catch (CalDavException e) {
      return null;
    }
  }

  /**
   * The deadline the deployment declares, or the default.
   *
   * @return the wait for one import task
   */
  private static Duration configuredTimeout() {
    String declared = System.getProperty(IMPORT_TIMEOUT_PROPERTY);
    if (StringUtils.isBlank(declared)) {
      return DEFAULT_IMPORT_TIMEOUT;
    }
    try {
      int seconds = Integer.parseInt(declared.trim());
      return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_IMPORT_TIMEOUT;
    } catch (NumberFormatException e) {
      return DEFAULT_IMPORT_TIMEOUT;
    }
  }
}

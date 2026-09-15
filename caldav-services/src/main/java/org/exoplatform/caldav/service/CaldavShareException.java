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

import java.util.List;

/**
 * A share of a calendar that could not be read, granted or removed for a
 * reason that is neither the caller's request nor their rights: the account,
 * the server's capability, or the server's own answer (EXO-90253).
 *
 * <p>
 * The code is the contract, the one the browser translates; the
 * preconditions and privileges are what the server said when it refused, so
 * the refusal can be shown as the server stated it rather than as a generic
 * failure.
 */
public class CaldavShareException extends RuntimeException {

  private static final long  serialVersionUID = 6124186957076217611L;

  private final String       code;

  private final List<String> preconditions;

  private final List<String> missingPrivileges;

  /**
   * A failure named by its code alone.
   *
   * @param code the message code
   */
  public CaldavShareException(String code) {
    this(code, List.of(), List.of(), null);
  }

  /**
   * A failure named by its code, with its cause.
   *
   * @param code the message code
   * @param cause what went wrong underneath
   */
  public CaldavShareException(String code, Throwable cause) {
    this(code, List.of(), List.of(), cause);
  }

  /**
   * A refusal the server explained.
   *
   * @param code the message code
   * @param preconditions the preconditions the server's error body named
   * @param missingPrivileges the privileges it said were missing
   * @param cause what went wrong underneath, may be null
   */
  public CaldavShareException(String code, List<String> preconditions, List<String> missingPrivileges, Throwable cause) {
    super(code, cause);
    this.code = code;
    this.preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
    this.missingPrivileges = missingPrivileges == null ? List.of() : List.copyOf(missingPrivileges);
  }

  /**
   * The message code the browser translates.
   *
   * @return the code
   */
  public String getCode() {
    return code;
  }

  /**
   * What the server's refusal named.
   *
   * @return the preconditions, possibly empty
   */
  public List<String> getPreconditions() {
    return preconditions;
  }

  /**
   * The privileges the server said were missing.
   *
   * @return the privileges, possibly empty
   */
  public List<String> getMissingPrivileges() {
    return missingPrivileges;
  }
}

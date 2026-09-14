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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

/**
 * What a resource says it supports in answer to {@code OPTIONS}: the
 * compliance classes of its {@code DAV} header (RFC 4918 §10.1) and the
 * methods of its {@code Allow} header (RFC 9110 §10.2.1) — the two pieces of
 * evidence the sharing capability is selected from (EXO-90253).
 *
 * <p>
 * Normalised once, here: compliance classes are compared case-insensitively
 * and methods by their upper-case name, and a header a server repeats is read
 * as the union of its values, which is what RFC 9110 §5.3 makes of a list
 * header sent twice.
 *
 * @param davTokens the compliance classes, lower-cased, never null
 * @param allowedMethods the allowed methods, upper-cased, never null
 */
public record DavOptions(Set<String> davTokens, Set<String> allowedMethods) {

  /**
   * The evidence as two raw header value lists.
   *
   * @param davHeaders every {@code DAV} header value the server sent
   * @param allowHeaders every {@code Allow} header value the server sent
   * @return the normalised evidence
   */
  public static DavOptions of(List<String> davHeaders, List<String> allowHeaders) {
    return new DavOptions(tokens(davHeaders, false), tokens(allowHeaders, true));
  }

  /**
   * Whether the {@code DAV} header carries a compliance class.
   *
   * @param token the class, any case
   * @return true when advertised
   */
  public boolean advertises(String token) {
    return token != null && davTokens.contains(token.trim().toLowerCase(Locale.ROOT));
  }

  /**
   * Whether the {@code Allow} header names a method.
   *
   * @param method the method name, any case
   * @return true when allowed
   */
  public boolean allows(String method) {
    return method != null && allowedMethods.contains(method.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * The comma-separated elements of every value of one list header.
   *
   * @param values the header values, may be null
   * @param upperCase whether elements are upper-cased (methods) rather than
   *          lower-cased (compliance classes)
   * @return the elements, never null
   */
  private static Set<String> tokens(List<String> values, boolean upperCase) {
    if (values == null) {
      return Set.of();
    }
    return values.stream()
                 .filter(StringUtils::isNotBlank)
                 .flatMap(value -> Arrays.stream(value.split(",")))
                 .map(String::trim)
                 .filter(StringUtils::isNotBlank)
                 .map(token -> upperCase ? token.toUpperCase(Locale.ROOT) : token.toLowerCase(Locale.ROOT))
                 .collect(Collectors.toUnmodifiableSet());
  }
}

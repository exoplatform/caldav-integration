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

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks the address of a CalDAV server BEFORE the platform is made to connect
 * to it.
 *
 * <p>
 * <b>Why this exists.</b> Once a server is declared, connected users drive
 * requests at that address from the platform, with server-side credentials,
 * over the verbs the relay allows. Every other guard in the relay is
 * client-facing, so before this class the effective trust boundary was
 * whatever an administrator typed — and an address pointing inside the
 * deployment's own network turned eXo into the thing that reaches it. That is
 * a server-side request forgery surface, open by omission rather than by
 * decision.
 *
 * <p>
 * <b>What it decides.</b> Scheme (https, http only where the deployment opts
 * in), host (loopback, link-local and private ranges refused by default), and
 * shape (no credentials in the URL, no fragment, no port outside the allowed
 * set). It <b>refuses</b>; it never rewrites what it was given, because a
 * sanitised address is an address nobody typed and nobody can reason about.
 *
 * <p>
 * <b>What it does not close — say it plainly.</b> The host check resolves the
 * name and judges the addresses it gets back <i>at declaration time</i>. A
 * name that resolves inside the allowed set now can resolve to a blocked
 * address a second later, and nothing here would know: that is DNS rebinding,
 * and validating at declaration time reduces the surface without closing it.
 * Closing it needs the check to move to connection time, per connection, on
 * the address actually dialled — which the JDK HTTP client does not offer a
 * hook for. Nothing in this class should be read as claiming otherwise.
 *
 * <p>
 * <b>The {@code {username}} placeholder.</b> A declared URL may carry one; it
 * is substituted per connecting user. It is refused in the scheme or authority
 * position, where a substituted value could move the request to another host,
 * and accepted after it, where it cannot. The rest of the URL is then checked
 * with the placeholder replaced by a plain sentinel, so the template is
 * validated in the shape it will actually be used in rather than rejected for
 * carrying a brace.
 */
@Component
public class CaldavServerUrlValidator {

  /** Refused: the string is not a usable absolute http(s) URL at all. */
  public static final String       MALFORMED_MESSAGE            = "caldav.server.url.malformed";

  /** Refused: the scheme is outside the allowed set. */
  public static final String       SCHEME_NOT_ALLOWED_MESSAGE   = "caldav.server.url.schemeNotAllowed";

  /** Refused: the URL carries a user name or password in its authority. */
  public static final String       CREDENTIALS_MESSAGE          = "caldav.server.url.credentials";

  /** Refused: the URL carries a fragment. */
  public static final String       FRAGMENT_MESSAGE             = "caldav.server.url.fragment";

  /** Refused: the port is outside the allowed set. */
  public static final String       PORT_NOT_ALLOWED_MESSAGE     = "caldav.server.url.portNotAllowed";

  /** Refused: the {@code {username}} placeholder sits in the authority. */
  public static final String       USERNAME_IN_AUTHORITY_MESSAGE = "caldav.server.url.usernameInAuthority";

  /** Refused: the host points at a loopback, link-local or private address. */
  public static final String       PRIVATE_ADDRESS_MESSAGE      = "caldav.server.url.privateAddress";

  /** Refused: the host resolves to nothing. */
  public static final String       UNRESOLVABLE_MESSAGE         = "caldav.server.url.unresolvable";

  /** The placeholder a declared URL may carry, substituted per user. */
  public static final String       USERNAME_PLACEHOLDER         = "{username}";

  /**
   * What the placeholder becomes while the URL is being checked: a plain
   * lowercase word, legal wherever the placeholder is legal, so the checks
   * below run over a URL of the shape the platform will actually build.
   */
  private static final String      PLACEHOLDER_SENTINEL         = "exocaldavuser";

  private static final String      SCHEME_SEPARATOR             = "://";

  private final Set<String>        allowedSchemes;

  private final Set<Integer>       allowedPorts;

  private final Set<String>        allowedHosts;

  private final boolean            allowPrivateAddresses;

  private final CaldavHostResolver hostResolver;

  /**
   * The validator Spring builds, reading the deployment's configuration and
   * resolving names through the JDK.
   *
   * @param allowedSchemes comma-separated URL schemes an administrator may
   *          declare; {@code https} alone by default, so a deployment fronting
   *          a plain-http CalDAV server has to say so
   * @param allowedPorts comma-separated ports a declared URL may reach,
   *          implicit default ports included; {@code 80,443} by default
   * @param allowedHosts comma-separated hosts exempted from the private-address
   *          block — the narrow opt-out, for the deployment whose CalDAV server
   *          genuinely is internal; empty by default
   * @param allowPrivateAddresses whether ANY private, loopback or link-local
   *          address may be declared; false by default, and the blunt
   *          instrument next to the host list above
   */
  @Autowired
  public CaldavServerUrlValidator(@Value("${exo.agenda.caldav.server.allowedSchemes:https}")
                                  String allowedSchemes,
                                  @Value("${exo.agenda.caldav.server.allowedPorts:80,443}")
                                  String allowedPorts,
                                  @Value("${exo.agenda.caldav.server.allowedHosts:}")
                                  String allowedHosts,
                                  @Value("${exo.agenda.caldav.server.allowPrivateAddresses:false}")
                                  boolean allowPrivateAddresses) {
    this(allowedSchemes, allowedPorts, allowedHosts, allowPrivateAddresses, InetAddress::getAllByName);
  }

  /**
   * The seam the tests use: the same validator with its name resolution handed
   * in, so the address checks can be exercised over a table instead of over
   * whatever the machine's resolver believes.
   *
   * @param allowedSchemes comma-separated URL schemes an administrator may declare
   * @param allowedPorts comma-separated ports a declared URL may reach
   * @param allowedHosts comma-separated hosts exempted from the private-address block
   * @param allowPrivateAddresses whether private addresses may be declared at all
   * @param hostResolver the resolution to judge declared hosts with
   */
  CaldavServerUrlValidator(String allowedSchemes,
                           String allowedPorts,
                           String allowedHosts,
                           boolean allowPrivateAddresses,
                           CaldavHostResolver hostResolver) {
    this.allowedSchemes = lowerCaseSet(allowedSchemes);
    this.allowedPorts = portSet(allowedPorts);
    this.allowedHosts = lowerCaseSet(allowedHosts);
    this.allowPrivateAddresses = allowPrivateAddresses;
    this.hostResolver = hostResolver;
  }

  /**
   * Refuses a declared CalDAV server address the platform must not be made to
   * connect to, with the message code the REST layer answers 400 with — at
   * declaration time, where an administrator reads the reason, rather than at
   * synchronisation time, where the same refusal is an unexplained sync
   * failure.
   *
   * @param declaredUrl the address as the administrator typed it, placeholder
   *          included
   * @throws IllegalArgumentException carrying a translatable message code when
   *           the address is refused
   */
  public void validate(String declaredUrl) {
    String url = StringUtils.trim(declaredUrl);
    if (StringUtils.isBlank(url) || containsUnsafeCharacter(url)) {
      throw new IllegalArgumentException(MALFORMED_MESSAGE);
    }
    if (url.indexOf('#') >= 0) {
      throw new IllegalArgumentException(FRAGMENT_MESSAGE);
    }
    rejectPlaceholderInAuthority(url);
    URI uri = parse(url.replace(USERNAME_PLACEHOLDER, PLACEHOLDER_SENTINEL));
    String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ENGLISH);
    if (!allowedSchemes.contains(scheme)) {
      throw new IllegalArgumentException(SCHEME_NOT_ALLOWED_MESSAGE);
    }
    if (uri.getRawUserInfo() != null || StringUtils.contains(uri.getRawAuthority(), '@')) {
      throw new IllegalArgumentException(CREDENTIALS_MESSAGE);
    }
    String host = hostOf(uri);
    if (!allowedPorts.contains(effectivePort(uri, scheme))) {
      throw new IllegalArgumentException(PORT_NOT_ALLOWED_MESSAGE);
    }
    checkAddresses(host);
  }

  /**
   * Refuses the {@code {username}} placeholder anywhere in the scheme or the
   * authority.
   *
   * <p>
   * This is the check that keeps the placeholder from becoming the hole. A
   * template such as {@code https://{username}.dav.example.org/} is a
   * per-user <i>destination</i>, not a per-user path: whoever controls the
   * substituted value chooses the host the platform connects to — and the
   * substitution the connector performs allows {@code @}, so even
   * {@code https://{username}.example.org/} lets a value like
   * {@code x@attacker.test} re-cut the authority and send the request
   * elsewhere. After the authority the substituted value can add path or query,
   * never a different host, which is why it is allowed there.
   *
   * @param url the trimmed declared URL, placeholder still in place
   * @throws IllegalArgumentException when the placeholder sits in the authority
   */
  private void rejectPlaceholderInAuthority(String url) {
    int separator = url.indexOf(SCHEME_SEPARATOR);
    int authorityEnd = url.length();
    if (separator >= 0) {
      for (int i = separator + SCHEME_SEPARATOR.length(); i < url.length(); i++) {
        char c = url.charAt(i);
        if (c == '/' || c == '?' || c == '#') {
          authorityEnd = i;
          break;
        }
      }
    }
    if (url.substring(0, authorityEnd).contains(USERNAME_PLACEHOLDER)) {
      throw new IllegalArgumentException(USERNAME_IN_AUTHORITY_MESSAGE);
    }
  }

  /**
   * Parses the sentinel-substituted URL, refusing anything that names no
   * scheme at all.
   *
   * <p>
   * Only absoluteness is decided here, deliberately: a relative reference is
   * not an address, whereas an absolute URL naming a scheme nobody allows is
   * better reported as the wrong scheme than as an unreadable string —
   * {@code file:///etc/passwd} and {@code javascript:alert(1)} both tell the
   * administrator something true only if the scheme check gets to speak.
   *
   * @param url the URL to parse, placeholder already replaced
   * @return the parsed URI
   * @throws IllegalArgumentException when the string is not a usable URL
   */
  private URI parse(String url) {
    try {
      URI uri = new URI(url);
      if (!uri.isAbsolute() || StringUtils.isBlank(uri.getScheme())) {
        throw new IllegalArgumentException(MALFORMED_MESSAGE);
      }
      return uri;
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(MALFORMED_MESSAGE);
    }
  }

  /**
   * The host a declared URL names, brackets stripped from an IPv6 literal.
   *
   * <p>
   * A URL whose authority the JDK cannot read as a host — an underscore in a
   * label, an empty authority, a bare path — is refused rather than guessed
   * at: an address the platform cannot agree on with its own HTTP client is
   * not an address worth storing.
   *
   * @param uri the parsed URL
   * @return the host, never blank
   * @throws IllegalArgumentException when the URL names no readable host
   */
  private String hostOf(URI uri) {
    String host = uri.getHost();
    if (StringUtils.isBlank(host)) {
      throw new IllegalArgumentException(MALFORMED_MESSAGE);
    }
    return StringUtils.removeEnd(StringUtils.removeStart(host, "["), "]");
  }

  /**
   * The port a declared URL actually reaches: the one it states, else the
   * scheme's default. Checking the effective port rather than only an explicit
   * one keeps the allow-list meaning what it says — a deployment narrowing it
   * to 8443 has not also allowed every implicit 443.
   *
   * @param uri the parsed URL
   * @param scheme the lower-cased scheme
   * @return the port the request would go to
   */
  private int effectivePort(URI uri, String scheme) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "http".equals(scheme) ? 80 : 443;
  }

  /**
   * Resolves the declared host and refuses it when ANY address it points at is
   * one the platform must not be driven to — loopback, link-local, private,
   * carrier-grade NAT, multicast, broadcast, the unspecified address, or an
   * IPv6 unique-local one.
   *
   * <p>
   * Every returned address is judged, not the first: a name answering with one
   * public address and one loopback address is a name that reaches loopback.
   *
   * <p>
   * A host the deployment listed in {@code allowedHosts} skips this check
   * entirely — that list is the deployment saying, explicitly, that this
   * internal server is the one it means.
   *
   * @param host the declared host, brackets already stripped
   * @throws IllegalArgumentException when the host resolves to nothing, or to
   *           an address the platform must not reach
   */
  private void checkAddresses(String host) {
    if (allowPrivateAddresses || allowedHosts.contains(StringUtils.lowerCase(host, Locale.ENGLISH))) {
      return;
    }
    InetAddress[] addresses;
    try {
      addresses = hostResolver.resolve(host);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(UNRESOLVABLE_MESSAGE);
    }
    if (addresses == null || addresses.length == 0) {
      throw new IllegalArgumentException(UNRESOLVABLE_MESSAGE);
    }
    for (InetAddress address : addresses) {
      if (isBlocked(address)) {
        throw new IllegalArgumentException(PRIVATE_ADDRESS_MESSAGE);
      }
    }
  }

  /**
   * Whether one resolved address is outside what a declared CalDAV server may
   * point at.
   *
   * <p>
   * The JDK predicates cover most of it; the byte inspection below covers what
   * they do not — 0.0.0.0/8, carrier-grade NAT, the IPv4 broadcast address,
   * IPv6 unique-local space, and the IPv4 address embedded in an IPv4-mapped
   * or IPv4-compatible IPv6 address, which is how {@code ::ffff:127.0.0.1}
   * would otherwise walk past a loopback check.
   *
   * @param address one address the declared host resolves to
   * @return true when the platform must not be driven to it
   */
  private boolean isBlocked(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (bytes.length == 4) {
      return isBlockedIpv4(bytes);
    }
    if (bytes.length == 16) {
      if ((bytes[0] & 0xFE) == 0xFC) {
        // fc00::/7 — IPv6 unique local addresses, the site-local replacement
        // the JDK's isSiteLocalAddress does not recognise.
        return true;
      }
      byte[] embedded = embeddedIpv4(bytes);
      return embedded.length == 4 && isBlockedIpv4(embedded);
    }
    return false;
  }

  /**
   * Whether four address bytes name an IPv4 address the platform must not be
   * driven to, over and above what the JDK predicates already refused.
   *
   * @param bytes the four bytes of an IPv4 address
   * @return true when the address is out of bounds
   */
  private boolean isBlockedIpv4(byte[] bytes) {
    int first = bytes[0] & 0xFF;
    int second = bytes[1] & 0xFF;
    return first == 0                                  // 0.0.0.0/8, "this network"
        || first == 127                                // loopback, restated for the embedded case
        || first == 255                                // broadcast
        || (first == 10)                               // RFC 1918, restated for the embedded case
        || (first == 172 && second >= 16 && second <= 31)
        || (first == 192 && second == 168)
        || (first == 169 && second == 254)             // link-local, restated for the embedded case
        || (first == 100 && second >= 64 && second <= 127); // RFC 6598 carrier-grade NAT
  }

  /**
   * The IPv4 address embedded in an IPv4-mapped ({@code ::ffff:a.b.c.d}) or
   * IPv4-compatible ({@code ::a.b.c.d}) IPv6 address, when there is one.
   *
   * @param bytes the sixteen bytes of an IPv6 address
   * @return the four embedded IPv4 bytes, or an empty array when the address
   *         embeds none — a real answer is always four bytes, so an empty one
   *         is unambiguous
   */
  private byte[] embeddedIpv4(byte[] bytes) {
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return new byte[0];
      }
    }
    boolean mapped = (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    boolean compatible = bytes[10] == 0 && bytes[11] == 0;
    if (!mapped && !compatible) {
      return new byte[0];
    }
    return new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
  }

  /**
   * Whether the declared URL carries a character it must not — a space, a
   * control character, a newline, or anything outside printable ASCII.
   *
   * <p>
   * The first three {@link URI} would refuse on its own; the last it does NOT
   * — {@code https://dav.example.org/dav/caf\u00e9/} parses cleanly and keeps
   * its non-ASCII path. This guard is what refuses it, and it is why the guard
   * is not redundant with parsing: an address the platform will have to put on
   * a request line is required percent-encoded, because otherwise "the same
   * address" stops being a question with one answer.
   *
   * @param url the trimmed declared URL
   * @return true when the string holds a character outside printable ASCII
   */
  private boolean containsUnsafeCharacter(String url) {
    for (int i = 0; i < url.length(); i++) {
      char c = url.charAt(i);
      if (c <= 0x20 || c >= 0x7F) {
        return true;
      }
    }
    return false;
  }

  /**
   * Splits a comma-separated configuration value into a set of lower-cased,
   * trimmed entries, blanks dropped.
   *
   * @param value the configured value, possibly null or blank
   * @return the entries, never null
   */
  private Set<String> lowerCaseSet(String value) {
    Set<String> entries = new LinkedHashSet<>();
    if (StringUtils.isBlank(value)) {
      return entries;
    }
    Arrays.stream(StringUtils.split(value, ','))
          .map(entry -> StringUtils.lowerCase(StringUtils.trim(entry), Locale.ENGLISH))
          .filter(StringUtils::isNotBlank)
          .forEach(entries::add);
    return entries;
  }

  /**
   * Splits a comma-separated configuration value into a set of port numbers,
   * ignoring what is not one rather than failing the context — a typo in a
   * deployment property must narrow what is allowed, never widen it.
   *
   * @param value the configured value, possibly null or blank
   * @return the ports, never null
   */
  private Set<Integer> portSet(String value) {
    Set<Integer> ports = new LinkedHashSet<>();
    if (StringUtils.isBlank(value)) {
      return ports;
    }
    for (String entry : StringUtils.split(value, ',')) {
      String trimmed = StringUtils.trim(entry);
      if (StringUtils.isNumeric(trimmed)) {
        ports.add(Integer.valueOf(trimmed));
      }
    }
    return ports;
  }

}

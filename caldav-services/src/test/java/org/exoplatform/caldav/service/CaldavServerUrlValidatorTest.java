/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.caldav.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The address of a CalDAV server is a security control, not a form field: once
 * declared, connected users drive credentialed requests at it from inside the
 * platform. So these tests are adversarial rather than confirmatory — the
 * cases that matter are the ones written to slip past, not the ones written to
 * pass.
 *
 * <p>
 * Name resolution is a table, never the machine's resolver: an address check
 * that needs DNS to be tested is a check nobody runs, and a suite that reaches
 * the network is a suite that goes red for reasons unrelated to the code. The
 * table below is what the validator is told the world looks like.
 */
public class CaldavServerUrlValidatorTest {

  /**
   * What every host in these tests resolves to. Anything absent is unknown,
   * which is itself one of the refusals pinned below.
   */
  private static final Map<String, InetAddress[]> DNS = new HashMap<>();

  static {
    DNS.put("dav.example.org", addresses("203.0.113.10"));
    DNS.put("public.example.org", addresses("203.0.113.20"));
    DNS.put("localhost", addresses("127.0.0.1"));
    DNS.put("internal.example.org", addresses("10.1.2.3"));
    DNS.put("metadata.example.org", addresses("169.254.169.254"));
    DNS.put("ula.example.org", addresses("fd00::1"));
    DNS.put("v6.example.org", addresses("2001:db8::1"));
    // One name, two answers: a public address AND a loopback one. Judging the
    // first address only would let this through.
    DNS.put("mixed.example.org", addresses("203.0.113.10", "127.0.0.1"));
    // The spellings of 127.0.0.1 a resolver still accepts on many platforms.
    // The point is not how the host is written, it is where it lands.
    DNS.put("2130706433", addresses("127.0.0.1"));
    DNS.put("0177.0.0.1", addresses("127.0.0.1"));
    // IP literals resolve to themselves, exactly as the JDK resolver does.
    DNS.put("0.0.0.0", addresses("0.0.0.0"));
    DNS.put("127.0.0.1", addresses("127.0.0.1"));
    DNS.put("10.1.2.3", addresses("10.1.2.3"));
    DNS.put("172.16.0.5", addresses("172.16.0.5"));
    DNS.put("192.168.1.5", addresses("192.168.1.5"));
    DNS.put("169.254.169.254", addresses("169.254.169.254"));
    DNS.put("100.64.0.1", addresses("100.64.0.1"));
    DNS.put("255.255.255.255", addresses("255.255.255.255"));
    DNS.put("203.0.113.10", addresses("203.0.113.10"));
    DNS.put("::1", addresses("::1"));
    DNS.put("2001:db8::1", addresses("2001:db8::1"));
    // ::ffff:127.0.0.1 kept as a REAL Inet6Address rather than the Inet4Address
    // the JDK silently folds it into, because that fold is what would otherwise
    // hide the case: an Inet6Address holding a mapped loopback answers false to
    // isLoopbackAddress.
    DNS.put("::ffff:127.0.0.1", new InetAddress[] { mappedIpv6(127, 0, 0, 1) });
  }

  /**
   * The validator as a deployment gets it out of the box: https only, ports 80
   * and 443, no host exempted, private addresses refused.
   */
  private final CaldavServerUrlValidator defaults = validator("https", "80,443", "", false);

  /**
   * Builds a validator over the test's resolution table.
   *
   * @param schemes comma-separated allowed schemes
   * @param ports comma-separated allowed ports
   * @param hosts comma-separated hosts exempted from the address block
   * @param allowPrivate whether private addresses are allowed outright
   * @return the validator to exercise
   */
  private CaldavServerUrlValidator validator(String schemes, String ports, String hosts, boolean allowPrivate) {
    return new CaldavServerUrlValidator(schemes, ports, hosts, allowPrivate, CaldavServerUrlValidatorTest::resolve);
  }

  /**
   * The table resolver handed to every validator here.
   *
   * @param host host of a declared URL
   * @return the addresses the table says it points at
   * @throws UnknownHostException when the table holds no answer
   */
  private static InetAddress[] resolve(String host) throws UnknownHostException {
    InetAddress[] answer = DNS.get(host);
    if (answer == null) {
      throw new UnknownHostException(host);
    }
    return answer;
  }

  /**
   * Parses IP literals into addresses without touching the network — the JDK
   * parses a literal without a query.
   *
   * @param literals the IP literals to parse
   * @return the parsed addresses
   */
  private static InetAddress[] addresses(String... literals) {
    InetAddress[] parsed = new InetAddress[literals.length];
    for (int i = 0; i < literals.length; i++) {
      try {
        parsed[i] = InetAddress.getByName(literals[i]);
      } catch (UnknownHostException e) {
        throw new IllegalStateException("The literal " + literals[i] + " should parse without a resolver", e);
      }
    }
    return parsed;
  }

  /**
   * An IPv4-mapped IPv6 address that stays an {@link Inet6Address} — the shape
   * a hostile resolver answer would take.
   *
   * @param a first byte of the embedded IPv4 address
   * @param b second byte of the embedded IPv4 address
   * @param c third byte of the embedded IPv4 address
   * @param d fourth byte of the embedded IPv4 address
   * @return the mapped address, sixteen bytes long
   */
  private static InetAddress mappedIpv6(int a, int b, int c, int d) {
    byte[] bytes = new byte[16];
    bytes[10] = (byte) 0xFF;
    bytes[11] = (byte) 0xFF;
    bytes[12] = (byte) a;
    bytes[13] = (byte) b;
    bytes[14] = (byte) c;
    bytes[15] = (byte) d;
    try {
      return Inet6Address.getByAddress("mapped", bytes, 0);
    } catch (UnknownHostException e) {
      throw new IllegalStateException("A sixteen-byte address should never fail to build", e);
    }
  }

  /**
   * Asserts one URL is refused, and refused for the stated reason — the code
   * matters, because it is what the administrator reads.
   *
   * @param validator the validator to ask
   * @param url the declared URL
   * @param expectedCode the message code the refusal must carry
   */
  private void assertRefused(CaldavServerUrlValidator validator, String url, String expectedCode) {
    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> validator.validate(url),
                                                    "Expected " + url + " to be refused");
    assertEquals(expectedCode, refusal.getMessage(), "Wrong refusal reason for " + url);
  }

  /**
   * An ordinary https address on a public host is accepted with no ceremony —
   * the case that must not become collateral damage of everything below.
   */
  @Test
  public void shouldAcceptAnOrdinaryHttpsAddress() {
    assertDoesNotThrow(() -> defaults.validate("https://dav.example.org/dav/"));
    assertDoesNotThrow(() -> defaults.validate("https://public.example.org:443/dav/cal/"));
    assertDoesNotThrow(() -> defaults.validate("https://dav.example.org/dav/?filter=x"));
    assertDoesNotThrow(() -> defaults.validate("  https://dav.example.org/dav/  "));
    assertDoesNotThrow(() -> defaults.validate("https://v6.example.org/dav/"));
    assertDoesNotThrow(() -> defaults.validate("https://[2001:db8::1]/dav/"));
  }

  /**
   * A declared URL carrying the {@code {username}} placeholder after the
   * authority is accepted, and validated in the shape it will be used in — the
   * template is not rejected for carrying a brace, and not waved through
   * either.
   */
  @Test
  public void shouldAcceptThePlaceholderAfterTheAuthority() {
    assertDoesNotThrow(() -> defaults.validate("https://dav.example.org/cal/{username}/"));
    assertDoesNotThrow(() -> defaults.validate("https://dav.example.org/{username}/cal/{username}/"));
    assertDoesNotThrow(() -> defaults.validate("https://dav.example.org/dav?user={username}"));
    // The URL around the placeholder is still checked: substituting it cannot
    // repair a scheme, a port or a host that was never allowed.
    assertRefused(defaults, "http://dav.example.org/cal/{username}/", CaldavServerUrlValidator.SCHEME_NOT_ALLOWED_MESSAGE);
    assertRefused(defaults, "https://internal.example.org/cal/{username}/", CaldavServerUrlValidator.PRIVATE_ADDRESS_MESSAGE);
  }

  /**
   * The placeholder is refused anywhere in the scheme or the authority, which
   * is where a hostile substitution stops being a path and becomes a
   * destination.
   *
   * <p>
   * The substitution the connector performs allows {@code @} and {@code %}, so
   * {@code https://{username}.example.org/} with the value
   * {@code x@attacker.test} would re-cut the authority and send the platform's
   * credentialed request to another host entirely. Refusing the template is
   * what closes that, because the substituted value itself is a user's CalDAV
   * account name and cannot be constrained further without breaking real
   * accounts.
   */
  @Test
  public void shouldRefuseThePlaceholderInTheAuthority() {
    String[] templates = { "https://{username}.dav.example.org/cal/", "https://{username}@dav.example.org/cal/",
        "https://dav.example.org:{username}/cal/", "{username}://dav.example.org/cal/", "https://{username}/cal/" };
    for (String template : templates) {
      assertRefused(defaults, template, CaldavServerUrlValidator.USERNAME_IN_AUTHORITY_MESSAGE);
    }
  }

  /**
   * Loopback is refused however it is spelled: by name, by dotted quad, by the
   * packed-decimal and octal forms a resolver still accepts, by IPv6, and by
   * the IPv4-mapped IPv6 form whose {@code isLoopbackAddress} answers false.
   */
  @Test
  public void shouldRefuseLoopbackHoweverItIsSpelled() {
    String[] hosts = { "localhost", "127.0.0.1", "2130706433", "0177.0.0.1", "[::1]", "[::ffff:127.0.0.1]" };
    for (String host : hosts) {
      assertRefused(defaults, "https://" + host + "/dav/", CaldavServerUrlValidator.PRIVATE_ADDRESS_MESSAGE);
    }
  }

  /**
   * The rest of the address space a platform must not be driven into: RFC 1918,
   * the unspecified address, the broadcast address, link-local — including
   * 169.254.169.254, the cloud instance-metadata endpoint, which is the single
   * most valuable target an SSRF has — carrier-grade NAT, and IPv6 unique-local
   * space.
   */
  @Test
  public void shouldRefusePrivateLinkLocalAndReservedAddresses() {
    String[] hosts = { "10.1.2.3", "172.16.0.5", "192.168.1.5", "0.0.0.0", "255.255.255.255", "169.254.169.254",
        "100.64.0.1", "internal.example.org", "metadata.example.org", "ula.example.org" };
    for (String host : hosts) {
      assertRefused(defaults, "https://" + host + "/dav/", CaldavServerUrlValidator.PRIVATE_ADDRESS_MESSAGE);
    }
  }

  /**
   * A name answering with one public address and one loopback address is a name
   * that reaches loopback. Every address is judged, not the first.
   */
  @Test
  public void shouldRefuseAHostWhoseAnswersAreOnlyPartlyPublic() {
    assertRefused(defaults, "https://mixed.example.org/dav/", CaldavServerUrlValidator.PRIVATE_ADDRESS_MESSAGE);
  }

  /**
   * A host that resolves to nothing is refused rather than stored and left to
   * fail at synchronisation time, where the reason would be invisible.
   */
  @Test
  public void shouldRefuseAHostThatResolvesToNothing() {
    assertRefused(defaults, "https://nowhere.example.invalid/dav/", CaldavServerUrlValidator.UNRESOLVABLE_MESSAGE);
  }

  /**
   * Shape refusals: credentials in the URL, a fragment, a port outside the
   * allowed set. None of these belong in a stored server address, and each is
   * named separately so the administrator is told which one they hit.
   */
  @Test
  public void shouldRefuseCredentialsFragmentAndOddPorts() {
    assertRefused(defaults, "https://user:secret@dav.example.org/dav/", CaldavServerUrlValidator.CREDENTIALS_MESSAGE);
    assertRefused(defaults, "https://user@dav.example.org/dav/", CaldavServerUrlValidator.CREDENTIALS_MESSAGE);
    assertRefused(defaults, "https://dav.example.org/dav/#frag", CaldavServerUrlValidator.FRAGMENT_MESSAGE);
    assertRefused(defaults, "https://dav.example.org:8443/dav/", CaldavServerUrlValidator.PORT_NOT_ALLOWED_MESSAGE);
    // The allow-list means what it says: narrowing it to 8443 does not leave
    // the implicit 443 of every other URL quietly allowed.
    assertRefused(validator("https", "8443", "", false),
                  "https://dav.example.org/dav/",
                  CaldavServerUrlValidator.PORT_NOT_ALLOWED_MESSAGE);
    assertDoesNotThrow(() -> validator("https", "8443", "", false).validate("https://dav.example.org:8443/dav/"));
  }

  /**
   * Anything that is not an absolute http(s) URL at all: a scheme that reaches
   * the file system or another protocol, a host the JDK cannot read, a blank,
   * and a string carrying a space or a newline — the last being how a crafted
   * value gets a second header bolted onto a request line.
   */
  @Test
  public void shouldRefuseWhatIsNotAUsableHttpUrl() {
    assertRefused(defaults, "file:///etc/passwd", CaldavServerUrlValidator.SCHEME_NOT_ALLOWED_MESSAGE);
    assertRefused(defaults, "ftp://dav.example.org/dav/", CaldavServerUrlValidator.SCHEME_NOT_ALLOWED_MESSAGE);
    assertRefused(defaults, "javascript:alert(1)", CaldavServerUrlValidator.SCHEME_NOT_ALLOWED_MESSAGE);
    assertRefused(defaults, "/dav/cal/", CaldavServerUrlValidator.MALFORMED_MESSAGE);
    assertRefused(defaults, "dav.example.org/dav/", CaldavServerUrlValidator.MALFORMED_MESSAGE);
    assertRefused(defaults, "https://0x7f.0.0.1/dav/", CaldavServerUrlValidator.MALFORMED_MESSAGE);
    assertRefused(defaults, "https://dav_x.example.org/dav/", CaldavServerUrlValidator.MALFORMED_MESSAGE);
    assertRefused(defaults, "https://dav.example.org/da v/", CaldavServerUrlValidator.MALFORMED_MESSAGE);
    assertRefused(defaults, "https://dav.example.org/dav/\r\nX-Injected: 1", CaldavServerUrlValidator.MALFORMED_MESSAGE);
    // java.net.URI happily PARSES a non-ASCII path, so this one is refused by
    // the printable-ASCII guard and by nothing else. An address the platform
    // will have to put on a request line is required percent-encoded, because
    // what "the same address" means stops being obvious otherwise.
    assertRefused(defaults, "https://dav.example.org/dav/caf\u00e9/", CaldavServerUrlValidator.MALFORMED_MESSAGE);
    assertRefused(defaults, "   ", CaldavServerUrlValidator.MALFORMED_MESSAGE);
    assertRefused(defaults, null, CaldavServerUrlValidator.MALFORMED_MESSAGE);
  }

  /**
   * The local development rig — {@code http://localhost:8888/dav/cal/{username}/}
   * — passes ONLY when the deployment has said all three things out loud, and is
   * refused, one reason at a time, until it has.
   *
   * <p>
   * This is the test that keeps the opt-in an opt-in: there is no shape of this
   * URL that walks through the default configuration, and no single property
   * that opens it either.
   */
  @Test
  public void shouldLetTheLocalRigThroughOnlyWithTheExplicitOptIn() {
    String rig = "http://localhost:8888/dav/cal/{username}/";

    assertRefused(defaults, rig, CaldavServerUrlValidator.SCHEME_NOT_ALLOWED_MESSAGE);
    assertRefused(validator("https,http", "80,443", "", false), rig, CaldavServerUrlValidator.PORT_NOT_ALLOWED_MESSAGE);
    assertRefused(validator("https,http", "80,443,8888", "", false), rig, CaldavServerUrlValidator.PRIVATE_ADDRESS_MESSAGE);

    assertDoesNotThrow(() -> validator("https,http", "80,443,8888", "localhost", false).validate(rig));
  }

  /**
   * The two opt-outs are what they claim and no more: naming a host exempts
   * THAT host from the address block and nothing else, and the blunt
   * {@code allowPrivateAddresses} switch opens the address block without
   * touching scheme, port or shape.
   */
  @Test
  public void shouldScopeTheOptOutsToWhatTheyName() {
    CaldavServerUrlValidator namedHost = validator("https", "80,443", "internal.example.org", false);
    assertDoesNotThrow(() -> namedHost.validate("https://internal.example.org/dav/"));
    // Another internal host is still refused — the exemption is not a mode.
    assertRefused(namedHost, "https://10.1.2.3/dav/", CaldavServerUrlValidator.PRIVATE_ADDRESS_MESSAGE);
    assertRefused(namedHost, "http://internal.example.org/dav/", CaldavServerUrlValidator.SCHEME_NOT_ALLOWED_MESSAGE);

    CaldavServerUrlValidator anyPrivate = validator("https", "80,443", "", true);
    assertDoesNotThrow(() -> anyPrivate.validate("https://10.1.2.3/dav/"));
    assertDoesNotThrow(() -> anyPrivate.validate("https://nowhere.example.invalid/dav/"));
    assertRefused(anyPrivate, "https://10.1.2.3:8443/dav/", CaldavServerUrlValidator.PORT_NOT_ALLOWED_MESSAGE);
    assertRefused(anyPrivate, "https://user@10.1.2.3/dav/", CaldavServerUrlValidator.CREDENTIALS_MESSAGE);
  }

  /**
   * A misspelt port in a deployment property narrows what is allowed, it never
   * widens it — a configuration typo must not be the thing that opens the
   * control.
   */
  @Test
  public void shouldIgnoreUnreadablePortConfiguration() {
    assertRefused(validator("https", "80, four-four-three", "", false),
                  "https://dav.example.org/dav/",
                  CaldavServerUrlValidator.PORT_NOT_ALLOWED_MESSAGE);
    assertRefused(validator("https", "", "", false),
                  "https://dav.example.org/dav/",
                  CaldavServerUrlValidator.PORT_NOT_ALLOWED_MESSAGE);
  }

}

# caldav-integration

This addon is a calendar connector for eXo Platform allowing to read events in a caldav server and display it in eXo. 
It also allow to send event from eXo to the caldav server.
## How to install
Launch this commands :
```
cd ${EXO_HOME}
./addon install exo-caldav-integration
```

## How to configure

Availables properties are :

- exo.agenda.caldav.connector.enabled : true/false (Default true)
- exo.agenda.caldav.connector.url : the url of the caldav server. There is no default value. As some caldav provider need the username inside the caldav url, the url can be set like this : 

`exo.agenda.caldav.connector.url=http://www.myserver.com/webdav/calendar/`

or
`exo.agenda.caldav.connector.url=http://localhost/dav.php/calendars/{username}/`

If exists, {username} will be replaced by the user username to call caldav API. 

## Server-side DAV relay

The browser never talks to a CalDAV server directly: every DAV request goes
through the platform relay at `/caldav/rest/dav/{serverId}/**`, which forwards
it to the declared server the id names — targets resolve only from the
administrator registry, never from a client-supplied URL — and injects the
connected user's stored credentials server-side. The password is therefore
never sent to the page, CORS stops mattering (BlueMind sends no CORS headers),
and each server gets its own path namespace, so two servers advertising
`/dav/`-rooted hrefs cannot collide. Advertised hrefs and Location headers are
rewritten under the per-server prefix by the relay itself.

Relay tuning properties (defaults in parentheses):

- `exo.agenda.caldav.relay.connectTimeoutSeconds` (10)
- `exo.agenda.caldav.relay.requestTimeoutSeconds` (30)
- `exo.agenda.caldav.relay.maxBodyBytes` (20971520) — cap applied to request
  and response bodies alike.


## What a declared server address may be

A declared CalDAV server is an address **the platform itself** connects to,
with server-side credentials, over the verbs the relay allows, whenever a
connected user drives a request at it. So the address an administrator types is
a trust boundary, not a preference: pointed at an internal address, eXo becomes
the thing that reaches it. Every declared address is therefore checked **before
it is stored** — at declaration time, where the administrator reads the reason,
rather than at synchronisation time, where the same refusal is an unexplained
sync failure. The check **refuses**; it never rewrites what it was given.

What is refused by default: a scheme other than `https`; a host that resolves
to a loopback, link-local, private, carrier-grade-NAT, multicast, broadcast or
IPv6 unique-local address (every address the name answers with is judged, not
the first); a URL carrying credentials, a fragment, or a port outside the
allowed set; and a `{username}` placeholder sitting in the scheme or authority,
where a substituted value could move the request to another host — after the
authority it is fine, and that is where it belongs.

**What this does not close.** The host check resolves the name and judges the
answer *at declaration time*. A name that resolves inside the allowed set now
can resolve to a blocked address a second later, and nothing here would know:
that is DNS rebinding. Validating at declaration time reduces the surface; it
does not close it.

Tuning properties (defaults in parentheses):

- `exo.agenda.caldav.server.allowedSchemes` (`https`) — comma-separated URL
  schemes an administrator may declare. A deployment fronting a plain-http
  CalDAV server has to say so.
- `exo.agenda.caldav.server.allowedPorts` (`80,443`) — comma-separated ports a
  declared URL may reach, implicit default ports included. An unreadable entry
  is ignored, so a typo narrows what is allowed and never widens it.
- `exo.agenda.caldav.server.allowedHosts` (empty) — comma-separated hosts
  exempted from the private-address block. The narrow opt-out, for the
  deployment whose CalDAV server genuinely is internal. Prefer it over the
  switch below: it names the one server you mean.
- `exo.agenda.caldav.server.allowPrivateAddresses` (`false`) — whether ANY
  loopback, link-local or private address may be declared. The blunt
  instrument; leave it false unless the internal server's address cannot be
  named ahead of time.

For the **local development rig**, whose Stalwart server answers on
`http://localhost:8888/dav/cal/{username}/`, all three of these are needed
before that address can be declared or re-activated from the administration
screen — one property per thing that is unusual about it:

```
exo.agenda.caldav.server.allowedSchemes=https,http
exo.agenda.caldav.server.allowedPorts=80,443,8888
exo.agenda.caldav.server.allowedHosts=localhost
```

The two rows seeded into an empty registry at startup (Stalwart from
`exo.agenda.caldav.connector.url`, and Bluemind) are product bootstrap rather
than administrator input and are **not** gated by this check — the Stalwart
default points at the rig by design and the Bluemind row ships a placeholder
host. When a seeded address would not pass, the boot log says so and names the
property; the first edit or re-activation of that row from the administration
screen then goes through the full check like any other write.


## Validated providers
The following providers was tested and validated with this caldav-integration addon

04/01/2024 - MDaemon

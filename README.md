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
`http://localhost:8888/dav/cal/{username}/`, all four of these lines are
needed — one property per thing that is unusual about that address, plus the
line that names the address itself:

```
exo.agenda.caldav.connector.url=http://localhost:8888/dav/cal/{username}/
exo.agenda.caldav.server.allowedSchemes=https,http
exo.agenda.caldav.server.allowedPorts=80,443,8888
exo.agenda.caldav.server.allowedHosts=localhost
```

With those set, the rig's address passes the check, so the Stalwart row is
seeded **active** and everything works as it always did. Without them the rig
does not work — which is the point: it is reached because the deployment said
so, not because the product shipped a default pointing at it.

### What a fresh install seeds, and in what state

Two rows are seeded into an **empty** registry at startup: Stalwart (from
`exo.agenda.caldav.connector.url` when set) and Bluemind. They are the
pre-filled form an administrator edits, not a working configuration.

**A seeded row is switched on only when its address passes the same check an
administrator's would.** Until [EXO-89794] seeding was exempt from the check
altogether, which left exactly the loophole the check exists to close: a fresh
install shipped an **active** registration at
`http://localhost:8888/dav/cal/{username}/`, so a connected user could drive
the relay's allowed verbs at loopback through a row nobody had typed. Neither
the check nor the seeds were the thing to drop; what had to go is the row
arriving switched on without ever meeting the check.

So today:

- An address the deployment **named and vouched for** — through
  `exo.agenda.caldav.connector.url`, plus whichever of the four properties
  above that address needs — passes, and the row is seeded active, exactly as
  before. The rig above is this case.
- Anything else is seeded **inactive**. Both shipped placeholder addresses
  (`https://stalwart.example.invalid/…` and `https://caldav.example.invalid/dav/`)
  are RFC 2606 `.invalid` names that can never resolve, so they can never be a
  live target and they fail the check by construction. The boot log says so,
  naming the address, the reason and the properties that would settle it.
  Nobody is offered the connector; nothing is driven at it.

An inactive seed is not a dead end: the administrator edits the row (checked)
and switches it on (checked too — activation has been a validated write since
[EXO-89774]). The check is never bypassed, only deferred to the person who
actually knows the address.

**Upgrades touch nothing.** All of the above governs a *fresh* registry only:
a deployment that already holds rows is never seeded again, so an install that
has been serving its users for months does not find its servers deactivated —
or judged at all — by taking this version. An already-declared server is only
ever re-judged when someone edits its address or switches it back on.


## Answering a meeting from your own calendar

When eXo copies a space meeting into a user's calendar, the user can accept,
decline or answer tentatively **in their own client** — a phone, Thunderbird,
the server's web UI — and eXo records it. It is the one field that travels back
from a copy; nothing else a client writes on a copy is ever imported.

An answer comes home by one of two routes:

- **Within a sweep**, when the server reports the copy as changed. This is the
  usual case and it is quick.
- **On the daily full read**, otherwise. Once a day eXo re-reads each bound
  collection's whole window rather than asking what changed, and reads any
  answer off the copies it meets on the way.

The second route matters more than it looks, because the first one only fires
once per change: a CalDAV sync report names an object and then moves the token
past it, so a change eXo could not act on when it was reported is never
reported again. The daily full read is what catches those.

### After upgrading

**Answers given before this version was installed are picked up on their own,
within a day of the upgrade. Nobody needs to answer again, and there is nothing
for an administrator to run.** The daily full read meets those copies whatever
the server said about them at the time.

Two limits are worth knowing:

- The re-read covers the synchronisation window only — by default 60 days back
  and 365 forward (`exo.agenda.caldav.sync.pastDays` /
  `exo.agenda.caldav.sync.futureDays`). An answer on a meeting that has since
  fallen out of the past window is not recovered.
- It applies to servers whose meeting copies land in the account's **own**
  calendar (mirror target `MAIN_CALENDAR`), because that is the collection eXo
  reads. Where the copies go to the dedicated `exo-meetings` calendar
  (`DEDICATED_CALENDAR`, the default), that collection is deliberately never
  read back in, so an answer written on a copy there does not reach eXo at all
  — before or after this version.

## Validated providers
The following providers was tested and validated with this caldav-integration addon

04/01/2024 - MDaemon


## Answering a meeting from a copy in the dedicated calendar

Where the meeting copies go to eXo's own `exo-meetings` collection — the
**default** destination — that collection is deliberately never brought into eXo
as a calendar: it holds copies of meetings the user already has, and importing
it would show every one of them twice. The consequence, until now, was that
**nothing read those copies at all**. An answer given on one of them reached eXo
only where the calendar server moves the object's version when a client writes;
on a server that records an answer without moving it, the answer stayed on the
copy for ever.

The mirror pass now asks that collection what has changed since it last asked —
an RFC 6578 sync report, the same evidence the ordinary calendars are read
with — and reads the owner's participation off the objects it names. It reads
**only** that: no calendar is created, no event is imported, nothing is written
to the server.

What it costs, so that nobody is surprised by it:

* one report per account per sweep, which answers with an empty list on a
  collection where nothing has happened;
* one fetch per copy eXo itself writes, once, on the sweep after the write —
  eXo's own push is a change the report reports, and no signal tells it from a
  client's. Those copies carry "needs action" and yield no answer;
* nothing at all on a server whose copies land in the account's own calendar,
  where that calendar is read through its own binding and the answers are
  already picked up there.

`exo.agenda.caldav.mirror.answers.maxPerPass` (default 100) caps how many
objects one pass fetches, for the one burst that is not ordinary: the first
sweep after a newly connected account is given its backlog of copies.

### After upgrading

The read is forward-looking. The first pass on each account takes the
collection's sync token **without** reading anything, and every pass after it
reads only what changed since — so an answer given on a copy *before* the
upgrade is not recovered by it. Answering again in eXo, or in the calendar,
records it.

### A copy that is left as it is

When an administrator changes a setting that governs the copies, every copy is
compared once against what eXo would write now and rewritten if it differs. A
copy carrying an answer eXo does not hold is **left alone** instead: on a server
that answers without moving the version there is no way to tell that answer from
eXo's own earlier writing, and rewriting it would destroy the only record of it.
Such a copy keeps the settings it was written with until its answer is read, and
the log names it.

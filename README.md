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


## Validated providers
The following providers was tested and validated with this caldav-integration addon

04/01/2024 - MDaemon

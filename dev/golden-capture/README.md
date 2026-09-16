# Golden capture tooling — THROWAWAY (EXO-89521)

Quarantined capture drivers for the golden-file harness (plan §7). **Nothing
in `dev/` ships**: this directory belongs to no Maven module — the
`caldav-services` JAR, the `caldav-webapp` WAR and the `caldav-packaging` ZIP
are built exclusively from their own module trees — so these files exist only
so the captures are reproducible and reviewable. Once PR3 (the Java ICS
engine) has replaced the browser connector, this directory has no further
purpose and can be deleted; the fixtures and the comparator are the durable
artifacts.

## capture-goldens.mjs — Stalwart (run freely)

Drives the **unmodified** browser connector under Node (jsdom globals, real
`tsdav`/`ical.js`) against the local Stalwart rig and records the write
goldens, read goldens and two protocol transcripts into
`caldav-services/src/test/resources/caldav/`. Scratch collections are created
per run and deleted afterwards.

```sh
cd caldav-webapp && npm ci && cd ..
TZ=Europe/Paris node dev/golden-capture/capture-goldens.mjs
```

Rig overrides: `CALDAV_RIG_URL`, `CALDAV_RIG_USER`, `CALDAV_RIG_PASSWORD`
(defaults match the dev rig, same as `HttpCalDavClientStalwartTest`).

## capture-bluemind.sh — BlueMind (run it yourself)

Prompts for the DAV URL, login and — silently — the password; records five
scrubbed transcripts (OPTIONS, capability PROPFIND, Depth:1 PROPFIND, an
opt-in MKCALENDAR probe, and a sync-collection REPORT) into
`caldav-services/src/test/resources/caldav/transcripts/`. The Authorization
header is never recorded and the password (and its Basic base64) is filtered
from every byte written. The capability + sync-collection probes settle the
plan's open question (§4.2 / risk 4) of which sync tier BlueMind supports;
the PROPFIND and MKCALENDAR captures supersede the two RECONSTRUCTED
fixtures PR #38 ships (`bluemind-propfind-dav-rooted.xml`,
`bluemind-mkcalendar-207-failing-propstat.xml`).

```sh
dev/golden-capture/capture-bluemind.sh
```

Review the produced files before committing them: credentials are scrubbed,
but your own login may appear inside hrefs.

## capture-bluemind-object-etags.sh — BlueMind, the import door's ETag channels (run it yourself)

Same prompts and the same scrubbing as `capture-bluemind.sh`. On **one
existing object of the main calendar** it records five scrubbed transcripts
into `caldav-services/src/test/resources/caldav/transcripts/`: the Depth:1
`getetag` listing of the collection, a Depth:0 `getetag` PROPFIND on the
object's href, a one-href `calendar-multiget` (getetag only), and the same
Depth:0 + multiget on a **non-existent** href. All probes are reads; the
missing-object probes address an href that does not exist and create
nothing. Together they settle the two halves of the review-F7 hypothesis the
import door verifies live (`BlueMindImportWriter`): whether the Depth:0 token
equals the listing's for the same object (the spelling hypothesis), and
whether a Depth:0 on a missing object answers 404 or a node minted from the
path. The script prints what each capture says at the end.

**Run on the rig's BlueMind on 2026-09-16** (object `51.ics` of the main
calendar `calendar:Default:751E6D1A-…`, probe `exo-absent-probe-1789544526.ics`):
every shape predicted from BlueMind's source held — Depth:0 `getetag` ==
listing `getetag` (`bmdav_3980966296_0`, so the spelling hypothesis holds and
the writer logs "agrees"); the one-href multiget answered one response with
`"Ym1kYXZfMzk4MDk2NjI5Nl8x"` (base64 of `bmdav_3980966296_1`, the REPORT
shape); Depth:0 on the missing href answered 207 with the path-minted
`bmdav_3856992451_0` (Depth:0 cannot tell existence); the multiget on the
missing href answered an empty multistatus. The five `*.captured.xml` files
are the fixtures `HttpCalDavClientServerQuirksTest` now runs on; the DERIVED
fixtures they superseded were dropped. Re-run the script to refresh them
after a BlueMind upgrade.

```sh
dev/golden-capture/capture-bluemind-object-etags.sh
```

Review the produced files before committing them: credentials are scrubbed,
but your login and the object uids appear inside hrefs.

## capture-bluemind-subscription.sh — BlueMind, the sharee's subscription (run it yourself — WRITES on the sharee's account, rig only)

Same prompts and the same scrubbing as the other BlueMind scripts, plus the
`X-BM-ApiKey` session key scrubbed from every byte, and three more prompts:
the container uid of the calendar shared from eXo, a container uid the sharee
has **no** access to, and another user's directory entry uid. It logs in **as
the sharee** (the colleague the calendar was shared with) and records, into
`caldav-services/src/test/resources/caldav/transcripts/`, the twelve steps
the EXO-90277 brief (Tribe task 90277, comment 337358) asks for: the login
answer with `authUser.uid`/`domainUid` (1); the calendar subscriptions
before (2), after the subscribe (4) and after the unsubscribe (10); the
`_subscribe` of the shared container (3) and its repeat (6, idempotency); the
sharee's DAV home listing the shared collection (5); an `_subscribe` of an
unknown container (7 — the 404-vs-500 `NOT_FOUND` question); an `_subscribe`
of a container without access and a PROPFIND on it (8, undone at once); an
`_subscribe` addressed to another uid (9 — the `ROLE_SELF` 403); the
`_unsubscribe` (10); whether the sharee received BlueMind's access-change
mail (11, typed in); and the dangling-subscription sequence behind hole 3 —
subscribe again, the owner revokes from eXo while the script waits, then the
listing, the home and the collection are captured (12), followed by a final
`_unsubscribe` that leaves the account as found.

**These are writes on the sharee's BlueMind account** (steps 3, 6, 8, 9, 10,
12): run it against a test account on the rig, never a colleague's real one.
The script prints what each capture settled at the end. The captured files
supersede the DERIVED fixtures `bluemind-rest-subscribe-200-empty.derived.json`,
`bluemind-rest-fault-permission-denied.derived.json`,
`bluemind-rest-fault-subscribe-not-found.derived.json` and
`bluemind-rest-subscriptions-calendar.derived.json`; point
`BlueMindSubscriptionClientTest` at them and drop the DERIVED ones, as
EXO-90307 did.

**Not yet run**: as of 2026-09-16 no capture has been made; every BlueMind
answer the feature relies on is derived from BlueMind's source at master
`1ef9ae23` and stated as such in the fixtures' headers.

```sh
dev/golden-capture/capture-bluemind-subscription.sh
```

Review the produced files before committing them: credentials and the key
are scrubbed, but the sharee's login, the uids and the container uids appear
in URLs and bodies.

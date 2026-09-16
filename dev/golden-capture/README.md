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
path. The script prints what each capture says at the end; the captures
supersede the four `bluemind-*.derived.xml` fixtures named in its header
(`bluemind-propfind-collection-depth1-items`,
`bluemind-propfind-object-depth0-getetag`,
`bluemind-report-multiget-one-href-getetag`,
`bluemind-report-multiget-missing-href`), which should then be dropped and
the tests pointed at the captured files.

```sh
dev/golden-capture/capture-bluemind-object-etags.sh
```

Review the produced files before committing them: credentials are scrubbed,
but your login and the object uids appear inside hrefs.

# Golden-file corpus — EXO-89521

The fidelity net of the server-side CalDAV migration (plan §7, Tribe note
50651): it pins **what the browser connector actually produces today**, so
that PR3's Java ICS engine is judged against reality, not intent. PR3 does
not merge until, for every write golden here, the engine's output shows **no
difference under `IcsSemanticComparator`** (`src/test/java/org/exoplatform/
caldav/golden/`), which compares meaning — instants, rosters, rules,
expansions — and is blind to folding, property order, DTSTAMP and PRODID.

## Provenance discipline

Every fixture states what it is. Three kinds exist here:

- **`captured`** — recorded live from the unmodified
  `caldav-webapp/.../caldavConnector.js` (commit noted in each envelope)
  running against the containerised **Stalwart v0.16** dev rig, by
  `dev/golden-capture/capture-goldens.mjs`. All `write/*.golden.json` and
  `read/*.read-golden.json` envelopes are captures; each carries a
  `provenance` block (asserted by `GoldenCorpusTest`), the exact PUT body the
  connector `sent`, and the object as the server stored it (`ics`).
- **`authored driver input`** — the `events/*.json` fixtures: hand-written
  agenda-event JSON in the exact shape agenda hands `pushEvent`/`deleteEvent`
  (mirrored from the code and from `pushEventIcs.test.js`). They are inputs,
  not evidence; the evidence is what capturing them produced.
- **`reconstructed`** — the `read/objects/*.ics` foreign-client objects
  (Thunderbird/Apple/sloppy-client shapes) and `events/seed-13-foreign-object.ics`:
  hand-authored to a documented client shape, because no live capture of those
  clients was available. What IS captured about them is authoritative anyway —
  each was really PUT to Stalwart and really read back through the connector,
  and the resulting `read-golden` records that live behaviour.

A reconstructed fixture that looks captured is worse than no fixture; when in
doubt, the envelope's `provenance.kind` decides.

## The write corpus, case by case

Each case pins one invariant from the connector's documented bug history (the
`_provenance.invariant` field carries the full statement):

| # | Case | Invariant pinned |
|---|---|---|
| 01 | simple timed (+`.update`) | single timed events are UTC-anchored, no VTIMEZONE; a re-push updates the one master in place |
| 02 | all-day, one day | `VALUE=DATE` + **exclusive DTEND** (day after) |
| 03 | all-day, multi-day, Sydney | covered days read in the event zone (east-of-Greenwich day-shift class) |
| 04 | weekly series, Paris | TZID anchoring + the Intl-derived two-rule VTIMEZONE (**the thing PR3 replaces**) |
| 05 | series + `.override` | override spliced next to the master; exactly one RECURRENCE-ID, in the master's TZID form |
| 06 | series spanning Oct DST | wall clock held across the transition — the EXO-89402 drift class |
| 07 | EXDATEs + `.occurrence-delete` | EXDATE in the master's form; occurrence delete rewrites, never deletes the object |
| 08 | attendees/organizer | truthful ORGANIZER + SCHEDULE-AGENT=CLIENT; quoted CN; address-less attendee omitted |
| 09 | VALARM | one DISPLAY alarm per reminder, minute triggers |
| 10 | conference + rich text | TEXT escaping, HTML→text, folding, CONFERENCE single-feature, URL back-link |
| 11 | Africa/Casablanca | the lunar-zone **approximation** (see waivers) |
| 12 | all-day series + `.override` | RECURRENCE-ID;VALUE=DATE — the all-day override form |
| 13 | merge into foreign object | EXDATE-contradicted override pruned, other foreign override + VTIMEZONE preserved |
| 14 | unresolvable zone | declined VTIMEZONE ⇒ whole series falls back to UTC, no dangling TZID |

## The read corpus

Captured output of `retrieveEvents` over the window `2026-10-01 →
2026-11-30`, runtime zone pinned to `Europe/Paris`, filtered to the scratch
collection; `etag`/`url`/`calendarId`/`color` are dropped (run- and
position-dependent — colour derivation stays a browser concern).

- **r01** Thunderbird-shaped weekly Paris series spanning the DST boundary —
  the wall clock holds, instants move 07:00Z→08:00Z (named test in
  `GoldenCorpusTest`).
- **r02** Apple-shaped all-day event.
- **r03** an object listing its override **before** its master — pins the
  connector's first-subcomponent branch.
- **r04** a TZID with **no VTIMEZONE** — pins today's resolution behaviour.
- **r05** round trip: the exact object the 06 write capture stored, read back.

**Load-bearing capture finding** (transcript
`../transcripts/stalwart-calendar-query-expand.xml`): the browser reads
through `calendar-query` with `CALDAV:expand`, and **Stalwart answers
server-expanded, UTC-normalised calendar-data** (one VEVENT per occurrence,
RRULE gone). The read goldens therefore pin the behaviour of the *system*
(connector + expanding server). A server that ignores `expand` exercises the
client-side `RecurExpansion` branch instead — **unproven for BlueMind**; the
`dev/golden-capture/capture-bluemind.sh` probes settle what BlueMind does.

## Known divergences PR3 is EXPECTED to show (waivers, not surprises)

Recall-first: these are places where today's behaviour is wrong or
approximate, so a faithful-to-ical4j port will differ. The comparator must
flag them, and PR3 must waive them **by name**, never silently:

1. **11 — Africa/Casablanca**: the Intl derivation projects the reference
   year's two Ramadan transitions as YEARLY rules; ical4j's registry carries
   the real (better) rules. Occurrence instants around Ramadan 2027 may
   legitimately differ.
2. **07 — `.occurrence-delete`**: the rewrite adds its EXDATE **floating**
   (`EXDATE:20260922T083000`, no TZID) while the master's other EXDATE
   carries `TZID=Europe/Paris` — a latent form-mismatch bug this capture
   surfaced. PR3 writing the anchored form is a fix; it must be recorded as
   such (and is a candidate follow-up fix in the current connector too).

## Deliberate omissions

- **VTODO/VJOURNAL/VFREEBUSY** — the connector reads and writes VEVENT only.
- **Multiple conferences** — only the first is written (pinned inside 10).
- **All-day series across DST** — dates carry no time; the transition cannot
  move them (02/03/12 cover the day-boundary arithmetic that CAN go wrong).
- **Floating times on the write path** — the connector never writes them
  (UTC or TZID+VTIMEZONE only); the read side covers floating via r04.
- **A recurring event with RDATE** — agenda cannot express one, so the
  connector never emits it; foreign RDATEs would ride the read path, where
  Stalwart's expansion flattens them anyway.
- **BlueMind-held read goldens** — no credentials in this session, by design;
  the BlueMind capture script exists precisely to add its transcripts.

## Regenerating

`TZ=Europe/Paris node dev/golden-capture/capture-goldens.mjs` against the
local rig (see `dev/golden-capture/README.md`). Regeneration is only
legitimate while the browser connector is still the live implementation —
after PR3 lands, these files are history, and history does not get
regenerated.

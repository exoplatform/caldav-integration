#!/usr/bin/env bash
#
# THROWAWAY CAPTURE SCRIPT — EXO-90307 (review F7, the ETag channels of the
# BlueMind import door).
#
# Records, on ONE existing object of the main calendar, the transcripts that
# settle how BlueMind's DAV server answers the three reads the import door
# uses — using credentials typed BY THE PERSON RUNNING IT: never stored, never
# passed as an argument, never written into any output. Every transcript is
# scrubbed before it is saved: request headers (so the Authorization header)
# are not recorded at all, and the password and its Basic base64 form are
# filtered out of every byte written, should a server ever echo them.
#
# All probes are reads. The two "missing object" probes address an href that
# does not exist and create nothing.
#
# What it captures, and what each capture settles:
#   1. PROPFIND Depth:1 getetag on the main calendar
#        -> the listing channel: the value the verification pass adopts into
#           CALDAV_OBJECT_SYNC (expected: raw "bmdav_<lnum>_0" per child,
#           GetTag.java:46-56). The reference every other capture is read
#           against; replaces bluemind-propfind-collection-depth1-items.derived.xml.
#   2. PROPFIND Depth:0 getetag on one existing .ics href
#        -> the single-object VERSION read. SAME token as that href's line in
#           (1): the spelling hypothesis holds (the request path hashes like
#           containerPath + uid + ".ics", DavStore.java:402) and the writer's
#           per-collection check will log "agrees". A DIFFERENT token: the
#           hypothesis fails; the writer's check logs "differs" and keeps the
#           listing for that collection — correct, at today's cost. Replaces
#           bluemind-propfind-object-depth0-getetag.derived.xml.
#   3. calendar-multiget REPORT with that one href, getetag only
#        -> the single-object PRESENCE read. One d:response for the href with
#           a quoted base64 getetag (CalendarMultigetExecutor.java:114-130) is
#           the expected shape; it confirms presence is answered per item.
#           Replaces bluemind-report-multiget-one-href-getetag.derived.xml.
#   4. PROPFIND Depth:0 getetag on a NON-EXISTENT href in the same calendar
#        -> the existence question. 207 with a getetag = the node is minted
#           from the path (MethodRouter.java:162-175 + DavStore.java:474-502
#           "assume yes"): Depth:0 cannot tell existence, as the writer
#           assumes. 404 = BlueMind does check; the writer still works (it
#           never reads existence from this channel), and a follow-up could
#           drop the multiget.
#   5. calendar-multiget REPORT with that non-existent href
#        -> the absence shape. An EMPTY multistatus (no d:response) is what
#           the source predicts (:82, :114-130) - and also what a failed
#           lookup answers (:83-86), which is why the writer never reads
#           absence from it. A d:response carrying "HTTP/1.1 404" would be
#           the RFC 4791 shape instead. Replaces
#           bluemind-report-multiget-missing-href.derived.xml.
#
# This file ships in NO artifact: dev/ belongs to no Maven module.
#
# Usage:  dev/golden-capture/capture-bluemind-object-etags.sh
#         (prompts for the DAV base URL, the login, and — silently — the password)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../../caldav-services/src/test/resources/caldav/transcripts"
mkdir -p "$OUT"
TODAY="$(date +%F)"

printf 'BlueMind DAV base URL (e.g. https://mail.example.com/dav/): '
read -r BASE_URL
printf 'Login (the CalDAV account, usually the email address): '
read -r LOGIN
printf 'Password (not echoed, not stored, not written anywhere): '
read -rs PASSWORD
printf '\n'
BASE_URL="${BASE_URL%/}/"
BASIC_B64="$(printf '%s:%s' "$LOGIN" "$PASSWORD" | base64 | tr -d '\n')"

# Every byte written to disk goes through this scrub. The password and its
# base64 form should never appear in a RESPONSE, but "should never" is not a
# redaction strategy. The patterns are regex-escaped so any password works.
escape_pattern() {
  printf '%s' "$1" | sed -e 's/[][\.*^$/|&\\]/\\&/g'
}
PASSWORD_PATTERN="$(escape_pattern "$PASSWORD")"
BASIC_PATTERN="$(escape_pattern "$BASIC_B64")"
scrub() {
  sed -e "s/${PASSWORD_PATTERN}/<REDACTED-PASSWORD>/g" -e "s/${BASIC_PATTERN}/<REDACTED-BASIC-CREDENTIALS>/g"
}

# One captured exchange: method, URL, extra curl args; writes status line,
# response headers and body — request headers (so the Authorization header)
# are never recorded.
capture() {
  local method="$1" url="$2" outfile="$3" note="$4"
  shift 4
  local tmp_headers tmp_body status
  tmp_headers="$(mktemp)"
  tmp_body="$(mktemp)"
  status=$(curl -s --config <(printf 'user = "%s:%s"\n' "$LOGIN" "$PASSWORD") -X "$method" -D "$tmp_headers" -o "$tmp_body" -w '%{http_code}' "$@" "$url" || echo 'unreachable')
  {
    printf '# CAPTURED live on %s against BlueMind at %s\n' "$TODAY" "$(printf '%s' "$BASE_URL" | sed 's|https\?://\([^/]*\).*|\1|')"
    printf '# by dev/golden-capture/capture-bluemind-object-etags.sh: %s %s\n' "$method" "$(printf '%s' "$url" | scrub)"
    printf '# %s\n' "$note"
    printf '# Authorization header not recorded; password and Basic credentials scrubbed.\n'
    printf '# HTTP status: %s\n' "$status"
    scrub < "$tmp_headers"
    printf '\n'
    scrub < "$tmp_body"
    printf '\n'
  } > "$outfile"
  rm -f "$tmp_headers" "$tmp_body"
  echo "  -> $outfile (HTTP $status)"
  LAST_STATUS="$status"
}

# Resolves a URL that may be absolute, or path-rooted like BlueMind's /dav/... hrefs.
absolutize() {
  case "$1" in
    http://*|https://*) printf '%s' "$1" ;;
    /*) printf '%s%s' "$(printf '%s' "$BASE_URL" | sed -E 's|(https?://[^/]+).*|\1|')" "$1" ;;
    *) printf '%s%s' "$BASE_URL" "$1" ;;
  esac
}

# Pulls one href out of a captured multistatus: the first href nested inside the
# named element, or — with no element — the href of the first response whose
# resourcetype says it is a calendar collection.
href_from() {
  python3 - "$1" "${2:-}" <<'PYEOF'
import re, sys
body = open(sys.argv[1], encoding='utf-8', errors='replace').read()
body = body.split('\n\n', 1)[-1]
want = sys.argv[2] if len(sys.argv) > 2 else ''
if want:
    m = re.search(r'<[\w-]*:?%s\b[^>]*>(.*?)</[\w-]*:?%s>' % (want, want), body, re.I | re.S)
    scope = m.group(1) if m else ''
    h = re.search(r'<[\w-]*:?href>([^<]+)</', scope, re.I)
    print(h.group(1).strip() if h else '')
else:
    for resp in re.findall(r'<[\w-]*:?response\b.*?</[\w-]*:?response>', body, re.I | re.S):
        if re.search(r'<[\w-]*:?calendar\s*/>', resp, re.I):
            h = re.search(r'<[\w-]*:?href>([^<]+)</', resp, re.I)
            if h:
                print(h.group(1).strip())
                break
    else:
        print('')
PYEOF
}

# The main calendar among the home's calendar collections: the one whose
# path names BlueMind's default container ("calendar:Default:<uid>", spelled
# raw or percent-encoded), else the first calendar collection listed.
main_calendar_from() {
  python3 - "$1" <<'PYEOF'
import re, sys
body = open(sys.argv[1], encoding='utf-8', errors='replace').read()
body = body.split('\n\n', 1)[-1]
calendars = []
for resp in re.findall(r'<[\w-]*:?response\b.*?</[\w-]*:?response>', body, re.I | re.S):
    if re.search(r'<[\w-]*:?calendar\s*/>', resp, re.I):
        h = re.search(r'<[\w-]*:?href>([^<]+)</', resp, re.I)
        if h:
            calendars.append(h.group(1).strip())
main = [c for c in calendars if re.search(r'calendar(:|%3A)Default(:|%3A)', c, re.I)]
print((main or calendars or [''])[0])
PYEOF
}

# The first .ics href of a captured Depth:1 listing, and the getetag on its line.
first_ics_from() {
  python3 - "$1" <<'PYEOF'
import re, sys
body = open(sys.argv[1], encoding='utf-8', errors='replace').read()
body = body.split('\n\n', 1)[-1]
for resp in re.findall(r'<[\w-]*:?response\b.*?</[\w-]*:?response>', body, re.I | re.S):
    h = re.search(r'<[\w-]*:?href>([^<]+\.ics)</', resp, re.I)
    if h:
        e = re.search(r'<[\w-]*:?getetag>([^<]*)</', resp, re.I)
        print(h.group(1).strip() + '\t' + (e.group(1).strip() if e else ''))
        break
else:
    print('')
PYEOF
}

# The getetag of the first response of a captured multistatus.
getetag_from() {
  python3 - "$1" <<'PYEOF'
import re, sys
body = open(sys.argv[1], encoding='utf-8', errors='replace').read()
body = body.split('\n\n', 1)[-1]
e = re.search(r'<[\w-]*:?getetag>([^<]*)</', body, re.I)
print(e.group(1).strip() if e else '')
PYEOF
}

# Whether a captured multistatus body carries at least one d:response.
has_response() {
  python3 - "$1" <<'PYEOF'
import re, sys
body = open(sys.argv[1], encoding='utf-8', errors='replace').read()
body = body.split('\n\n', 1)[-1]
print('yes' if re.search(r'<[\w-]*:?response\b', body, re.I) else 'no')
PYEOF
}

GETETAG_BODY='<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:getetag/></D:prop></D:propfind>'
multiget_body() {
  printf '<?xml version="1.0" encoding="utf-8"?><C:calendar-multiget xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><D:prop><D:getetag/></D:prop><D:href>%s</D:href></C:calendar-multiget>' "$1"
}

echo 'Discovery: principal, calendar home, main calendar (three reads, not kept)...'
DISCOVERY="$(mktemp -d)"
capture PROPFIND "$BASE_URL" "$DISCOVERY/principal.xml" 'discovery only' \
  -H 'Depth: 0' -H 'Content-Type: application/xml' --data-binary \
  '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:current-user-principal/></D:prop></D:propfind>'
PRINCIPAL="$(href_from "$DISCOVERY/principal.xml" 'current-user-principal')"
if [ -z "$PRINCIPAL" ]; then
  echo '    No current-user-principal in the answer — cannot discover the calendar home; stopping.'
  rm -rf "$DISCOVERY"; unset PASSWORD BASIC_B64 PASSWORD_PATTERN BASIC_PATTERN
  exit 1
fi
capture PROPFIND "$(absolutize "$PRINCIPAL")" "$DISCOVERY/home.xml" 'discovery only' \
  -H 'Depth: 0' -H 'Content-Type: application/xml' --data-binary \
  '<?xml version="1.0"?><D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><D:prop><C:calendar-home-set/></D:prop></D:propfind>'
HOME="$(href_from "$DISCOVERY/home.xml" 'calendar-home-set')"
if [ -z "$HOME" ]; then
  echo '    No calendar-home-set in the answer; stopping.'
  rm -rf "$DISCOVERY"; unset PASSWORD BASIC_B64 PASSWORD_PATTERN BASIC_PATTERN
  exit 1
fi
capture PROPFIND "$(absolutize "$HOME")" "$DISCOVERY/collections.xml" 'discovery only' \
  -H 'Depth: 1' -H 'Content-Type: application/xml' --data-binary \
  '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:displayname/></D:prop></D:propfind>'
CAL="$(main_calendar_from "$DISCOVERY/collections.xml")"
rm -rf "$DISCOVERY"
if [ -z "$CAL" ]; then
  echo '    No calendar collection under the home; stopping.'
  unset PASSWORD BASIC_B64 PASSWORD_PATTERN BASIC_PATTERN
  exit 1
fi
CAL="${CAL%/}/"
echo "    main calendar: $CAL"

echo '1/5 PROPFIND Depth:1 getetag on the main calendar — the listing channel, the reference...'
capture PROPFIND "$(absolutize "$CAL")" "$OUT/bluemind-propfind-collection-depth1-getetag.captured.xml" \
  'The listing channel: what CaldavMirrorVerificationService.adoptVersion records. Expected per child .ics: the raw token bmdav_<lnum>_0 (GetTag.java:46-56). Supersedes bluemind-propfind-collection-depth1-items.derived.xml.' \
  -H 'Depth: 1' -H 'Content-Type: application/xml' --data-binary "$GETETAG_BODY"
LINE="$(first_ics_from "$OUT/bluemind-propfind-collection-depth1-getetag.captured.xml")"
ICS="${LINE%%$'\t'*}"
LISTED_ETAG="${LINE#*$'\t'}"
if [ -z "$ICS" ]; then
  echo '    The main calendar lists no .ics object; create one event in BlueMind and run again.'
  unset PASSWORD BASIC_B64 PASSWORD_PATTERN BASIC_PATTERN
  exit 1
fi
echo "    object: $ICS"
echo "    listed getetag: $LISTED_ETAG"

echo '2/5 PROPFIND Depth:0 getetag on that href — the single-object version read...'
capture PROPFIND "$(absolutize "$ICS")" "$OUT/bluemind-propfind-object-depth0-getetag.captured.xml" \
  'The single-object version read. Equal to the same href line of the Depth:1 capture = the spelling hypothesis holds (PropFindProtocol.java:69, GetTag.java:47,56, DavStore.java:402); different = the writer keeps the listing for this collection. Supersedes bluemind-propfind-object-depth0-getetag.derived.xml.' \
  -H 'Depth: 0' -H 'Content-Type: application/xml' --data-binary "$GETETAG_BODY"
DEPTH0_ETAG="$(getetag_from "$OUT/bluemind-propfind-object-depth0-getetag.captured.xml")"

echo '3/5 calendar-multiget REPORT with that one href, getetag only — the presence read...'
capture REPORT "$(absolutize "$CAL")" "$OUT/bluemind-report-multiget-one-href-getetag.captured.xml" \
  'The presence read. Expected: one d:response for the href with a quoted base64 getetag (CalendarMultigetExecutor.java:114-130, SyncTokens.java:78-83) - another string than the listing token, never recorded. Supersedes bluemind-report-multiget-one-href-getetag.derived.xml.' \
  -H 'Depth: 1' -H 'Content-Type: application/xml' --data-binary "$(multiget_body "$ICS")"
MULTIGET_ETAG="$(getetag_from "$OUT/bluemind-report-multiget-one-href-getetag.captured.xml")"

MISSING="${CAL}exo-absent-probe-$(date +%s).ics"
echo "4/5 PROPFIND Depth:0 getetag on a NON-EXISTENT href ($MISSING)..."
capture PROPFIND "$(absolutize "$MISSING")" "$OUT/bluemind-propfind-missing-object-depth0.captured.xml" \
  'The existence question on Depth:0. 207 with a getetag = a node minted from the path (MethodRouter.java:162-175, DavStore.java:474-502 default "assume yes"): Depth:0 cannot tell existence, as the writer assumes. 404 = the server checks after all.' \
  -H 'Depth: 0' -H 'Content-Type: application/xml' --data-binary "$GETETAG_BODY"
MISSING_DEPTH0_STATUS="$LAST_STATUS"
MISSING_DEPTH0_ETAG="$(getetag_from "$OUT/bluemind-propfind-missing-object-depth0.captured.xml")"

echo '5/5 calendar-multiget REPORT with that non-existent href...'
capture REPORT "$(absolutize "$CAL")" "$OUT/bluemind-report-multiget-missing-href.captured.xml" \
  'The absence shape. An empty multistatus (no d:response) is what the source predicts (CalendarMultigetExecutor.java:82,114-130) and also what a failed lookup answers (:83-86) - why the writer never reads absence from this channel. A d:response with HTTP/1.1 404 would be the RFC 4791 shape.' \
  -H 'Depth: 1' -H 'Content-Type: application/xml' --data-binary "$(multiget_body "$MISSING")"
MISSING_MULTIGET_RESPONSE="$(has_response "$OUT/bluemind-report-multiget-missing-href.captured.xml")"

unset PASSWORD BASIC_B64 PASSWORD_PATTERN BASIC_PATTERN
echo
echo 'What the captures say:'
if [ -n "$LISTED_ETAG" ] && [ "$LISTED_ETAG" = "$DEPTH0_ETAG" ]; then
  echo "  - Depth:0 getetag == listing getetag ($LISTED_ETAG): the spelling hypothesis HOLDS; the writer will log 'agrees' on this collection and serve it with single-object reads."
else
  echo "  - Depth:0 getetag ($DEPTH0_ETAG) != listing getetag ($LISTED_ETAG): the hypothesis FAILS; the writer will log 'differs' and keep the Depth:1 listing for this collection (correct, at today's cost)."
fi
if [ -n "$MULTIGET_ETAG" ]; then
  echo "  - one-href multiget answered a response with getetag $MULTIGET_ETAG: presence is answered per item, in the REPORT channel's shape (expected quoted base64)."
else
  echo "  - one-href multiget answered NO response for an existing object: the presence read is not usable as shaped; the writer falls back to the listing on every write (correct, at today's cost). Worth a look at the capture."
fi
case "$MISSING_DEPTH0_STATUS" in
  207) echo "  - Depth:0 on a missing href answered 207 with getetag '$MISSING_DEPTH0_ETAG': the node is minted from the path; Depth:0 cannot tell existence (as the writer assumes)." ;;
  404) echo '  - Depth:0 on a missing href answered 404: BlueMind checks existence here after all; the writer stays correct (it reads existence from the multiget), and a follow-up could rely on Depth:0 alone.' ;;
  *)   echo "  - Depth:0 on a missing href answered $MISSING_DEPTH0_STATUS: neither predicted shape; see the capture." ;;
esac
if [ "$MISSING_MULTIGET_RESPONSE" = 'no' ]; then
  echo '  - multiget on a missing href answered an empty multistatus: the predicted absence shape, indistinguishable from a failed lookup - which is why the writer never reads absence from it.'
else
  echo '  - multiget on a missing href answered a d:response (probably HTTP/1.1 404): the RFC 4791 shape; the client already leaves such an entry out, so the writer behaves the same.'
fi
echo
echo 'Done. Before committing, review the captured files in:'
echo "  $OUT"
echo 'Credentials are scrubbed, but your login/email and the object uids appear'
echo 'inside hrefs — keep or anonymise them as you see fit. These captures'
echo 'supersede the four DERIVED fixtures named in the notes above, which'
echo 'should then be dropped and the tests pointed at the captured files.'

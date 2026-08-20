#!/usr/bin/env bash
#
# THROWAWAY CAPTURE SCRIPT — EXO-89521 (golden-file harness, plan §7).
#
# Records the BlueMind protocol-quirk transcripts the harness still lacks,
# using credentials typed BY THE PERSON RUNNING IT — never stored, never
# passed as an argument, never written into any output. Every transcript is
# scrubbed before it is saved: the Authorization header is never captured
# (curl request headers are not recorded at all), and the password and its
# Basic base64 form are additionally filtered out of every byte written,
# should a server ever echo them.
#
# What it captures, and what each capture settles:
#   1. OPTIONS on the DAV root        -> the DAV/Allow headers (does the server
#                                        advertise MKCALENDAR and sync-collection?)
#   2. PROPFIND Depth:0 on the home   -> supported-report-set + getctag +
#                                        sync-token: THE probe that settles the
#                                        open sync-tier question of the plan
#                                        (§4.2 / risk 4): sync-collection (tier 1)
#                                        versus ctag (tier 2) versus neither.
#   3. PROPFIND Depth:1 on the home   -> the /dav/-rooted href shape, captured;
#                                        supersedes the RECONSTRUCTED
#                                        bluemind-propfind-dav-rooted.xml of PR #38.
#   4. MKCALENDAR probe (asks first)  -> the refusal shape (the 207 whose failing
#                                        propstat creates nothing); supersedes the
#                                        RECONSTRUCTED
#                                        bluemind-mkcalendar-207-failing-propstat.xml
#                                        of PR #38. If the server unexpectedly
#                                        creates the collection, it is deleted.
#   5. sync-collection REPORT         -> the tier-1 answer (or refusal) itself.
#
# This file ships in NO artifact: dev/ belongs to no Maven module.
#
# Usage:  dev/golden-capture/capture-bluemind.sh
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
  status=$(curl -sk -u "$LOGIN:$PASSWORD" -X "$method" -D "$tmp_headers" -o "$tmp_body" -w '%{http_code}' "$@" "$url" || echo 'unreachable')
  {
    printf '# CAPTURED live on %s against BlueMind at %s\n' "$TODAY" "$(printf '%s' "$BASE_URL" | sed 's|https\?://\([^/]*\).*|\1|')"
    printf '# by dev/golden-capture/capture-bluemind.sh: %s %s\n' "$method" "$(printf '%s' "$url" | scrub)"
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

echo '1/5 OPTIONS on the DAV root (advertised methods and DAV compliance classes)...'
capture OPTIONS "$BASE_URL" "$OUT/bluemind-options.captured.http" \
  'Settles what the server advertises: MKCALENDAR in Allow, sync-collection in DAV.'

echo '2/5 PROPFIND Depth:0 — supported-report-set, getctag, sync-token (THE sync-tier probe)...'
capture PROPFIND "$BASE_URL" "$OUT/bluemind-propfind-capabilities.captured.xml" \
  'Settles the plan §4.2 open question: sync-token+supported-report-set present = tier 1; getctag only = tier 2; neither = tier 3.' \
  -H 'Depth: 0' -H 'Content-Type: application/xml' --data-binary \
  '<?xml version="1.0"?><D:propfind xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/"><D:prop><D:resourcetype/><D:displayname/><D:supported-report-set/><D:sync-token/><CS:getctag/><D:current-user-privilege-set/></D:prop></D:propfind>'

echo '3/5 PROPFIND Depth:1 — the /dav/-rooted href shape (supersedes the reconstructed fixture)...'
capture PROPFIND "$BASE_URL" "$OUT/bluemind-propfind-home-depth1.captured.xml" \
  'The raw authenticated listing the reconstructed bluemind-propfind-dav-rooted.xml of PR #38 stood in for; this capture supersedes it.' \
  -H 'Depth: 1' -H 'Content-Type: application/xml' --data-binary \
  '<?xml version="1.0"?><D:propfind xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/"><D:prop><D:resourcetype/><D:displayname/><CS:getctag/><D:sync-token/></D:prop></D:propfind>'

PROBE_URL="${BASE_URL}exo-golden-probe-$(date +%s)/"
printf '4/5 MKCALENDAR probe at %s\n' "$PROBE_URL"
printf '    This WRITES to your account if the server accepts it (it is then deleted).\n    Proceed? [y/N] '
read -r CONFIRM
if [ "$CONFIRM" = 'y' ] || [ "$CONFIRM" = 'Y' ]; then
  capture MKCALENDAR "$PROBE_URL" "$OUT/bluemind-mkcalendar-probe.captured.xml" \
    'The MKCALENDAR answer shape (expected: a refusal, or a 207 whose failing propstat creates nothing — RFC 4791 5.3.1 makes MKCALENDAR atomic). Supersedes the reconstructed bluemind-mkcalendar-207-failing-propstat.xml of PR #38.' \
    -H 'Content-Type: application/xml; charset=utf-8' --data-binary \
    '<?xml version="1.0" encoding="utf-8"?><C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><D:set><D:prop><D:displayname>eXo golden probe</D:displayname><C:calendar-description>Temporary probe collection, safe to delete</C:calendar-description></D:prop></D:set></C:mkcalendar>'
  if [ "$LAST_STATUS" = '201' ]; then
    echo '    The server actually created the collection; deleting the probe...'
    curl -sk -u "$LOGIN:$PASSWORD" -X DELETE -o /dev/null -w '    DELETE answered %{http_code}\n' "$PROBE_URL"
  fi
else
  echo '    Skipped on request; the reconstructed MKCALENDAR fixture of PR #38 stays the only record.'
fi

echo '5/5 sync-collection REPORT with an empty token (tier-1 behaviour itself)...'
capture REPORT "$BASE_URL" "$OUT/bluemind-sync-collection-probe.captured.xml" \
  'The tier-1 answer itself: a multistatus with hrefs+a new sync-token means RFC 6578 works; a 4xx/501 means the engine lives on ctag (tier 2) or listings (tier 3).' \
  -H 'Depth: 0' -H 'Content-Type: application/xml' --data-binary \
  '<?xml version="1.0" encoding="utf-8"?><D:sync-collection xmlns:D="DAV:"><D:sync-token/><D:sync-level>1</D:sync-level><D:prop><D:getetag/></D:prop></D:sync-collection>'

unset PASSWORD BASIC_B64 PASSWORD_PATTERN BASIC_PATTERN
echo
echo 'Done. Before committing, review the five files in:'
echo "  $OUT"
echo 'Credentials are scrubbed, but your login/email may appear inside hrefs —'
echo 'keep or anonymise them as you see fit. These captures supersede the two'
echo 'RECONSTRUCTED fixtures in PR #38 (bluemind-propfind-dav-rooted.xml and'
echo 'bluemind-mkcalendar-207-failing-propstat.xml), which should then be dropped.'

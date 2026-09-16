#!/usr/bin/env bash
#
# THROWAWAY CAPTURE SCRIPT — EXO-90277 (a colleague's BlueMind subscription
# to a calendar shared from eXo).
#
# Records, AS THE SHAREE, the transcripts that settle how BlueMind's REST API
# answers the subscription edits eXo now makes on the colleague's behalf —
# using credentials typed BY THE PERSON RUNNING IT: never stored, never passed
# as an argument, never written into any output. Every transcript is scrubbed
# before it is saved: request headers (so the Authorization header and the
# X-BM-ApiKey session key) are not recorded at all, and the password, its
# Basic base64 form and the session key are filtered out of every byte
# written, should a server ever echo them.
#
# !!! THESE ARE WRITES ON THE SHAREE'S ACCOUNT — RIG ONLY. !!!
# Steps 3, 6, 8, 9, 10 and 12 subscribe and unsubscribe the sharee's own
# BlueMind account to calendars (step 9 only tries, and must be refused). The
# script leaves the account as it found it — every subscription it adds is
# removed at the end — but run it against a test account on the rig, never
# against a colleague's real one.
#
# What it captures, and what each capture settles (the file:line references
# are BlueMind master 1ef9ae23; the predictions are the DERIVED fixtures'):
#   1. POST /api/auth/login as the sharee
#        -> authUser.uid and authUser.domainUid, the two facts eXo holds
#           nowhere and reads from this answer (LoginResponse.java,
#           AuthUser.java): the {domainUid} of every path below, and the uid
#           the client cross-checks before any edit. Fixture
#           bluemind-rest-login-sharee.captured.json.
#   2. GET /api/users/{domainUid}/subscriptions/{uid}?type=calendar
#        -> the baseline listing (IUserSubscription.java:44-47,
#           ContainerSubscriptionDescriptor.java); the shared container must
#           NOT be in it, or the share was already accepted by hand.
#   3. POST .../{uid}/_subscribe [{containerUid, offlineSync:true, automount:true}]
#        -> 200 with an EMPTY body predicted (void method,
#           UserSubscriptionService.java:113-142). The one call the feature
#           makes. Fixture bluemind-rest-subscribe-shared.captured.json.
#   4. GET the listing again
#        -> the shared container listed with offlineSync/automount as stored.
#   5. PROPFIND Depth:1 on the sharee's DAV calendar home
#        -> the shared collection listed under the SHAREE's home
#           (DavStore.java:441-449 lists subscriptions) — the raw capture the
#           DERIVED bluemind-propfind-home-depth1-sharee-subscribed.xml still
#           owes; this is what makes the calendar appear in eXo.
#   6. POST _subscribe again, same body
#        -> idempotent: 200 again, the listing unchanged
#           (UserSubscriptionService.java:134-138 updates, never refuses).
#   7. POST _subscribe an UNKNOWN container uid
#        -> the fault {errorCode:NOT_FOUND} (subscriptionToContainer :282-298).
#           404 was predicted by the brief; ResponseBuilder.java:70-76 says
#           500. The client accepts both; this settles which one the rig sends.
#           Fixture bluemind-rest-subscribe-unknown-container.captured.json.
#   8. POST _subscribe a container the sharee has NO access to, then PROPFIND it
#        -> 200 predicted (subscribe makes no ACL check, :113-142), then the
#           status the DAV collection answers to a reader without access — the
#           status eXo's listing must survive. Unsubscribed again at once.
#   9. POST _subscribe on ANOTHER uid's path with the sharee's session
#        -> 403 {errorCode:PERMISSION_DENIED} predicted (RBAC ROLE_SELF,
#           :129-130; ResponseBuilder.java:70-76). The refusal eXo never relies
#           on — the client checks authUser.uid first — but should exist.
#           Fixture bluemind-rest-subscribe-other-uid.captured.json.
#  10. POST .../{uid}/_unsubscribe ["containerUid"]
#        -> 200 empty (:184-209); the listing and the DAV home no longer carry
#           the container. Fixture bluemind-rest-unsubscribe-shared.captured.json.
#  11. Observation (no request): whether the sharee received BlueMind's
#        "X has modified your access rights" mail when the share was granted
#        (AclChangedNotificationVerticle) — answered by looking at the sharee's
#        inbox, typed in when asked.
#  12. The dangling-subscription question behind hole 3: the sharee is
#        subscribed again (as in 3), the OWNER revokes the share from eXo
#        while the script waits, then GET listing / PROPFIND home / PROPFIND
#        the collection are captured -> whether the subscription row survives
#        the ACL removal (MailboxAutoSubscribeAclHook.java:60-91 says it does,
#        for calendars) and what the sharee's home lists for it. Then a final
#        _unsubscribe restores the account.
#
# This file ships in NO artifact: dev/ belongs to no Maven module.
#
# Usage:  dev/golden-capture/capture-bluemind-subscription.sh
#         (prompts for the BlueMind URL, the SHAREE's login, — silently — their
#          password, the shared container uid, a container uid the sharee has
#          no access to, and another user's uid)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../../caldav-services/src/test/resources/caldav/transcripts"
mkdir -p "$OUT"
TODAY="$(date +%F)"

printf 'BlueMind base URL (e.g. https://mail.example.com/ — the REST API is under /api, DAV under /dav): '
read -r BASE_URL
printf 'SHAREE login (the colleague the calendar was shared with, usually the email address): '
read -r LOGIN
printf 'SHAREE password (not echoed, not stored, not written anywhere): '
read -rs PASSWORD
printf '\n'
printf 'Container uid of the calendar shared with them from eXo (e.g. exo-cal-<anchor>): '
read -r SHARED
printf 'Container uid the sharee has NO access to (e.g. calendar:Default:<uid of a third user>): '
read -r NO_ACCESS
printf 'Directory entry uid of ANOTHER user (e.g. the owner'"'"'s, the segment of /dav/principals/__uids__/<uid>/): '
read -r OTHER_UID
BASE_URL="${BASE_URL%/}"
ROOT="$(printf '%s' "$BASE_URL" | sed -E 's|(https?://[^/]+).*|\1|')"
BASIC_B64="$(printf '%s:%s' "$LOGIN" "$PASSWORD" | base64 | tr -d '\n')"
KEY=''

# Every byte written to disk goes through this scrub. The password, its
# base64 form and the session key should never appear in a RESPONSE, but
# "should never" is not a redaction strategy. The patterns are regex-escaped
# so any password works; the key pattern is added once the login answers.
escape_pattern() {
  printf '%s' "$1" | sed -e 's/[][\.*^$/|&\\]/\\&/g'
}
PASSWORD_PATTERN="$(escape_pattern "$PASSWORD")"
BASIC_PATTERN="$(escape_pattern "$BASIC_B64")"
KEY_PATTERN=''
scrub() {
  if [ -n "$KEY_PATTERN" ]; then
    sed -e "s/${PASSWORD_PATTERN}/<REDACTED-PASSWORD>/g" -e "s/${BASIC_PATTERN}/<REDACTED-BASIC-CREDENTIALS>/g" -e "s/${KEY_PATTERN}/<REDACTED-API-KEY>/g"
  else
    sed -e "s/${PASSWORD_PATTERN}/<REDACTED-PASSWORD>/g" -e "s/${BASIC_PATTERN}/<REDACTED-BASIC-CREDENTIALS>/g"
  fi
}

# One captured exchange: method, URL, output file, note, extra curl args;
# writes status line, response headers and body — request headers (so the
# Authorization header and X-BM-ApiKey) are never recorded. Credentials and
# the key travel through a curl config read from a process substitution,
# never through the command line.
capture() {
  local method="$1" url="$2" outfile="$3" note="$4"
  shift 4
  local tmp_headers tmp_body status
  tmp_headers="$(mktemp)"
  tmp_body="$(mktemp)"
  status=$(curl -s --config <(printf 'user = "%s:%s"\n' "$LOGIN" "$PASSWORD") -X "$method" -D "$tmp_headers" -o "$tmp_body" -w '%{http_code}' "$@" "$url" || echo 'unreachable')
  write_capture "$method" "$url" "$outfile" "$note" "$status" "$tmp_headers" "$tmp_body"
}

# One captured REST exchange carrying the session key instead of Basic
# credentials (BlueMind's REST root ignores Basic; RestRootHandler.java reads
# X-BM-ApiKey).
capture_rest() {
  local method="$1" url="$2" outfile="$3" note="$4"
  shift 4
  local tmp_headers tmp_body status
  tmp_headers="$(mktemp)"
  tmp_body="$(mktemp)"
  status=$(curl -s --config <(printf 'header = "X-BM-ApiKey: %s"\n' "$KEY") -X "$method" -H 'Accept: application/json' -D "$tmp_headers" -o "$tmp_body" -w '%{http_code}' "$@" "$url" || echo 'unreachable')
  write_capture "$method" "$url" "$outfile" "$note" "$status" "$tmp_headers" "$tmp_body"
}

write_capture() {
  local method="$1" url="$2" outfile="$3" note="$4" status="$5" tmp_headers="$6" tmp_body="$7"
  {
    printf '# CAPTURED live on %s against BlueMind at %s\n' "$TODAY" "$(printf '%s' "$ROOT" | sed 's|https\?://||')"
    printf '# by dev/golden-capture/capture-bluemind-subscription.sh: %s %s\n' "$method" "$(printf '%s' "$url" | scrub)"
    printf '# %s\n' "$note"
    printf '# Request headers not recorded; password, Basic credentials and the X-BM-ApiKey session key scrubbed.\n'
    printf '# HTTP status: %s\n' "$status"
    scrub < "$tmp_headers"
    printf '\n'
    scrub < "$tmp_body"
    printf '\n'
  } > "$outfile"
  rm -f "$tmp_headers" "$tmp_body"
  echo "  -> $outfile (HTTP $status)"
  LAST_STATUS="$status"
  LAST_FILE="$outfile"
}

# One JSON value at a dotted path of a captured body, or empty.
json_of() {
  python3 - "$1" "$2" <<'PYEOF'
import json, sys
body = open(sys.argv[1], encoding='utf-8', errors='replace').read().split('\n\n', 1)[-1].strip()
try:
    node = json.loads(body) if body else None
except ValueError:
    print(''); sys.exit(0)
for part in sys.argv[2].split('.'):
    if isinstance(node, dict):
        node = node.get(part)
    else:
        node = None
print('' if node is None else (json.dumps(node) if isinstance(node, (dict, list)) else str(node)))
PYEOF
}

# Whether a captured subscriptions listing names a container uid.
lists_container() {
  python3 - "$1" "$2" <<'PYEOF'
import json, sys
body = open(sys.argv[1], encoding='utf-8', errors='replace').read().split('\n\n', 1)[-1].strip()
try:
    subs = json.loads(body) if body else []
except ValueError:
    print('unparseable'); sys.exit(0)
hit = [s for s in subs if isinstance(s, dict) and s.get('containerUid') == sys.argv[2]]
print('yes offlineSync=%s automount=%s' % (hit[0].get('offlineSync'), hit[0].get('automount')) if hit else 'no')
PYEOF
}

# Whether a captured multistatus lists an href ending with the container.
home_lists() {
  python3 - "$1" "$2" <<'PYEOF'
import re, sys, urllib.parse
body = open(sys.argv[1], encoding='utf-8', errors='replace').read().split('\n\n', 1)[-1]
want = sys.argv[2]
for h in re.findall(r'<[\w-]*:?href>([^<]+)</', body, re.I):
    if urllib.parse.unquote(h.strip()).rstrip('/').endswith('/' + want):
        print('yes'); break
else:
    print('no')
PYEOF
}

# Pulls one href out of a captured multistatus: the first href nested inside
# the named element.
href_from() {
  python3 - "$1" "$2" <<'PYEOF'
import re, sys
body = open(sys.argv[1], encoding='utf-8', errors='replace').read().split('\n\n', 1)[-1]
m = re.search(r'<[\w-]*:?%s\b[^>]*>(.*?)</[\w-]*:?%s>' % (sys.argv[2], sys.argv[2]), body, re.I | re.S)
scope = m.group(1) if m else ''
h = re.search(r'<[\w-]*:?href>([^<]+)</', scope, re.I)
print(h.group(1).strip() if h else '')
PYEOF
}

absolutize() {
  case "$1" in
    http://*|https://*) printf '%s' "$1" ;;
    /*) printf '%s%s' "$ROOT" "$1" ;;
    *) printf '%s/%s' "$BASE_URL" "$1" ;;
  esac
}

# Percent-encodes one path segment (RFC 3986 unreserved kept), as the client does.
encode_segment() {
  python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe="-._~"))' "$1"
}

subscribe_body() {
  printf '[{"containerUid":"%s","offlineSync":true,"automount":true}]' "$1"
}

PROPS_BODY='<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><D:prop><D:resourcetype/><D:displayname/><D:owner/><D:current-user-privilege-set/><C:supported-calendar-component-set/></D:prop></D:propfind>'

cleanup() {
  unset PASSWORD BASIC_B64 PASSWORD_PATTERN BASIC_PATTERN KEY KEY_PATTERN
}

echo '1/12 POST /api/auth/login as the sharee — authUser.uid and domainUid...'
LOGIN_URL="$ROOT/api/auth/login?login=$(encode_segment "$LOGIN")&origin=exo-caldav-capture"
tmp_headers="$(mktemp)"; tmp_body="$(mktemp)"
# The password is piped, never handed to python3 as an argument: an argv is
# visible in ps to every local user for the life of the call, and the header
# above promises it is never passed as one.
PASSWORD_JSON="$(printf '%s' "$PASSWORD" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')"
status=$(curl -s -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' --data-binary "$PASSWORD_JSON" -D "$tmp_headers" -o "$tmp_body" -w '%{http_code}' "$LOGIN_URL" || echo 'unreachable')
unset PASSWORD_JSON
KEY="$(python3 -c 'import json,sys; b=open(sys.argv[1]).read().strip(); print(json.loads(b).get("authKey","") if b else "")' "$tmp_body" 2>/dev/null || true)"
if [ -n "$KEY" ]; then KEY_PATTERN="$(escape_pattern "$KEY")"; fi
write_capture POST "$LOGIN_URL" "$OUT/bluemind-rest-login-sharee.captured.json" \
  'The login answer as the SHAREE: status, authKey (scrubbed), and authUser{uid, domainUid} - the two facts eXo reads from here (LoginResponse.java, AuthUser.java). Replaces the DERIVED bluemind-rest-login-ok.derived.json shape for the sharee.' \
  "$status" "$tmp_headers" "$tmp_body"
if [ "$status" != 200 ] || [ -z "$KEY" ]; then
  echo '    The login did not answer 200 with an authKey; stopping.'
  cleanup; exit 1
fi
UID_="$(json_of "$LAST_FILE" 'authUser.uid')"
DOMAIN="$(json_of "$LAST_FILE" 'authUser.domainUid')"
echo "    authUser.uid: $UID_   authUser.domainUid: $DOMAIN"
if [ -z "$UID_" ] || [ -z "$DOMAIN" ]; then
  echo '    No authUser.uid or authUser.domainUid in the answer: the client would refuse to edit anything. Stopping.'
  cleanup; exit 1
fi
SUBS="$ROOT/api/users/$(encode_segment "$DOMAIN")/subscriptions/$(encode_segment "$UID_")"

echo '2/12 GET subscriptions?type=calendar — the baseline...'
capture_rest GET "$SUBS?type=calendar" "$OUT/bluemind-rest-subscriptions-baseline.captured.json" \
  'The sharee''s calendar subscriptions BEFORE eXo subscribes them (IUserSubscription.java:44-47). The shared container must not be listed yet. Compare with bluemind-rest-subscriptions-calendar.derived.json.'
BASELINE_HAS="$(lists_container "$LAST_FILE" "$SHARED")"
echo "    shared container listed before: $BASELINE_HAS"

echo '3/12 POST _subscribe the shared container — THE call the feature makes...'
capture_rest POST "$SUBS/_subscribe" "$OUT/bluemind-rest-subscribe-shared.captured.json" \
  'POST _subscribe [{containerUid, offlineSync:true, automount:true}] as the sharee. Predicted 200 with an EMPTY body (UserSubscriptionService.java:113-142). Replaces bluemind-rest-subscribe-200-empty.derived.json.' \
  -H 'Content-Type: application/json' --data-binary "$(subscribe_body "$SHARED")"
SUBSCRIBE_STATUS="$LAST_STATUS"

echo '4/12 GET subscriptions again — the container as stored...'
capture_rest GET "$SUBS?type=calendar" "$OUT/bluemind-rest-subscriptions-after-subscribe.captured.json" \
  'The listing after step 3: the shared container with offlineSync and automount as BlueMind stored them.'
AFTER_HAS="$(lists_container "$LAST_FILE" "$SHARED")"
echo "    shared container listed after: $AFTER_HAS"

echo '5/12 PROPFIND Depth:1 on the sharee'"'"'s DAV calendar home — what eXo lists...'
DISCOVERY="$(mktemp -d)"
capture PROPFIND "$ROOT/dav/" "$DISCOVERY/principal.xml" 'discovery only' \
  -H 'Depth: 0' -H 'Content-Type: application/xml' --data-binary \
  '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:current-user-principal/></D:prop></D:propfind>'
PRINCIPAL="$(href_from "$DISCOVERY/principal.xml" 'current-user-principal')"
HOME=''
if [ -n "$PRINCIPAL" ]; then
  capture PROPFIND "$(absolutize "$PRINCIPAL")" "$DISCOVERY/home.xml" 'discovery only' \
    -H 'Depth: 0' -H 'Content-Type: application/xml' --data-binary \
    '<?xml version="1.0"?><D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><D:prop><C:calendar-home-set/></D:prop></D:propfind>'
  HOME="$(href_from "$DISCOVERY/home.xml" 'calendar-home-set')"
fi
rm -rf "$DISCOVERY"
HOME_HAS='not discovered'
if [ -n "$HOME" ]; then
  capture PROPFIND "$(absolutize "$HOME")" "$OUT/bluemind-propfind-home-depth1-sharee-subscribed.captured.xml" \
    'The SHAREE''s calendar home after the subscribe: the shared collection listed under their own __uids__ home (DavStore.java:441-449 lists subscriptions). The raw capture bluemind-propfind-home-depth1-sharee-subscribed.xml (DERIVED) owed.' \
    -H 'Depth: 1' -H 'Content-Type: application/xml' --data-binary "$PROPS_BODY"
  HOME_HAS="$(home_lists "$LAST_FILE" "$SHARED")"
else
  echo '    No principal/home discovered over DAV; the home listing is skipped.'
fi
echo "    shared collection under the sharee's home: $HOME_HAS"

echo '6/12 POST _subscribe again — idempotency...'
capture_rest POST "$SUBS/_subscribe" "$OUT/bluemind-rest-subscribe-shared-again.captured.json" \
  'The same _subscribe a second time. Predicted 200 again: an existing subscription is updated, not refused (UserSubscriptionService.java:134-138).' \
  -H 'Content-Type: application/json' --data-binary "$(subscribe_body "$SHARED")"
AGAIN_STATUS="$LAST_STATUS"

UNKNOWN="calendar:Default:exo-absent-probe-$(date +%s)"
echo "7/12 POST _subscribe an UNKNOWN container ($UNKNOWN) — 404 or 500 NOT_FOUND..."
capture_rest POST "$SUBS/_subscribe" "$OUT/bluemind-rest-subscribe-unknown-container.captured.json" \
  'An _subscribe naming a container BlueMind does not hold. Fault {errorCode:NOT_FOUND} predicted (subscriptionToContainer :282-298); the brief predicted HTTP 404, ResponseBuilder.java:70-76 says 500 - this settles it. Replaces bluemind-rest-fault-subscribe-not-found.derived.json.' \
  -H 'Content-Type: application/json' --data-binary "$(subscribe_body "$UNKNOWN")"
UNKNOWN_STATUS="$LAST_STATUS"
UNKNOWN_CODE="$(json_of "$LAST_FILE" 'errorCode')"

echo '8/12 POST _subscribe a container the sharee has NO access to, then PROPFIND it...'
capture_rest POST "$SUBS/_subscribe" "$OUT/bluemind-rest-subscribe-no-access.captured.json" \
  'An _subscribe to a container the sharee cannot read. 200 predicted: subscribe makes no ACL check (UserSubscriptionService.java:113-142); the subscription is a display preference, readable or not.' \
  -H 'Content-Type: application/json' --data-binary "$(subscribe_body "$NO_ACCESS")"
NO_ACCESS_STATUS="$LAST_STATUS"
NO_ACCESS_PROPFIND='skipped'
if [ -n "$HOME" ]; then
  capture PROPFIND "$(absolutize "${HOME%/}/$(encode_segment "$NO_ACCESS")/")" "$OUT/bluemind-propfind-collection-no-access.captured.xml" \
    'PROPFIND Depth:0 on the subscribed-but-unreadable collection under the sharee''s home: the status eXo''s home listing and per-collection reads must survive for a subscription without access.' \
    -H 'Depth: 0' -H 'Content-Type: application/xml' --data-binary "$PROPS_BODY"
  NO_ACCESS_PROPFIND="$LAST_STATUS"
fi
capture_rest POST "$SUBS/_unsubscribe" "$OUT/bluemind-rest-unsubscribe-no-access.captured.json" \
  'Undoes step 8 at once.' \
  -H 'Content-Type: application/json' --data-binary "[\"$NO_ACCESS\"]"

echo "9/12 POST _subscribe on ANOTHER uid's path ($OTHER_UID) with the sharee's session — the RBAC refusal..."
capture_rest POST "$ROOT/api/users/$(encode_segment "$DOMAIN")/subscriptions/$(encode_segment "$OTHER_UID")/_subscribe" \
  "$OUT/bluemind-rest-subscribe-other-uid.captured.json" \
  'An _subscribe addressed to another user''s subscriptions with the sharee''s session. 403 {errorCode:PERMISSION_DENIED} predicted (ROLE_SELF, UserSubscriptionService.java:129-130; ResponseBuilder.java:70-76). eXo never relies on it - BlueMindSubscriptionClient checks authUser.uid before any POST - but it should hold. Replaces bluemind-rest-fault-permission-denied.derived.json.' \
  -H 'Content-Type: application/json' --data-binary "$(subscribe_body "$SHARED")"
OTHER_STATUS="$LAST_STATUS"
OTHER_CODE="$(json_of "$LAST_FILE" 'errorCode')"

echo '10/12 POST _unsubscribe the shared container — the revoke''s call...'
capture_rest POST "$SUBS/_unsubscribe" "$OUT/bluemind-rest-unsubscribe-shared.captured.json" \
  'POST _unsubscribe ["containerUid"] as the sharee. Predicted 200 empty (UserSubscriptionService.java:184-209). What blueMindRevoke posts.' \
  -H 'Content-Type: application/json' --data-binary "[\"$SHARED\"]"
UNSUBSCRIBE_STATUS="$LAST_STATUS"
capture_rest GET "$SUBS?type=calendar" "$OUT/bluemind-rest-subscriptions-after-unsubscribe.captured.json" \
  'The listing after step 10: the shared container gone.'
GONE_LIST="$(lists_container "$LAST_FILE" "$SHARED")"
GONE_HOME='skipped'
if [ -n "$HOME" ]; then
  capture PROPFIND "$(absolutize "$HOME")" "$OUT/bluemind-propfind-home-depth1-sharee-unsubscribed.captured.xml" \
    'The sharee''s home after the unsubscribe: the shared collection no longer listed.' \
    -H 'Depth: 1' -H 'Content-Type: application/xml' --data-binary "$PROPS_BODY"
  GONE_HOME="$(home_lists "$LAST_FILE" "$SHARED")"
fi

echo '11/12 Observation: did the sharee receive BlueMind'"'"'s "access rights modified" mail for this share?'
printf '    Look at the sharee'"'"'s inbox, then type yes / no / unknown: '
read -r MAIL_SEEN

echo '12/12 The dangling-subscription question (hole 3): subscribing again, then the OWNER revokes...'
capture_rest POST "$SUBS/_subscribe" "$OUT/bluemind-rest-subscribe-before-revoke.captured.json" \
  'Step 12a: the sharee subscribed again, so the owner''s revoke below meets a live subscription.' \
  -H 'Content-Type: application/json' --data-binary "$(subscribe_body "$SHARED")"
echo '    Now, IN eXo, have the OWNER stop sharing this calendar with the sharee (the Share drawer).'
printf '    Press Enter once the revoke is done (or type skip): '
read -r REVOKED
REVOKE_LIST='skipped'; REVOKE_HOME='skipped'; REVOKE_COLLECTION='skipped'
if [ "$REVOKED" != 'skip' ]; then
  capture_rest GET "$SUBS?type=calendar" "$OUT/bluemind-rest-subscriptions-after-revoke.captured.json" \
    'The sharee''s listing AFTER the owner revoked while they were subscribed. Predicted: the subscription row SURVIVES (BlueMind auto-unsubscribes on an ACL removal for mailboxacl only, MailboxAutoSubscribeAclHook.java:60-91) - the dangling subscription hole 3 is about.'
  REVOKE_LIST="$(lists_container "$LAST_FILE" "$SHARED")"
  if [ -n "$HOME" ]; then
    capture PROPFIND "$(absolutize "$HOME")" "$OUT/bluemind-propfind-home-depth1-sharee-revoked.captured.xml" \
      'The sharee''s home AFTER the revoke with the subscription dangling: whether the href is still listed (which would keep a dead calendar in eXo''s listing and defeat the HIDDEN_SHARE retirement, CaldavSyncService forgetRevokedShares).' \
      -H 'Depth: 1' -H 'Content-Type: application/xml' --data-binary "$PROPS_BODY"
    REVOKE_HOME="$(home_lists "$LAST_FILE" "$SHARED")"
    capture PROPFIND "$(absolutize "${HOME%/}/$(encode_segment "$SHARED")/")" "$OUT/bluemind-propfind-collection-revoked.captured.xml" \
      'PROPFIND Depth:0 on the revoked-but-still-subscribed collection: the status a dangling subscription answers.' \
      -H 'Depth: 0' -H 'Content-Type: application/xml' --data-binary "$PROPS_BODY"
    REVOKE_COLLECTION="$LAST_STATUS"
  fi
fi
capture_rest POST "$SUBS/_unsubscribe" "$OUT/bluemind-rest-unsubscribe-final.captured.json" \
  'Restores the account: the subscription of step 12a removed (BlueMind removes the row even if the container is gone, UserSubscriptionService.java:184-209 "unsub anyway").' \
  -H 'Content-Type: application/json' --data-binary "[\"$SHARED\"]"
FINAL_STATUS="$LAST_STATUS"

tmp_headers="$(mktemp)"; tmp_body="$(mktemp)"
curl -s --config <(printf 'header = "X-BM-ApiKey: %s"\n' "$KEY") -X POST -o /dev/null "$ROOT/api/auth/logout" || true
rm -f "$tmp_headers" "$tmp_body"
cleanup

echo
echo 'What the captures say:'
echo "  - login: authUser.uid=$UID_ domainUid=$DOMAIN — the client reads both from here; a login answer without them edits nothing."
case "$BASELINE_HAS" in
  no) echo '  - baseline: the shared container was NOT subscribed before the run (a hand acceptance did not happen).' ;;
  *)  echo "  - baseline: the shared container was already listed ($BASELINE_HAS) — somebody accepted it by hand; steps 3-6 exercised the update path, not the creation." ;;
esac
if [ "$SUBSCRIBE_STATUS" = 200 ]; then
  echo "  - _subscribe answered 200 (predicted); afterwards the listing says: $AFTER_HAS; the sharee's home lists the collection: $HOME_HAS."
else
  echo "  - _subscribe answered $SUBSCRIBE_STATUS: NOT the predicted 200; see the capture before trusting the feature on this server."
fi
[ "$AGAIN_STATUS" = 200 ] && echo '  - a second _subscribe answered 200: idempotent, as predicted.' || echo "  - a second _subscribe answered $AGAIN_STATUS: NOT idempotent as predicted; the drain must not repeat a landed subscribe on this server."
case "$UNKNOWN_STATUS" in
  404) echo "  - an unknown container answered 404 ($UNKNOWN_CODE): the brief's prediction; the client's 404 branch is the one taken." ;;
  500) echo "  - an unknown container answered 500 ($UNKNOWN_CODE): the source's prediction (ResponseBuilder.java:70-76); the client's 500+NOT_FOUND branch is the one taken." ;;
  *)   echo "  - an unknown container answered $UNKNOWN_STATUS ($UNKNOWN_CODE): neither predicted shape; see the capture." ;;
esac
echo "  - a container without access: _subscribe answered $NO_ACCESS_STATUS (200 predicted); PROPFIND on it answered $NO_ACCESS_PROPFIND — the status eXo's listing must survive."
case "$OTHER_STATUS" in
  403) echo "  - another uid's path answered 403 ($OTHER_CODE): ROLE_SELF holds, as predicted." ;;
  *)   echo "  - another uid's path answered $OTHER_STATUS ($OTHER_CODE): NOT the predicted 403 — the authUser.uid check in the client is then the ONLY guard; worth a look." ;;
esac
echo "  - _unsubscribe answered $UNSUBSCRIBE_STATUS; afterwards the listing names the container: $GONE_LIST; the home lists it: $GONE_HOME."
echo "  - ACL-change mail received by the sharee: $MAIL_SEEN (capture 11; settles whether 'transparent' is informed on this rig)."
if [ "$REVOKED" != 'skip' ]; then
  echo "  - after the owner's revoke while subscribed: listing names the container: $REVOKE_LIST; home lists it: $REVOKE_HOME; PROPFIND on the collection: $REVOKE_COLLECTION."
  case "$REVOKE_LIST" in
    yes*) echo '    -> the subscription DANGLES after an ACL removal (as predicted from MailboxAutoSubscribeAclHook.java): blueMindRevoke must unsubscribe, which it does (hole 3 confirmed).' ;;
    no)   echo '    -> BlueMind removed the subscription with the ACL: hole 3 is closed on the server side too; the unsubscribe eXo posts is then a harmless no-op.' ;;
  esac
fi
echo "  - final _unsubscribe answered $FINAL_STATUS: the account is left as it was found."
echo
echo 'Done. Before committing, review the captured files in:'
echo "  $OUT"
echo 'Credentials and the session key are scrubbed, but the sharee login, uids'
echo 'and container uids appear in URLs and bodies — keep or anonymise them as'
echo 'you see fit. Point BlueMindSubscriptionClientTest at the captured files'
echo 'and drop the DERIVED fixtures they supersede, as EXO-90307 did.'

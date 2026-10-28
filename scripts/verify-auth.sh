#!/usr/bin/env bash
# Live verification for track signup-login_20261028 (US-16.1–16.3).
# Proves against the real local cluster: signup 201 + one user row + one
# access token + one UserRegistered outbox row and Kafka message; duplicate
# 409; login 200 with access+refresh; wrong password/unknown email the same
# 401; invalid input 400. Cleans up its test account afterwards.
set -euo pipefail

NAMESPACE="${NAMESPACE:-gluon-local}"
USER_URL="${USER_URL:-http://localhost:18083}"
PIDS=()
TMP_DIR="$(mktemp -d)"
cleanup() {
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

psql_scalar() {
  kubectl exec -n "$NAMESPACE" deploy/user-postgres -- \
    psql -U user -d user -tAc "$1" | tr -d '[:space:]'
}

post_json() {
  local url="$1" body="$2" out="$3"
  curl -sS -o "$out" -w '%{http_code}' -X POST "$url" \
    -H 'Content-Type: application/json' -d "$body"
}

json_field() {
  python3 -c "import json,sys; print(json.load(sys.stdin)$1)" < "$2"
}

echo "== US-16 live verification =="
kubectl -n "$NAMESPACE" port-forward svc/user-service 18083:8080 >/dev/null 2>&1 &
PIDS+=("$!")
for _ in $(seq 1 30); do
  curl -sf "$USER_URL/health/ready" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf "$USER_URL/health/ready" >/dev/null || {
  echo "FAIL: user-service never became ready" >&2
  exit 1
}

EMAIL="verify-$(python3 -c 'import uuid; print(uuid.uuid4())')@example.com"
PASSWORD="correct horse battery"

echo "-- signup 201 --"
STATUS="$(post_json "$USER_URL/auth/signup" \
  "{\"email\":\"  $EMAIL \",\"password\":\"$PASSWORD\"}" \
  "$TMP_DIR/signup.json")"
[ "$STATUS" = "201" ] || { echo "FAIL: signup -> $STATUS" >&2; cat "$TMP_DIR/signup.json" >&2; exit 1; }
USER_ID="$(json_field '["userId"]' "$TMP_DIR/signup.json")"
TOKEN="$(json_field '["token"]' "$TMP_DIR/signup.json")"
[ -n "$TOKEN" ] || { echo "FAIL: signup returned no token" >&2; exit 1; }
echo "   user $USER_ID"

USERS="$(psql_scalar "select count(*) from user_account where id = '$USER_ID' and email = '$EMAIL'")"
TOKENS="$(psql_scalar "select count(*) from auth_tokens where user_id = '$USER_ID' and kind = 'access'")"
OUTBOX="$(psql_scalar "select count(*) from outbox_events where aggregate_id = '$USER_ID' and payload->>'eventType' = 'UserRegistered'")"
[ "$USERS" = "1" ] || { echo "FAIL: expected 1 user row, got $USERS" >&2; exit 1; }
[ "$TOKENS" = "1" ] || { echo "FAIL: expected 1 access token, got $TOKENS" >&2; exit 1; }
[ "$OUTBOX" = "1" ] || { echo "FAIL: expected 1 outbox row, got $OUTBOX" >&2; exit 1; }
echo "   1 user, 1 access token, 1 outbox row"

echo "-- one UserRegistered message on user-events --"
MESSAGES="$(kubectl exec -n "$NAMESPACE" deploy/kafka -- \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic user-events --from-beginning --timeout-ms 10000 2>/dev/null || true)"
echo "$MESSAGES" | grep -q 'UserRegistered' || {
  echo "FAIL: no UserRegistered message observed" >&2
  exit 1
}
echo "$MESSAGES" | grep -q "$USER_ID" || {
  echo "FAIL: UserRegistered message does not reference $USER_ID" >&2
  exit 1
}
echo "   observed UserRegistered for $USER_ID"

echo "-- duplicate signup 409 --"
STATUS="$(post_json "$USER_URL/auth/signup" \
  "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" "$TMP_DIR/dup.json")"
[ "$STATUS" = "409" ] || { echo "FAIL: duplicate -> $STATUS" >&2; cat "$TMP_DIR/dup.json" >&2; exit 1; }
echo "   409"

echo "-- invalid signup 400 --"
STATUS="$(post_json "$USER_URL/auth/signup" \
  '{"email":"not-an-email","password":"short"}' "$TMP_DIR/invalid.json")"
[ "$STATUS" = "400" ] || { echo "FAIL: invalid signup -> $STATUS" >&2; exit 1; }
echo "   400"

echo "-- login 200 with token + refreshToken --"
STATUS="$(post_json "$USER_URL/auth/login" \
  "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" "$TMP_DIR/login.json")"
[ "$STATUS" = "200" ] || { echo "FAIL: login -> $STATUS" >&2; cat "$TMP_DIR/login.json" >&2; exit 1; }
LOGIN_TOKEN="$(json_field '["token"]' "$TMP_DIR/login.json")"
REFRESH_TOKEN="$(json_field '["refreshToken"]' "$TMP_DIR/login.json")"
LOGIN_USER_ID="$(json_field '["userId"]' "$TMP_DIR/login.json")"
[ -n "$LOGIN_TOKEN" ] && [ -n "$REFRESH_TOKEN" ] || { echo "FAIL: login tokens missing" >&2; exit 1; }
[ "$LOGIN_USER_ID" = "$USER_ID" ] || { echo "FAIL: login userId $LOGIN_USER_ID != signup $USER_ID" >&2; exit 1; }
LOGIN_TOKENS="$(psql_scalar "select count(*) from auth_tokens where user_id = '$USER_ID'")"
[ "$LOGIN_TOKENS" = "3" ] || { echo "FAIL: expected 3 tokens after login, got $LOGIN_TOKENS" >&2; exit 1; }
echo "   200; 3 tokens stored"

echo "-- wrong password and unknown email are the same 401 --"
STATUS="$(post_json "$USER_URL/auth/login" \
  "{\"email\":\"$EMAIL\",\"password\":\"wrong password\"}" "$TMP_DIR/wrong.json")"
[ "$STATUS" = "401" ] || { echo "FAIL: wrong password -> $STATUS" >&2; exit 1; }
STATUS="$(post_json "$USER_URL/auth/login" \
  "{\"email\":\"nobody-$(python3 -c 'import uuid; print(uuid.uuid4())')@example.com\",\"password\":\"$PASSWORD\"}" \
  "$TMP_DIR/unknown.json")"
[ "$STATUS" = "401" ] || { echo "FAIL: unknown email -> $STATUS" >&2; exit 1; }
[ "$(json_field '["error"]' "$TMP_DIR/wrong.json")" = "$(json_field '["error"]' "$TMP_DIR/unknown.json")" ] || {
  echo "FAIL: 401 messages differ" >&2
  exit 1
}
echo "   401 / 401, same message"

echo "-- cleaning up test account --"
kubectl exec -n "$NAMESPACE" deploy/user-postgres -- psql -U user -d user -q -c \
  "delete from outbox_events where aggregate_id = '$USER_ID'; delete from user_account where id = '$USER_ID'" >/dev/null

echo "All US-16 live checks passed."

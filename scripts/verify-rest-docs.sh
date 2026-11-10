#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -euo pipefail

# Verifies the REST docs convention end-to-end (track rest-docs_20261101,
# gluon TD-20.10): brings up Postgres and a real sbt run instance, fetches the
# served OpenAPI spec at /docs/docs.yaml, and checks it carries every
# endpoint's summary/description/tag, the error descriptions, DTO field
# descriptions and body examples, that each endpoint lists only the error
# statuses it can return, and that auth examples are obviously fake - not just
# that UserDocsSuite's in-process Docs.undocumented check passes.
#
# Requires: sbt, Docker, python3 with PyYAML.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SBT_PID=""
SPEC="$(mktemp)"

cleanup() {
  echo
  echo "Cleaning up..."
  if [ -n "$SBT_PID" ]; then
    pkill -9 -P "$SBT_PID" >/dev/null 2>&1 || true
    kill -9 "$SBT_PID" >/dev/null 2>&1 || true
  fi
  docker compose down -v >/dev/null 2>&1 || true
  rm -f "$SPEC"
}
trap cleanup EXIT

wait_ready() {
  for _ in $(seq 1 180); do
    status="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health/ready || true)"
    [ "$status" = "200" ] && return 0
    sleep 1
  done
  echo "FAIL: user-service did not become ready in time." >&2
  return 1
}

echo "1. docker compose up -d (fresh volume)..."
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d
echo "   OK: postgres starting"

echo
echo "2. Starting user-service (sbt run) in the background..."
if [ -z "${GITHUB_TOKEN:-}" ] || [ -z "${GITHUB_ACTOR:-}" ]; then
  echo "WARN: GITHUB_TOKEN / GITHUB_ACTOR not set - resolving purerestlib from" >&2
  echo "      GitHub Packages will fail without a read:packages token." >&2
fi
sbt run </dev/null >/tmp/user-service-verify-rest-docs.log 2>&1 &
SBT_PID=$!
wait_ready
echo "   OK: user-service ready on :8080"

echo
echo "3. GET /docs/docs.yaml..."
status="$(curl -s -o "$SPEC" -w '%{http_code}' http://localhost:8080/docs/docs.yaml)"
[ "$status" = "200" ] || { echo "FAIL: /docs/docs.yaml returned $status" >&2; exit 1; }
echo "   OK: served (200)"

echo
echo "4. Checking the spec..."
python3 -I - "$SPEC" <<'PY'
import re
import sys
import yaml

spec = yaml.safe_load(open(sys.argv[1]))
paths, schemas = spec["paths"], spec["components"]["schemas"]
failed = False

def check(label, ok):
    global failed
    print(("   OK: " if ok else "   FAIL: ") + label,
          file=sys.stdout if ok else sys.stderr)
    failed |= not ok

# Statuses each endpoint may list (success + its narrowed errors; tapir adds
# 400 for undecodable inputs on its own).
allowed = {
    ("/auth/signup", "post"): {"201", "400", "409"},
    ("/auth/login", "post"): {"200", "400", "401"},
    ("/health", "get"): {"200"},
    ("/health/ready", "get"): {"200", "503"},
}
expected_tags = {
    ("/auth/signup", "post"): ["auth"],
    ("/auth/login", "post"): ["auth"],
    ("/health", "get"): ["health"],
    ("/health/ready", "get"): ["health"],
}
for (path, method), statuses in allowed.items():
    op = paths.get(path, {}).get(method)
    name = f"{method.upper()} {path}"
    if op is None:
        check(f"{name} documented", False)
        continue
    check(f"{name} has summary/description/tag",
          bool(op.get("summary")) and bool(op.get("description"))
          and bool(op.get("tags")))
    check(f"{name} tag is {expected_tags[(path, method)]}",
          op.get("tags") == expected_tags[(path, method)])
    check(f"{name} has no parameters (bodies only)",
          not op.get("parameters"))
    responses = op.get("responses", {})
    check(f"{name} lists only {sorted(statuses)} (got {sorted(responses)})",
          set(responses) <= statuses)
    for code, r in responses.items():
        if code.startswith("2"):
            continue
        check(f"{name} {code} described", bool(r.get("description")))

signup = paths["/auth/signup"]["post"]
login = paths["/auth/login"]["post"]
check("POST /auth/signup lists 201/400/409",
      {"201", "400", "409"} <= set(signup["responses"]))
check("POST /auth/login lists 200/400/401",
      {"200", "400", "401"} <= set(login["responses"]))
check("POST /auth/signup request body has an example",
      any("example" in c for c in signup["requestBody"]["content"].values()))
check("POST /auth/login request body has an example",
      any("example" in c for c in login["requestBody"]["content"].values()))
check("POST /auth/signup 201 has an example body",
      any("example" in c for c in signup["responses"]["201"]["content"].values()))
check("POST /auth/login 200 has an example body",
      any("example" in c for c in login["responses"]["200"]["content"].values()))
for path, op, code in [
    ("/auth/signup", signup, "400"),
    ("/auth/signup", signup, "409"),
    ("/auth/login", login, "400"),
    ("/auth/login", login, "401"),
]:
    check(f"{path} {code} has an example body",
          any("example" in c for c in op["responses"][code]["content"].values()))

for name in ["SignupRequest", "LoginRequest", "SignupResponse",
             "LoginResponse", "ErrorResponse"]:
    props = schemas.get(name, {}).get("properties", {})
    missing = [f for f, s in props.items() if not s.get("description")]
    check(f"schema {name} fields all described"
          + (f" (missing: {missing})" if missing else ""),
          bool(props) and not missing)

# TD-20.10: auth examples must be obviously fake, never a real credential or
# token. Tokens are placeholder strings, never JWT-shaped.
def prop_example(schema, field):
    s = schemas[schema]["properties"][field]
    return s.get("examples", [s.get("example")])[0]

jwt = re.compile(r"^[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}$")
examples = [
    prop_example("SignupRequest", "email"),
    prop_example("SignupRequest", "password"),
    prop_example("LoginRequest", "email"),
    prop_example("LoginRequest", "password"),
    prop_example("SignupResponse", "token"),
    prop_example("LoginResponse", "token"),
    prop_example("LoginResponse", "refreshToken"),
]
check("auth email examples use example.com",
      all(e.endswith("@example.com") for e in examples[0:4:2]))
check("auth password example is the fake placeholder",
      examples[1] == examples[3] == "correct-horse-battery-staple")
check("auth token examples are fake placeholders",
      all(e.startswith("example-") and "not-valid" in e
          for e in examples[4:]))
check("no auth example is JWT-shaped",
      not any(jwt.match(str(e)) for e in examples))

sys.exit(1 if failed else 0)
PY

echo
echo "All checks passed."

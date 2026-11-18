#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo 'Usage: ./ask.sh "/mcp add {\"a\":19,\"b\":23}"' >&2
  exit 2
fi

endpoint="${A2A_BASE_URL:-http://127.0.0.1:${A2A_SERVER_PORT:-8889}}"
payload="$(python3 - "$*" <<'PYCODE'
import json
import sys
import uuid
print(json.dumps({
    "jsonrpc": "2.0", "id": str(uuid.uuid4()), "method": "message/send",
    "params": {
        "configuration": {"blocking": True},
        "message": {
            "role": "user", "kind": "message", "messageId": str(uuid.uuid4()),
            "parts": [{"kind": "text", "text": sys.argv[1]}]
        }
    }
}))
PYCODE
)"
curl --fail-with-body --silent --show-error --max-time 120 \
  "$endpoint/" -H 'Content-Type: application/json' --data-binary "$payload"
printf '\n'

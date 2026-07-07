#!/usr/bin/env python3
"""Cross-language envelope smoke test, Python side (RISKS.md R7 made executable).

Emits an OperationResult wire document the way a future Python worker would — plain
stdlib json, no Java anywhere. The committed output
(spec/operation-results/valid/python-emitted-failure.json) must schema-validate and
round-trip byte-identically through the Java reader/writer; if the envelope carried
any Java-ism, this is where it would surface.

Regenerate: python3 spec/tools/emit_operation_result.py > \
    spec/operation-results/valid/python-emitted-failure.json
"""

import json
import sys

result = {
    "status": "failure",
    "error": {
        "code": "RATE_LIMIT",
        "message": "429 from upstream: retry after 30s",
        "retryable": True,
        "origin": "infra",
        "details": {
            "httpStatus": 429,
            "retryAfterSeconds": 30,
        },
    },
    "usage": {
        "tokens": 2048,
        "costUsd": 0.0125,
    },
}

json.dump(result, sys.stdout, indent=2, sort_keys=False)
sys.stdout.write("\n")

#!/usr/bin/env bash
# OpenAPI -> TypeScript 타입 생성
# 사전 조건: BE bootRun (http://localhost:8080/api/v3/api-docs 접근 가능)
set -euo pipefail

OPENAPI_URL="${OPENAPI_URL:-http://localhost:8080/api/v3/api-docs}"
OUTPUT="${OUTPUT:-src/lib/api/generated.ts}"

echo "[gen-api] fetching OpenAPI spec from $OPENAPI_URL"
echo "[gen-api] output -> $OUTPUT"

npx --yes openapi-typescript "$OPENAPI_URL" --output "$OUTPUT"

echo "[gen-api] done"

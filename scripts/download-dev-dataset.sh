#!/usr/bin/env bash
#
# scripts/download-dev-dataset.sh
#
# DEV/LOCAL TEST 한정 외부 데이터셋 다운로드 스크립트.
# - COCO val2017 sample (50장) 다운로드
# - 라이센스: 이미지별 상이 (대부분 Flickr CC-BY 2.0)
# - 출처: https://cocodataset.org/
#
# 사용 범위:
#   - dev/local 테스트 한정
#   - 배포/재배포/외부 공유/production 사용 금지
#   - 다운로드 디렉토리(storage/raw/seed-real/)는 .gitignore 처리됨
#
# Usage:
#   ./scripts/download-dev-dataset.sh           # 50장 다운로드
#   ./scripts/download-dev-dataset.sh --count 10  # 10장만 다운로드 (PoC)
#
set -euo pipefail

# ─────────────────────────────────────────────────────────────
# Configuration
# ─────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST_DIR="${PROJECT_ROOT}/storage/raw/seed-real/coco-val2017-sample"
MANIFEST_FILE="${DEST_DIR}/MANIFEST.sha256"
ATTRIBUTION_FILE="${PROJECT_ROOT}/storage/raw/seed-real/ATTRIBUTION.md"

# 외부 다운로드 URL allowlist (security.md SSRF 방어)
ALLOWED_HOST="images.cocodataset.org"
BASE_URL="http://images.cocodataset.org/val2017"

# 재시도 설정
MAX_RETRIES=3
TIMEOUT_SEC=30

# 다운로드 매수 (인자로 변경 가능)
COUNT=50
if [[ "${1:-}" == "--count" && -n "${2:-}" ]]; then
  COUNT="${2}"
fi

# ─────────────────────────────────────────────────────────────
# COCO val2017 image ID list (verified IDs from official split)
# 처음 50개 — 다양한 카테고리 (사람/차량/실내/거리)
# ─────────────────────────────────────────────────────────────
COCO_IDS=(
  "000000000139" "000000000285" "000000000632" "000000000724" "000000000776"
  "000000000785" "000000000802" "000000000872" "000000000885" "000000001000"
  "000000001268" "000000001296" "000000001353" "000000001425" "000000001490"
  "000000001503" "000000001532" "000000001584" "000000001675" "000000001761"
  "000000001818" "000000001993" "000000002006" "000000002149" "000000002153"
  "000000002157" "000000002261" "000000002299" "000000002431" "000000002473"
  "000000002532" "000000002587" "000000002592" "000000002685" "000000002898"
  "000000002923" "000000003156" "000000003255" "000000003501" "000000003553"
  "000000003661" "000000003845" "000000003934" "000000004134" "000000004395"
  "000000004495" "000000004765" "000000005001" "000000005037" "000000005060"
)

# ─────────────────────────────────────────────────────────────
# Functions
# ─────────────────────────────────────────────────────────────
log()   { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }
fail()  { printf "[ERROR] %s\n" "$*" >&2; exit 1; }

# Allowlist 검증 (SSRF 방어)
validate_url() {
  local url="$1"
  if [[ ! "$url" == http://${ALLOWED_HOST}/* ]] && [[ ! "$url" == https://${ALLOWED_HOST}/* ]]; then
    fail "URL not in allowlist: $url"
  fi
}

# 단일 이미지 다운로드 (재시도 포함)
download_one() {
  local image_id="$1"
  local url="${BASE_URL}/${image_id}.jpg"
  local out="${DEST_DIR}/${image_id}.jpg"

  validate_url "$url"

  if [[ -f "$out" && -s "$out" ]]; then
    log "skip (exists): ${image_id}.jpg"
    return 0
  fi

  local attempt=1
  while [[ $attempt -le $MAX_RETRIES ]]; do
    if curl -sS -f -L --max-time "$TIMEOUT_SEC" -o "$out.tmp" "$url"; then
      mv "$out.tmp" "$out"
      local size
      size=$(wc -c < "$out" | tr -d ' ')
      log "ok ${image_id}.jpg (${size} bytes)"
      return 0
    fi
    log "retry ${attempt}/${MAX_RETRIES} for ${image_id}.jpg"
    rm -f "$out.tmp"
    attempt=$((attempt + 1))
    sleep 2
  done

  log "FAIL ${image_id}.jpg after ${MAX_RETRIES} retries"
  return 1
}

# SHA256 manifest 생성
generate_manifest() {
  log "generating SHA256 manifest..."
  (
    cd "$DEST_DIR"
    if command -v shasum >/dev/null 2>&1; then
      shasum -a 256 *.jpg > "MANIFEST.sha256"
    elif command -v sha256sum >/dev/null 2>&1; then
      sha256sum *.jpg > "MANIFEST.sha256"
    else
      log "WARN: no sha256 tool found, skipping manifest"
      return 0
    fi
  )
  log "manifest: ${MANIFEST_FILE}"
}

# ─────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────
main() {
  log "=== DEV/LOCAL dataset download ==="
  log "destination: ${DEST_DIR}"
  log "count: ${COUNT}"

  mkdir -p "$DEST_DIR"

  # ATTRIBUTION 파일이 없으면 동봉용 사본 안내
  if [[ ! -f "$ATTRIBUTION_FILE" ]]; then
    log "WARN: ATTRIBUTION.md not found at ${ATTRIBUTION_FILE}"
    log "      please ensure attribution before use."
  fi

  local ok=0
  local fail=0
  local i=0
  for image_id in "${COCO_IDS[@]}"; do
    if [[ $i -ge $COUNT ]]; then break; fi
    if download_one "$image_id"; then
      ok=$((ok + 1))
    else
      fail=$((fail + 1))
    fi
    i=$((i + 1))
  done

  log "=== Result: ${ok} ok / ${fail} fail ==="

  if [[ $ok -gt 0 ]]; then
    generate_manifest
  fi

  local total_bytes
  total_bytes=$(du -sh "$DEST_DIR" 2>/dev/null | awk '{print $1}')
  log "total size: ${total_bytes}"

  if [[ $fail -gt 0 ]]; then
    exit 1
  fi
}

main "$@"

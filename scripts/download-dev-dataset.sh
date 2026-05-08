#!/usr/bin/env bash
#
# scripts/download-dev-dataset.sh
#
# DEV/LOCAL TEST 한정 외부 데이터셋 다운로드 스크립트.
# - COCO val2017 annotations 다운로드 → 카테고리별 이미지 선별
# - COCO val2017 sample (fallback: 50장 하드코딩 IDs)
# - 라이센스: 이미지별 상이 (대부분 Flickr CC-BY 2.0)
# - 출처: https://cocodataset.org/
#
# 사용 범위:
#   - dev/local 테스트 한정
#   - 배포/재배포/외부 공유/production 사용 금지
#   - 다운로드 디렉토리(storage/raw/seed-real/)는 .gitignore 처리됨
#
# Usage:
#   ./scripts/download-dev-dataset.sh           # annotation 기반 카테고리별 다운로드
#   ./scripts/download-dev-dataset.sh --count 10  # 기존 방식 10장만 다운로드 (PoC)
#
set -euo pipefail

# ─────────────────────────────────────────────────────────────
# Configuration
# ─────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST_DIR="${PROJECT_ROOT}/storage/raw/seed-real/coco-val2017-sample"
SEED_REAL_DIR="${PROJECT_ROOT}/storage/raw/seed-real"
MANIFEST_FILE="${DEST_DIR}/MANIFEST.sha256"
ATTRIBUTION_FILE="${SEED_REAL_DIR}/ATTRIBUTION.md"
ANN_ZIP="${SEED_REAL_DIR}/annotations_trainval2017.zip"
ANN_JSON="${SEED_REAL_DIR}/instances_val2017.json"

# 이벤트별 이미지 저장 디렉토리
DIR_FALL_VIOLENCE_ABNORMAL="${SEED_REAL_DIR}/fall-violence-abnormal"
DIR_TRAFFIC_ACCIDENT="${SEED_REAL_DIR}/traffic-accident"
DIR_FLOOD_FIRE="${SEED_REAL_DIR}/flood-fire"

# 외부 다운로드 URL allowlist (security.md SSRF 방어)
ALLOWED_HOST_1="images.cocodataset.org"
ALLOWED_HOST_2="commondatastorage.googleapis.com"
ANNOTATIONS_HOST="images.cocodataset.org"
BASE_URL="http://images.cocodataset.org/val2017"
ANNOTATIONS_ZIP_URL="http://images.cocodataset.org/annotations/annotations_trainval2017.zip"

# 카테고리별 다운로드 매수
COUNT_PERSON=30      # cat_id=1 (person) — EVT_FALL/VIOLENCE/ABNORMAL
COUNT_VEHICLE=20     # cat_id=3,4,6,8 (car,motorcycle,bus,truck) — EVT_ACCIDENT
COUNT_OUTDOOR=10     # cat_id=10,13,17,72 — EVT_FLOOD/FIRE 임시 대체용

# 재시도 설정
MAX_RETRIES=3
TIMEOUT_SEC=30

# 다운로드 매수 (인자로 변경 가능 — fallback 모드 전용)
COUNT=50
FALLBACK_MODE=false
if [[ "${1:-}" == "--count" && -n "${2:-}" ]]; then
  COUNT="${2}"
  FALLBACK_MODE=true
fi

# ─────────────────────────────────────────────────────────────
# COCO val2017 image ID list (verified IDs from official split)
# 처음 50개 — 다양한 카테고리 (사람/차량/실내/거리)
# Python3 없을 때 fallback으로 사용
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
warn()  { printf "[WARN]  %s\n" "$*" >&2; }

# Allowlist 검증 (security.md SSRF 방어)
validate_url() {
  local url="$1"
  if [[ "$url" == http://${ALLOWED_HOST_1}/* ]] || \
     [[ "$url" == https://${ALLOWED_HOST_1}/* ]] || \
     [[ "$url" == http://${ALLOWED_HOST_2}/* ]] || \
     [[ "$url" == https://${ALLOWED_HOST_2}/* ]]; then
    return 0
  fi
  fail "URL not in allowlist: $url (허용 호스트: ${ALLOWED_HOST_1}, ${ALLOWED_HOST_2})"
}

# 단일 이미지 다운로드 (재시도 포함)
download_one() {
  local image_id="$1"
  local dest_dir="${2:-${DEST_DIR}}"
  local url="${BASE_URL}/${image_id}.jpg"
  local out="${dest_dir}/${image_id}.jpg"

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
      log "ok ${image_id}.jpg (${size} bytes) → $(basename "$dest_dir")"
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
  local target_dir="${1:-${DEST_DIR}}"
  log "generating SHA256 manifest in $(basename "$target_dir")..."
  (
    cd "$target_dir"
    if ! ls ./*.jpg >/dev/null 2>&1; then
      log "WARN: no jpg files in $target_dir, skipping manifest"
      return 0
    fi
    if command -v shasum >/dev/null 2>&1; then
      shasum -a 256 *.jpg > "MANIFEST.sha256"
    elif command -v sha256sum >/dev/null 2>&1; then
      sha256sum *.jpg > "MANIFEST.sha256"
    else
      log "WARN: no sha256 tool found, skipping manifest"
      return 0
    fi
  )
  log "manifest: ${target_dir}/MANIFEST.sha256"
}

# annotations zip 다운로드 및 instances_val2017.json 추출
download_annotations() {
  if [[ -f "$ANN_JSON" ]]; then
    log "annotations already exist: ${ANN_JSON}"
    return 0
  fi

  log "downloading COCO annotations zip (~241MB)..."
  validate_url "$ANNOTATIONS_ZIP_URL"

  local attempt=1
  while [[ $attempt -le $MAX_RETRIES ]]; do
    if curl -sS -f -L --max-time 300 -o "${ANN_ZIP}.tmp" "$ANNOTATIONS_ZIP_URL"; then
      mv "${ANN_ZIP}.tmp" "$ANN_ZIP"
      log "ok annotations zip ($(du -sh "$ANN_ZIP" | awk '{print $1}'))"
      break
    fi
    warn "retry ${attempt}/${MAX_RETRIES} for annotations zip"
    rm -f "${ANN_ZIP}.tmp"
    attempt=$((attempt + 1))
    sleep 5
  done

  if [[ ! -f "$ANN_ZIP" ]]; then
    fail "annotations zip 다운로드 실패"
  fi

  log "extracting instances_val2017.json..."
  if command -v unzip >/dev/null 2>&1; then
    unzip -p "$ANN_ZIP" "annotations/instances_val2017.json" > "$ANN_JSON"
  else
    fail "unzip 명령이 없습니다. unzip을 설치 후 재실행하세요."
  fi

  if [[ -f "$ANN_JSON" && -s "$ANN_JSON" ]]; then
    log "ok instances_val2017.json ($(du -sh "$ANN_JSON" | awk '{print $1}'))"
    rm -f "$ANN_ZIP"
    log "removed annotations zip (instances_val2017.json 추출 완료)"
  else
    fail "instances_val2017.json 추출 실패"
  fi
}

# Python3로 annotation JSON 파싱하여 카테고리별 image ID 리스트 추출
# 결과를 공백 구분 문자열로 stdout 출력
extract_ids_by_category() {
  local ann_json="$1"
  local cat_ids="$2"   # 쉼표 구분 (예: "1" or "3,4,6,8")
  local limit="$3"

  python3 - "$ann_json" "$cat_ids" "$limit" <<'PYEOF'
import sys
import json

ann_json = sys.argv[1]
cat_ids_raw = sys.argv[2]
limit = int(sys.argv[3])

target_cat_ids = set(int(c) for c in cat_ids_raw.split(','))

with open(ann_json, 'r') as f:
    data = json.load(f)

# 카테고리에 해당하는 image_id 수집 (중복 제거, 순서 유지)
seen = set()
result = []
for ann in data.get('annotations', []):
    if ann['category_id'] in target_cat_ids:
        img_id = ann['image_id']
        if img_id not in seen:
            seen.add(img_id)
            result.append(img_id)
        if len(result) >= limit:
            break

# image_id → file_name 매핑
id_to_fname = {img['id']: img['file_name'] for img in data.get('images', [])}

# 12자리 숫자 ID만 출력 (확장자 제거)
for img_id in result:
    fname = id_to_fname.get(img_id, '')
    if fname:
        # e.g. "000000000139.jpg" → "000000000139"
        print(fname.replace('.jpg', '').replace('.jpeg', ''))
PYEOF
}

# annotation 기반 카테고리별 다운로드
download_by_category() {
  log "=== annotation 기반 카테고리별 다운로드 ==="

  mkdir -p "$DIR_FALL_VIOLENCE_ABNORMAL" "$DIR_TRAFFIC_ACCIDENT" "$DIR_FLOOD_FIRE"

  # 1. annotations 다운로드
  download_annotations

  # 2. person (cat_id=1) → fall-violence-abnormal/
  log "--- person 이미지 (cat_id=1, ${COUNT_PERSON}장) → fall-violence-abnormal/ ---"
  local person_ids
  person_ids=$(extract_ids_by_category "$ANN_JSON" "1" "$COUNT_PERSON")
  local ok=0; local fail_cnt=0
  while IFS= read -r image_id; do
    [[ -z "$image_id" ]] && continue
    if download_one "$image_id" "$DIR_FALL_VIOLENCE_ABNORMAL"; then
      ok=$((ok + 1))
    else
      fail_cnt=$((fail_cnt + 1))
    fi
  done <<< "$person_ids"
  log "person: ${ok} ok / ${fail_cnt} fail"

  # 3. vehicle (cat_id=3,4,6,8) → traffic-accident/
  log "--- vehicle 이미지 (cat_id=3,4,6,8, ${COUNT_VEHICLE}장) → traffic-accident/ ---"
  local vehicle_ids
  vehicle_ids=$(extract_ids_by_category "$ANN_JSON" "3,4,6,8" "$COUNT_VEHICLE")
  ok=0; fail_cnt=0
  while IFS= read -r image_id; do
    [[ -z "$image_id" ]] && continue
    if download_one "$image_id" "$DIR_TRAFFIC_ACCIDENT"; then
      ok=$((ok + 1))
    else
      fail_cnt=$((fail_cnt + 1))
    fi
  done <<< "$vehicle_ids"
  log "vehicle: ${ok} ok / ${fail_cnt} fail"

  # 4. outdoor (cat_id=10,13,17,72) → flood-fire/
  log "--- outdoor 이미지 (cat_id=10,13,17,72, ${COUNT_OUTDOOR}장) → flood-fire/ ---"
  local outdoor_ids
  outdoor_ids=$(extract_ids_by_category "$ANN_JSON" "10,13,17,72" "$COUNT_OUTDOOR")
  ok=0; fail_cnt=0
  while IFS= read -r image_id; do
    [[ -z "$image_id" ]] && continue
    if download_one "$image_id" "$DIR_FLOOD_FIRE"; then
      ok=$((ok + 1))
    else
      fail_cnt=$((fail_cnt + 1))
    fi
  done <<< "$outdoor_ids"
  log "outdoor: ${ok} ok / ${fail_cnt} fail"

  # 5. manifest 생성
  generate_manifest "$DIR_FALL_VIOLENCE_ABNORMAL"
  generate_manifest "$DIR_TRAFFIC_ACCIDENT"
  generate_manifest "$DIR_FLOOD_FIRE"
}

# fallback: 하드코딩 IDs 기반 다운로드 (Python3 없을 때)
download_fallback() {
  log "=== fallback 모드: 하드코딩 IDs 다운로드 (count=${COUNT}) ==="
  warn "Python3 없음 — annotation 파싱 불가. 기존 방식(하드코딩 IDs)으로 진행합니다."
  warn "카테고리별 이미지 분류가 되지 않으므로 apply-seed-images.sh 실행 전"
  warn "이미지를 수동으로 이벤트 디렉토리로 이동하거나 Python3를 설치하세요."

  mkdir -p "$DEST_DIR"

  local ok=0
  local fail_cnt=0
  local i=0
  for image_id in "${COCO_IDS[@]}"; do
    if [[ $i -ge $COUNT ]]; then break; fi
    if download_one "$image_id" "$DEST_DIR"; then
      ok=$((ok + 1))
    else
      fail_cnt=$((fail_cnt + 1))
    fi
    i=$((i + 1))
  done

  log "=== Result: ${ok} ok / ${fail_cnt} fail ==="

  if [[ $ok -gt 0 ]]; then
    generate_manifest "$DEST_DIR"
  fi

  local total_bytes
  total_bytes=$(du -sh "$DEST_DIR" 2>/dev/null | awk '{print $1}')
  log "total size: ${total_bytes}"

  if [[ $fail_cnt -gt 0 ]]; then
    exit 1
  fi
}

# ─────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────
main() {
  log "=== DEV/LOCAL dataset download ==="
  log "seed-real root: ${SEED_REAL_DIR}"

  # ATTRIBUTION 파일 존재 확인
  if [[ ! -f "$ATTRIBUTION_FILE" ]]; then
    warn "ATTRIBUTION.md not found at ${ATTRIBUTION_FILE}"
    warn "please ensure attribution before use."
  fi

  # --count 인자 있으면 fallback 모드 강제
  if [[ "$FALLBACK_MODE" == "true" ]]; then
    download_fallback
    return
  fi

  # Python3 존재 확인
  if command -v python3 >/dev/null 2>&1; then
    log "Python3 found: $(python3 --version)"
    download_by_category
  else
    warn "Python3가 설치되어 있지 않습니다."
    warn "카테고리별 annotation 파싱을 위해 Python3 3.6+ 설치를 권장합니다."
    warn "  macOS: brew install python3"
    warn "  Ubuntu: sudo apt-get install python3"
    warn ""
    download_fallback
    return
  fi

  local total_bytes
  total_bytes=$(du -sh "$SEED_REAL_DIR" 2>/dev/null | awk '{print $1}')
  log "=== 완료 ==="
  log "total size: ${total_bytes}"
  log ""
  log "다음 단계: ./scripts/apply-seed-images.sh"
  log "  → storage/raw/seed/{rawSn}/frame_N.jpg 구조로 매핑합니다."
}

main "$@"

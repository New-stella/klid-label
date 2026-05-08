#!/usr/bin/env bash
#
# scripts/apply-seed-images.sh
#
# DEV/LOCAL TEST 한정 — 다운로드된 이벤트 이미지를 DB 시드 RAW_SN 구조로 매핑.
# storage/raw/seed-real/{event-dir}/ 이미지 →
#   storage/raw/seed/{rawSn}/frame_1.jpg ~ frame_5.jpg
#
# 이벤트별 RAW_SN 매핑 (dev-seed.sql 기준):
#   EVT_FALL      → 9001,9007,9013,9019,9025,9031
#   EVT_VIOLENCE  → 9002,9008,9014,9020,9026,9032
#   EVT_ACCIDENT  → 9003,9009,9015,9021,9027,9033
#   EVT_ABNORMAL  → 9004,9010,9016,9022,9028
#   EVT_FLOOD     → 9005,9011,9017,9023,9029
#   EVT_FIRE      → 9006,9012,9018,9024,9030
#
# 소스 디렉토리 매핑:
#   EVT_FALL / EVT_VIOLENCE / EVT_ABNORMAL → fall-violence-abnormal/
#   EVT_ACCIDENT                            → traffic-accident/
#   EVT_FLOOD / EVT_FIRE                   → flood-fire/
#
# Usage:
#   ./scripts/apply-seed-images.sh           # 전체 적용
#   ./scripts/apply-seed-images.sh --dry-run  # 변경사항만 출력, 실제 적용 안 함
#
set -euo pipefail

# ─────────────────────────────────────────────────────────────
# Configuration
# ─────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SEED_REAL_DIR="${PROJECT_ROOT}/storage/raw/seed-real"
SEED_DIR="${PROJECT_ROOT}/storage/raw/seed"

DIR_FALL_VIOLENCE_ABNORMAL="${SEED_REAL_DIR}/fall-violence-abnormal"
DIR_TRAFFIC_ACCIDENT="${SEED_REAL_DIR}/traffic-accident"
DIR_FLOOD_FIRE="${SEED_REAL_DIR}/flood-fire"

FRAMES_PER_RAW=5

# ─────────────────────────────────────────────────────────────
# 이벤트별 RAW_SN 목록 (dev-seed.sql 기준)
# ─────────────────────────────────────────────────────────────
RAW_SNS_FALL=(9001 9007 9013 9019 9025 9031)
RAW_SNS_VIOLENCE=(9002 9008 9014 9020 9026 9032)
RAW_SNS_ACCIDENT=(9003 9009 9015 9021 9027 9033)
RAW_SNS_ABNORMAL=(9004 9010 9016 9022 9028)
RAW_SNS_FLOOD=(9005 9011 9017 9023 9029)
RAW_SNS_FIRE=(9006 9012 9018 9024 9030)

# ─────────────────────────────────────────────────────────────
# 인자 파싱
# ─────────────────────────────────────────────────────────────
DRY_RUN=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    *) ;;
  esac
done

# ─────────────────────────────────────────────────────────────
# Functions
# ─────────────────────────────────────────────────────────────
log()   { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }
fail()  { printf "[ERROR] %s\n" "$*" >&2; exit 1; }
warn()  { printf "[WARN]  %s\n" "$*" >&2; }

# 디렉토리에서 jpg 이미지 목록을 배열로 수집
# 결과를 global 변수 IMG_LIST에 저장
collect_images() {
  local dir="$1"
  IMG_LIST=()
  if [[ ! -d "$dir" ]]; then
    return
  fi
  while IFS= read -r -d '' f; do
    IMG_LIST+=("$f")
  done < <(find "$dir" -maxdepth 1 -name "*.jpg" -type f -print0 | sort -z)
}

# 단일 RAW_SN 폴더에 frame_1.jpg ~ frame_5.jpg 복사 (멱등성)
# 이미지 개수가 FRAMES_PER_RAW보다 적으면 모듈로 순환
apply_frames() {
  local raw_sn="$1"
  local source_dir="$2"
  shift 2
  local images=("$@")  # source image paths

  local img_count=${#images[@]}
  if [[ $img_count -eq 0 ]]; then
    warn "RAW_SN=${raw_sn}: 소스 이미지 없음 (${source_dir}) — 건너뜁니다"
    return
  fi

  local dest_dir="${SEED_DIR}/${raw_sn}"

  for frame_n in $(seq 1 "$FRAMES_PER_RAW"); do
    local idx=$(( (frame_n - 1) % img_count ))
    local src="${images[$idx]}"
    local dest="${dest_dir}/frame_${frame_n}.jpg"

    if [[ "$DRY_RUN" == "true" ]]; then
      log "[dry-run] would copy $(basename "$src") → seed/${raw_sn}/frame_${frame_n}.jpg"
    else
      mkdir -p "$dest_dir"
      cp "$src" "$dest"
      log "copied $(basename "$src") → seed/${raw_sn}/frame_${frame_n}.jpg"
    fi
  done
}

# 이벤트 그룹 처리
process_event_group() {
  local event_name="$1"
  local source_dir="$2"
  shift 2
  local raw_sns=("$@")

  log "--- ${event_name} (RAW_SNs: ${raw_sns[*]}) ---"

  collect_images "$source_dir"
  local images=("${IMG_LIST[@]+"${IMG_LIST[@]}"}")

  if [[ ${#images[@]} -eq 0 ]]; then
    warn "${event_name}: ${source_dir} 에 이미지가 없습니다 — 건너뜁니다"
    return
  fi

  log "${event_name}: ${#images[@]}장 소스 이미지 → ${#raw_sns[@]}개 RAW_SN에 배정"

  for raw_sn in "${raw_sns[@]}"; do
    apply_frames "$raw_sn" "$source_dir" "${images[@]}"
  done
}

# ─────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────
main() {
  if [[ "$DRY_RUN" == "true" ]]; then
    log "=== apply-seed-images.sh [DRY-RUN] ==="
  else
    log "=== apply-seed-images.sh ==="
  fi
  log "seed-real: ${SEED_REAL_DIR}"
  log "seed:      ${SEED_DIR}"

  # seed-real 이미지 존재 여부 확인
  local has_images=false
  for check_dir in "$DIR_FALL_VIOLENCE_ABNORMAL" "$DIR_TRAFFIC_ACCIDENT" "$DIR_FLOOD_FIRE"; do
    if [[ -d "$check_dir" ]] && ls "$check_dir"/*.jpg >/dev/null 2>&1; then
      has_images=true
      break
    fi
  done

  if [[ "$has_images" == "false" ]]; then
    warn "seed-real 디렉토리에 이미지가 없습니다."
    warn "먼저 download-dev-dataset.sh를 실행하세요:"
    warn "  ./scripts/download-dev-dataset.sh"
    exit 1
  fi

  # seed/ 디렉토리 존재 확인
  if [[ ! -d "$SEED_DIR" ]]; then
    if [[ "$DRY_RUN" == "true" ]]; then
      log "[dry-run] seed 디렉토리가 없음 — 실제 실행 시 생성됩니다: ${SEED_DIR}"
    else
      mkdir -p "$SEED_DIR"
      log "created seed dir: ${SEED_DIR}"
    fi
  fi

  local total_frames=0

  # EVT_FALL → fall-violence-abnormal
  process_event_group "EVT_FALL" "$DIR_FALL_VIOLENCE_ABNORMAL" "${RAW_SNS_FALL[@]}"
  total_frames=$(( total_frames + ${#RAW_SNS_FALL[@]} * FRAMES_PER_RAW ))

  # EVT_VIOLENCE → fall-violence-abnormal
  process_event_group "EVT_VIOLENCE" "$DIR_FALL_VIOLENCE_ABNORMAL" "${RAW_SNS_VIOLENCE[@]}"
  total_frames=$(( total_frames + ${#RAW_SNS_VIOLENCE[@]} * FRAMES_PER_RAW ))

  # EVT_ABNORMAL → fall-violence-abnormal
  process_event_group "EVT_ABNORMAL" "$DIR_FALL_VIOLENCE_ABNORMAL" "${RAW_SNS_ABNORMAL[@]}"
  total_frames=$(( total_frames + ${#RAW_SNS_ABNORMAL[@]} * FRAMES_PER_RAW ))

  # EVT_ACCIDENT → traffic-accident
  process_event_group "EVT_ACCIDENT" "$DIR_TRAFFIC_ACCIDENT" "${RAW_SNS_ACCIDENT[@]}"
  total_frames=$(( total_frames + ${#RAW_SNS_ACCIDENT[@]} * FRAMES_PER_RAW ))

  # EVT_FLOOD → flood-fire
  process_event_group "EVT_FLOOD" "$DIR_FLOOD_FIRE" "${RAW_SNS_FLOOD[@]}"
  total_frames=$(( total_frames + ${#RAW_SNS_FLOOD[@]} * FRAMES_PER_RAW ))

  # EVT_FIRE → flood-fire
  process_event_group "EVT_FIRE" "$DIR_FLOOD_FIRE" "${RAW_SNS_FIRE[@]}"
  total_frames=$(( total_frames + ${#RAW_SNS_FIRE[@]} * FRAMES_PER_RAW ))

  log ""
  if [[ "$DRY_RUN" == "true" ]]; then
    log "=== [DRY-RUN 완료] 총 ${total_frames}개 프레임이 복사될 예정 ==="
    log "    실제 적용: ./scripts/apply-seed-images.sh"
  else
    log "=== 완료: 총 ${total_frames}개 프레임 적용 ==="
    log "    라벨링 캔버스에서 실제 이미지 확인 후 YOLO/SAM2 동작을 검증하세요."
  fi
}

main "$@"

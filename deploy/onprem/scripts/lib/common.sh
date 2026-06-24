# shellcheck shell=bash
# ============================================================================
# common.sh — klid-label 온프렘 패키지 공통 함수 라이브러리
#
#   각 스크립트는 첫 줄에 `set -euo pipefail` 선언 후 이 파일을 source 한다.
#   이 파일 자체는 실행 파일이 아니다(shebang 없음) — source 전용.
#
#   제공: 로깅(log/info/warn/die), require_cmd, require_root,
#         repo_root / onprem_root, sha256 생성·검증, confirm.
# ============================================================================

# ----------------------------------------------------------------------------
# 색상 (TTY 일 때만)
# ----------------------------------------------------------------------------
if [[ -t 1 ]]; then
  _C_RED=$'\033[31m'; _C_YEL=$'\033[33m'; _C_GRN=$'\033[32m'; _C_BLU=$'\033[34m'; _C_RST=$'\033[0m'
else
  _C_RED=""; _C_YEL=""; _C_GRN=""; _C_BLU=""; _C_RST=""
fi

# ----------------------------------------------------------------------------
# 로깅
# ----------------------------------------------------------------------------
log()  { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
info() { printf '%s %s[INFO]%s %s\n' "$(date '+%H:%M:%S')" "${_C_BLU}" "${_C_RST}" "$*"; }
ok()   { printf '%s %s[ OK ]%s %s\n' "$(date '+%H:%M:%S')" "${_C_GRN}" "${_C_RST}" "$*"; }
warn() { printf '%s %s[WARN]%s %s\n' "$(date '+%H:%M:%S')" "${_C_YEL}" "${_C_RST}" "$*" >&2; }
die()  { printf '%s %s[FAIL]%s %s\n' "$(date '+%H:%M:%S')" "${_C_RED}" "${_C_RST}" "$*" >&2; exit 1; }

# ----------------------------------------------------------------------------
# 사전 조건 검사
# ----------------------------------------------------------------------------
# require_cmd <command> [<command> ...] — 모든 명령이 PATH 에 있어야 함
require_cmd() {
  local missing=0 c
  for c in "$@"; do
    if ! command -v "$c" >/dev/null 2>&1; then
      warn "필수 명령을 찾을 수 없습니다: ${c}"
      missing=1
    fi
  done
  [[ "${missing}" -eq 0 ]] || die "필수 명령이 누락되었습니다. 위 항목을 설치한 뒤 다시 실행하세요."
}

require_root() {
  if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
    die "이 스크립트는 root 권한이 필요합니다. sudo 로 다시 실행하세요."
  fi
}

# ----------------------------------------------------------------------------
# 경로 헬퍼
# ----------------------------------------------------------------------------
# 이 라이브러리 파일의 위치 → onprem 루트(deploy/onprem) 와 repo 루트를 도출.
_common_self="${BASH_SOURCE[0]}"
COMMON_DIR="$(cd "$(dirname "${_common_self}")" && pwd)"          # scripts/lib
SCRIPTS_DIR="$(cd "${COMMON_DIR}/.." && pwd)"                     # scripts
ONPREM_ROOT="$(cd "${SCRIPTS_DIR}/.." && pwd)"                    # deploy/onprem

onprem_root() { printf '%s\n' "${ONPREM_ROOT}"; }

# repo 루트(모노레포 루트) — 빌드머신 패키징 단계에서만 의미가 있다.
# deploy/onprem/scripts/lib 에서 3단계 위. .git 또는 settings.gradle 존재로 검증.
repo_root() {
  local r
  r="$(cd "${ONPREM_ROOT}/../.." && pwd)"
  printf '%s\n' "${r}"
}

# ----------------------------------------------------------------------------
# 디렉토리 보장
# ----------------------------------------------------------------------------
ensure_dir() {
  local d
  for d in "$@"; do
    [[ -d "$d" ]] || mkdir -p "$d"
  done
}

# ----------------------------------------------------------------------------
# sha256 — 플랫폼 무관 (linux: sha256sum, macOS: shasum -a 256)
# ----------------------------------------------------------------------------
_sha256_cmd() {
  if command -v sha256sum >/dev/null 2>&1; then
    echo "sha256sum"
  elif command -v shasum >/dev/null 2>&1; then
    echo "shasum -a 256"
  else
    die "sha256 도구를 찾을 수 없습니다 (sha256sum / shasum)."
  fi
}

# sha256_write <dir> — dir 내 모든 일반 파일의 체크섬을 SHA256SUMS 로 기록
sha256_write() {
  local dir="$1"
  [[ -d "$dir" ]] || die "sha256_write: 디렉토리 없음: ${dir}"
  # _sha256_cmd 가 "shasum -a 256" 처럼 공백을 포함할 수 있으므로 배열로 안전하게 단어분리.
  local _cmd_arr; read -ra _cmd_arr <<< "$(_sha256_cmd)"
  ( cd "$dir" && find . -type f ! -name 'SHA256SUMS' -print0 \
      | sort -z | xargs -0 "${_cmd_arr[@]}" > SHA256SUMS )
  ok "체크섬 기록: ${dir}/SHA256SUMS"
}

# sha256_verify <dir> — SHA256SUMS 와 대조 검증
sha256_verify() {
  local dir="$1"
  local sums="${dir}/SHA256SUMS"
  [[ -f "${sums}" ]] || { warn "체크섬 파일 없음(검증 생략): ${sums}"; return 0; }
  # 공백 포함 명령(shasum -a 256) 단어분리 안전 처리 — 배열 사용.
  local _cmd_arr; read -ra _cmd_arr <<< "$(_sha256_cmd)"
  ( cd "$dir" && "${_cmd_arr[@]}" -c SHA256SUMS >/dev/null ) \
    && ok "체크섬 검증 통과: ${dir}" \
    || die "체크섬 검증 실패: ${dir} — 전송 중 파일이 손상되었을 수 있습니다."
}

# ----------------------------------------------------------------------------
# 확인 프롬프트 (비대화/CI 는 ASSUME_YES=1 로 우회)
# ----------------------------------------------------------------------------
confirm() {
  local prompt="${1:-계속하시겠습니까?}"
  if [[ "${ASSUME_YES:-0}" == "1" ]]; then
    info "${prompt} (ASSUME_YES=1 → 자동 진행)"
    return 0
  fi
  local ans
  read -r -p "${prompt} [y/N] " ans
  [[ "${ans}" == "y" || "${ans}" == "Y" ]]
}

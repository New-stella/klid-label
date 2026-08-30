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
# 상위 시스템 로그인 URL 가드 (H-ISSUE-02, fail-closed)
# ----------------------------------------------------------------------------
# 저작도구는 자체 로그인 UI 가 없어, 토큰 없음/만료(401) 시 관제서버·포털 로그인 페이지로
# redirect 해야 한다(frontend/src/features/auth/redirectToUpstream.ts). 대상 URL 은 VITE_* 라
# Vite 가 <b>빌드 시점</b>에 정적 치환하므로, 빌드에 값이 없으면 dist 안에 빈 문자열이 박히고
# 대상 서버에서는 고칠 수 없다. 그 상태의 증상은 에러가 아니라 "아무 반응 없는 막다른 화면"이라
# 배포 후에야 드러난다 → 빌드를 진행시키지 않고 여기서 끊는다.
#
# ⚠ 이 가드는 <b>배포 산출물을 만드는 스크립트에서만</b> 호출한다. frontend 에서 직접 도는
#   로컬/CI `npm run build` 는 `.env.development` 의 빈 값이 정상이므로 대상이 아니다.
require_upstream_login_urls() {
  local key value missing=0
  for key in VITE_CONTROL_LOGIN_URL VITE_PORTAL_LOGIN_URL; do
    value="${!key:-}"
    if [[ -z "${value}" ]]; then
      warn "${key} 미설정 — 세션 만료 시 상위 로그인 페이지로 이동할 수 없습니다."
      missing=1
    elif [[ "${value}" != http://* && "${value}" != https://* ]]; then
      # 스킴이 없으면 브라우저가 상대경로로 해석해 저작도구 자기 자신으로 되돌아온다.
      warn "${key} 값에 스킴이 없습니다(현재: ${value}) — http:// 또는 https:// 로 시작해야 합니다."
      missing=1
    fi
  done
  [[ "${missing}" -eq 0 ]] || die "상위 시스템 로그인 URL 이 필요합니다. 예:
       VITE_CONTROL_LOGIN_URL=https://control.example.local/login
       VITE_PORTAL_LOGIN_URL=https://portal.example.local/login
     (deploy/onprem/docs/04-configuration.md 'frontend 빌드 타임 변수' 참고)"
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

# ----------------------------------------------------------------------------
# 단일 파일 체크섬 검증 (fail-closed)
# ----------------------------------------------------------------------------
# _hash_file <algo> <file> — 파일의 해시(소문자 hex)를 stdout 으로 출력.
#   algo: sha256 | sha512
_hash_file() {
  local algo="$1" file="$2"
  if command -v "${algo}sum" >/dev/null 2>&1; then
    "${algo}sum" "${file}" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    local bits="256"; [[ "${algo}" == "sha512" ]] && bits="512"
    shasum -a "${bits}" "${file}" | awk '{print tolower($1)}'
  else
    die "해시 도구를 찾을 수 없습니다 (${algo}sum / shasum)."
  fi
}

# verify_file_sha256 <file> <expected> [<label>]
#   - expected 비어있음 → 강한 warn 후 진행(미검증). 비대화/CI 에서도 안전.
#   - 일치 → ok, 불일치 → die(fail-closed).
verify_file_sha256() {
  local file="$1" expected="$2" label="${3:-$(basename "${file}")}"
  [[ -f "${file}" ]] || die "체크섬 검증 대상 파일 없음: ${file}"
  if [[ -z "${expected}" ]]; then
    warn "⚠ [무결성 미검증] ${label}: 기대 SHA256 이 비어있습니다(versions.sh 의 *_SHA256 미설정)."
    warn "  공급망 무결성을 위해 공식 체크섬을 versions.sh 에 채우는 것을 강력히 권장합니다."
    return 0
  fi
  local actual; actual="$(_hash_file sha256 "${file}")"
  expected="$(printf '%s' "${expected}" | tr '[:upper:]' '[:lower:]')"
  if [[ "${actual}" == "${expected}" ]]; then
    ok "체크섬 일치(SHA256): ${label}" >&2   # $() 캡처 컨텍스트 오염 방지(방어적)
  else
    die "체크섬 불일치(SHA256): ${label}
       기대: ${expected}
       실제: ${actual}
     → 다운로드가 변조/손상되었을 수 있습니다. 파일을 삭제하고 다시 받으세요."
  fi
}

# verify_file_sha512 <file> <expected> [<label>] — Caddy 처럼 공식 SHA512 만 있는 경우.
verify_file_sha512() {
  local file="$1" expected="$2" label="${3:-$(basename "${file}")}"
  [[ -f "${file}" ]] || die "체크섬 검증 대상 파일 없음: ${file}"
  if [[ -z "${expected}" ]]; then
    warn "⚠ [무결성 미검증] ${label}: 기대 SHA512 이 비어있습니다."
    return 0
  fi
  local actual; actual="$(_hash_file sha512 "${file}")"
  expected="$(printf '%s' "${expected}" | tr '[:upper:]' '[:lower:]')"
  if [[ "${actual}" == "${expected}" ]]; then
    ok "체크섬 일치(SHA512): ${label}" >&2   # $() 캡처 컨텍스트 오염 방지(방어적)
  else
    die "체크섬 불일치(SHA512): ${label}
       기대: ${expected}
       실제: ${actual}
     → 다운로드가 변조/손상되었을 수 있습니다. 파일을 삭제하고 다시 받으세요."
  fi
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

# ----------------------------------------------------------------------------
# 폐쇄망 RPM 설치 — 로컬 yum 저장소 방식 (2026-08-30 신설)
#
#   ★ 왜 RPM 파일을 dnf 에 직접 넘기지 않는가:
#     조달 단계에서 --resolve --alldeps 로 전이 의존성을 <전량> 받는다(타깃의 설치 상태를
#     빌드머신이 알 수 없으므로 완전성을 우선한다). 그 세트에는 glibc·coreutils·rpm 같은
#     기반 패키지도 섞이는데, 이를 `dnf install <파일들>` 로 넘기면 이미 설치된 버전과
#     충돌해 <설치가 통째로 실패>한다(실측: "cannot install both glibc-... and glibc-...").
#     번들 디렉토리를 <로컬 저장소>로 주면 dnf 가 스스로 의존성을 해소해 "실제로 필요한
#     것만" 설치한다 — 실측: 261개 반입 → 105개만 설치, glibc 무변경.
#
#   ★ module_hotfixes=1 이 필수다(el8 한정 함정, 2026-08-30 실측):
#     RHEL 8 의 httpd 는 <모듈러 패키지>(httpd-2.4.37-...module+el8...)인데 createrepo_c 는
#     모듈러 메타데이터(modules.yaml)를 만들지 않는다. 그대로 두면 dnf 가
#       "No available modular metadata for modular package 'httpd-...', it cannot be installed"
#     로 거부한다. module_hotfixes=1 은 그 패키지를 일반 패키지처럼 다루게 한다.
#     ⚠ el9 에는 모듈러가 거의 없어 이 함정이 없다 — el8 로 내려오면서 새로 생긴 것이다.
#
#   ★ GPG: 폐쇄망 타깃에는 EPEL/RPM Fusion/PGDG 공개키가 없다. syspkgs/gpg/ 로 함께 반입한
#     키를 rpm --import 한 뒤 gpgcheck=1 로 검증한다(기본값). 키가 없거나 서명되지 않은
#     RPM 을 써야 하면 KLID_RPM_GPGCHECK=0 으로 낮춘다(그 경우 무결성은 SHA256SUMS 에만 의존).
# ----------------------------------------------------------------------------

# klid_import_rpm_gpg_keys — 번들에 반입된 RPM GPG 공개키를 타깃 rpm DB 에 등록(멱등).
klid_import_rpm_gpg_keys() {
  local gpg_dir="${1:-$(onprem_root)/syspkgs/gpg}"
  command -v rpm >/dev/null 2>&1 || return 0
  shopt -s nullglob
  local keys=("${gpg_dir}"/RPM-GPG-KEY-*)
  shopt -u nullglob
  if [[ "${#keys[@]}" -eq 0 ]]; then
    warn "[rpm] 반입된 GPG 공개키가 없습니다: ${gpg_dir}"
    warn "      서명 검증이 필요한 RPM 설치가 거부될 수 있습니다(KLID_RPM_GPGCHECK=0 로 우회 가능)."
    return 1
  fi
  rpm --import "${keys[@]}" 2>/dev/null \
    && ok "[rpm] GPG 공개키 ${#keys[@]} 개 등록: ${gpg_dir}" \
    || warn "[rpm] GPG 공개키 등록 일부 실패: ${gpg_dir}"
  return 0
}

# klid_dnf_install_from_bundle <repo_id> <rpm_dir> <pkg...>
#   번들 디렉토리를 로컬 저장소로 등록하고 지정 패키지를 오프라인 설치한다.
#   repodata 가 없으면(구 번들) RPM 파일 직접 설치로 폴백한다.
klid_dnf_install_from_bundle() {
  local repo_id="$1" rpm_dir="$2"; shift 2
  local pkgs=("$@")

  [[ -d "${rpm_dir}" ]] || { warn "[rpm] 번들 디렉토리 없음: ${rpm_dir}"; return 1; }
  command -v dnf >/dev/null 2>&1 || { warn "[rpm] dnf 가 없어 로컬 저장소 설치를 쓸 수 없습니다."; return 1; }

  local gpgcheck="${KLID_RPM_GPGCHECK:-1}"

  if [[ ! -f "${rpm_dir}/repodata/repomd.xml" ]]; then
    # 구 번들(createrepo_c 이전) 호환 — 파일 직접 설치. 기반 패키지 충돌 위험이 있다.
    warn "[rpm] 로컬 저장소 메타데이터가 없습니다: ${rpm_dir}/repodata/"
    warn "      RPM 파일 직접 설치로 폴백합니다(기반 패키지 버전 충돌 시 실패할 수 있음)."
    warn "      빌드머신에서 50/55-collect 를 다시 실행해 repodata 를 포함시키는 것을 권장합니다."
    shopt -s nullglob; local files=("${rpm_dir}"/*.rpm); shopt -u nullglob
    [[ "${#files[@]}" -ge 1 ]] || { warn "[rpm] 설치할 RPM 이 없습니다: ${rpm_dir}"; return 1; }
    # ★ 서명 검증은 로컬 저장소 경로와 <같은 정책>(KLID_RPM_GPGCHECK, 기본 1)을 따른다.
    #   구 동작 폐기(2026-08-30): gpgcheck=0 고정. 폴백이라는 이유로 검증을 끄면, 운영자가
    #   gpgcheck 를 켜 뒀는데도 "구 번들이면 조용히 무검증"이 되어 정책이 경로에 따라 갈린다.
    dnf install -y --disablerepo='*' --setopt="gpgcheck=${gpgcheck}" "${files[@]}" && return 0
    if [[ "${gpgcheck}" == "1" ]]; then
      warn "[rpm] 파일 직접 설치가 실패했습니다 — 서명 검증 문제일 수 있습니다."
      warn "      번들 GPG 키 반입(syspkgs/gpg/)을 확인하세요. 임시 우회: KLID_RPM_GPGCHECK=0"
    fi
    rpm -Uvh --replacepkgs "${files[@]}" && return 0
    return 1
  fi

  local repo_file="/etc/yum.repos.d/klid-${repo_id}.repo"
  info "[rpm] 로컬 저장소 등록: ${repo_id} → ${rpm_dir} (gpgcheck=${gpgcheck})"
  cat > "${repo_file}" <<REPO
[klid-${repo_id}]
name=KLID offline bundle (${repo_id})
baseurl=file://${rpm_dir}
enabled=1
gpgcheck=${gpgcheck}
repo_gpgcheck=0
module_hotfixes=1
REPO

  local rc=0
  # --disablerepo='*' 로 외부 네트워크 미접근을 강제하고, 방금 만든 로컬 저장소만 켠다.
  dnf install -y --disablerepo='*' --enablerepo="klid-${repo_id}" "${pkgs[@]}" || rc=1

  if [[ "${rc}" -ne 0 && "${gpgcheck}" == "1" ]]; then
    warn "[rpm] gpgcheck=1 설치 실패 — 서명 검증 문제일 수 있습니다."
    warn "      번들 GPG 키가 반입됐는지 확인하세요(syspkgs/gpg/). 임시 우회: KLID_RPM_GPGCHECK=0"
  fi

  rm -f "${repo_file}"
  return "${rc}"
}

# ----------------------------------------------------------------------------
# 설치 역할(role) — 서버 2대 구성 (2026-08-30 신설)
#
#   ★ 대상 장비는 2대다:
#     · app : 프론트엔드(httpd 정적 서빙 + /api 프록시) + 백엔드(외부 WAS 에 api.war 반입)
#             ffmpeg·ffprobe 는 <이 서버의 전제조건>이며 관제지원시스템 팀이 설치한다.
#     · ai  : ai-server(번들 파이썬 + 오프라인 휠 + YOLOX/SAM2 모델). httpd 불필요.
#             GPU 가 있으나 이번 반입은 <CPU 전용>이다(torch CPU 휠).
#
#   ★ 매체는 하나다 — 물리적으로 두 패키지로 쪼개지 않는다. 같은 매체를 두 장비에 올리고
#     <설치할 때 역할만 고른다>. 쪼개면 매체 관리·체크섬·문서가 두 벌이 되고, 그 비용이
#     "쓰지 않는 파일이 디스크에 남는다"보다 크다.
#
#   ★ 하위호환: 역할을 지정하지 않으면 all(=종전대로 전체 설치)이다. 기존 단일 서버 설치
#     절차와 문서를 깨지 않기 위한 기본값이며, 바꾸지 말 것.
# ----------------------------------------------------------------------------

# KLID_ROLE 정규화 — all | app | ai. 잘못된 값은 die(조용히 all 로 흘리지 않는다).
klid_normalize_role() {
  local r="${1:-all}"
  case "${r}" in
    all|app|ai) printf '%s\n' "${r}" ;;
    *) die "알 수 없는 설치 역할: ${r} (허용: all | app | ai)" ;;
  esac
}

# klid_role_has <role> — 현재 KLID_ROLE 이 <role> 을 포함하면 0.
#   all 은 두 역할을 모두 포함한다.
klid_role_has() {
  local want="$1" cur="${KLID_ROLE:-all}"
  [[ "${cur}" == "all" || "${cur}" == "${want}" ]]
}

# ----------------------------------------------------------------------------
# ffmpeg / ffprobe — 대상 장비 전제조건 검증 (2026-08-30 신설, 서버 A 전용)
#
#   ★ 왜 검증이 필요한가: ffmpeg 는 <우리가 자동으로 설치하지 않는다>(관제 설치본을
#     덮어쓰지 않기 위해). 그래서 관제가 설치하지 않았을 때 그것을 잡아내는 장치가
#     이 검증뿐이다. 없으면 backend 의 프레임 추출·영상 메타 조회가 죽는데, 서버는
#     <정상 기동하고 헬스체크도 통과>한다 — 배치를 실제로 돌려야 드러난다.
#     그래서 warn 이 아니라 die 다.
#
#   ★ 경로는 하드코딩하지 않고 <백엔드 설정을 읽는다>. 검증 대상과 런타임이 실제로
#     쓰는 값이 갈리면 검증이 무의미하다(FFMPEG_BIN 기본값은 bare `ffmpeg` 라 PATH 해석).
# ----------------------------------------------------------------------------

# klid_config_value <file> <KEY> — KEY=value 형태에서 마지막 값을 뽑아 trim + 따옴표 제거.
#   주석(#)으로 시작하는 줄은 제외. 없으면 빈 문자열.
klid_config_value() {
  local file="$1" key="$2" v=""
  [[ -f "${file}" ]] || return 0
  v="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "${file}" 2>/dev/null | grep -v '^[[:space:]]*#' | tail -1 | cut -d= -f2- || true)"
  v="$(printf '%s' "${v}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")"
  printf '%s' "${v}"
}

# klid_resolve_bin <value> — 절대경로면 그대로, bare 이름이면 PATH 에서 해석.
#   해석 실패 시 빈 문자열(호출부가 die 를 판단한다).
klid_resolve_bin() {
  local v="$1"
  [[ -n "${v}" ]] || return 0
  if [[ "${v}" == /* ]]; then
    printf '%s' "${v}"
  else
    command -v "${v}" 2>/dev/null || true
  fi
}

# klid_verify_ffmpeg_runtime <ffmpeg 경로> <ffprobe 경로>
#   ① 실행 가능 여부 ② `-version` 이 0 을 반환하는지 ③ 아주 작은 입력으로 실동작
#   ★ ②를 통과해도 ③에서 걸리는 경우가 있다(코덱·필터가 빠진 최소 빌드). backend 는
#     프레임을 <jpg 로> 쓰므로 ③은 그 경로(mjpeg 인코더 + ffprobe 판독)를 그대로 태운다.
klid_verify_ffmpeg_runtime() {
  local ff="$1" fp="$2" rc=0 out=""

  [[ -x "${ff}" ]] || die "ffmpeg 실행 파일이 없거나 실행 권한이 없습니다: ${ff}"
  [[ -x "${fp}" ]] || die "ffprobe 실행 파일이 없거나 실행 권한이 없습니다: ${fp}"

  # ---- ② -version 실행 ----
  out="$("${ff}" -hide_banner -version 2>&1 | head -n1)" || rc=$?
  [[ "${rc}" -eq 0 ]] || die "ffmpeg 실행에 실패했습니다(rc=${rc}): ${ff}
       출력: ${out}"
  ok "[ffmpeg] ${ff} → ${out}"

  rc=0
  out="$("${fp}" -hide_banner -version 2>&1 | head -n1)" || rc=$?
  [[ "${rc}" -eq 0 ]] || die "ffprobe 실행에 실패했습니다(rc=${rc}): ${fp}
       출력: ${out}"
  ok "[ffmpeg] ${fp} → ${out}"

  # ---- ③ 실동작(1프레임 jpg 생성 → ffprobe 판독) ----
  local tmp; tmp="$(mktemp -d)"
  local jpg="${tmp}/probe.jpg" ferr="${tmp}/ffmpeg.err"
  rc=0
  "${ff}" -nostdin -hide_banner -loglevel error \
          -f lavfi -i color=c=black:s=32x32:d=0.1 \
          -frames:v 1 -f image2 -y "${jpg}" >"${ferr}" 2>&1 || rc=$?

  if [[ "${rc}" -ne 0 ]]; then
    # lavfi(가상 입력)가 없는 최소 빌드일 수 있다. 그건 실사용(파일 디코드)과 무관하므로
    # 여기서만 warn 으로 낮춘다. 그 외 실패는 실제 고장이므로 die.
    if grep -qiE 'lavfi|Unknown input format|No such filter|Unrecognized option' "${ferr}"; then
      warn "[ffmpeg] 실동작 확인을 건너뜁니다 — 이 빌드에 lavfi(가상 입력)가 없습니다."
      warn "         ${ff} -version 은 정상입니다. 실사용은 파일 디코드라 영향이 없을 수 있으나,"
      warn "         첫 배치 수행 시 프레임 추출을 반드시 1회 확인하세요."
      rm -rf "${tmp}"
      return 0
    fi
    local detail; detail="$(head -n5 "${ferr}" 2>/dev/null || true)"
    rm -rf "${tmp}"
    die "ffmpeg 실동작 확인 실패(rc=${rc}) — 설치는 됐으나 정상 동작하지 않습니다: ${ff}
       ${detail}"
  fi

  [[ -s "${jpg}" ]] || { rm -rf "${tmp}"; die "ffmpeg 가 프레임 이미지를 만들지 못했습니다: ${ff}
       backend 는 프레임을 jpg 로 씁니다(mjpeg 인코더 필요)."; }

  rc=0
  out="$("${fp}" -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "${jpg}" 2>&1)" || rc=$?
  if [[ "${rc}" -ne 0 || -z "${out}" ]]; then
    rm -rf "${tmp}"
    die "ffprobe 가 방금 만든 이미지를 읽지 못했습니다(rc=${rc}): ${fp}
       출력: ${out}
       backend 의 영상 길이·해상도 조회가 같은 경로를 씁니다."
  fi
  rm -rf "${tmp}"
  ok "[ffmpeg] 실동작 확인 통과 — 1프레임 jpg 생성 후 ffprobe 판독(${out})"
}

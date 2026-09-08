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
# [제거됨] require_upstream_login_urls — 상위 시스템 로그인 URL 빌드 가드 (H-ISSUE-02)
# ----------------------------------------------------------------------------
# 2026-08-30 제거. 사라진 것이 아니라 <자리를 옮겼다>.
#
# 그 가드는 <빌드>가 VITE_CONTROL_LOGIN_URL / VITE_PORTAL_LOGIN_URL 을 요구하게 했다. 값이
# 빌드 시점에 dist 안으로 정적 치환되던 시절에는 그게 유일한 방어 지점이었다. 그런데 폐쇄망
# 반입은 빌드머신에서 한 번 만들어 매체로 넘기는 모델이라, 빌드가 고객 환경의 실주소를 요구하면
# <배포 가능한 산출물 자체를 만들 수 없다> — 실제로 예시 주소가 구워진 dist 가 반입 대상으로
# 놓여 있었고, 배포 후에는 고칠 방법이 없었다.
#
# 지금은 그 값들이 대상 서버의 설정 정본(/etc/klid/frontend.env)에서 <런타임>에 읽힌다.
# 따라서 fail-closed 검사도 그 값이 실제로 필요해지는 시점으로 옮겼다:
#     deploy/onprem/scripts/install/render-frontend-config.sh   (미설정·스킴 누락이면 생성 거부)
#     deploy/onprem/scripts/install/14-install-frontend.sh      (그 실패로 설치 중단)
#
# ⚠ 빌드 스크립트에 이 함수를 다시 만들지 말 것 — 되살리는 순간 산출물이 다시 환경 종속이 된다.

# ----------------------------------------------------------------------------
# 경로 헬퍼
# ----------------------------------------------------------------------------
# 이 라이브러리 파일의 위치 → onprem 루트(deploy/onprem) 와 repo 루트를 도출.
_common_self="${BASH_SOURCE[0]}"
COMMON_DIR="$(cd "$(dirname "${_common_self}")" && pwd)"          # scripts/lib
SCRIPTS_DIR="$(cd "${COMMON_DIR}/.." && pwd)"                     # scripts
ONPREM_ROOT="$(cd "${SCRIPTS_DIR}/.." && pwd)"                    # deploy/onprem

onprem_root() { printf '%s\n' "${ONPREM_ROOT}"; }

# ----------------------------------------------------------------------------
# 설치 공통 변수 기본값 — ★ 단계 스크립트를 <단독으로> 돌릴 수 있게 하는 자리
# ----------------------------------------------------------------------------
# 종전에는 이 값들을 install.sh 만 export 했다. 그래서 install/1x-*.sh 를 직접 실행하면
# set -u 에 걸려 "KLID_PREFIX: unbound variable" 로 즉시 죽었다 — 즉 <단계별 수동 진행이
# 구조적으로 불가능>했다. 폐쇄망에서 한 단계가 막혔을 때 그 단계부터 이어서 돌릴 수 없다는
# 뜻이라, 일괄 스크립트가 유일한 경로였다.
#
# ★ 이미 설정된 값은 덮지 않는다(`:=`). install.sh 는 이 파일을 source 한 뒤 같은 값을
#   `${VAR:-기본}` 으로 다시 export 하므로 동작이 달라지지 않고, 환경변수 재정의도 그대로 이긴다.
# ⚠ 기본값은 install.sh 의 것과 <같아야 한다>. 달라지면 일괄 설치와 단계별 설치가 서로
#   다른 경로에 설치하게 된다.
# KLID_PREFIX 를 <사람이 명시했는지> 기억해 둔다 — 아래 역할별 기본값이 그것을 덮지 않는다.
_klid_prefix_was_explicit=0
[[ -n "${KLID_PREFIX:-}" ]] && _klid_prefix_was_explicit=1
# ★ 설치 루트는 <역할마다 다르다> (2026-09-08 확정).
#   현장은 이미 /GCLOUD 아래에 서 있다 — WAS 는 /GCLOUD/JBOSS/jboss-eap-8.1, 웹 문서 루트는
#   /GCLOUD/WebApp/label-studio 다(둘 다 우리가 만든 자리가 아니라 현장이 준 자리다).
#   그런데 ai-server 만 /opt/klid 아래 있어 <혼자 다른 곳>이었다. 그래서 AI 장비의 설치 루트를
#   /GCLOUD/klid-at 으로 맞춘다.
#
#   ⚠ 전역 기본값은 /opt/klid <그대로 둔다>. AI 장비 말고는 이 값 아래에 실제로 놓이는 것이
#     api.war 중간 보관소(app/) 뿐이고, 웹은 KLID_WEB_ROOT 로 현장 경로를 덮어 쓴다.
#     전역을 통째로 바꾸면 그 두 서버의 문서·기본값이 현장과 어긋난다.
: "${KLID_PREFIX:=/opt/klid}"
# AI 장비(role=ai) 전용 설치 루트 — runtime/python + ai/ 가 여기 들어간다.
#   그 둘은 11·13 단계가 만드는데 두 단계 <모두 role=ai 에서만> 돈다(install.sh _build_steps).
#   즉 AI 장비에서는 이 값이 곧 ai-server 설치 루트다.
: "${KLID_AI_PREFIX:=/GCLOUD/klid-at}"
: "${KLID_ETC:=/etc/klid}"
: "${KLID_DATA:=/var/lib/klid}"
: "${KLID_LOG:=/var/log/klid}"
: "${KLID_USER:=klid}"
: "${KLID_GROUP:=klid}"
: "${SYSTEMD_DIR:=/etc/systemd/system}"
: "${STORAGE_RAW_PATH:=/nas-storage}"
: "${STORAGE_DEIDENTIFIED_PATH:=/nas-storage}"
: "${USE_BUNDLED_POSTGRES:=1}"
: "${INSTALL_BACKEND_SYSTEMD_UNIT:=0}"
: "${KLID_ROLE:=all}"
export KLID_AI_PREFIX

# ----------------------------------------------------------------------------
# AI 장비 설치 루트 적용 — role=ai 단계 스크립트가 <자기 첫 줄에서> 부른다
# ----------------------------------------------------------------------------
#   왜 함수인가: KLID_ROLE 은 common.sh 를 읽는 시점에 아직 정해지지 않는다(install.sh 가
#   인자를 나중에 파싱한다). 그래서 "role 을 아는 쪽"이 부르게 한다. 11·13 단계는 정의상
#   role=ai 에서만 도는 스크립트이므로 <자기가 ai 임을 이미 안다> — 단독 실행도 그래서 안전하다.
#
#   ⚠ 사람이 KLID_PREFIX 를 명시했으면 덮지 않는다. 그 경우가 단일 서버(role=all)에서
#     ai 까지 한 장비에 얹는 형상이며, 그때는 웹·WAS 와 같은 루트를 써야 한다.
klid_use_ai_prefix() {
  [[ "${_klid_prefix_was_explicit:-0}" == "1" ]] && return 0
  KLID_PREFIX="${KLID_AI_PREFIX}"
  export KLID_PREFIX
}

# ----------------------------------------------------------------------------
# 설치 루트 불일치 가드 — <조용히 엉뚱한 곳에 설치하는> 사고를 막는다
# ----------------------------------------------------------------------------
#   회차 패치를 얹을 때, 쓰려는 루트가 없고 <다른 알려진 루트>에 설치본이 있으면 멈춘다.
#   막지 않으면 설치가 빈 새 경로에 산출물을 놓고 성공으로 끝나고, 돌고 있는 서비스는
#   여전히 옛 경로를 본다 — 패치가 반영되지 않았는데 아무도 모른다.
#
#   ⚠ 자동 호출하지 않는다. 이 판정은 <실제로 그 루트에 설치하려는 단계>만 의미가 있고,
#     빌드머신·조회 스크립트까지 걸리면 오탐이 된다.
klid_assert_prefix_sane() {
  [[ "${KLID_SKIP_PREFIX_GUARD:-0}" != "1" ]] || return 0
  [[ "${_klid_prefix_was_explicit:-0}" != "1" ]] || return 0
  [[ ! -d "${KLID_PREFIX}" ]] || return 0
  local other
  for other in /opt/klid "${KLID_AI_PREFIX}" /data/klid; do
    [[ "${other}" != "${KLID_PREFIX}" ]] || continue
    if [[ -d "${other}/ai" || -d "${other}/app" || -d "${other}/web" ]]; then
      printf '\n\033[31m[FAIL]\033[0m 설치 루트가 어긋납니다.\n' >&2
      printf '  이번에 쓸 루트는 %s 인데 그 경로가 없고, %s 에 설치본이 있습니다.\n' \
             "${KLID_PREFIX}" "${other}" >&2
      printf '  이대로 진행하면 <빈 새 경로>에 설치하고 성공으로 끝나며, 돌고 있는 서비스는\n' >&2
      printf '  여전히 옛 경로를 봅니다 — 반영되지 않았는데 드러나지 않습니다.\n\n' >&2
      printf '  둘 중 하나를 고르세요:\n' >&2
      printf '   1) 설치 루트를 옮긴다(권장):  sudo ./scripts/relocate-prefix.sh --from=%s --to=%s\n' \
             "${other}" "${KLID_PREFIX}" >&2
      printf '   2) 옛 경로를 그대로 쓴다:     sudo KLID_PREFIX=%s <이 명령>\n\n' "${other}" >&2
      exit 1
    fi
  done
}

# 단계 스크립트가 서브셸/자식 프로세스를 띄워도 값이 이어지도록 export 한다.
export KLID_PREFIX KLID_ETC KLID_DATA KLID_LOG KLID_USER KLID_GROUP SYSTEMD_DIR
export STORAGE_RAW_PATH STORAGE_DEIDENTIFIED_PATH USE_BUNDLED_POSTGRES
export INSTALL_BACKEND_SYSTEMD_UNIT KLID_ROLE

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

# klid_gpg_key_files <dir> — 디렉터리 안의 <PGP 공개키 파일> 경로를 개행 구분으로 출력.
#
#   ★★ 파일 <이름>으로 고르지 않는다 — 이름 규약이 배포처마다 다르다 (2026-08-30 실측 사고).
#     PGDG(PostgreSQL 공식 리포)의 키 파일명은 `PGDG-RPM-GPG-KEY-RHEL` 이라 종전의
#     `RPM-GPG-KEY-*` 글롭에 <걸리지 않는다>. 그 한 글자 차이로:
#       · 조달이 그 키를 매체에 담지 못했고
#       · 설치(이 함수)가 담겼더라도 import 하지 못했을 것이며
#       · 타깃에서 `Public key for postgresql16-....rpm is not installed / GPG check FAILED`
#         (Key ID 40bca2b408b40d20)로 번들 PG 설치가 <첫 단계에서> 죽었다.
#     set -e 라 그 뒤 12·14·15·16·17·19 단계가 통째로 실행되지 않았다 — WAR·httpd·dist 가
#     아무것도 깔리지 않는데 "설치 스크립트가 죽었다"는 신호만 남는다.
#   → 그래서 판정을 <내용>으로 바꾼다: PGP 공개키 블록이 들어 있으면 키로 본다.
#     새 리포가 어떤 이름을 쓰든 자동으로 걸린다.
klid_gpg_key_files() {
  local dir="${1:-}" f
  [[ -d "${dir}" ]] || return 0
  shopt -s nullglob
  for f in "${dir}"/*; do
    [[ -f "${f}" ]] || continue
    # 무결성 목록·안내문·커버리지 리포트는 키가 아니다(내용 검사로도 걸러지지만 명시한다).
    case "$(basename "${f}")" in SHA256SUMS|README*|KEY-COVERAGE*) continue ;; esac
    grep -qs -- '-----BEGIN PGP PUBLIC KEY BLOCK-----' "${f}" || continue
    printf '%s\n' "${f}"
  done
  shopt -u nullglob
}

# klid_import_rpm_gpg_keys — 번들에 반입된 RPM GPG 공개키를 타깃 rpm DB 에 등록(멱등).
klid_import_rpm_gpg_keys() {
  local gpg_dir="${1:-$(onprem_root)/syspkgs/gpg}"
  command -v rpm >/dev/null 2>&1 || return 0
  local keys=() _k
  while IFS= read -r _k; do
    [[ -n "${_k}" ]] && keys+=("${_k}")
  done < <(klid_gpg_key_files "${gpg_dir}")
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

# ----------------------------------------------------------------------------
# klid_rpm_gpg_snippet — [빌드머신] 수집 환경(el8 컨테이너 또는 네이티브 dnf)에서 실행할
#   <GPG 공개키 수집 + 서명 키 커버리지 검증> 코드 조각을 stdout 으로 출력한다.
#
#   50/55-collect-*.sh 가 `bash -c "$( _env; klid_rpm_gpg_snippet; _body )"` 형태로 본문 앞에
#   붙여 실행한다. 두 스크립트가 <같은 코드>를 쓰게 하려는 것이다 — 키 수집 규칙이 갈리면
#   한쪽만 고쳐지고 다른 쪽은 조용히 옛 규칙으로 남는다(이번 사고가 정확히 그 형태였다).
#
#   ★★ 왜 "수집 후 검증"이 필요한가 (2026-08-30 사고의 진짜 교훈):
#     종전 코드는 키를 <복사 시도>만 하고(`cp ... || true`) 그것이 맞는 키인지 보지 않았다.
#     그래서 PGDG 키가 한 건도 안 담겼는데도 수집이 "성공"으로 끝났고, 결함은 반출 직전
#     무네트워크 리허설에서야 드러났다. 조달이 <자기가 받은 RPM 의 서명 키>를 스스로 대조하지
#     않으면, 같은 사각이 새 리포를 추가할 때마다 되살아난다.
#
#   ⚠ 이 검증은 rpm·gpg 가 있는 환경에서만 가능하다(맥 빌드머신에는 없다). 그래서 수집이
#     도는 바로 그 자리(컨테이너/네이티브 dnf)에서 함께 돈다.
#   ⚠ 커버리지 실패는 <exit 90> 이다. 호출부가 "dnf 부재로 수집 못함"(graceful SKIP)과
#     구분해서 보고해야 하기 때문이다 — 둘을 같은 rc 로 뭉개면 "네트워크가 없었나 보다"로
#     오독된다.
# ----------------------------------------------------------------------------
klid_rpm_gpg_snippet() {
  cat <<'GPGSNIPPET'
# ==== (공유 조각) RPM GPG 공개키 수집·검증 — klid_rpm_gpg_snippet ====
# _klid_key_files <dir> — 내용으로 판정한 PGP 공개키 파일 목록(이름 규약에 의존하지 않는다).
_klid_key_files() {
  local dir="$1" f
  [ -d "${dir}" ] || return 0
  for f in "${dir}"/*; do
    [ -f "${f}" ] || continue
    case "${f##*/}" in SHA256SUMS|README*|KEY-COVERAGE*) continue ;; esac
    grep -qs -- '-----BEGIN PGP PUBLIC KEY BLOCK-----' "${f}" || continue
    printf '%s\n' "${f}"
  done
}

# _klid_collect_gpg_keys <src_dir> <out_dir> — 리포가 설치한 공개키를 매체로 복사.
_klid_collect_gpg_keys() {
  local src="$1" out="$2" f n=0
  mkdir -p "${out}"
  while IFS= read -r f; do
    [ -n "${f}" ] || continue
    cp -f "${f}" "${out}/${f##*/}" && n=$((n+1))
  done < <(_klid_key_files "${src}")
  echo "[collect] RPM GPG 공개키 ${n} 개 수집 → ${out}"
  _klid_key_files "${out}" | sed 's#^.*/#  key: #'
}

# _klid_provided_keyids <gpg_out> — 매체에 담긴 키가 제공하는 키 ID(주키+부키, 소문자 16hex).
#   ★ 부키(sub)까지 세는 이유: 리포에 따라 서명은 <서명 전용 부키>로 한다. 주키만 보면
#     정상 반입인데 "키 없음"으로 오판한다.
_klid_provided_keyids() {
  local f
  command -v gpg >/dev/null 2>&1 || return 0
  while IFS= read -r f; do
    [ -n "${f}" ] || continue
    gpg --show-keys --with-colons "${f}" 2>/dev/null | awk -F: '$1=="pub" || $1=="sub" { print $5 }'
  done < <(_klid_key_files "$1") | tr 'A-F' 'a-f' | sort -u
}

# _klid_rpm_sig_keyid <rpm> — 그 RPM 을 서명한 키 ID(소문자 16hex). 미서명이면 빈 문자열.
_klid_rpm_sig_keyid() {
  rpm -qp --nosignature --qf '%{SIGPGP:pgpsig} %{RSAHEADER:pgpsig}\n' "$1" 2>/dev/null \
    | grep -oiE '[0-9a-f]{16}' | head -n1 | tr 'A-F' 'a-f'
}

# _klid_verify_gpg_coverage <gpg_out> <label> <rpm_dir...>
#   수집한 RPM 전량의 서명 키가 매체의 공개키로 검증 가능한지 대조하고
#   <gpg_out>/KEY-COVERAGE-<label>.txt 에 요약을 남긴다. 미보유 키가 있으면 exit 90.
#   ★ label 로 파일을 나누는 이유: 50(syspkgs)과 55(postgresql)가 같은 gpg 디렉터리를 쓰므로,
#     한 이름을 공유하면 <나중에 도는 쪽이 앞의 리포트를 덮어> 무엇을 검사했는지 남지 않는다.
_klid_verify_gpg_coverage() {
  local out="$1" label="$2"; shift 2
  local report="${out}/KEY-COVERAGE-${label}.txt"
  local provided tmp d f id total=0 unsigned=0 missing=0
  provided="$(_klid_provided_keyids "${out}")"
  if [ -z "${provided}" ]; then
    echo "ERROR: 매체의 GPG 공개키에서 키 ID 를 추출하지 못했습니다(gpg 부재 또는 키 0건): ${out}" >&2
    exit 90
  fi
  tmp="$(mktemp)"
  for d in "$@"; do
    [ -d "${d}" ] || continue
    for f in "${d}"/*.rpm; do
      [ -e "${f}" ] || continue
      total=$((total+1))
      id="$(_klid_rpm_sig_keyid "${f}")"
      if [ -z "${id}" ]; then
        unsigned=$((unsigned+1))
        printf 'UNSIGNED\t%s\n' "${f##*/}" >> "${tmp}"
      else
        printf '%s\t%s\n' "${id}" "${f##*/}" >> "${tmp}"
      fi
    done
  done

  {
    echo "# 번들 RPM 서명 키 커버리지 (자동 생성 — 직접 편집 금지)"
    echo "# 생성: $(date '+%Y-%m-%d %H:%M:%S%z')"
    echo "# 검사 대상 RPM: ${total} 개 / 대상 디렉터리: $*"
    echo "#"
    echo "# 여기 MISSING 이 하나라도 있으면 타깃에서 gpgcheck=1 설치가 거부된다."
    echo "# (증상: 'Public key for xxx.rpm is not installed' → 'GPG check FAILED')"
    echo ""
    printf '%-9s %-16s %6s  %s\n' "상태" "키ID" "RPM수" "예시"
  } > "${report}"

  local keyid cnt example status
  while IFS= read -r keyid; do
    [ -n "${keyid}" ] || continue
    cnt="$(awk -F'\t' -v k="${keyid}" '$1==k' "${tmp}" | wc -l | tr -d ' ')"
    example="$(awk -F'\t' -v k="${keyid}" '$1==k {print $2; exit}' "${tmp}")"
    if [ "${keyid}" = "UNSIGNED" ]; then
      status="UNSIGNED"
    elif printf '%s\n' "${provided}" | grep -qx "${keyid}"; then
      status="OK"
    else
      status="MISSING"
      missing=$((missing + cnt))
    fi
    printf '%-9s %-16s %6s  %s\n' "${status}" "${keyid}" "${cnt}" "${example}" >> "${report}"
  done < <(awk -F'\t' '{print $1}' "${tmp}" | sort -u)
  rm -f "${tmp}"

  cat "${report}"
  if [ "${missing}" -gt 0 ]; then
    echo "ERROR: 서명 키를 매체가 갖고 있지 않은 RPM 이 ${missing} 개 있습니다 — 위 MISSING 행 참조." >&2
    exit 90
  fi
  if [ "${unsigned}" -gt 0 ]; then
    echo "WARN: 서명되지 않은 RPM ${unsigned} 개 — gpgcheck=1 에서 거부될 수 있습니다." >&2
  fi
  echo "[collect] GPG 키 커버리지 확인: RPM ${total} 개, 미보유 키 0 (리포트: ${report})"
}
# ==== (공유 조각 끝) ====
GPGSNIPPET
}

# klid_dnf_install_from_bundle <repo_id> <rpm_dir> <pkg...>
#   번들 디렉토리를 로컬 저장소로 등록하고 지정 패키지를 오프라인 설치한다.
#   repodata 가 없으면(구 번들) RPM 파일 직접 설치로 폴백한다.
# ---- 이미 설치된 시스템 패키지는 건드리지 않는다 (반입 약속 이행) ------------
#
#   ★ 왜 필요한가 — `dnf install <pkg>` 는 <이미 설치돼 있어도> 저장소에 더 신 버전이 있으면
#     그것으로 <업그레이드>한다. 우리 번들은 특정 배포판 판 기준으로 모여 있으므로, 그보다 낮은
#     판의 장비에서 그대로 돌리면 기반 라이브러리까지 올라간다. 그것이 반입 확인 요청서에서
#     "가장 피하려는 일"로 적어 상대에게 약속한 바다.
#
#   그래서 두 겹으로 막는다:
#     1) 이미 설치된 것은 <설치 목록에서 뺀다>. 전부 있으면 이 단계를 통째로 건너뛴다.
#     2) 남은 것을 설치하기 전에 <미리보기>를 돌려, 기존 패키지를 올리거나 내리려 하면
#        설치하지 않고 <중단>한다.
#
#   ⚠ 우회 토글 KLID_ALLOW_PKG_CHANGE=1 은 <의도적으로 기본이 아니다>. 켜면 위 약속이 깨진다.

# 인자로 받은 패키지 중 <설치돼 있지 않은 것>만 공백 구분으로 출력한다.
klid_rpm_missing() {
  local p out=""
  for p in "$@"; do
    rpm -q "${p}" >/dev/null 2>&1 || out+="${p} "
  done
  printf '%s' "${out% }"
}

# 설치 미리보기에서 기존 패키지 변경(업그레이드/다운그레이드)이 잡히면 0(=참) 을 반환한다.
#   dnf 는 --assumeno 로 트랜잭션 요약만 찍고 중단하므로(종료코드 1) 출력만 본다.
#
#   ★ 바뀌는 패키지 목록을 전역 KLID_PKG_CHANGES 에 담는다 (2026-08-31).
#     왜 필요한가 — 목록 없이 "중단했다"고만 말하면 운영자가 <무엇이> 바뀌는지 몰라 판단할 수
#     없고, 남는 선택지가 "우회 토글을 켠다" 뿐이 된다. 실측에서 걸린 것은 PostgreSQL 이
#     openssl-libs <1개>, httpd 가 audit-libs·libselinux·libsemanage <3개>였다. 그 정도라면
#     운영자가 보고 결정할 수 있는 크기다. 목록을 숨기면 그 결정을 못 하게 만든다.
#
#   ★★ 미리보기가 <돌지 못한 경우>를 "바뀌는 것 없음"과 구분한다 (2026-08-31, 실측 결함).
#     `dnf install --assumeno` 는 GPG 공개키가 없으면 트랜잭션 요약을 <아예 만들지 않고> 죽는다.
#     그때 변경 목록이 비므로, 목록이 비었다는 것만 보고 판정하면 <안전장치가 통과시킨다> —
#     보호 장치가 fail-open 이 되는 것이라 없느니만 못하다. 그래서 요약 표식(Transaction Summary
#     / Nothing to do)이 실제로 있었는지를 함께 본다. 없으면 <바뀔 수 있다>고 본다(fail-closed).
klid_dnf_would_change_existing() {
  local repo_id="$1"; shift
  local preview
  preview="$(dnf install --assumeno --disablerepo='*' --enablerepo="klid-${repo_id}" "$@" 2>&1 || true)"

  if ! printf '%s\n' "${preview}" \
       | grep -Eq '^(Transaction Summary|트랜잭션 요약|Nothing to do|Complete!)'; then
    KLID_PKG_CHANGES="(미상 — 설치 미리보기가 실행되지 못했습니다)"
    warn "[rpm] 설치 미리보기를 만들지 못했습니다 — 무엇이 바뀔지 알 수 없어 <바뀐다>고 봅니다."
    warn "      흔한 원인: 번들 GPG 공개키 미등록(syspkgs/gpg) · 저장소 경로 접근 불가."
    warn "      dnf 마지막 출력:"
    printf '%s\n' "${preview}" | grep -vE '^\s*$' | tail -3 | while IFS= read -r _l; do
      warn "        ${_l}"
    done
    return 0
  fi

  # 요약 블록의 'Upgrading:'/'Downgrading:' 바로 아래 들여쓴 줄이 대상 패키지다.
  #   ⚠ dnf 는 섹션 사이에 <빈 줄을 넣지 않는다> — 'Upgrading:' 다음 줄이 곧바로
  #     'Installing dependencies:' 다. 그래서 "빈 줄에서 끝난다"고 보면 뒤따르는 설치
  #     섹션까지 업그레이드로 집계돼 <1건이 48건으로> 부풀었다(실측). 헤더 줄을 만날 때마다
  #     플래그를 다시 판정해야 한다.
  KLID_PKG_CHANGES="$(printf '%s\n' "${preview}" \
    | awk '/^[^ \t].*:[ \t]*$/{f=/^(Upgrading|Downgrading|업그레이드|다운그레이드):/;hdr=$0;next} /^[ \t]*$/{f=0} f&&/^[ \t]/{sub(/:$/,"",hdr);print hdr": "$1" "$3}' \
    | sort -u)"
  [[ -n "${KLID_PKG_CHANGES}" ]]
}

klid_dnf_install_from_bundle() {
  local repo_id="$1" rpm_dir="$2"; shift 2
  local pkgs=("$@")

  # ---- 1겹: 이미 설치된 것은 제외한다 ----
  if command -v rpm >/dev/null 2>&1 && [[ "${#pkgs[@]}" -gt 0 ]]; then
    local _missing
    _missing="$(klid_rpm_missing "${pkgs[@]}")"
    if [[ -z "${_missing}" ]]; then
      ok "[rpm] 이미 전부 설치돼 있어 건너뜁니다(장비 무변경): ${pkgs[*]}"
      return 0
    fi
    if [[ "${_missing}" != "${pkgs[*]}" ]]; then
      info "[rpm] 이미 설치된 것은 제외합니다 — 설치 대상: ${_missing}"
    fi
    # shellcheck disable=SC2206
    pkgs=(${_missing})
  fi

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

  # ---- 2겹: 기존 패키지를 바꾸려 하면 설치하지 않고 중단한다 ----
  if [[ "${KLID_ALLOW_PKG_CHANGE:-0}" != "1" ]] \
     && klid_dnf_would_change_existing "${repo_id}" "${pkgs[@]}"; then
    rm -f "${repo_file}"
    warn "[rpm] 이 설치는 <이미 설치된 패키지를 변경>하려 합니다 — 중단합니다."
    warn "      설치하려던 것 : ${pkgs[*]}"
    warn "      바뀌는 패키지 :"
    while IFS= read -r _line; do [[ -n "${_line}" ]] && warn "        - ${_line}"; done \
      <<< "${KLID_PKG_CHANGES}"
    warn "      원인: 번들이 이 장비보다 <새 보안 패치(erratum)>를 물고 있어, 의존성을 맞추느라"
    warn "            기반 라이브러리까지 올리려는 상태입니다. 배포판 판이 같아도 일어납니다"
    warn "            — 반입 매체는 수집 시점의 최신 패치로 모이기 때문입니다."
    warn "      조치 ①(권장) 위 패키지가 이 장비의 전제조건으로 <이미 설치돼 있어야 하는 것>이면"
    warn "                   그것을 먼저 설치·갱신한 뒤 다시 실행하세요. 그러면 이 단계는 건너뜁니다."
    warn "      조치 ②       현재 판을 알려 주시면(rpm -q 결과) 그 판으로 다시 수집해 넣겠습니다."
    warn "                   (판 확인:  cat /etc/redhat-release  ·  rpm -q <위 패키지명>)"
    warn "      ⚠ 그대로 진행하려면 KLID_ALLOW_PKG_CHANGE=1 — 위 패키지가 실제로 바뀝니다."
    return 1
  fi

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
    all|app|was|web|ai) printf '%s\n' "${r}" ;;
    *) die "알 수 없는 설치 역할: ${r} (허용: all | app | was | web | ai)" ;;
  esac
}

# ----------------------------------------------------------------------------
# 역할 ↔ 역량(capability) — ★ 역할 판정의 단일 소유 지점 (2026-09-05)
#
#   왜 역량으로 가르나: 현장 장비가 웹·WAS·AI 로 갈리는데(2026-09-04 확정) 역할이
#   all|app|ai 셋뿐이라, 통합 진입점(site-install.sh)이 <단계 번호를 하드코딩>해
#   web/was 를 흉내 내고 있었다(was → --skip=10,14,20 · web → --only=14,20).
#   단계가 하나만 늘거나 줄어도 그 목록이 조용히 어긋난다 — 실제로 이 라운드에서
#   PostgreSQL 단계를 걷어내며 즉시 어긋났다. 그래서 번호가 아니라 <역량>으로 가른다.
#
#   역량 셋: was(백엔드 WAR) · web(프론트 정적자산) · ai(추론 서버)
#   역할 → 역량:  all={was,web,ai}  app={was,web}  was={was}  web={web}  ai={ai}
#
#   ⚠ 하위호환: --role=app 은 종전대로 백엔드+프론트를 한 대에 올리는 단일 서버 구성이다.
#     기존 절차가 그대로 동작한다.
#   ⚠ 「app」은 이제 <역할 이름>일 뿐 역량이 아니다. klid_role_has 에 app 을 묻지 말 것 —
#     묻고 싶은 것은 언제나 was 인지 web 인지다. 그 구분을 흐리면 웹 장비에 WAR 이,
#     WAS 장비에 httpd 가 올라간다.
# ----------------------------------------------------------------------------
klid_role_caps() {
  case "${KLID_ROLE:-all}" in
    all) printf 'was web ai\n' ;;
    app) printf 'was web\n' ;;
    was) printf 'was\n' ;;
    web) printf 'web\n' ;;
    ai)  printf 'ai\n' ;;
    *)   printf '\n' ;;
  esac
}

# klid_role_has <capability> — 현재 역할이 그 역량을 포함하면 0.
#   capability 는 was | web | ai 셋뿐이다.
klid_role_has() {
  local want="$1" cap
  case "${want}" in
    was|web|ai) ;;
    *) die "klid_role_has: 알 수 없는 역량 '${want}' (허용: was | web | ai). 역할 이름(app·all)을 묻지 마세요." ;;
  esac
  for cap in $(klid_role_caps); do
    [[ "${cap}" == "${want}" ]] && return 0
  done
  return 1
}

# ----------------------------------------------------------------------------
# 실행 계정 판정 — <이 장비에서 실제로 서비스를 돌리는 계정>을 쓴다 (2026-09-07 확정, 구속)
#
#   ★ klid 계정을 만들지 않는다. 현장 보안 정책상 계정 생성은 계정 담당의 일이고,
#     설치 도구가 만들면 정책 위반이다. 대신 이미 있는 계정을 쓴다:
#       was → jboss    (WAS 가 그 계정으로 돈다 — 설정·산출물 권한이 저절로 맞는다)
#       web → apache   (httpd 가 그 계정으로 돈다 — 정적자산을 읽어야 한다)
#     그 자리에서 <실제로 도는 프로세스의 소유자>를 먼저 보고, 못 찾으면 관례 이름을 쓴다.
#     파일에 적힌 값보다 도는 프로세스를 신뢰하는 이유는 JBOSS_HOME 탐지와 같다.
#
#   ⚠ ai 역할은 klid 를 그대로 쓴다 — 그 장비에는 이미 그 계정으로 유닛이 돌고 있다.
#     없으면 만들지 않고 멈춘다(설치가 사유를 말한다).
#
#   klid_default_run_user  → 계정명(stdout). 못 정하면 빈 문자열.
# ----------------------------------------------------------------------------
klid_default_run_user() {
  local u=""
  if klid_role_has was; then
    # ⚠ 대괄호로 끊지 않으면 <grep 자신의 명령줄>이 잡혀 엉뚱한 계정이 나온다(실측).
    u="$(ps -eo user=,args= 2>/dev/null | grep -m1 -- '-Djboss[.]home[.]dir=' | awk '{print $1}' || true)"
    [[ -n "${u}" && "${u}" != "root" ]] || u="jboss"
    id "${u}" >/dev/null 2>&1 && { printf '%s\n' "${u}"; return 0; }
    id jboss >/dev/null 2>&1 && { printf 'jboss\n'; return 0; }
  fi
  if klid_role_has web; then
    u="$(ps -eo user=,comm= 2>/dev/null | awk '$2=="httpd"||$2=="apache2"{print $1}' \
         | grep -v '^root$' | head -1 || true)"
    [[ -n "${u}" ]] || u="apache"
    id "${u}" >/dev/null 2>&1 && { printf '%s\n' "${u}"; return 0; }
  fi
  return 1
}

# ----------------------------------------------------------------------------
# DB 접속 정규화 — 현장 값을 libpq 가 이해하는 모양으로 바꾼다 (2026-09-07 신설)
#
#   ★ 왜 필요한가. 현장 application.properties 의 CONTROL_DB_HOST 는 <JDBC 다중 호스트
#     문자열>이다: "10.177.199.148:19999,10.177.199.149". 이 값을 그대로 PGHOST 로 넘기면
#     libpq 가 콜론 붙은 포트를 못 읽어 접속이 실패한다. 2026-09-07 현장에서 증분 적용이
#     이 자리에서 막혔고, 화면에는 "psql 이 없습니다" 뒤에 가려 원인이 안 보였다.
#
#   ★ 여러 대를 살려 둔다 — libpq 는 PGHOST/PGPORT 의 쉼표 목록을 페일오버로 쓴다.
#     DDL 은 쓰기 노드에 붙어야 하므로 target_session_attrs=read-write 를 함께 세운다.
#     (대기 노드에 붙으면 read-only 오류로 <시끄럽게> 실패한다 — 조용한 것보다 낫다.)
#
#   klid_pg_env  → PGHOST/PGPORT/PGTARGETSESSIONATTRS 를 export 한다.
# ----------------------------------------------------------------------------
klid_pg_env() {
  local raw="${CONTROL_DB_HOST:-127.0.0.1}" defport="${CONTROL_DB_PORT:-5432}"
  local hosts="" ports="" entry h p
  # ★ IFS 를 갈아끼우며 for 로 도는 방식은 쓰지 않는다 — 2026-09-07 에 그렇게 짰다가
  #   둘째 호스트가 사라지고 포트가 어긋났다. 줄 단위로 읽으면 그 함정이 없다.
  while IFS= read -r entry; do
    entry="${entry//[[:space:]]/}"
    [[ -n "${entry}" ]] || continue
    case "${entry}" in
      *:*) h="${entry%%:*}"; p="${entry##*:}" ;;
      *)   h="${entry}";     p="${defport}"   ;;
    esac
    [[ "${p}" =~ ^[0-9]+$ ]] || p="${defport}"
    hosts="${hosts:+${hosts},}${h}"
    ports="${ports:+${ports},}${p}"
  #   ⚠ printf 에 개행을 붙인다 — 없으면 read 가 <마지막 항목을 버린다>(EOF 미종결 행).
  done < <(printf '%s\n' "${raw}" | tr ',' '\n')
  [[ -n "${hosts}" ]] || { hosts="127.0.0.1"; ports="${defport}"; }
  export PGHOST="${PGHOST_OVERRIDE:-${hosts}}"
  export PGPORT="${PGPORT_OVERRIDE:-${ports}}"
  # 이미 지정돼 있으면 존중한다(수동 진단 중일 수 있다).
  export PGTARGETSESSIONATTRS="${PGTARGETSESSIONATTRS:-read-write}"
}

# ----------------------------------------------------------------------------
# 설정 파일에서 DB 접속값을 읽어 온다 (2026-09-07 신설)
#
#   ★ WAR 형상의 정본은 ${KLID_ETC}/application.properties 다. 종전에는 이 스크립트들이
#     환경변수만 읽어, 운영자가 다섯 개를 손으로 export 해야 했다. 손으로 옮기는 값이
#     늘수록 틀린다 — 실제로 DB 이름을 기본값(klid_system)으로 잘못 알고 진행할 뻔했다.
#   ★ source 하지 않는다 — 비밀번호에 !·#·공백이 들어 있어 셸이 다르게 해석한다.
#   ★ <이미 환경에 있는 값은 덮지 않는다> — 명시 지정이 언제나 이긴다.
# ----------------------------------------------------------------------------
klid_load_db_props() {
  local f="${1:-${KLID_ETC}/application.properties}" k v
  [[ -r "${f}" ]] || return 0
  for k in CONTROL_DB_HOST CONTROL_DB_PORT CONTROL_DB_NAME \
           CONTROL_DB_USERNAME CONTROL_DB_PASSWORD DB_SCHEMA; do
    [[ -n "${!k:-}" ]] && continue
    v="$(grep -E "^[[:space:]]*${k}=" "${f}" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
    v="${v%$'\r'}"
    [[ -n "${v}" ]] && export "${k}=${v}"
  done
  return 0
}

# ----------------------------------------------------------------------------
# JBOSS_HOME 탐지 — <한 곳에서만> 판정한다 (2026-09-07 이관)
#
#   ★ 왜 여기로 옮겼나. 같은 판정이 세 곳에 복제돼 있었고 <목록이 달랐다>:
#       install/17-deploy-jboss.sh : /GCLOUD/JBOSS/jboss-eap-* 를 안다  (현장 실측 경로)
#       deploy-update.sh           : 그 경로를 <모른다>                 ← 조용한 결함
#       klid-jboss-fix.sh          : 또 다른 목록
#     그래서 업데이트 배포가 현장에서 <현재 WAR 를 못 찾고>, 그 결과
#     ①기존 WAR 백업이 조용히 건너뛰어지고 ②--rollback 이 되돌릴 것을 갖지 못한다.
#     배포 자체는 17 에 위임하므로 <성공한다> — 되돌릴 수단만 사라진다. 이 조합이 위험하다.
#
#   우선순위: 인자 > 돌고 있는 프로세스 > was.env > 흔한 경로
#   ★ "돌고 있는 프로세스"를 파일보다 신뢰하는 이유 — 설치 디렉터리가 여러 벌 있을 때
#     실제로 쓰이는 것은 하나뿐이고, 파일에 적힌 값은 낡았을 수 있다.
#
#   klid_detect_jboss_home [<--jboss-home 으로 받은 값>]
# ----------------------------------------------------------------------------
klid_detect_jboss_home() {
  local arg="${1:-}" h=""
  if [[ -n "${arg}" ]]; then printf '%s\n' "${arg}"; return 0; fi

  # ⚠ 대괄호로 끊는다 — 안 그러면 grep 자신의 인자가 먼저 잡힌다.
  h="$(ps -eo args= 2>/dev/null | tr ' ' '\n' | grep -m1 -- '-Djboss[.]home[.]dir=' | cut -d= -f2- || true)"
  [[ -n "${h}" && -d "${h}" ]] && { printf '%s\n' "${h}"; return 0; }

  if [[ -f "${KLID_ETC}/was.env" ]]; then
    h="$(grep -E '^[[:space:]]*WAS_HOME=' "${KLID_ETC}/was.env" | tail -1 | cut -d= -f2- | tr -d '"'"'"' ' || true)"
    [[ -n "${h}" && -d "${h}" ]] && { printf '%s\n' "${h}"; return 0; }
  fi

  local c
  for c in /GCLOUD/JBOSS/jboss-eap-* /opt/jboss-eap-* /opt/rh/eap*/root/usr/share/wildfly /opt/wildfly*; do
    [[ -d "${c}/standalone" ]] && { printf '%s\n' "${c}"; return 0; }
  done
  return 1
}

# ----------------------------------------------------------------------------
# 반입용 소스 추출 — git 이 추적하는 것만 담는다 (2026-09-05 신설)
#
#   ★ 왜 tar 제외 목록이 아니라 git 인가
#     제외 목록을 손으로 유지하면 <반드시 빠뜨린다>. 실제로 그랬다 — 구 방식의 제외 목록에
#     backend/storage 가 없어서, 소스를 담는 순간 <영상·프레임 운영 데이터 636MB 가 매체로
#     함께 나갈> 상태였다. 테스트 로그와 .env 도 같은 이유로 빠져 있었다.
#     git 은 .gitignore 를 이미 정본으로 갖고 있으므로, 추적된 것만 담으면 그 부류가
#     <구조적으로> 빠진다. 목록을 늘려 대응하지 않는다.
#
#   ★ 기준은 HEAD 다 — 워킹트리가 아니다.
#     반입물은 <배포된 판>과 일치해야 하고, 그것이 재현 가능해야 한다. 미커밋 변경이 있으면
#     소스에는 들어가지 않으므로 아래에서 경고한다(산출물이 그 변경으로 빌드됐다면 어긋난다).
#
#   klid_export_source <하위 디렉터리> <받을 상위 경로>
#     예: klid_export_source backend "${PAY}/src"   → ${PAY}/src/backend/
# ----------------------------------------------------------------------------
klid_repo_root() {
  git -C "$(onprem_root)" rev-parse --show-toplevel 2>/dev/null
}

klid_export_source() {
  local name="$1" dest_parent="$2"
  local repo; repo="$(klid_repo_root)"
  [[ -n "${repo}" ]] || die "[src] git 저장소를 찾지 못했습니다 — 소스 추출은 빌드머신(저장소 안)에서만 됩니다."
  git -C "${repo}" rev-parse --verify -q "HEAD:${name}" >/dev/null     || die "[src] HEAD 에 '${name}' 가 없습니다 — 경로를 확인하세요."

  # 미커밋 변경 경고 — 산출물이 그 변경으로 빌드됐다면 소스와 어긋난다.
  if ! git -C "${repo}" diff --quiet HEAD -- "${name}" 2>/dev/null; then
    warn "[src] '${name}' 에 미커밋 변경이 있습니다 — 소스는 HEAD 기준이라 그 변경이 <빠집니다>."
    warn "      산출물을 그 변경으로 빌드했다면 매체 안 소스와 어긋납니다. 먼저 커밋하세요."
  fi

  local to="${dest_parent}/${name}"
  rm -rf "${to}"
  ensure_dir "${to}"
  git -C "${repo}" archive "HEAD:${name}" | ( cd "${to}" && tar -xf - )     || die "[src] 소스 추출 실패: ${name}"
  ok "[src] ${name} → ${to}  ($(du -sh "${to}" | cut -f1) · $(find "${to}" -type f | wc -l | tr -d ' ')개 파일)"
}

# klid_write_source_info <받을 상위 경로> — 어느 판의 소스인지 남긴다.
klid_write_source_info() {
  local dest_parent="$1"
  local repo; repo="$(klid_repo_root)"
  ensure_dir "${dest_parent}"
  {
    echo "# klid-label 반입 소스 기록"
    echo "#   git 이 추적하는 파일만 담는다(.gitignore 가 정본) — 운영 데이터·로그·비밀값은 구조적으로 빠진다."
    echo "#   기준은 HEAD 이며 워킹트리가 아니다. 재현: git archive HEAD:<디렉터리>"
    echo "exported_at=$(date '+%Y-%m-%d %H:%M:%S%z')"
    echo "git_commit=$(git -C "${repo}" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "git_describe=$(git -C "${repo}" describe --always --dirty 2>/dev/null || echo unknown)"
  } > "${dest_parent}/SOURCE-INFO.txt"
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

# ----------------------------------------------------------------------------
# 배포 향(flavor) — 관제 연동(control) / 포털 연동(portal) (2026-08-31 신설)
#
#   ★★ 화면 산출물(dist)은 <향마다 따로 만들어> 매체에 두 벌 싣는다. 라우트 채널 값
#     (VITE_BUILD_CHANNEL)은 <빌드 시점>에 굳어 반대 향의 화면 코드를 산출물에서 통째로
#     걷어내기 때문이다. 관제 산출물에는 /portal 라우트가 0건이고 포털 산출물에는 내부
#     화면(/dashboard·/admin/*·/manage/*)이 0건이다. 그래서 <어느 dist 를 까느냐>가
#     설치 시점 판정이 되고, 그 판정의 단일 진입점이 이 함수다.
#     ⚠ 잘못 깔면 <오류 없이 조용히> 어긋난다 — 빌드도 설치도 성공하고 httpd 도 뜬다.
#       그래서 호출부는 반드시 fail-closed 로 다룬다(없으면 다른 향을 깔지 말고 즉시 실패).
#
#   ★ 우선순위 — <설정 정본이 환경변수를 이긴다>. 그 이유는 이 값의 소비자가 둘이고
#     그 둘이 반드시 같은 답을 봐야 하기 때문이다:
#       ① 이 함수      → 어느 dist 를 배치할지
#       ② render-frontend-config.sh → 어느 상위 로그인 URL 을 필수로 요구할지
#     ②는 언제나 ${KLID_ETC}/frontend.env 를 읽고, 그 파일은 재설치 때 <덮이지 않는다>.
#     그러니 여기서 환경변수를 우선하면 "포털 장비에 관제 dist 를 깔아 놓고 포털 URL 을
#     요구하는" 어긋난 상태가 만들어진다. 정본이 이미 선언돼 있으면 그것을 따르고,
#     환경변수와 다르면 <조용히 넘어가지 않고> 경고한다.
#
#   ★ 판정 자체는 render-frontend-config.sh 와 어긋나지 않게 맞춘다 — 허용값은 control|portal
#     둘뿐이고 대소문자를 가리며, 그 밖의 값은 즉시 실패다(오타를 통과시키지 않는다).
#     ⚠ 단 <미선언>의 뜻은 축마다 다르며 그것이 의도다:
#        · 이 함수(배치할 dist)      → control (설치 템플릿의 출고 기본값과 같다)
#        · 생성기(필수 URL)          → 둘 다 요구 (모르는 형상을 느슨히 통과시키지 않는다)
#       두 기본값을 "일관성"을 이유로 통일하지 말 것 — 전자는 무엇을 복사할지라 반드시
#       하나를 골라야 하고, 후자는 무엇을 요구할지라 넓게 잡는 쪽이 안전하다.
# ----------------------------------------------------------------------------

# klid_deploy_flavor — 이 장비의 배포 향을 stdout 으로 (control | portal).
#   읽는 순서: ${KLID_ETC}/frontend.env 선언 → 환경변수 KLID_DEPLOY_FLAVOR → control
#   알 수 없는 값이면 die.
klid_deploy_flavor() {
  local cfg="${FE_CONFIG_FILE:-${KLID_ETC:-/etc/klid}/frontend.env}"
  local declared env_v v
  declared="$(klid_config_value "${cfg}" KLID_DEPLOY_FLAVOR)"
  env_v="${KLID_DEPLOY_FLAVOR:-}"

  if [[ -n "${declared}" ]]; then
    v="${declared}"
    if [[ -n "${env_v}" && "${env_v}" != "${declared}" ]]; then
      warn "배포 향이 엇갈립니다 — 설정 정본을 따릅니다: ${cfg} 의 '${declared}' (환경변수는 '${env_v}')"
      warn "  재설치는 이 파일을 덮지 않습니다. 향을 바꾸려면 파일의 KLID_DEPLOY_FLAVOR 를 직접 고치세요."
    fi
  else
    v="${env_v}"
  fi
  [[ -n "${v}" ]] || v="control"

  # 허용 값은 여기 한 곳에만 적는다 — 목록 상수를 따로 두면 그것이 두 번째 진실원이 되어
  # 한쪽만 갱신된다. 값 자체는 frontend/src/lib/buildChannel.ts 의 BUILD_CHANNELS 와 같아야
  # 하며(셸이라 import 할 수 없다), 바꿀 때는 양쪽을 함께 본다.
  case "${v}" in
    control|portal) printf '%s' "${v}" ;;
    *)
      die "KLID_DEPLOY_FLAVOR 값을 알 수 없습니다: '${v}' — control 또는 portal 만 씁니다.
     (배포 향을 안 정했으면 ${cfg} 의 그 줄을 비워 두세요. 그러면 화면 산출물은 control 을
      배치하고, 설정 생성기는 두 로그인 주소를 모두 요구합니다.)"
      ;;
  esac
}

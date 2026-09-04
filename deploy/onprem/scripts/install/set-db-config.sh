#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# set-db-config.sh — [대상 서버 A] DB 접속 정보를 application.properties 에 안전하게 기록
#
#   ★ 왜 스크립트인가 — 손으로 적다가 조용히 틀리는 자리다.
#     설정 파일의 오타는 <오류를 내지 않는다>. 앱이 기동에 실패하거나(운이 좋으면) 엉뚱한
#     DB 를 물고 뜬다. 그래서 값을 넣기 <전에> 실제로 붙어 보고, 넣은 <뒤에> 다시 확인한다.
#
#   ★ 비밀번호를 명령줄로 넘기지 않는 것을 기본으로 한다.
#     명령줄 인자는 `ps -ef` 에 보이고 셸 히스토리에도 남는다(CWE-214 / CWE-532).
#     아무 인자도 주지 않으면 <화면에 찍히지 않게> 입력받는다.
#
#   사용법
#     sudo ./scripts/install/set-db-config.sh                       # 대화식(권장)
#     sudo ./scripts/install/set-db-config.sh --host=10.0.0.1 --port=5432 \
#            --name=klid_system --user=klid_user                    # 암호만 물어봄
#     옵션: --hosts=<h1:p1,h2:p2>  이중화 주소를 직접 지정(아래 「이중화」 참조)
#           --schema=<이름>        스키마 이름(기본 klid_at)
#
#   ★★ 데이터베이스 이름과 스키마 이름은 <다른 축>이다. 헷갈리면 앱이 빈 스키마를 본다.
#     · CONTROL_DB_NAME  = 데이터베이스   (예: klid_system · klid-at · klid)
#     · DB_SCHEMA        = 스키마          (우리 테이블이 사는 곳 — 기본 klid_at)
#     db/schema.sql 이 CREATE SCHEMA klid_at 을 포함하므로, 어느 데이터베이스에 로드하든
#     테이블은 klid_at 스키마 안에 만들어진다. 데이터베이스 이름은 현장 값을 그대로 쓰면 되고
#     스키마는 klid_at 이어야 한다 — 바꾸려면 --schema 로 <앱과 로드 대상을 함께> 맞춘다.
#           --password-stdin       암호를 표준입력으로 받는다(자동화용)
#           --no-test              접속 시험을 건너뛴다(psql 이 없을 때)
#           --show                 현재 설정만 보여 준다(비밀번호는 가린다)
#
#   ⚠ 이 스크립트는 <값을 파일에 쓴다>. 파일은 ${KLID_ETC}/application.properties 이고
#     WAS 실행 계정만 읽을 수 있어야 한다(0640). 소유·권한도 함께 맞춘다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
require_root

KLID_ETC="${KLID_ETC:-/etc/klid}"
PROPS="${KLID_ETC}/application.properties"

DB_HOST=""; DB_PORT=""; DB_NAME=""; DB_USER=""; DB_HOSTS=""; DB_SCHEMA_ARG=""
PASS_STDIN=0; DO_TEST=1; SHOW_ONLY=0

for arg in "$@"; do
  case "${arg}" in
    --host=*)        DB_HOST="${arg#*=}" ;;
    --port=*)        DB_PORT="${arg#*=}" ;;
    --name=*)        DB_NAME="${arg#*=}" ;;
    --user=*)        DB_USER="${arg#*=}" ;;
    --hosts=*)       DB_HOSTS="${arg#*=}" ;;
    --schema=*)      DB_SCHEMA_ARG="${arg#*=}" ;;
    --password-stdin) PASS_STDIN=1 ;;
    --no-test)       DO_TEST=0 ;;
    --show)          SHOW_ONLY=1 ;;
    --help|-h)       sed -n '3,28p' "${BASH_SOURCE[0]}"; exit 0 ;;
    # ⚠ --password=... 를 일부러 받지 않는다. ps 와 히스토리에 남기 때문이다.
    --password=*)    die "--password= 는 지원하지 않습니다 — ps -ef 와 셸 히스토리에 남습니다.
     인자 없이 실행해 화면에 찍히지 않게 입력하거나, --password-stdin 을 쓰세요." ;;
    *) die "알 수 없는 옵션: ${arg} (--help 로 사용법)" ;;
  esac
done

[[ -f "${PROPS}" ]] || die "설정 파일이 없습니다: ${PROPS}
     먼저 12단계를 실행하세요:  sudo ./scripts/install-step.sh 12-install-backend.sh"

prop_get() { grep -E "^[[:space:]]*$1=" "${PROPS}" | tail -1 | cut -d= -f2- || true; }

if [[ "${SHOW_ONLY}" -eq 1 ]]; then
  info "[db] 현재 설정 (${PROPS})"
  echo "  CONTROL_DB_HOST     = $(prop_get CONTROL_DB_HOST)"
  echo "  CONTROL_DB_PORT     = $(prop_get CONTROL_DB_PORT)"
  echo "  CONTROL_DB_NAME     = $(prop_get CONTROL_DB_NAME)"
  echo "  CONTROL_DB_USERNAME = $(prop_get CONTROL_DB_USERNAME)"
  _p="$(prop_get CONTROL_DB_PASSWORD)"
  echo "  CONTROL_DB_PASSWORD = $( [[ -n "${_p}" ]] && echo '(설정됨 — 값은 표시하지 않습니다)' || echo '★비어 있음' )"
  echo
  echo "  조립되는 JDBC URL   = jdbc:postgresql://$(prop_get CONTROL_DB_HOST):$(prop_get CONTROL_DB_PORT)/$(prop_get CONTROL_DB_NAME)"
  exit 0
fi

# ---- 값 받기 (기존 값을 기본값으로 제시한다) ----
ask() {  # ask <변수명> <라벨> <기본값>
  local __v="$1" __label="$2" __def="$3" __in=""
  [[ -n "${!__v}" ]] && return 0          # 인자로 이미 받았으면 묻지 않는다
  read -r -p "  ${__label}$( [[ -n "${__def}" ]] && echo " [${__def}]" ): " __in </dev/tty || true
  printf -v "${__v}" '%s' "${__in:-${__def}}"
}

echo
info "[db] DB 접속 정보 입력 (엔터 = 대괄호 안 기본값)"
ask DB_HOST "DB 호스트" "$(prop_get CONTROL_DB_HOST)"
ask DB_PORT "DB 포트"   "$(prop_get CONTROL_DB_PORT)"
ask DB_NAME "DB 이름"   "$(prop_get CONTROL_DB_NAME)"
ask DB_USER "DB 사용자" "$(prop_get CONTROL_DB_USERNAME)"

[[ -n "${DB_HOST}" && -n "${DB_PORT}" && -n "${DB_NAME}" && -n "${DB_USER}" ]] \
  || die "[db] 호스트·포트·이름·사용자는 모두 필요합니다."
[[ "${DB_PORT}" =~ ^[0-9]+$ ]] || die "[db] 포트는 숫자여야 합니다: ${DB_PORT}"

# ---- 이중화 주소 ----
#   pgjdbc 는 URL 에 호스트를 여러 개 받는다: jdbc:postgresql://h1:p1,h2:p2/db
#   이 템플릿은 HOST 와 PORT 를 따로 조립하므로, 둘 이상일 때는
#   HOST 에 "h1:p1,h2" 를, PORT 에 "p2" 를 넣어 최종 URL 이 맞아떨어지게 한다.
#   ⚠ 보기에 어색하지만 <조립 결과가 정답>이다. 아래에서 실제 URL 을 찍어 확인시킨다.
if [[ -n "${DB_HOSTS}" ]]; then
  # 예: 10.177.199.148:19999,10.177.199.149:19999
  _last="${DB_HOSTS##*:}"
  _head="${DB_HOSTS%:*}"
  [[ "${_last}" =~ ^[0-9]+$ ]] || die "[db] --hosts 는 host:port 쌍을 쉼표로 이어 주세요: ${DB_HOSTS}"
  DB_HOST="${_head}"; DB_PORT="${_last}"
  info "[db] 이중화 주소로 조립합니다: ${DB_HOSTS}"
fi

# 스키마 — 인자 > 기존 설정 > 기본값 klid_at
DB_SCHEMA_VAL="${DB_SCHEMA_ARG:-$(prop_get DB_SCHEMA)}"
DB_SCHEMA_VAL="${DB_SCHEMA_VAL:-klid_at}"

JDBC_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"
info "[db] 조립되는 JDBC URL = ${JDBC_URL}"
info "[db] 스키마            = ${DB_SCHEMA_VAL}   (데이터베이스와 다른 축입니다)"

# ---- 비밀번호 ----
DB_PASS=""
if [[ "${PASS_STDIN}" -eq 1 ]]; then
  IFS= read -r DB_PASS || true
else
  read -r -s -p "  DB 비밀번호(화면에 표시되지 않습니다): " DB_PASS </dev/tty || true; echo
  read -r -s -p "  한 번 더 입력: " _again </dev/tty || true; echo
  [[ "${DB_PASS}" == "${_again}" ]] || die "[db] 두 입력이 다릅니다."
fi
[[ -n "${DB_PASS}" ]] || die "[db] 비밀번호가 비어 있습니다."

# ---- 넣기 <전에> 실제로 붙어 본다 ----
#   ★ 이 확인이 이 스크립트의 값어치다. 파일에 쓴 뒤 WAS 를 띄워서 아는 것보다
#     지금 아는 편이 훨씬 싸다.
if [[ "${DO_TEST}" -eq 1 ]]; then
  if command -v psql >/dev/null 2>&1; then
    info "[db] 접속 시험..."
    _first_host="${DB_HOST%%,*}"; _first_host="${_first_host%%:*}"
    _first_port="${DB_PORT}"
    [[ "${DB_HOST}" == *:* ]] && _first_port="${DB_HOST#*:}" && _first_port="${_first_port%%,*}"
    if PGPASSWORD="${DB_PASS}" psql -h "${_first_host}" -p "${_first_port}" \
         -U "${DB_USER}" -d "${DB_NAME}" -tAc 'select 1' >/dev/null 2>&1; then
      ok "[db] 접속 성공 (${_first_host}:${_first_port}/${DB_NAME})"
      _cnt="$(PGPASSWORD="${DB_PASS}" psql -h "${_first_host}" -p "${_first_port}" -U "${DB_USER}" -d "${DB_NAME}" \
              -tAc "select count(*) from information_schema.tables where table_schema='${DB_SCHEMA_VAL}'" 2>/dev/null || echo '?')"
      # ★ 기대값을 하드코딩하지 않는다 — 마이그레이션이 늘면 숫자가 바뀌고, 하드코딩한 숫자는
      #   반드시 낡는다(실제로 "73" 이 낡은 채 여러 문서에 복제돼 있었다). 매체의 schema.sql 에서
      #   그때그때 센다. information_schema.tables 는 <뷰를 포함>하므로 테이블+뷰로 비교한다.
      _sch="$(onprem_root)/db/schema.sql"
      _want=""
      if [[ -f "${_sch}" ]]; then
        _t="$(grep -c '^CREATE TABLE klid_at\.' "${_sch}" || echo 0)"
        _v="$(grep -c '^CREATE VIEW klid_at\.'  "${_sch}" || echo 0)"
        _want="$(( _t + _v ))"
      fi
      if [[ "${_cnt}" == "0" || "${_cnt}" == "?" ]]; then
        warn "[db] 스키마 '${DB_SCHEMA_VAL}' 의 테이블·뷰가 ${_cnt} 개입니다."
        warn "     ★ 스키마가 비어도 앱은 <기동에 성공>합니다 — 유일한 판정은 개수를 세는 것입니다."
        [[ -n "${_want}" ]] && warn "     이 매체의 기대값: ${_want} (테이블 ${_t} + 뷰 ${_v} — schema.sql 실측)"
        warn "     적재:  sudo ./scripts/install-step.sh 16-load-schema.sh"
      elif [[ -n "${_want}" && "${_cnt}" != "${_want}" ]]; then
        warn "[db] 스키마 개수 불일치 — 현재 ${_cnt} / 이 매체 기대 ${_want} (테이블 ${_t} + 뷰 ${_v})."
        warn "     현장 DB 가 이전 반입 형상일 수 있습니다. db/README.md 의 증분 적용 규칙을 보세요."
      else
        ok "[db] 스키마 '${DB_SCHEMA_VAL}' 테이블·뷰 ${_cnt} 개 — 이 매체 기대값과 일치"
      fi
    else
      warn "[db] 접속에 실패했습니다 — 값이 틀렸거나 방화벽·pg_hba 가 막고 있습니다."
      warn "     그래도 파일에 기록할까요? (틀린 값을 넣으면 앱이 기동에 실패합니다)"
      confirm "  기록하고 계속" || die "[db] 중단합니다."
    fi
  else
    warn "[db] psql 이 없어 접속 시험을 건너뜁니다(값 검증 없이 기록합니다)."
  fi
fi

# ---- 기록 ----
_bak="${PROPS}.bak.$(date '+%Y%m%d%H%M%S')"
cp -p "${PROPS}" "${_bak}"
info "[db] 원본 보존: ${_bak}"

set_prop() {  # set_prop <키> <값>  — 있으면 교체, 없으면 추가
  local k="$1" v="$2" esc
  esc="$(printf '%s' "${v}" | sed 's/[&|\\]/\\&/g')"
  if grep -qE "^[[:space:]]*${k}=" "${PROPS}"; then
    sed -i "s|^[[:space:]]*${k}=.*|${k}=${esc}|" "${PROPS}"
  else
    printf '%s=%s\n' "${k}" "${v}" >> "${PROPS}"
  fi
}
set_prop CONTROL_DB_HOST     "${DB_HOST}"
set_prop CONTROL_DB_PORT     "${DB_PORT}"
set_prop CONTROL_DB_NAME     "${DB_NAME}"
set_prop CONTROL_DB_USERNAME "${DB_USER}"
set_prop CONTROL_DB_PASSWORD "${DB_PASS}"
set_prop DB_SCHEMA           "${DB_SCHEMA_VAL}"

# ---- 권한 — WAS 실행 계정만 읽게 ----
WAS_USER="$(ps -eo user=,args= 2>/dev/null | grep 'jboss-modules.jar' | awk '$1!="root"{print $1; exit}' || true)"
[[ -n "${WAS_USER}" ]] || WAS_USER="jboss"
WAS_GROUP="$(id -gn "${WAS_USER}" 2>/dev/null || echo "${WAS_USER}")"
chown "root:${WAS_GROUP}" "${PROPS}" 2>/dev/null || warn "[db] 소유 그룹 변경 실패(${WAS_GROUP})"
chmod 0640 "${PROPS}"
chmod 0600 "${_bak}"      # 백업에도 비밀번호가 들어 있다

if sudo -u "${WAS_USER}" test -r "${PROPS}"; then
  ok "[db] ${WAS_USER} 가 ${PROPS} 를 읽을 수 있습니다"
else
  die "[db] ${WAS_USER} 가 ${PROPS} 를 읽지 못합니다 — 상위 디렉터리 권한과 SELinux 문맥을 확인하세요."
fi

echo
ok "[db] 기록 완료"
info "  URL  : ${JDBC_URL}"
info "  사용자: ${DB_USER}   (비밀번호는 표시하지 않습니다)"
echo
info "다음: WAS 재기동 후 확인"
info "  sudo systemctl restart <WAS 유닛명>"
info "  curl -i http://127.0.0.1:8080/api/actuator/health/liveness"
echo
warn "⚠ 백업 파일에도 비밀번호가 들어 있습니다: ${KLID_ETC}/application.properties.bak.*"
warn "  운영 이관이 끝나면 오래된 백업은 지우세요."

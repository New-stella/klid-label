#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# site-install.sh — [대상 서버] 한 줄로 끝내는 통합 설치 (서버마다 이것만 실행)
#
#   ★ 왜 만들었나 (2026-09-04)
#     서버 1대를 손으로 설치하는 동안 <조용히 틀리는> 것이 네 번 나왔다:
#       ① standalone.conf JAVA_OPTS 블록 오타 5개 — 오류가 안 나고 값만 무효가 됨
#       ② /etc/klid 디렉터리에 실행 계정 진입 권한이 없어 설정 파일을 못 읽음
#          (파일 소유자는 맞았다 — 디렉터리가 root:root 750 이었다)
#       ③ WAR 이 구본인데 이름만 바꿔 재배포 — 로깅 충돌 그대로
#       ④ NAS 마운트가 /nas-storage 가 아니라 /nas-storage1 이었는데 설정은 기본값
#     서버가 여러 대면 이 네 가지를 대수만큼 다시 겪는다. 그래서 <손으로 적는 자리를 없앤다>.
#
#   사용법 — 서버마다 이 한 줄
#
#     [WAS 서버]
#       sudo ./scripts/site-install.sh --role=app \
#            --storage=/nas-storage1/klid \
#            --db-hosts=10.177.199.148:19999,10.177.199.149:19999 \
#            --db-name=klid_system --db-user=postgres \
#            --restart
#
#     [AI 서버]
#       sudo ./scripts/site-install.sh --role=ai
#
#   옵션
#     --role=app|ai        (필수)
#     --storage=<경로>     영상·프레임 저장 루트. 미지정이면 마운트에서 자동 탐지
#     --db-hosts=<h:p,...> DB 주소(이중화 가능). --role=app 에서만
#     --db-name= --db-user=  DB 이름·사용자
#     --db-schema=<이름>   스키마(기본 klid_at). ★ 데이터베이스 이름과 <다른 축>이다
#     --skip-db            DB 설정을 건너뛴다(이미 맞춰 뒀을 때)
#     --skip-install       10~16 설치 단계를 건너뛴다(재실행·부분 수정 시)
#     --node=first|more    ★ WAS 가 여러 대일 때. 2번째 서버부터 --node=more (기본 first)
#     --restart            마지막에 WAS 를 재기동한다
#     --check              아무것도 바꾸지 않고 <무엇을 할지>만 보여 준다
#
#   ★★ WAS 가 여러 대일 때 — DB 는 <한 벌>이다
#     4대가 같은 DB 를 본다. 그래서 DB 생성·스키마 적재는 <첫 대에서만> 한다.
#     2번째 서버부터 --node=more 를 주면 그 두 단계를 건너뛴다.
#     ⚠ 안 주면 이미 있는 스키마 위에 다시 적재를 시도한다 — 실패하거나, 더 나쁘게는
#       첫 대가 만든 데이터를 건드릴 수 있다.
#
#     [1번 서버]  sudo ./scripts/site-install.sh --role=app --node=first  ...
#     [2~4번]     sudo ./scripts/site-install.sh --role=app --node=more   ...
#
#   ★ 멱등하다. 다시 돌려도 안전하며, 이미 맞는 것은 건너뛴다.
#   ★ 비밀번호는 명령줄로 받지 않는다 — ps -ef 와 히스토리에 남는다(CWE-214/532).
#     필요한 시점에 화면에 안 찍히게 물어본다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"
require_root

ONPREM="$(onprem_root)"
KLID_ETC="${KLID_ETC:-/etc/klid}"

ROLE=""; STORAGE=""; DB_HOSTS=""; DB_NAME=""; DB_USER=""
SKIP_DB=0; SKIP_INSTALL=0; DO_RESTART=0; CHECK_ONLY=0; DB_SCHEMA_ARG=""
NODE="first"   # first | more  — 아래 「여러 대」 참조

for arg in "$@"; do
  case "${arg}" in
    --role=*)     ROLE="${arg#*=}" ;;
    --node=*)     NODE="${arg#*=}" ;;
    --storage=*)  STORAGE="${arg#*=}" ;;
    --db-hosts=*) DB_HOSTS="${arg#*=}" ;;
    --db-name=*)  DB_NAME="${arg#*=}" ;;
    --db-user=*)  DB_USER="${arg#*=}" ;;
    --db-schema=*) DB_SCHEMA_ARG="${arg#*=}" ;;
    --skip-db)      SKIP_DB=1 ;;
    --skip-install) SKIP_INSTALL=1 ;;
    --restart)      DO_RESTART=1 ;;
    --check)        CHECK_ONLY=1 ;;
    --help|-h)    sed -n '3,45p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "알 수 없는 옵션: ${arg} (--help 로 사용법)" ;;
  esac
done

case "${NODE}" in first|more) ;; *) die "--node 는 first 또는 more 입니다: ${NODE}" ;; esac
case "${ROLE}" in
  app|ai) ;;
  "") die "--role=app 또는 --role=ai 가 필요합니다. (--help 로 사용법)" ;;
  *) die "알 수 없는 역할: ${ROLE} (app|ai)" ;;
esac

banner() { echo; printf '════════ %s ════════\n' "$*"; }

# ---------------------------------------------------------------------------
# 0) 현장 자동 탐지 — 사람이 적지 않아도 되는 것은 적게 하지 않는다
# ---------------------------------------------------------------------------
banner "0. 현장 탐지"

# 실행 계정 — 돌고 있는 프로세스에서 읽는다(추측하지 않는다)
detect_was_user() {
  # ★ root 는 후보에서 뺀다 (2026-09-04 현장 실측).
  #   WAS 가 root 로 떠 있는데 배포 디렉터리·설정 파일은 jboss 소유인 경우가 실재했다.
  #   ps 값을 그대로 믿으면 chgrp root /etc/klid 가 되어 jboss 가 설정을 <다시> 못 읽는다.
  #   그래서 ①root 아닌 프로세스 계정 → ②배포 디렉터리 소유자 → ③jboss → ④klid 순으로 본다.
  local u jh dd
  u="$(ps -eo user=,args= 2>/dev/null | grep 'jboss-modules.jar' | awk '$1!="root"{print $1; exit}' || true)"
  [[ -n "${u}" ]] && { printf '%s\n' "${u}"; return 0; }

  jh="$(ps -eo args= 2>/dev/null | tr ' ' '\n' | grep -m1 -- '-Djboss.home.dir=' | cut -d= -f2- || true)"
  [[ -z "${jh}" ]] && for c in /GCLOUD/JBOSS/jboss-eap-* /opt/jboss-eap-* /opt/wildfly*; do
    [[ -d "${c}/standalone" ]] && { jh="${c}"; break; }
  done
  dd="${jh}/standalone/deployments"
  if [[ -d "${dd}" ]]; then
    u="$(stat -c '%U' "${dd}" 2>/dev/null || true)"
    [[ -n "${u}" && "${u}" != "root" ]] && { printf '%s\n' "${u}"; return 0; }
  fi

  id jboss >/dev/null 2>&1 && { printf 'jboss\n'; return 0; }
  printf 'klid\n'
}
detect_web_user() {
  local u
  u="$(ps -eo user=,args= 2>/dev/null | grep '[h]ttpd' | awk '{print $1}' | grep -v '^root$' | head -n1 || true)"
  [[ -n "${u}" ]] && { printf '%s\n' "${u}"; return 0; }
  id apache >/dev/null 2>&1 && { printf 'apache\n'; return 0; }
  printf '\n'
}

if [[ "${ROLE}" == "app" ]]; then
  RUN_USER="$(detect_was_user)"
  WEB_USER="$(detect_web_user)"
else
  # AI 서버는 우리 systemd 유닛이 기동하므로 전용 계정을 쓴다(기본 klid).
  RUN_USER="${KLID_USER:-klid}"
  WEB_USER=""
fi
RUN_GROUP="$(id -gn "${RUN_USER}" 2>/dev/null || echo "${RUN_USER}")"
row() { printf '  %-22s %s\n' "$1" "${2:-—}"; }
row "역할"        "${ROLE} (${NODE} 노드)"
row "실행 계정"   "${RUN_USER}:${RUN_GROUP}"
[[ -n "${WEB_USER}" ]] && row "웹 실행 계정" "${WEB_USER}"

# 저장소 — 마운트에서 찾는다. 기본값 /nas-storage 를 그대로 쓰다 조용히 깨진 적이 있다.
if [[ -z "${STORAGE}" && "${ROLE}" == "app" ]]; then
  for c in /nas-storage /nas-storage1 /nas-storage2; do
    mountpoint -q "${c}" 2>/dev/null && { STORAGE="${c}/klid"; break; }
  done
  [[ -z "${STORAGE}" ]] && STORAGE="$(mount | awk '/type (nfs|nfs4|cifs)/{print $3}' | head -n1)"
  [[ -n "${STORAGE}" && "${STORAGE}" != */klid ]] && STORAGE="${STORAGE}/klid"
fi
[[ "${ROLE}" == "app" ]] && row "저장소 루트" "${STORAGE:-★찾지 못함 — --storage= 로 지정하세요}"

# 저장소 원본(참고 표시). ★ 이름으로 공유 여부를 <추측하지 않는다> — export 이름에 호스트명이
#   들어 있어도 공유일 수 있고, 이름이 같아도 다른 볼륨일 수 있다. 실제 증명은 아래 카나리가 한다.
if [[ "${ROLE}" == "app" && -n "${STORAGE}" ]]; then
  _src="$(findmnt -n -o SOURCE --target "${STORAGE}" 2>/dev/null || mount | awk -v p="${STORAGE}" '$3==p{print $1}' | head -n1)"
  [[ -n "${_src}" ]] && row "저장소 원본" "${_src}"
fi

if [[ "${ROLE}" == "app" && -z "${STORAGE}" ]]; then
  die "저장소 경로를 정하지 못했습니다 — --storage=<경로> 로 주세요.
     ⚠ 기본값 /nas-storage 를 그대로 쓰면 <기동과 조회는 정상인데> 비식별·프레임추출·산출물이
       전부 실패합니다. 마운트 확인:  mount | grep -iE 'nfs|nas'"
fi

if [[ "${CHECK_ONLY}" -eq 1 ]]; then
  banner "계획 (--check — 아무것도 바꾸지 않습니다)"
  echo "  1. 설치 단계 실행       $( [[ ${SKIP_INSTALL} -eq 1 ]] && echo '건너뜀' || echo "install.sh --role=${ROLE}  (KLID_USER=${RUN_USER})" )"
  [[ "${ROLE}" == "app" ]] && {
  echo "  2. ${KLID_ETC} 권한 교정   그룹 ${RUN_GROUP} · 디렉터리 750 · 설정파일 640"
  echo "  3. 저장소 경로 반영     STORAGE_RAW_PATH / STORAGE_DEIDENTIFIED_PATH = ${STORAGE}"
  echo "  4. DB 설정              $( [[ ${SKIP_DB} -eq 1 ]] && echo '건너뜀' || echo "${DB_HOSTS:-(대화식)} / ${DB_NAME:-?} / ${DB_USER:-?}" )"
  echo "  4-b. 스키마 적재        $( [[ "${NODE}" == "first" ]] && echo '첫 노드 — 비어 있으면 db/schema.sql 적재' || echo '건너뜀(--node=more)' )"
  echo "  5. JBoss 배포           17-deploy-jboss.sh $( [[ ${DO_RESTART} -eq 1 ]] && echo '--restart' )"
  echo "  6. undertow/io 설정     18-jboss-settings.sh --apply"
  echo "  7. 최종 검증"; }
  [[ "${ROLE}" == "ai" ]] && echo "  2. klid-ai-server 기동 · 헬스 확인"
  exit 0
fi

# ---------------------------------------------------------------------------
# 1) 설치 단계
# ---------------------------------------------------------------------------
if [[ "${SKIP_INSTALL}" -eq 1 ]]; then
  info "[1/7] 설치 단계 건너뜀(--skip-install)"
else
  banner "1. 설치 단계 (install.sh --role=${ROLE})"
  # ★ KLID_USER/GROUP 을 실행 계정으로 넘긴다. 기본값 klid 로 두면 WAS(jboss)가 설정을
  #   읽지 못해 <WAS 는 뜨고 앱만> 기동에 실패한다 — 오늘 실제로 걸린 자리다.
  #   ★ 17단계는 여기서 돌리지 않는다(설정·권한을 먼저 맞춘 뒤 5번에서 돈다).
  # ★ 이 단계에서는 DB 를 <건드리지 않는다>(SKIP_DB_INIT=1).
  #   DB 이름·사용자·비밀번호는 아래 4번에서야 확정되기 때문이다. 여기서 돌리면
  #   15/16 단계가 기본값(klid_system)으로 동작해 <엉뚱한 DB 를 만들거나 못 찾는다>.
  #   실제 스키마 적재는 4번 뒤의 4-b 에서 확정값으로 수행한다.
  KLID_USER="${RUN_USER}" KLID_GROUP="${RUN_GROUP}" \
  STORAGE_RAW_PATH="${STORAGE:-}" STORAGE_DEIDENTIFIED_PATH="${STORAGE:-}" \
  SKIP_JBOSS_DEPLOY=1 SKIP_DB_INIT=1 \
    "${ONPREM}/scripts/install.sh" --role="${ROLE}"
fi

# ---------------------------------------------------------------------------
# AI 서버는 여기서 끝난다
# ---------------------------------------------------------------------------
if [[ "${ROLE}" == "ai" ]]; then
  banner "2. ai-server 기동"
  systemctl daemon-reload 2>/dev/null || true
  systemctl enable --now klid-ai-server 2>/dev/null || warn "[ai] 유닛 기동 실패 — journalctl -u klid-ai-server"
  sleep 3
  _code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:9300/health 2>/dev/null || echo 000)"
  if [[ "${_code}" == "200" ]]; then ok "[ai] /health 200 — 정상"
  else warn "[ai] /health 응답 ${_code} — journalctl -u klid-ai-server -n 100"; fi
  echo
  info "★ 이 서버 주소를 WAS 서버의 AI 장비 목록(관리자 → 연동 서버 주소)에 등록해야 합니다."
  info "  등록하지 않으면 WAS 가 이 서버로 요청을 보내지 않습니다."
  exit 0
fi

# ---------------------------------------------------------------------------
# 2) /etc/klid 권한 — 파일이 아니라 <디렉터리>가 막는다
# ---------------------------------------------------------------------------
banner "2. ${KLID_ETC} 권한"
if [[ -d "${KLID_ETC}" ]]; then
  chgrp "${RUN_GROUP}" "${KLID_ETC}"
  chmod 0750 "${KLID_ETC}"          # ★ 실행 계정의 <진입(x)> 권한 — 이게 없으면 소유자여도 못 읽는다
  chgrp -R "${RUN_GROUP}" "${KLID_ETC}" 2>/dev/null || true
  for f in "${KLID_ETC}"/application.properties "${KLID_ETC}"/*.env; do
    [[ -f "${f}" ]] && chmod 0640 "${f}"
  done
  command -v restorecon >/dev/null 2>&1 && restorecon -R "${KLID_ETC}" 2>/dev/null || true
  # 권한 표가 아니라 <실제로 읽어서> 판정한다
  if [[ -f "${KLID_ETC}/application.properties" ]]; then
    sudo -u "${RUN_USER}" test -r "${KLID_ETC}/application.properties" \
      && ok "[perm] ${RUN_USER} 가 설정 파일을 읽을 수 있습니다" \
      || die "[perm] ${RUN_USER} 가 설정 파일을 여전히 못 읽습니다 — SELinux 를 확인하세요:
     getenforce · ls -Zd ${KLID_ETC} · ausearch -m avc -ts recent | tail"
  fi
else
  warn "[perm] ${KLID_ETC} 가 없습니다 — 설치 단계가 돌지 않았습니다(--skip-install?)."
fi

# ---------------------------------------------------------------------------
# 3) 저장소 경로 — 기본값을 그대로 두면 조용히 깨진다
# ---------------------------------------------------------------------------
banner "3. 저장소 경로"
PROPS="${KLID_ETC}/application.properties"
set_prop() {
  local k="$1" v="$2" esc
  esc="$(printf '%s' "${v}" | sed 's/[&|\\]/\\&/g')"
  if grep -qE "^[[:space:]]*${k}=" "${PROPS}"; then
    sed -i "s|^[[:space:]]*${k}=.*|${k}=${esc}|" "${PROPS}"
  else
    printf '%s=%s\n' "${k}" "${v}" >> "${PROPS}"
  fi
}
if [[ -f "${PROPS}" ]]; then
  cp -p "${PROPS}" "${PROPS}.bak.$(date '+%Y%m%d%H%M%S')" && chmod 0600 "${PROPS}".bak.* 2>/dev/null || true
  set_prop STORAGE_RAW_PATH          "${STORAGE}"
  set_prop STORAGE_DEIDENTIFIED_PATH "${STORAGE}"
  ok "[storage] STORAGE_RAW_PATH / STORAGE_DEIDENTIFIED_PATH = ${STORAGE}"
fi
install -d -o "${RUN_USER}" -g "${RUN_GROUP}" -m 0775 "${STORAGE}" 2>/dev/null \
  || warn "[storage] ${STORAGE} 생성/소유 변경 실패 — NAS 권한은 스토리지 담당 소관일 수 있습니다."
# ★ 공유 저장소 증명 — 4대가 <같은> 볼륨을 봐야 한다.
#   이름 비교가 아니라 <한 서버가 쓴 파일이 다른 서버에서 보이는지>로 판정한다.
#   공유가 아니면 1번이 넣은 영상을 2번이 못 보는데, <기동도 조회도 정상>이라
#   "왜 어떤 영상만 안 보이지"로만 드러난다. 그래서 설치 시점에 못 박는다.
_canary="${STORAGE}/.klid-shared-storage"
if [[ "${NODE}" == "first" ]]; then
  if printf '%s %s\n' "$(hostname -s)" "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "${_canary}" 2>/dev/null; then
    chown "${RUN_USER}:${RUN_GROUP}" "${_canary}" 2>/dev/null || true
    ok "[storage] 공유 확인 표식 기록: ${_canary}"
    info "          다음 서버에서 --node=more 로 설치하면 이 표식이 보이는지 확인합니다."
  else
    warn "[storage] 공유 확인 표식을 쓰지 못했습니다(쓰기 권한?) — 아래 쓰기 시험을 보세요."
  fi
else
  if [[ -f "${_canary}" ]]; then
    _others="$(grep -vc "^$(hostname -s) " "${_canary}" 2>/dev/null || echo 0)"
    if [[ "${_others}" -gt 0 ]]; then
      ok "[storage] ★ 공유 저장소 확인 — 다른 서버가 쓴 표식이 보입니다:"
      sed 's/^/            /' "${_canary}" | tail -n 5
    else
      warn "[storage] 표식은 있는데 <이 서버가 쓴 것뿐>입니다 — 공유가 아닐 수 있습니다."
    fi
    printf '%s %s\n' "$(hostname -s)" "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "${_canary}" 2>/dev/null || true
  else
    warn "[storage] ★★ 공유 저장소가 아닐 수 있습니다."
    warn "          1번 서버가 남긴 표식(${_canary})이 <여기서 보이지 않습니다>."
    warn "          4대가 같은 볼륨을 보지 않으면 1번이 넣은 영상을 이 서버가 못 봅니다 —"
    warn "          그런데 기동도 조회도 정상이라 한참 뒤에 \"왜 어떤 영상만 안 보이지\"로만 드러납니다."
    warn "          현재 마운트: $(findmnt -n -o SOURCE --target "${STORAGE}" 2>/dev/null || echo '(확인 실패)')"
    warn "          1번 서버에서 --node=first 로 먼저 설치했는지, 스토리지가 공유 export 인지 확인하세요."
  fi
fi

if sudo -u "${RUN_USER}" test -w "${STORAGE}" 2>/dev/null; then
  ok "[storage] ${RUN_USER} 가 ${STORAGE} 에 쓸 수 있습니다"
else
  warn "[storage] ★ ${RUN_USER} 가 ${STORAGE} 에 쓰지 못합니다."
  warn "          비식별·프레임추출·산출물이 전부 실패하는데 <기동과 조회는 정상>이라"
  warn "          한참 뒤에야 드러납니다. 스토리지 담당과 권한을 협의하세요."
fi

# ---------------------------------------------------------------------------
# 4) DB
# ---------------------------------------------------------------------------
banner "4. DB 설정"
if [[ "${SKIP_DB}" -eq 1 ]]; then
  info "[db] 건너뜀(--skip-db)"
else
  _dbargs=()
  [[ -n "${DB_HOSTS}" ]] && _dbargs+=(--hosts="${DB_HOSTS}")
  [[ -n "${DB_NAME}"  ]] && _dbargs+=(--name="${DB_NAME}")
  [[ -n "${DB_USER}"  ]] && _dbargs+=(--user="${DB_USER}")
  [[ -n "${DB_SCHEMA_ARG}" ]] && _dbargs+=(--schema="${DB_SCHEMA_ARG}")
  KLID_ETC="${KLID_ETC}" "${ONPREM}/scripts/install/set-db-config.sh" "${_dbargs[@]}"
fi

# ---------------------------------------------------------------------------
# 4-b) 스키마 적재 — ★ 확정된 DB 값으로, 첫 노드에서만
#   설치 단계(1번)가 아니라 여기서 하는 이유: 그때는 DB 이름·사용자·비밀번호를 모른다.
#   여러 대가 <같은 DB 한 벌>을 보므로 --node=more 는 건너뛴다.
# ---------------------------------------------------------------------------
banner "4-b. 스키마 적재"
if [[ "${NODE}" != "first" ]]; then
  info "[schema] --node=${NODE} 이므로 건너뜁니다(DB 는 한 벌이고 첫 노드가 이미 적재했습니다)."
elif [[ "${SKIP_DB}" -eq 1 ]]; then
  info "[schema] --skip-db 이므로 건너뜁니다."
elif [[ ! -f "${PROPS}" ]]; then
  warn "[schema] ${PROPS} 가 없어 적재할 수 없습니다."
else
  _get() { grep -E "^[[:space:]]*$1=" "${PROPS}" | tail -1 | cut -d= -f2-; }
  _H="$(_get CONTROL_DB_HOST)"; _P="$(_get CONTROL_DB_PORT)"
  _N="$(_get CONTROL_DB_NAME)"; _U="$(_get CONTROL_DB_USERNAME)"
  _W="$(_get CONTROL_DB_PASSWORD)"; _S="$(_get DB_SCHEMA)"; _S="${_S:-klid_at}"
  # 이중화 주소면 첫 호스트로 붙는다(적재는 한 번만 하면 되고, 프록시/HA 뒤라 어느 쪽이든 같다)
  _H1="${_H%%,*}"; _P1="${_P}"
  [[ "${_H1}" == *:* ]] && { _P1="${_H1#*:}"; _H1="${_H1%%:*}"; }

  if ! command -v psql >/dev/null 2>&1; then
    warn "[schema] psql 이 없어 자동 적재를 못 합니다. 아래를 <사람이> 실행하세요:"
    warn "  psql -h ${_H1} -p ${_P1} -U ${_U} -d ${_N} -v ON_ERROR_STOP=1 -f ${ONPREM}/db/schema.sql"
  else
    _cnt="$(PGPASSWORD="${_W}" psql -h "${_H1}" -p "${_P1}" -U "${_U}" -d "${_N}" -tAc \
            "select count(*) from information_schema.tables where table_schema='${_S}'" 2>/dev/null || echo '?')"
    _want_t="$(grep -c '^CREATE TABLE klid_at\.' "${ONPREM}/db/schema.sql" 2>/dev/null || echo 0)"
    _want_v="$(grep -c '^CREATE VIEW klid_at\.'  "${ONPREM}/db/schema.sql" 2>/dev/null || echo 0)"
    _want=$(( _want_t + _want_v ))
    info "[schema] 대상 ${_H1}:${_P1}/${_N} 스키마 ${_S} — 현재 ${_cnt} 개 / 매체 기대 ${_want} 개"
    if [[ "${_cnt}" == "0" ]]; then
      info "[schema] 비어 있어 ${ONPREM}/db/schema.sql 을 적재합니다..."
      if PGPASSWORD="${_W}" psql -h "${_H1}" -p "${_P1}" -U "${_U}" -d "${_N}" \
           -v ON_ERROR_STOP=1 -q -f "${ONPREM}/db/schema.sql" >/dev/null; then
        _now="$(PGPASSWORD="${_W}" psql -h "${_H1}" -p "${_P1}" -U "${_U}" -d "${_N}" -tAc \
                "select count(*) from information_schema.tables where table_schema='${_S}'" 2>/dev/null || echo '?')"
        [[ "${_now}" == "${_want}" ]] \
          && ok   "[schema] 적재 완료 — ${_now} 개 (기대값과 일치)" \
          || warn "[schema] 적재했으나 개수가 다릅니다 — 현재 ${_now} / 기대 ${_want}"
      else
        warn "[schema] ★ 적재에 실패했습니다. 권한(CREATE SCHEMA)과 DB 이름을 확인하세요."
      fi
    elif [[ "${_cnt}" == "${_want}" ]]; then
      ok "[schema] 이미 적재돼 있고 개수가 기대값과 같습니다 — 건너뜁니다."
    else
      warn "[schema] ★ 이미 테이블이 있는데 개수가 다릅니다(현재 ${_cnt} / 기대 ${_want})."
      warn "         <덮어쓰지 않습니다> — 현장 데이터가 사라질 수 있습니다."
      warn "         이전 반입 형상일 수 있으니 db/README.md 의 증분 적용 규칙을 보세요."
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 5) JBoss 배포
# ---------------------------------------------------------------------------
banner "5. JBoss 배포"
_jargs=(); [[ "${DO_RESTART}" -eq 1 ]] && _jargs+=(--restart)
KLID_ETC="${KLID_ETC}" STORAGE_RAW_PATH="${STORAGE}" \
  "${ONPREM}/scripts/install/17-deploy-jboss.sh" "${_jargs[@]}" || warn "[jboss] 배포 단계에서 문제가 있었습니다 — 위 출력을 보세요."

# ---------------------------------------------------------------------------
# 6) undertow / io
# ---------------------------------------------------------------------------
banner "6. WAS 요청 처리 설정"
"${ONPREM}/scripts/install/18-jboss-settings.sh" --apply \
  || warn "[jboss] undertow/io 설정을 걸지 못했습니다(WAS 미기동?). 나중에 다시 실행하세요:
     sudo ./scripts/install/18-jboss-settings.sh --apply"

# ---------------------------------------------------------------------------
# 7) 최종 검증 — "설치했다"가 아니라 "돈다"를 확인한다
# ---------------------------------------------------------------------------
banner "7. 검증"
_fail=0
chk() {  # chk <라벨> <성공조건 명령>
  if eval "$2" >/dev/null 2>&1; then ok "  $1"; else warn "  ★ $1"; _fail=$((_fail+1)); fi
}
chk "설정 파일을 ${RUN_USER} 가 읽는다"      "sudo -u ${RUN_USER} test -r ${PROPS}"
chk "저장소에 ${RUN_USER} 가 쓴다"            "sudo -u ${RUN_USER} test -w ${STORAGE}"
chk "JAVA_OPTS 가 실제로 걸렸다"              "ps -eo args= | tr ' ' '\n' | grep -q spring.config.additional-location"
chk "백엔드 liveness 200"                     "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:8080/api/actuator/health/liveness)\" = 200 ]"
chk "ffmpeg/ffprobe 존재"                     "command -v ffmpeg && command -v ffprobe"
# ★ 여러 대가 같은 DB 를 볼 때 이것이 꺼져 있으면 배치가 <대수만큼 중복 실행>된다.
chk "Quartz 클러스터링 켜짐"                  "grep -qE '^[[:space:]]*QUARTZ_CLUSTERED=true' ${PROPS}"

echo
if [[ "${_fail}" -eq 0 ]]; then
  ok "전부 통과했습니다."
else
  warn "${_fail} 개 항목이 통과하지 못했습니다 — 위 ★ 표시를 보세요."
  warn "  · JAVA_OPTS 미적용  → WAS 재기동이 필요합니다(--restart 를 안 주셨다면)"
  warn "  · liveness 실패     → tail -n 200 <JBOSS_HOME>/standalone/log/server.log"
fi

echo
info "★ 기동 성공은 완료 판정이 아닙니다. 다음 둘은 <사람이> 확인해야 합니다:"
info "  1) 대용량 영상 업로드 1회 — WAS 설정 이관이 실제로 먹었는지 아는 유일한 방법"
info "  2) 스키마 테이블 개수 — 스키마가 비어도 앱은 기동에 성공합니다"
info "     기대값:  grep -c '^CREATE TABLE klid_at\\.' ${ONPREM}/db/schema.sql"
[[ -n "${WEB_USER}" ]] && info "  3) 프론트: http://<이 서버>/ 가 화면을 그리는지 (httpd 실행 계정 ${WEB_USER})"
echo

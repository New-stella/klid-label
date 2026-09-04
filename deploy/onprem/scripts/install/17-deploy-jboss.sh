#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 17-deploy-jboss.sh — [대상 서버 A] JBoss EAP 에 api.war 자동 배포 + JVM 옵션 배선
#
#   ★ 왜 이 스크립트가 생겼나 (2026-09-04)
#     이 구간은 원래 "사람이 한다"였다. 그런데 현장에서 손으로 하다 실제로 아래가 났다:
#       · standalone.conf 한 블록에 오타 5개 — spring.config'.'additional-location 의 점 누락,
#         profiles.active'='prd 의 등호, java.security.'egd' 철자, ${JAVA_'OPTS'},
#         그리고 마지막 조합 줄의 좌변이 JAVA_OPTS 가 아니라 KLID_OPTS
#         → 앞의 네 줄이 전부 무효가 되는데 <오류가 나지 않는다>
#       · WAR 파일명이 바뀐 채 배포 → 컨텍스트가 /api 가 아니게 됨
#       · 배포 실패(로깅 서브시스템 충돌) 사유가 .failed 파일에만 남아 못 보고 지나침
#     전부 "조용히 틀리는" 부류다. 그래서 <손으로 적는 자리를 없앤다>.
#
#   하는 일
#     0) JBOSS_HOME·판·자바 확인          — EAP 8.x 아니면 즉시 중단
#     1) 실행 계정 권한 교정               — jboss 가 설정·데이터·로그를 읽고 쓸 수 있게
#     2) standalone.conf 에 JAVA_OPTS 배선 — 마커로 감싼 블록, 재실행하면 <교체>(멱등)
#     3) WAR 배포 + 마커 생성              — 기존 배포본 정리 포함
#     4) 결과 판정                         — .deployed / .failed 를 실제로 기다려 확인
#
#   안 하는 일
#     · WAS 재기동 — 기본은 하지 않는다(같은 WAS 에 다른 애플리케이션이 있을 수 있다).
#       --restart 를 주면 한다. 안 주면 실행할 명령을 마지막에 찍는다.
#     · undertow/io 설정 — 18-jboss-settings.sh 가 담당한다(기동 중 WAS 에 CLI 로 건다).
#
#   사용법
#     sudo ./scripts/install/17-deploy-jboss.sh [옵션]
#       --jboss-home=<경로>   JBOSS_HOME 직접 지정(미지정 시 자동 탐지)
#       --war-name=<이름>     배포 파일명(기본: 이미 배포된 이름이 있으면 그것, 없으면 api.war)
#       --was-unit=<유닛명>   systemd 유닛명(미지정 시 자동 탐지)
#       --restart             배선 후 WAS 를 재기동한다
#       --check               아무것도 바꾸지 않고 현재 상태만 본다
#
#   ⚠ 컨텍스트 경로는 WAR 안 WEB-INF/jboss-web.xml 이 /api 로 고정한다.
#     그래서 <파일명이 무엇이든 상관없다> — 옛 규칙 "rename 금지"는 폐기됐다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
require_root

ONPREM="$(onprem_root)"
KLID_ETC="${KLID_ETC:-/etc/klid}"
KLID_PREFIX="${KLID_PREFIX:-/opt/klid}"
KLID_DATA="${KLID_DATA:-/var/lib/klid}"
KLID_LOG="${KLID_LOG:-/var/log/klid}"

JBOSS_HOME_ARG=""
WAR_NAME_ARG=""
WAS_UNIT_ARG=""
DO_RESTART=0
CHECK_ONLY=0

for arg in "$@"; do
  case "${arg}" in
    --jboss-home=*) JBOSS_HOME_ARG="${arg#*=}" ;;
    --war-name=*)   WAR_NAME_ARG="${arg#*=}" ;;
    --was-unit=*)   WAS_UNIT_ARG="${arg#*=}" ;;
    --restart)      DO_RESTART=1 ;;
    --check)        CHECK_ONLY=1 ;;
    --help|-h)      sed -n '3,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "알 수 없는 옵션: ${arg} (--help 로 사용법)" ;;
  esac
done

# ---------------------------------------------------------------------------
# 0) JBOSS_HOME 찾기
#    우선순위: 인자 > was.env > 돌고 있는 프로세스 > 흔한 경로
#    ★ "돌고 있는 프로세스"를 파일보다 신뢰하는 이유 — 설치 디렉터리가 여러 벌 있을 때
#      실제로 쓰이는 것은 하나뿐이고, 파일에 적힌 값은 낡았을 수 있다.
# ---------------------------------------------------------------------------
detect_jboss_home() {
  local h=""
  if [[ -n "${JBOSS_HOME_ARG}" ]]; then printf '%s\n' "${JBOSS_HOME_ARG}"; return 0; fi

  # 프로세스에서 -Djboss.home.dir 를 읽는다
  h="$(ps -eo args= 2>/dev/null | tr ' ' '\n' | grep -m1 -- '-Djboss.home.dir=' | cut -d= -f2- || true)"
  [[ -n "${h}" && -d "${h}" ]] && { printf '%s\n' "${h}"; return 0; }

  # was.env
  if [[ -f "${KLID_ETC}/was.env" ]]; then
    h="$(grep -E '^[[:space:]]*WAS_HOME=' "${KLID_ETC}/was.env" | tail -1 | cut -d= -f2- | tr -d '"'"'"' ' || true)"
    [[ -n "${h}" && -d "${h}" ]] && { printf '%s\n' "${h}"; return 0; }
  fi

  # 흔한 경로
  local c
  for c in /GCLOUD/JBOSS/jboss-eap-* /opt/jboss-eap-* /opt/rh/eap*/root/usr/share/wildfly /opt/wildfly*; do
    [[ -d "${c}/standalone" ]] && { printf '%s\n' "${c}"; return 0; }
  done
  return 1
}

JBOSS_HOME="$(detect_jboss_home || true)"
[[ -n "${JBOSS_HOME}" ]] || die "JBOSS_HOME 을 찾지 못했습니다.
     --jboss-home=<경로> 로 직접 주거나, WAS 가 돌고 있는지 확인하세요:
       ps -ef | grep '[j]boss' | tr ' ' '\\n' | grep jboss.home.dir"

SERVER_BASE="${JBOSS_HOME}/standalone"
DEPLOY_DIR="${SERVER_BASE}/deployments"
CONF_DIR="${SERVER_BASE}/configuration"
LOG_DIR="${SERVER_BASE}/log"
STANDALONE_CONF="${JBOSS_HOME}/bin/standalone.conf"

[[ -d "${DEPLOY_DIR}" ]] || die "배포 디렉터리가 없습니다: ${DEPLOY_DIR}
     standalone 모드가 아니거나(domain?) JBOSS_HOME 이 잘못됐습니다."

info "[jboss] JBOSS_HOME = ${JBOSS_HOME}"

# ---------------------------------------------------------------------------
# 0-1) 판 확인 — EAP 8.x(Jakarta EE 9+) 인가
#      ★ 판번호 문자열보다 <모듈 존재>가 확실하다. 제품명 표기는 판마다 달라진다.
#        jakarta/servlet 이 있으면 EE9+ → Spring Boot 3.3 WAR 가 돈다.
#        javax/servlet 만 있으면 EE8 → 배포 자체가 불가능하다.
# ---------------------------------------------------------------------------
MODBASE="${JBOSS_HOME}/modules/system/layers/base"
if [[ -d "${MODBASE}/jakarta/servlet/api/main" ]]; then
  ok "[jboss] Jakarta EE 9+ 확인(jakarta.servlet 모듈 존재) — WAR 배포 가능"
elif [[ -d "${MODBASE}/javax/servlet/api/main" ]]; then
  die "[jboss] 이 WAS 는 Jakarta EE 8(javax.*) 입니다 — EAP 7.x 계열로 보입니다.
     저작도구 WAR 는 Spring Boot 3.3(jakarta.*) 이라 <이 WAS 에는 배포할 수 없습니다>.
     EAP 8.x 이상이 필요합니다. 상세: docs/10-was-settings.md"
else
  warn "[jboss] 서블릿 API 모듈을 찾지 못했습니다(경로 구조가 다를 수 있음): ${MODBASE}"
  warn "        판 확인을 건너뜁니다 — 배포가 실패하면 EAP 판부터 확인하세요."
fi

# 제품 판 표기(참고용, 실패시키지 않는다)
if [[ -f "${JBOSS_HOME}/version.txt" ]]; then
  info "[jboss] $(head -n1 "${JBOSS_HOME}/version.txt")"
fi

# ---------------------------------------------------------------------------
# 0-2) 실행 계정 — 돌고 있는 프로세스에서 읽는다(추측하지 않는다)
# ---------------------------------------------------------------------------
# ★ root 는 후보에서 뺀다 — WAS 가 root 로 떠 있어도 배포물·설정은 jboss 소유인 형상이 실재한다.
#   ps 값을 그대로 믿으면 설정 파일 그룹을 root 로 바꿔 <jboss 가 못 읽게> 만든다.
WAS_USER="$(ps -eo user=,args= 2>/dev/null | grep 'jboss-modules.jar' | awk '$1!="root"{print $1; exit}' || true)"
if [[ -z "${WAS_USER}" ]]; then
  WAS_USER="$(stat -c '%U' "${DEPLOY_DIR}" 2>/dev/null || echo jboss)"
  [[ "${WAS_USER}" == "root" ]] && WAS_USER="$(id jboss >/dev/null 2>&1 && echo jboss || echo klid)"
  info "[jboss] 배포 디렉터리 소유자로 실행 계정을 정합니다: ${WAS_USER}"
  info "        (WAS 가 root 로 떠 있거나 내려가 있어도 이 값이 맞습니다. 다르면 --check 로 확인)"
fi
WAS_GROUP="$(id -gn "${WAS_USER}" 2>/dev/null || echo "${WAS_USER}")"
info "[jboss] WAS 실행 계정 = ${WAS_USER}:${WAS_GROUP}"

# 자바 판(참고)
if command -v java >/dev/null 2>&1; then
  info "[jboss] java = $(java -version 2>&1 | head -n1)"
fi

# ---------------------------------------------------------------------------
# 0-2b) ★ 기동 주체 불일치 검사 (2026-09-04 현장 실측)
#   WAS 프로세스는 떠 있는데 systemd 유닛이 inactive 인 형상이 실재했다(수동 기동).
#   그 상태에서 `systemctl start` 를 하면 <두 번째 인스턴스>가 뜨려다 포트를 못 잡고
#   "부팅이 안 된다"로 나타난다. 배포 실패로 오인하기 쉬워 여기서 미리 갈라 준다.
# ---------------------------------------------------------------------------
_unit="$(systemctl list-units --type=service --all --no-legend 2>/dev/null \
         | awk '{print $1}' | grep -iE '^(jboss|eap|wildfly)' | head -n1 | sed 's/\.service$//')"
if [[ -n "${_unit}" ]]; then
  _unit_state="$(systemctl is-active "${_unit}" 2>/dev/null || echo unknown)"
  _proc_up=0; pgrep -f 'jboss-modules.jar' >/dev/null 2>&1 && _proc_up=1
  info "[jboss] systemd 유닛 ${_unit} = ${_unit_state} · 프로세스 = $( [[ ${_proc_up} -eq 1 ]] && echo '떠 있음' || echo '없음' )"
  if [[ "${_unit_state}" != "active" && "${_proc_up}" -eq 1 ]]; then
    warn "════════════════════════════════════════════════════════════════"
    warn "★ WAS 가 systemd 밖에서 떠 있습니다(유닛은 ${_unit_state} 인데 프로세스는 살아 있음)."
    warn "  이 상태에서 systemctl start/restart 를 하면 <두 번째 인스턴스>가 뜨려다"
    warn "  포트를 못 잡고 실패합니다 — 배포 실패로 오인하기 쉽습니다."
    warn "  지금 프로세스:"
    ps -o pid=,ppid=,user=,lstart=,cmd= -p "$(pgrep -f 'jboss-modules.jar' | head -1)" 2>/dev/null \
      | cut -c1-150 | sed 's/^/    /'
    warn "  ⇒ 재기동 전에 지금 프로세스를 먼저 내리고 systemd 로 일원화하세요."
    warn "════════════════════════════════════════════════════════════════"
    [[ "${DO_RESTART}" -eq 1 ]] && die "[jboss] --restart 는 이 상태에서 위험해 중단합니다.
     기동 주체를 정리한 뒤 --restart 없이 다시 실행하거나, 재기동을 사람이 수행하세요."
  fi
fi

# ---------------------------------------------------------------------------
# 0-3) 배포 파일명 결정
#      이미 배포돼 있는 우리 WAR 가 있으면 <그 이름을 그대로 쓴다>.
#      ★ 이름을 바꾸면 같은 애플리케이션이 두 벌 배포돼 컨텍스트가 충돌한다.
#      ★ 컨텍스트는 WAR 안 jboss-web.xml 이 /api 로 고정하므로 이름은 무엇이든 된다.
# ---------------------------------------------------------------------------
WAR_SRC="${ONPREM}/artifacts/backend/api.war"
[[ -f "${WAR_SRC}" ]] || die "반입 WAR 없음: ${WAR_SRC}"

# WAR 안에 우리 서술자가 있는지 — 없으면 배포해도 로깅 충돌로 실패한다
if command -v unzip >/dev/null 2>&1; then
  # ★ 목록을 변수에 담아 두고 검사한다. `unzip -l | grep -q` 는 set -o pipefail 아래에서
  #   grep 이 먼저 끝나며 unzip 이 SIGPIPE 로 죽어 <찾았는데 못 찾은 것으로> 분기한다(실측).
  _war_list="$(unzip -l "${WAR_SRC}" 2>/dev/null || true)"
  if ! printf '%s' "${_war_list}" | grep -qF 'WEB-INF/jboss-deployment-structure.xml'; then
    die "[jboss] 반입 WAR 에 WEB-INF/jboss-deployment-structure.xml 이 없습니다.
     이 상태로 배포하면 EAP 로깅 서브시스템과 충돌해 실패합니다:
       'LoggerFactory is not a Logback LoggerContext but Logback is on the classpath'
     그리고 <운 좋게 배포에 성공해도> logback 설정이 무시돼 <민감정보 마스킹이 사라집니다>.
     서술자가 포함된 WAR 로 다시 반입하세요. 상세: docs/06-troubleshooting.md"
  fi
  printf '%s' "${_war_list}" | grep -qF 'WEB-INF/jboss-web.xml' \
    || warn "[jboss] WAR 에 jboss-web.xml 이 없습니다 — 컨텍스트가 <파일명>을 따라갑니다.
        그 경우 배포 파일명이 반드시 api.war 여야 합니다."
fi

detect_war_name() {
  [[ -n "${WAR_NAME_ARG}" ]] && { printf '%s\n' "${WAR_NAME_ARG}"; return 0; }
  local found=() f
  shopt -s nullglob
  for f in "${DEPLOY_DIR}"/*.war; do
    # 우리 WAR 인지 — 서술자 존재로 판정한다(이름에 기대지 않는다)
    local _l; _l="$(unzip -l "${f}" 2>/dev/null || true)"
    if printf '%s' "${_l}" | grep -qE 'WEB-INF/jboss-web\.xml|WEB-INF/classes/kr/co/cudo/authoring'; then
      found+=("$(basename "${f}")")
    fi
  done
  shopt -u nullglob
  if [[ "${#found[@]}" -eq 1 ]]; then printf '%s\n' "${found[0]}"; return 0; fi
  if [[ "${#found[@]}" -gt 1 ]]; then
    warn "[jboss] 저작도구 WAR 로 보이는 배포본이 여럿입니다: ${found[*]}"
    warn "        같은 컨텍스트(/api)를 두 벌이 잡으면 충돌합니다. --war-name= 으로 하나를 고르고"
    warn "        나머지는 배포 디렉터리에서 <직접 치우세요>(이 스크립트는 지우지 않습니다)."
    printf '%s\n' "${found[0]}"; return 0
  fi
  printf 'api.war\n'
}
WAR_NAME="$(detect_war_name)"
WAR_DST="${DEPLOY_DIR}/${WAR_NAME}"
info "[jboss] 배포 파일명 = ${WAR_NAME}  (컨텍스트 /api 는 WAR 안 jboss-web.xml 이 고정)"

# ---------------------------------------------------------------------------
# check 모드 — 여기까지의 관측만 보여 주고 끝낸다
# ---------------------------------------------------------------------------
if [[ "${CHECK_ONLY}" -eq 1 ]]; then
  echo
  info "[check] 현재 상태 (아무것도 바꾸지 않았습니다)"
  echo "  JBOSS_HOME      : ${JBOSS_HOME}"
  echo "  배포 디렉터리   : ${DEPLOY_DIR}"
  echo "  실행 계정       : ${WAS_USER}:${WAS_GROUP}"
  echo "  배포 파일명     : ${WAR_NAME}"
  echo "  standalone.conf : ${STANDALONE_CONF}"
  if grep -q '^# >>> klid-label' "${STANDALONE_CONF}" 2>/dev/null; then
    echo "  JAVA_OPTS 블록  : 이미 배선됨"
  else
    echo "  JAVA_OPTS 블록  : 없음 (배선 필요)"
  fi
  echo "  설정 파일       : ${KLID_ETC}/application.properties $( [[ -f "${KLID_ETC}/application.properties" ]] && echo 있음 || echo '★없음' )"
  if [[ -f "${KLID_ETC}/application.properties" ]]; then
    if sudo -u "${WAS_USER}" test -r "${KLID_ETC}/application.properties" 2>/dev/null; then
      echo "  ${WAS_USER} 읽기      : OK"
    else
      echo "  ${WAS_USER} 읽기      : ★불가 (권한 교정 필요)"
    fi
  fi
  ls -1 "${DEPLOY_DIR}"/*.war* 2>/dev/null | sed 's/^/  배포물: /' || true
  exit 0
fi

# ---------------------------------------------------------------------------
# 1) 실행 계정 권한 교정
#    ★ 설치 스크립트 기본값은 KLID_USER=klid 인데 WAS 는 jboss 로 돈다. 그대로 두면
#      WAS 는 정상으로 뜨고 <애플리케이션만> 설정을 못 읽어 기동에 실패한다 — 조용한 실패다.
# ---------------------------------------------------------------------------
info "[perm] 실행 계정(${WAS_USER}) 권한 교정"

if [[ -d "${KLID_ETC}" ]]; then
  chgrp -R "${WAS_GROUP}" "${KLID_ETC}" 2>/dev/null || warn "[perm] ${KLID_ETC} 그룹 변경 실패"
  # 비밀값을 담는 파일만 0640, 나머지는 건드리지 않는다
  for f in "${KLID_ETC}"/application.properties "${KLID_ETC}"/*.env; do
    [[ -f "${f}" ]] || continue
    chmod 0640 "${f}" 2>/dev/null || true
  done
fi
for d in "${KLID_DATA}" "${KLID_LOG}" "${KLID_PREFIX}/app"; do
  [[ -d "${d}" ]] || continue
  chown -R "${WAS_USER}:${WAS_GROUP}" "${d}" 2>/dev/null || warn "[perm] ${d} 소유 변경 실패"
done

# 실제로 읽히는지 확인한다 — 권한 표가 아니라 <읽어 봐서> 판정한다
if [[ -f "${KLID_ETC}/application.properties" ]]; then
  if sudo -u "${WAS_USER}" test -r "${KLID_ETC}/application.properties"; then
    ok "[perm] ${WAS_USER} 가 ${KLID_ETC}/application.properties 를 읽을 수 있습니다"
  else
    die "[perm] ${WAS_USER} 가 ${KLID_ETC}/application.properties 를 읽지 못합니다.
     이대로면 WAS 는 뜨고 애플리케이션만 기동에 실패합니다(조용한 실패).
     상위 디렉터리 권한(${KLID_ETC})과 SELinux 문맥을 확인하세요."
  fi
else
  warn "[perm] ${KLID_ETC}/application.properties 가 없습니다 — 12단계를 먼저 실행하세요:"
  warn "       sudo ./scripts/install-step.sh 12-install-backend.sh"
fi

# NAS 저장소는 스토리지 운영 주체 소관이라 <손대지 않고 확인만> 한다
_storage="${STORAGE_RAW_PATH:-/nas-storage}"
if [[ -d "${_storage}" ]]; then
  if sudo -u "${WAS_USER}" test -w "${_storage}"; then
    ok "[perm] ${WAS_USER} 가 ${_storage} 에 쓸 수 있습니다"
  else
    warn "[perm] ★ ${WAS_USER} 가 ${_storage} 에 쓰지 못합니다."
    warn "       비식별·프레임 추출·산출물 생성이 전부 실패하는데 <기동과 조회는 정상>이라"
    warn "       한참 뒤에야 드러납니다. 스토리지 담당과 권한을 협의하세요."
  fi
fi

# ---------------------------------------------------------------------------
# 2) standalone.conf 에 JAVA_OPTS 배선 (마커 블록 — 멱등)
#    ★ 손으로 적지 않는다. 재실행하면 블록만 교체되므로 값이 흔들리지 않는다.
# ---------------------------------------------------------------------------
BEGIN_MARK='# >>> klid-label BEGIN (자동 생성 — 이 블록은 17-deploy-jboss.sh 가 교체합니다)'
END_MARK='# <<< klid-label END'

if [[ ! -f "${STANDALONE_CONF}" ]]; then
  warn "[conf] ${STANDALONE_CONF} 이 없습니다 — JAVA_OPTS 배선을 건너뜁니다."
  warn "       WAS 를 systemd 가 다른 방식으로 띄우고 있을 수 있습니다. 확인:"
  warn "         systemctl cat <WAS 유닛명> | grep -iE 'EnvironmentFile|ExecStart|JAVA_OPTS'"
else
  _bak="${STANDALONE_CONF}.bak.$(date '+%Y%m%d%H%M%S')"
  cp -p "${STANDALONE_CONF}" "${_bak}"
  info "[conf] 원본 보존: ${_bak}"

  # 기존 블록 제거(있으면) 후 새로 덧붙인다
  if grep -qF "${BEGIN_MARK}" "${STANDALONE_CONF}"; then
    sed -i "/$(printf '%s' "${BEGIN_MARK}" | sed 's/[[\.*^$/]/\\&/g')/,/$(printf '%s' "${END_MARK}" | sed 's/[[\.*^$/]/\\&/g')/d" "${STANDALONE_CONF}"
    info "[conf] 기존 klid-label 블록을 제거하고 다시 씁니다(멱등)"
  fi

  cat >> "${STANDALONE_CONF}" <<EOF

${BEGIN_MARK}
#   손으로 고치지 마세요 — 다시 돌리면 통째로 교체됩니다.
#   값을 바꾸려면 이 스크립트를 고치거나, 블록 <바깥>에 적으세요.
#   ⚠ 시크릿을 -D 로 넘기지 않습니다. ps -ef 에 그대로 보입니다(CWE-214).
#     접속 정보·시크릿은 ${KLID_ETC}/application.properties 에 있고 여기는 <위치만> 넘깁니다.
KLID_OPTS="-Dspring.config.additional-location=file:${KLID_ETC}/"
KLID_OPTS="\${KLID_OPTS} -Dspring.profiles.active=${SPRING_PROFILE:-prd}"
KLID_OPTS="\${KLID_OPTS} -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
KLID_OPTS="\${KLID_OPTS} -Duser.timezone=Asia/Seoul"
KLID_OPTS="\${KLID_OPTS} -Djava.security.egd=file:/dev/./urandom"
JAVA_OPTS="\${JAVA_OPTS} \${KLID_OPTS}"
export JAVA_OPTS
${END_MARK}
EOF

  # 문법 검증 — 깨진 파일을 남기지 않는다
  if ! sh -n "${STANDALONE_CONF}" 2>/dev/null; then
    cp -p "${_bak}" "${STANDALONE_CONF}"
    die "[conf] 편집 결과가 셸 문법 검사를 통과하지 못해 원본으로 되돌렸습니다: ${_bak}"
  fi
  ok "[conf] JAVA_OPTS 배선 완료: ${STANDALONE_CONF}"
fi

# ---------------------------------------------------------------------------
# 3) WAR 배포
# ---------------------------------------------------------------------------
info "[deploy] ${WAR_SRC} → ${WAR_DST}"

# 이전 시도가 남긴 마커를 치운다 — 남아 있으면 스캐너가 새 배포를 집지 않는다
rm -f "${WAR_DST}.failed" "${WAR_DST}.deployed" "${WAR_DST}.dodeploy" \
      "${WAR_DST}.isdeploying" "${WAR_DST}.pending" "${WAR_DST}.undeployed" 2>/dev/null || true

install -m 0644 -o "${WAS_USER}" -g "${WAS_GROUP}" "${WAR_SRC}" "${WAR_DST}"

# SELinux 문맥 — 배포 디렉터리의 기존 문맥을 따라가게 한다
command -v restorecon >/dev/null 2>&1 && restorecon "${WAR_DST}" 2>/dev/null || true

touch "${WAR_DST}.dodeploy"
chown "${WAS_USER}:${WAS_GROUP}" "${WAR_DST}.dodeploy"
ok "[deploy] 배포 요청 마커 생성: ${WAR_NAME}.dodeploy"

# ---------------------------------------------------------------------------
# 4) 재기동(선택) + 결과 판정
# ---------------------------------------------------------------------------
detect_was_unit() {
  [[ -n "${WAS_UNIT_ARG}" ]] && { printf '%s\n' "${WAS_UNIT_ARG}"; return 0; }
  if [[ -f "${KLID_ETC}/was.env" ]]; then
    local u; u="$(grep -E '^[[:space:]]*WAS_UNIT=' "${KLID_ETC}/was.env" | tail -1 | cut -d= -f2- | tr -d '"'"'"' ' || true)"
    [[ -n "${u}" ]] && { printf '%s\n' "${u}"; return 0; }
  fi
  systemctl list-units --type=service --all --no-legend 2>/dev/null \
    | awk '{print $1}' | grep -iE '^(jboss|eap|wildfly)' | head -n1 | sed 's/\.service$//'
}
WAS_UNIT="$(detect_was_unit || true)"

if [[ "${DO_RESTART}" -eq 1 ]]; then
  [[ -n "${WAS_UNIT}" ]] || die "[restart] WAS 유닛명을 찾지 못했습니다 — --was-unit=<유닛명> 으로 주세요."
  info "[restart] systemctl restart ${WAS_UNIT}"
  systemctl restart "${WAS_UNIT}"
  ok "[restart] 재기동 요청 완료"
else
  info "[restart] 재기동은 하지 않습니다(--restart 를 주면 합니다)."
  info "          JAVA_OPTS 는 JVM 기동 옵션이라 <재기동해야> 반영됩니다."
fi

# 배포 결과를 실제로 기다린다 — "복사했으니 됐다"로 끝내지 않는다
info "[verify] 배포 결과 대기(최대 180초)..."
_deadline=$(( $(date +%s) + 180 ))
_result=""
while [[ "$(date +%s)" -lt "${_deadline}" ]]; do
  if [[ -f "${WAR_DST}.deployed" ]]; then _result="deployed"; break; fi
  if [[ -f "${WAR_DST}.failed"   ]]; then _result="failed";   break; fi
  sleep 2
done

echo
if [[ "${_result}" == "deployed" ]]; then
  ok "[verify] 배포 성공 — ${WAR_NAME}.deployed"
elif [[ "${_result}" == "failed" ]]; then
  warn "[verify] ★ 배포 실패 — ${WAR_NAME}.failed"
  echo "──────── 실패 사유 ────────"
  sed -e 's/\\n/\n/g' "${WAR_DST}.failed" 2>/dev/null | head -n 40
  echo "───────────────────────────"
  warn "자주 나는 것:"
  warn "  · LoggerFactory is not a Logback LoggerContext"
  warn "      → WAR 에 WEB-INF/jboss-deployment-structure.xml 이 없다(위 0-3 검사에서 걸렀어야 함)"
  warn "  · DB 접속 실패 / 설정 없음"
  warn "      → ${KLID_ETC}/application.properties 값과 ${WAS_USER} 읽기 권한 확인"
  warn "  · 스키마 없음"
  warn "      → 16-load-schema.sh 로 db/schema.sql 적재"
  warn "        기대 테이블 수는 매체에서 센다: grep -c '^CREATE TABLE klid_at\.' db/schema.sql"
  warn "전체 로그: tail -n 200 ${LOG_DIR}/server.log"
  exit 1
else
  warn "[verify] 180초 안에 .deployed/.failed 가 생기지 않았습니다."
  if [[ "${DO_RESTART}" -eq 0 ]]; then
    warn "        WAS 를 아직 재기동하지 않았다면 정상입니다 — 재기동 후 다시 확인하세요."
  else
    warn "        WAS 기동 자체가 느리거나 실패했을 수 있습니다: ${LOG_DIR}/server.log"
  fi
fi

# ---------------------------------------------------------------------------
# 마무리 안내
# ---------------------------------------------------------------------------
echo
info "[jboss] 남은 단계"
if [[ "${DO_RESTART}" -eq 0 ]]; then
  info "  1) WAS 재기동 — JAVA_OPTS 반영에 필요합니다"
  info "       sudo systemctl restart ${WAS_UNIT:-<WAS 유닛명>}"
fi
info "  2) 옵션이 실제로 걸렸는지 확인 — 네 줄이 나와야 합니다"
info "       ps -ef | grep '[j]boss' | tr ' ' '\\n' | grep -E 'spring.config|spring.profiles|MaxRAM|egd'"
info "  3) undertow/io 설정 — 별도 스크립트가 있습니다(기동 중인 WAS 에 CLI 로 겁니다)"
info "       sudo ./scripts/install/18-jboss-settings.sh --check     # 먼저 현재 값을 본다"
info "       sudo ./scripts/install/18-jboss-settings.sh --apply"
info "  4) 살아 있는지"
info "       curl -i http://127.0.0.1:8080/api/actuator/health/liveness"
echo

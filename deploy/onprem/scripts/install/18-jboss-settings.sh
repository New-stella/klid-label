#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 18-jboss-settings.sh — [대상 서버 A] JBoss EAP 요청 처리 설정 (undertow · io)
#
#   17-deploy-jboss.sh 가 <배포와 JVM 옵션>을 맡고, 이 스크립트가 <기동 중인 WAS 의 설정>을 맡는다.
#   둘을 나눈 이유: 이쪽은 WAS 가 <떠 있어야> 걸 수 있고(jboss-cli 관리 포트 접속),
#   실패해도 배포를 되돌릴 필요가 없다.
#
#   무엇을 거는가 (docs/10-was-settings.md 1·2·4절)
#     · undertow http-listener  max-post-size            대용량 업로드 본문 한도
#     · io worker               task-max-threads         요청 스레드 예산
#     · undertow http-listener  proxy-address-forwarding <반드시 false> — 보안
#
#   ★ 기본은 <읽기만> 한다. 바꾸려면 --apply 를 명시해야 한다.
#     같은 WAS 에 다른 애플리케이션이 있을 수 있어, 남의 설정을 말없이 바꾸지 않는다.
#
#   사용법
#     sudo ./scripts/install/18-jboss-settings.sh --check          # 현재 값만 본다(기본)
#     sudo ./scripts/install/18-jboss-settings.sh --apply          # 실제로 건다
#     sudo ./scripts/install/18-jboss-settings.sh --apply --threads=400
#     옵션: --jboss-home=<경로> --max-post-size=<바이트> --threads=<수>
#           --user=<관리자> --password=<암호>   (원격/인증이 필요한 경우에만)
#
#   ⚠ 스레드 예산은 <WAS 전체>에 걸린다. 이 스크립트는 <현재 값보다 작게 내리지 않는다> —
#     다른 애플리케이션의 예산을 우리가 줄이면 안 되기 때문이다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
require_root

KLID_ETC="${KLID_ETC:-/etc/klid}"

JBOSS_HOME_ARG=""
MODE="check"
# 앞단 httpd 의 LimitRequestBody 와 같은 값(config/frontend/httpd-klid.conf.template = 1.2GB).
#   ⚠ 실제 상한은 둘 중 <작은 쪽>이다. 한쪽만 키우면 다른 쪽에서 잘린다.
MAX_POST_SIZE="${MAX_POST_SIZE:-1258291200}"
# 애플리케이션의 server.tomcat.threads.max 와 같은 값(동작 변화 없음, 예산을 <명시>하는 것이 목적).
WANT_THREADS="${WANT_THREADS:-200}"
CLI_USER=""
CLI_PASS=""

for arg in "$@"; do
  case "${arg}" in
    --check)             MODE="check" ;;
    --apply)             MODE="apply" ;;
    --jboss-home=*)      JBOSS_HOME_ARG="${arg#*=}" ;;
    --max-post-size=*)   MAX_POST_SIZE="${arg#*=}" ;;
    --threads=*)         WANT_THREADS="${arg#*=}" ;;
    --user=*)            CLI_USER="${arg#*=}" ;;
    --password=*)        CLI_PASS="${arg#*=}" ;;
    --help|-h)           sed -n '3,28p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "알 수 없는 옵션: ${arg} (--help 로 사용법)" ;;
  esac
done

[[ "${MAX_POST_SIZE}" =~ ^[0-9]+$ ]] || die "--max-post-size 는 바이트 수여야 합니다: ${MAX_POST_SIZE}"
[[ "${WANT_THREADS}"  =~ ^[0-9]+$ ]] || die "--threads 는 정수여야 합니다: ${WANT_THREADS}"

# ---- JBOSS_HOME ----
detect_jboss_home() {
  local h=""
  [[ -n "${JBOSS_HOME_ARG}" ]] && { printf '%s\n' "${JBOSS_HOME_ARG}"; return 0; }
  h="$(ps -eo args= 2>/dev/null | tr ' ' '\n' | grep -m1 -- '-Djboss.home.dir=' | cut -d= -f2- || true)"
  [[ -n "${h}" && -d "${h}" ]] && { printf '%s\n' "${h}"; return 0; }
  if [[ -f "${KLID_ETC}/was.env" ]]; then
    h="$(grep -E '^[[:space:]]*WAS_HOME=' "${KLID_ETC}/was.env" | tail -1 | cut -d= -f2- | tr -d '"'"'"' ' || true)"
    [[ -n "${h}" && -d "${h}" ]] && { printf '%s\n' "${h}"; return 0; }
  fi
  local c
  for c in /GCLOUD/JBOSS/jboss-eap-* /opt/jboss-eap-* /opt/wildfly*; do
    [[ -d "${c}/bin" ]] && { printf '%s\n' "${c}"; return 0; }
  done
  return 1
}
JBOSS_HOME="$(detect_jboss_home || true)"
[[ -n "${JBOSS_HOME}" ]] || die "JBOSS_HOME 을 찾지 못했습니다 — --jboss-home=<경로> 로 주세요."
CLI="${JBOSS_HOME}/bin/jboss-cli.sh"
[[ -x "${CLI}" ]] || die "jboss-cli.sh 가 없거나 실행 권한이 없습니다: ${CLI}"

# WAS 실행 계정으로 CLI 를 돌린다 — root 로 돌리면 로컬 silent 인증이 안 될 수 있고
# CLI 가 남기는 파일(.jbossclirc·history)의 소유가 어긋난다.
WAS_USER="$(ps -eo user=,args= 2>/dev/null | grep 'jboss-modules.jar' | awk '$1!="root"{print $1; exit}' || true)"
[[ -n "${WAS_USER}" ]] || WAS_USER="$(stat -c '%U' "${JBOSS_HOME}/bin" 2>/dev/null || echo jboss)"

CLI_AUTH=()
[[ -n "${CLI_USER}" ]] && CLI_AUTH+=(--user="${CLI_USER}")
[[ -n "${CLI_PASS}" ]] && CLI_AUTH+=(--password="${CLI_PASS}")

run_cli() {  # run_cli <명령>  — 출력 그대로, 실패해도 죽지 않는다
  sudo -u "${WAS_USER}" "${CLI}" --connect "${CLI_AUTH[@]}" --command="$1" 2>&1 || true
}

# WAS 가 떠 있는지 먼저 본다 — 안 떠 있으면 CLI 가 붙지 못한다
_probe="$(run_cli ':read-attribute(name=server-state)')"
if ! printf '%s' "${_probe}" | grep -q '"running"'; then
  warn "[cli] 관리 인터페이스에 붙지 못했습니다. WAS 가 떠 있어야 이 단계를 수행할 수 있습니다."
  echo "──── jboss-cli 출력 ────"; printf '%s\n' "${_probe}" | tail -n 6; echo "────────────────────────"
  warn "확인:  systemctl status <WAS 유닛명>"
  warn "원격/인증이 필요한 형상이면 --user= --password= 를 주세요."
  warn "그래도 안 되면 아래 CLI 명령을 <사람이> 직접 실행하면 됩니다:"
  echo
  cat <<EOF
  ${CLI} --connect
  /subsystem=undertow/server=default-server/http-listener=default:write-attribute(name=max-post-size,value=${MAX_POST_SIZE})
  /subsystem=undertow/server=default-server/http-listener=default:read-attribute(name=proxy-address-forwarding)
  /subsystem=io/worker=default:write-attribute(name=task-max-threads,value=${WANT_THREADS})
  :reload
EOF
  exit 1
fi
ok "[cli] 관리 인터페이스 연결 OK (실행 계정 ${WAS_USER})"

attr() {  # attr <경로> <속성> — 값만 뽑는다
  run_cli "$1:read-attribute(name=$2)" \
    | grep -m1 '"result"' | sed 's/.*=> *//; s/^"//; s/"$//' | tr -d ' '
}

LISTENER='/subsystem=undertow/server=default-server/http-listener=default'
WORKER='/subsystem=io/worker=default'

CUR_POST="$(attr "${LISTENER}" max-post-size)"
CUR_PROXY="$(attr "${LISTENER}" proxy-address-forwarding)"
CUR_THREADS="$(attr "${WORKER}" task-max-threads)"

echo
info "[현재 값]"
echo "  max-post-size            : ${CUR_POST:-?}"
echo "  proxy-address-forwarding : ${CUR_PROXY:-?}"
echo "  task-max-threads         : ${CUR_THREADS:-?}"
echo
info "[목표 값]"
echo "  max-post-size            : ${MAX_POST_SIZE}"
echo "  proxy-address-forwarding : false   (★ 보안 — 켜져 있으면 반드시 끈다)"
echo "  task-max-threads         : ${WANT_THREADS} 이상 (현재가 더 크면 <그대로 둔다>)"
echo

# ---- 보안 판정은 check 모드에서도 <크게> 알린다 ----
if [[ "${CUR_PROXY}" == "true" ]]; then
  warn "════════════════════════════════════════════════════════════════"
  warn "★ proxy-address-forwarding 이 켜져 있습니다 — 보안 방어가 무력화됩니다."
  warn "  getRemoteAddr() 이 X-Forwarded-For 값으로 무검증 치환되어, 신뢰 프록시 대조와"
  warn "  웹훅 실패 횟수 제한이 헤더 한 줄 회전으로 우회됩니다(CWE-348 / CWE-307)."
  warn "  앱 쪽 가드는 WAS 설정 파일을 볼 수 없어 이 경우를 잡지 못합니다."
  warn "  ⚠ 톰캣의 RemoteIpValve 에 해당하는 것이며 <이름이 달라> 그냥 보면 놓칩니다."
  warn "════════════════════════════════════════════════════════════════"
fi

if [[ "${MODE}" == "check" ]]; then
  info "[check] 아무것도 바꾸지 않았습니다. 적용하려면 --apply 를 주세요."
  exit 0
fi

# ---- 적용 ----
NEED_RELOAD=0

if [[ "${CUR_POST}" != "${MAX_POST_SIZE}" ]]; then
  info "[apply] max-post-size ${CUR_POST:-?} → ${MAX_POST_SIZE}"
  _r="$(run_cli "${LISTENER}:write-attribute(name=max-post-size,value=${MAX_POST_SIZE})")"
  printf '%s' "${_r}" | grep -q '"success"' \
    && { ok "[apply] max-post-size 적용"; NEED_RELOAD=1; } \
    || { warn "[apply] max-post-size 실패:"; printf '%s\n' "${_r}" | tail -n 4; }
else
  ok "[apply] max-post-size 이미 목표값 — 건너뜀"
fi

if [[ "${CUR_PROXY}" == "true" ]]; then
  info "[apply] proxy-address-forwarding true → false (보안)"
  _r="$(run_cli "${LISTENER}:write-attribute(name=proxy-address-forwarding,value=false)")"
  printf '%s' "${_r}" | grep -q '"success"' \
    && { ok "[apply] proxy-address-forwarding 끔"; NEED_RELOAD=1; } \
    || { warn "[apply] proxy-address-forwarding 변경 실패:"; printf '%s\n' "${_r}" | tail -n 4; }
else
  ok "[apply] proxy-address-forwarding = ${CUR_PROXY:-false} — 안전"
fi

# ★ 스레드는 <올리는 방향으로만> 바꾼다. 남의 예산을 우리가 줄이지 않는다.
if [[ "${CUR_THREADS}" =~ ^[0-9]+$ ]] && [[ "${CUR_THREADS}" -ge "${WANT_THREADS}" ]]; then
  ok "[apply] task-max-threads=${CUR_THREADS} (목표 ${WANT_THREADS} 이상) — 그대로 둡니다"
else
  info "[apply] task-max-threads ${CUR_THREADS:-미설정} → ${WANT_THREADS}"
  _r="$(run_cli "${WORKER}:write-attribute(name=task-max-threads,value=${WANT_THREADS})")"
  printf '%s' "${_r}" | grep -q '"success"' \
    && { ok "[apply] task-max-threads 적용"; NEED_RELOAD=1; } \
    || { warn "[apply] task-max-threads 실패:"; printf '%s\n' "${_r}" | tail -n 4; }
fi

echo
if [[ "${NEED_RELOAD}" -eq 1 ]]; then
  info "[apply] 변경 사항 반영을 위해 리로드합니다(:reload — 재기동보다 가볍습니다)"
  run_cli ':reload' >/dev/null
  ok "[apply] reload 요청 완료"
  info "        standalone.xml 이 갱신되고 직전 판은 configuration/standalone_xml_history/ 에 남습니다."
else
  ok "[apply] 바뀐 것이 없어 리로드하지 않았습니다."
fi

echo
info "확인 — 이 스크립트를 --check 로 다시 돌리면 반영 결과가 보입니다."
info "★ 업로드 한도는 <기동 성공으로 검증되지 않습니다> — 대용량 업로드를 실제로 1회 수행하세요."

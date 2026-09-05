#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# klid-jboss-fix.sh — 이미 배포된 WAR 에 JBoss EAP 서술자를 주입하고 재배포한다
#
#   증상: deployments/*.war.failed 에 아래가 남고 배포가 실패한다.
#     LoggerFactory is not a Logback LoggerContext but Logback is on the classpath
#     (org.slf4j.impl.Slf4jLoggerFactory from …/slf4j-jboss-logmanager-…jar)
#
#   원인: EAP 로깅 서브시스템이 배포물에 자기 slf4j 구현을 얹어 WAR 안 logback 과 충돌한다.
#         WAR 에 WEB-INF/jboss-deployment-structure.xml 이 없을 때 난다.
#
#   ★ 이 스크립트는 <현장 응급 조치>다. 정본은 서술자가 포함된 채로 빌드된 WAR 이며,
#     새 반입 매체(klid-at-...-jboss-...)의 api.war 에는 이미 들어 있다.
#     여기서 주입하면 매체 SHA256 과 어긋나므로 배포 기록에 남길 것.
#
#   사용법
#     chmod +x klid-jboss-fix.sh
#     sudo ./klid-jboss-fix.sh                 # 자동 탐지 후 주입·재배포
#     sudo ./klid-jboss-fix.sh --check         # 아무것도 바꾸지 않고 상태만 본다
#     옵션: --jboss-home=<경로>  --war=<파일명>
# ============================================================================

JBOSS_HOME_ARG=""; WAR_ARG=""; CHECK=0
# ★ 웹 컨텍스트 — WAR 안 jboss-web.xml 이 정하는 값과 같아야 한다(현장: /label-studio).
#   여기서 갈리면 헬스체크가 404 를 받고 <설치가 실패한 것처럼> 보인다.
APP_CONTEXT="${APP_CONTEXT:-/label-studio}"

for a in "$@"; do
  case "$a" in
    --jboss-home=*) JBOSS_HOME_ARG="${a#*=}" ;;
    --war=*)        WAR_ARG="${a#*=}" ;;
    --check)        CHECK=1 ;;
    --help|-h)      sed -n '3,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "알 수 없는 옵션: $a" >&2; exit 1 ;;
  esac
done
[[ "${EUID:-$(id -u)}" -eq 0 ]] || { echo "✗ root 로 실행하세요 (sudo)" >&2; exit 1; }

# ---- JBOSS_HOME ----
JH="${JBOSS_HOME_ARG}"
if [[ -z "${JH}" ]]; then
  JH="$(ps -eo args= 2>/dev/null | tr ' ' '\n' | grep -m1 -- '-Djboss.home.dir=' | cut -d= -f2- || true)"
fi
if [[ -z "${JH}" ]]; then
  for c in /GCLOUD/JBOSS/jboss-eap-* /opt/jboss-eap-* /opt/wildfly*; do
    [[ -d "${c}/standalone/deployments" ]] && { JH="${c}"; break; }
  done
fi
[[ -n "${JH}" && -d "${JH}/standalone/deployments" ]] \
  || { echo "✗ JBOSS_HOME 을 찾지 못했습니다 — --jboss-home=<경로> 로 주세요." >&2; exit 1; }

D="${JH}/standalone/deployments"
LOG="${JH}/standalone/log/server.log"
echo "== JBOSS_HOME : ${JH}"

# ---- 대상 WAR ----
#   ★ 이름에 기대지 않는다. 우리 애플리케이션 클래스가 들어 있는 WAR 를 찾는다.
WAR=""
if [[ -n "${WAR_ARG}" ]]; then
  WAR="${WAR_ARG}"
else
  shopt -s nullglob
  for f in "${D}"/*.war; do
    if unzip -l "${f}" 2>/dev/null | grep -qF 'WEB-INF/classes/kr/co/cudo/authoring'; then
      WAR="$(basename "${f}")"; break
    fi
  done
  shopt -u nullglob
fi
[[ -n "${WAR}" ]] || { echo "✗ 저작도구 WAR 를 찾지 못했습니다: ${D}
   --war=<파일명> 으로 지정하거나, 매체의 api.war 를 먼저 복사하세요." >&2; exit 1; }
echo "== 대상 WAR   : ${WAR}"

_list="$(unzip -l "${D}/${WAR}" 2>/dev/null || true)"
_has_ds=0; printf '%s' "${_list}" | grep -qF 'WEB-INF/jboss-deployment-structure.xml' && _has_ds=1
_has_web=0; printf '%s' "${_list}" | grep -qF 'WEB-INF/jboss-web.xml' && _has_web=1
echo "   jboss-deployment-structure.xml : $( [[ ${_has_ds} -eq 1 ]] && echo 있음 || echo '★없음' )"
echo "   jboss-web.xml                  : $( [[ ${_has_web} -eq 1 ]] && echo 있음 || echo '★없음' )"

echo "== 현재 배포 마커"
ls -1 "${D}/${WAR}".* 2>/dev/null | sed 's#.*/#   #' || echo "   (없음)"

if [[ "${CHECK}" -eq 1 ]]; then
  echo; echo "(--check — 아무것도 바꾸지 않았습니다)"; exit 0
fi

if [[ "${_has_ds}" -eq 1 && "${_has_web}" -eq 1 ]]; then
  echo "== 서술자가 이미 둘 다 있습니다 — 주입은 건너뛰고 재배포만 합니다."
else
  command -v zip >/dev/null 2>&1 || command -v jar >/dev/null 2>&1 \
    || { echo "✗ zip 도 jar 도 없습니다 — 둘 중 하나가 필요합니다." >&2; exit 1; }

  W="$(mktemp -d)"; trap 'rm -rf "${W}"' EXIT
  mkdir -p "${W}/WEB-INF"

  cat > "${W}/WEB-INF/jboss-deployment-structure.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!-- klid-label — EAP 로깅 서브시스템이 배포물 로깅을 가로채지 않게 한다.
     이것이 없으면 배포가 LoggerFactory 충돌로 실패하고, 운 좋게 떠도
     logback 설정(민감정보 마스킹 포함)이 무시된다. 지우지 말 것. -->
<jboss-deployment-structure xmlns="urn:jboss:deployment-structure:1.3">
  <deployment>
    <exclude-subsystems>
      <subsystem name="logging"/>
      <subsystem name="jpa"/>
    </exclude-subsystems>
    <exclusions>
      <module name="org.jboss.logging"/>
      <module name="org.slf4j"/>
      <module name="org.slf4j.impl"/>
      <module name="org.apache.commons.logging"/>
      <module name="org.apache.log4j"/>
      <module name="org.jboss.logmanager"/>
    </exclusions>
  </deployment>
</jboss-deployment-structure>
EOF

  cat > "${W}/WEB-INF/jboss-web.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!-- klid-label — 컨텍스트를 /api 로 고정한다(파일명 무관). -->
<jboss-web>
  <context-root>/api</context-root>
</jboss-web>
EOF

  cp "${D}/${WAR}" "${W}/${WAR}"
  ( cd "${W}"
    if command -v zip >/dev/null 2>&1; then
      zip -q -g "${WAR}" WEB-INF/jboss-deployment-structure.xml WEB-INF/jboss-web.xml
    else
      jar uf "${WAR}" WEB-INF/jboss-deployment-structure.xml WEB-INF/jboss-web.xml
    fi )

  # 주입 결과 확인 — 넣었다고 믿지 않는다
  _l2="$(unzip -l "${W}/${WAR}" 2>/dev/null || true)"
  printf '%s' "${_l2}" | grep -qF 'WEB-INF/jboss-deployment-structure.xml' \
    || { echo "✗ 주입에 실패했습니다(서술자가 안 보임)." >&2; exit 1; }
  echo "== 서술자 주입 완료"

  _own="$(stat -c '%U:%G' "${D}/${WAR}" 2>/dev/null || echo jboss:jboss)"
  cp -f "${W}/${WAR}" "${D}/${WAR}"
  chown "${_own}" "${D}/${WAR}" 2>/dev/null || true
  command -v restorecon >/dev/null 2>&1 && restorecon "${D}/${WAR}" 2>/dev/null || true
fi

# ---- 잔재 마커 정리 후 재배포 ----
#   ★ .failed 가 남아 있으면 스캐너가 새 배포를 집지 않는다. 옛 이름의 마커도 함께 치운다.
rm -f "${D}/${WAR}".failed "${D}/${WAR}".deployed "${D}/${WAR}".isdeploying \
      "${D}/${WAR}".pending "${D}/${WAR}".undeployed 2>/dev/null || true
rm -f "${D}"/klid-at-api.war.failed 2>/dev/null || true

_own="$(stat -c '%U:%G' "${D}/${WAR}" 2>/dev/null || echo jboss:jboss)"
touch "${D}/${WAR}.dodeploy"; chown "${_own}" "${D}/${WAR}.dodeploy" 2>/dev/null || true
echo "== 배포 요청: ${WAR}.dodeploy"

# ---- 결과를 실제로 기다린다 ----
echo -n "== 대기"
for _ in $(seq 1 60); do
  [[ -f "${D}/${WAR}.deployed" ]] && { echo; echo "✓ 배포 성공 — ${WAR}.deployed"; break; }
  [[ -f "${D}/${WAR}.failed"   ]] && { echo; echo "✗ 배포 실패 — ${WAR}.failed"; echo "──── 사유 ────"
        sed -e 's/\\n/\n/g' "${D}/${WAR}.failed" | head -n 25; echo "──────────────"; break; }
  echo -n "."; sleep 2
done
echo

if [[ -f "${D}/${WAR}.deployed" ]]; then
  echo "== 헬스 확인"
  _c="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:8080${APP_CONTEXT}/api/actuator/health/liveness 2>/dev/null || echo 000)"
  echo "   /api/actuator/health/liveness → ${_c}"
  if [[ "${_c}" != "200" ]]; then
    cat <<'EOF'

   ⚠ 배포는 됐는데 응답이 200 이 아닙니다. 흔한 원인 둘:

   1) JAVA_OPTS 미적용 — 지금 도는 WAS 프로세스가 오래전에 떠서 standalone.conf 를
      못 읽었을 수 있습니다. 확인:
        ps -ef | grep '[j]boss' | tr ' ' '\n' | grep -c spring.config
      0 이면 앱이 /etc/klid/application.properties 를 아예 안 읽고 뜬 것이라
      DB 접속에 실패합니다 → WAS 재기동이 필요합니다.

   2) DB 설정/스키마 — CONTROL_DB_* 값과 스키마 적재 여부를 확인하세요.
        grep -E '^CONTROL_DB_' /etc/klid/application.properties
EOF
  fi
fi

echo
echo "== 로그 끝부분"
tail -n 25 "${LOG}" 2>/dev/null | sed 's/^/   /'

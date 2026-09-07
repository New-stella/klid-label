#!/usr/bin/env bash
# ============================================================================
# collect-site-info.sh — [대상 서버] 현장 정보 한 번에 수집 (읽기 전용)
#
#   ★ 아무것도 바꾸지 않는다. 설치·설정·기동 어느 것도 건드리지 않고 <보기만> 한다.
#     그래서 set -e 를 쓰지 않는다 — 항목 하나가 없어도 나머지를 계속 수집해야 한다.
#
#   왜 필요한가
#     설치가 막힐 때마다 명령 한 줄씩 주고받으면 왕복이 길어진다. 막히기 <전에> 필요한 값을
#     한 번에 모아 두면, 무엇이 없어서 막힐지도 미리 드러난다.
#
#   사용법
#     ./scripts/collect-site-info.sh                    # 화면 출력
#     ./scripts/collect-site-info.sh > site-info.txt    # 파일로 저장해 전달
#     sudo ./scripts/collect-site-info.sh               # ★ root 로 돌리면 더 많이 본다
#                                                       #   (권한 시험·설정 파일 존재 등)
#
#   ⚠ 비밀번호·시크릿은 <값을 찍지 않는다>. "설정됨/비어 있음" 만 표시한다.
#     그래도 출력에는 내부 IP·경로·계정명이 들어가니 외부로 보낼 때는 확인하고 보낼 것.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ONPREM="$(cd "${SELF_DIR}/.." && pwd)"
KLID_ETC="${KLID_ETC:-/etc/klid}"

sec() { printf '\n\n══════ %s ══════\n' "$*"; }
row() { printf '  %-26s %s\n' "$1" "${2:-—}"; }
has() { command -v "$1" >/dev/null 2>&1; }
AM_ROOT=0; [[ "${EUID:-$(id -u)}" -eq 0 ]] && AM_ROOT=1

echo "klid-label 현장 정보 — $(date '+%Y-%m-%d %H:%M:%S')"
echo "수집 위치: $(hostname) / 수집 계정: $(id -un)$( [[ ${AM_ROOT} -eq 0 ]] && echo '  ⚠ root 가 아니라 일부 항목을 못 봅니다' )"

# ---------------------------------------------------------------------------
sec "1. OS · 기반"
row "호스트명"      "$(hostname -f 2>/dev/null || hostname)"
row "배포판"        "$(cat /etc/redhat-release 2>/dev/null || grep -m1 PRETTY_NAME /etc/os-release 2>/dev/null | cut -d= -f2- | tr -d '"')"
row "커널/아키텍처" "$(uname -srm)"
row "glibc"         "$(ldd --version 2>/dev/null | head -n1 | awk '{print $NF}')"
row "SELinux"       "$(getenforce 2>/dev/null || echo '(getenforce 없음)')"
row "메모리"        "$(free -h 2>/dev/null | awk '/^Mem:/{print $2" (사용 "$3")"}')"
row "CPU"           "$(nproc 2>/dev/null) 코어"
echo "  디스크:"
df -h / /opt /var /nas-storage /nas-storage1 2>/dev/null | sed 's/^/    /' | sort -u

# ---------------------------------------------------------------------------
sec "2. WAS (JBoss EAP)"
_ps="$(ps -eo user=,pid=,args= 2>/dev/null | grep 'jboss-modules.jar' | grep -v grep | head -n1 || true)"
if [[ -n "${_ps}" ]]; then
  row "실행 중"      "예"
  row "실행 계정"    "$(printf '%s' "${_ps}" | awk '{print $1}')"
  JBOSS_HOME="$(printf '%s' "${_ps}" | tr ' ' '\n' | grep -m1 -- '-Djboss.home.dir=' | cut -d= -f2- || true)"
  row "JBOSS_HOME"   "${JBOSS_HOME}"
  row "모드"         "$(printf '%s' "${_ps}" | grep -qi 'org.jboss.as.standalone' && echo standalone || echo '★domain? — 배포 절차가 다릅니다')"
  row "server-config" "$(printf '%s' "${_ps}" | tr ' ' '\n' | grep -m1 -E '^(--server-config=|-c$)' | cut -d= -f2-)"
  row "자바 실행파일" "$(printf '%s' "${_ps}" | awk '{print $3}')"
else
  row "실행 중"      "★아니오 — WAS 가 내려가 있습니다"
  for c in /GCLOUD/JBOSS/jboss-eap-* /opt/jboss-eap-* /opt/wildfly*; do
    [[ -d "${c}/standalone" ]] && { JBOSS_HOME="${c}"; break; }
  done
  row "JBOSS_HOME(추정)" "${JBOSS_HOME:-찾지 못함}"
fi

if [[ -n "${JBOSS_HOME}" && -d "${JBOSS_HOME}" ]]; then
  row "version.txt"  "$(head -n1 "${JBOSS_HOME}/version.txt" 2>/dev/null || echo '(없음)')"
  # ★ 판정은 판번호가 아니라 모듈 존재로 — 이것이 배포 가능 여부를 가른다
  _mb="${JBOSS_HOME}/modules/system/layers/base"
  if   [[ -d "${_mb}/jakarta/servlet/api/main" ]]; then row "서블릿 네임스페이스" "jakarta.* (EE9+) → 배포 가능 ✓"
  elif [[ -d "${_mb}/javax/servlet/api/main"   ]]; then row "서블릿 네임스페이스" "★javax.* (EE8) → 우리 WAR 배포 불가"
  else row "서블릿 네임스페이스" "판정 실패(경로 구조 상이)"; fi
  row "standalone.conf" "$( [[ -f "${JBOSS_HOME}/bin/standalone.conf" ]] && echo 있음 || echo '★없음' )"
  row "klid JAVA_OPTS 블록" "$(grep -q '^# >>> klid-label' "${JBOSS_HOME}/bin/standalone.conf" 2>/dev/null && echo '배선됨' || echo '없음(17단계가 넣습니다)')"
  echo "  배포 디렉터리 (${JBOSS_HOME}/standalone/deployments):"
  ls -l "${JBOSS_HOME}/standalone/deployments" 2>/dev/null | sed 's/^/    /' | tail -n 15
  echo "  실패 마커 내용(있으면):"
  for f in "${JBOSS_HOME}/standalone/deployments"/*.failed; do
    [[ -e "${f}" ]] || continue
    echo "    ── $(basename "${f}")"
    sed -e 's/\\n/\n/g' "${f}" 2>/dev/null | head -n 8 | sed 's/^/      /'
  done
fi
row "WAS systemd 유닛" "$(systemctl list-units --type=service --all --no-legend 2>/dev/null | awk '{print $1}' | grep -iE '^(jboss|eap|wildfly)' | head -n3 | tr '\n' ' ')"
echo "  유닛이 JAVA_OPTS 를 따로 주는지(주면 standalone.conf 가 무시될 수 있음):"
for u in $(systemctl list-units --type=service --all --no-legend 2>/dev/null | awk '{print $1}' | grep -iE '^(jboss|eap|wildfly)' | head -n2); do
  systemctl cat "${u}" 2>/dev/null | grep -iE 'EnvironmentFile|JAVA_OPTS|ExecStart' | sed "s/^/    [${u}] /"
done

# ---------------------------------------------------------------------------
sec "3. 자바"
row "java (PATH)"   "$(java -version 2>&1 | head -n1)"
row "JAVA_HOME"     "${JAVA_HOME:-(미설정)}"
ls -d /usr/lib/jvm/* 2>/dev/null | sed 's/^/    설치본: /'

# ---------------------------------------------------------------------------
sec "4. 웹 서버 (httpd)"
row "httpd 설치"    "$(has httpd && httpd -v 2>/dev/null | head -n1 || echo '★없음')"
row "httpd 실행"    "$(systemctl is-active httpd 2>/dev/null || echo 'inactive/없음')"
row "실행 계정"     "$(ps -eo user=,args= 2>/dev/null | grep '[h]ttpd' | awk '{print $1}' | sort -u | tr '\n' ' ')"
row "klid 설정"     "$(ls /etc/httpd/conf.d/*klid* 2>/dev/null | tr '\n' ' ' || echo '없음')"
row "정적 dist"     "$( [[ -d /opt/klid/web/dist ]] && echo "있음 ($(find /opt/klid/web/dist -type f 2>/dev/null | wc -l | tr -d ' ') 파일)" || echo '★없음' )"

# ---------------------------------------------------------------------------
sec "5. 설치물 · 설정"
row "${KLID_ETC}"   "$( [[ -d "${KLID_ETC}" ]] && echo 있음 || echo '★없음 — install.sh 미실행' )"
if [[ -d "${KLID_ETC}" ]]; then ls -l "${KLID_ETC}" 2>/dev/null | sed 's/^/    /'; fi
_props="${KLID_ETC}/application.properties"
if [[ -f "${_props}" ]]; then
  # ★ 값은 찍지 않는다 — 비밀번호가 들어 있다
  for k in CONTROL_DB_HOST CONTROL_DB_PORT CONTROL_DB_NAME CONTROL_DB_USERNAME AI_SERVER_URL STORAGE_RAW_PATH DEIDENTIFY_API_URL; do
    row "  ${k}" "$(grep -E "^[[:space:]]*${k}=" "${_props}" | tail -1 | cut -d= -f2-)"
  done
  _pw="$(grep -E '^[[:space:]]*CONTROL_DB_PASSWORD=' "${_props}" | tail -1 | cut -d= -f2- || true)"
  row "  CONTROL_DB_PASSWORD" "$( [[ -n "${_pw}" ]] && echo '(설정됨)' || echo '★비어 있음' )"
  # WAS 계정이 읽을 수 있는가 — 권한 표가 아니라 실제로 읽어서 판정한다
  _wu="$(ps -eo user=,args= 2>/dev/null | grep -m1 'jboss-modules.jar' | awk '{print $1}' || true)"
  if [[ ${AM_ROOT} -eq 1 && -n "${_wu}" ]]; then
    row "  ${_wu} 읽기" "$(sudo -u "${_wu}" test -r "${_props}" && echo 'OK' || echo '★불가 — 앱만 조용히 기동 실패합니다')"
  fi
fi
row "/opt/klid"      "$( [[ -d /opt/klid ]] && du -sh /opt/klid 2>/dev/null | cut -f1 || echo '없음' )"
row "/var/lib/klid"  "$( [[ -d /var/lib/klid ]] && stat -c '%U:%G %a' /var/lib/klid 2>/dev/null || echo '없음' )"
row "/var/log/klid"  "$( [[ -d /var/log/klid ]] && stat -c '%U:%G %a' /var/log/klid 2>/dev/null || echo '없음' )"

# ---------------------------------------------------------------------------
sec "6. 저장소 (NAS)"
for p in /nas-storage /nas-storage1; do
  [[ -e "${p}" ]] || { row "${p}" "없음"; continue; }
  row "${p}" "$(stat -c '%U:%G %a' "${p}" 2>/dev/null) · $(df -h "${p}" 2>/dev/null | tail -1 | awk '{print $4" 여유"}')"
  _wu="$(ps -eo user=,args= 2>/dev/null | grep -m1 'jboss-modules.jar' | awk '{print $1}' || true)"
  if [[ ${AM_ROOT} -eq 1 && -n "${_wu}" ]]; then
    row "  ${_wu} 쓰기" "$(sudo -u "${_wu}" test -w "${p}" && echo 'OK' || echo '★불가 — 비식별·프레임추출·산출물이 전부 실패합니다')"
  fi
done
mount | grep -iE 'nfs|cifs|nas' | sed 's/^/    마운트: /'

# ---------------------------------------------------------------------------
sec "7. DB (PostgreSQL)"
row "psql 클라이언트" "$(has psql && psql --version || echo '★없음 — 접속 시험을 못 합니다')"
if [[ -f "${_props}" ]] && has psql; then
  _h="$(grep -E '^[[:space:]]*CONTROL_DB_HOST=' "${_props}" | tail -1 | cut -d= -f2- || true)"
  _p="$(grep -E '^[[:space:]]*CONTROL_DB_PORT=' "${_props}" | tail -1 | cut -d= -f2- || true)"
  _n="$(grep -E '^[[:space:]]*CONTROL_DB_NAME=' "${_props}" | tail -1 | cut -d= -f2- || true)"
  row "설정된 대상" "${_h}:${_p}/${_n}  (접속 시험은 비밀번호가 필요해 여기서 하지 않습니다)"
fi
echo "  포트 도달 확인은 아래를 <직접> 돌려 보세요(주소는 현장값):"
echo "    timeout 3 bash -c '</dev/tcp/10.177.199.148/19999' && echo OK || echo 불가"
echo "  매체 schema.sql 기대 개수:"
if [[ -f "${ONPREM}/db/schema.sql" ]]; then
  _t="$(grep -c '^CREATE TABLE klid_at\.' "${ONPREM}/db/schema.sql" || true)"
  _v="$(grep -c '^CREATE VIEW klid_at\.'  "${ONPREM}/db/schema.sql" || true)"
  row "  테이블 / 뷰" "${_t} / ${_v}  → information_schema.tables 기대 $(( _t + _v ))"
else
  row "  schema.sql" "★매체에 없음"
fi

# ---------------------------------------------------------------------------
sec "8. AI 서버 · 외부 연동 도달성"
row "ffmpeg"   "$(has ffmpeg  && ffmpeg  -version 2>/dev/null | head -n1 || echo '★없음 (서버 A 전제조건)')"
row "ffprobe"  "$(has ffprobe && ffprobe -version 2>/dev/null | head -n1 || echo '★없음')"
probe() {  # probe <라벨> <host> <port>
  local r; timeout 3 bash -c "</dev/tcp/$2/$3" 2>/dev/null && r="도달" || r="★불가"
  row "$1" "$2:$3 — ${r}"
}
if [[ -f "${_props}" ]]; then
  _ai="$(grep -E '^[[:space:]]*AI_SERVER_URL=' "${_props}" | tail -1 | cut -d= -f2- | sed 's#^https\?://##' || true)"
  [[ -n "${_ai}" ]] && probe "AI 서버(설정값)" "${_ai%%:*}" "$(printf '%s' "${_ai#*:}" | cut -d/ -f1)"
fi
probe "AI GPU 1" 10.177.33.162 9300
probe "AI GPU 2" 10.177.33.163 9300
echo "  ⚠ AI 서버는 별도 장비(--role=ai)에 따로 설치해야 합니다. 도달 불가면 아직 미설치일 수 있습니다."

# ---------------------------------------------------------------------------
sec "9. 포트 점유"
if has ss; then ss -lntp 2>/dev/null | grep -E ':(80|8080|8443|9300|9990|5432|19999)\b' | sed 's/^/    /'
elif has netstat; then netstat -lntp 2>/dev/null | grep -E ':(80|8080|9300|9990|5432)\b' | sed 's/^/    /'
else echo "    (ss/netstat 없음)"; fi

# ---------------------------------------------------------------------------
sec "10. 애플리케이션 살아 있는지"
for u in "http://127.0.0.1:8080/api/actuator/health/liveness" "http://127.0.0.1/" "http://127.0.0.1:9300/health"; do
  if has curl; then
    row "${u}" "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${u}" 2>/dev/null || echo '연결 실패')"
  fi
done

# ---------------------------------------------------------------------------
sec "11. 반입 매체"
row "매체 경로"   "${ONPREM}"
row "VERSION.built" "$(grep -E 'git_commit|package_built_at' "${ONPREM}/VERSION.built" 2>/dev/null | tr '\n' ' ')"
row "api.war"     "$( [[ -f "${ONPREM}/artifacts/backend/api.war" ]] && stat -c '%s bytes  %y' "${ONPREM}/artifacts/backend/api.war" 2>/dev/null | cut -c1-40 || echo '★없음' )"
if has unzip && [[ -f "${ONPREM}/artifacts/backend/api.war" ]]; then
  for d in jboss-deployment-structure jboss-web; do
    row "  WEB-INF/${d}.xml" "$(unzip -l "${ONPREM}/artifacts/backend/api.war" 2>/dev/null | grep -q "WEB-INF/${d}.xml" && echo '있음 ✓' || echo '★없음 — 이 WAR 로는 배포가 실패합니다')"
  done
fi
row "매체 쓰기 가능" "$( [[ -w "${ONPREM}" ]] && echo '예' || echo '아니오(읽기 전용 — 패치 적용 시 복사 필요)' )"

echo
echo "══════ 끝 ══════"
echo "이 출력을 그대로 전달하면 다음 단계를 바로 잡을 수 있습니다."
echo "⚠ 내부 IP·경로·계정명이 들어 있으니 외부 전달 시 확인하세요. 비밀번호는 포함돼 있지 않습니다."

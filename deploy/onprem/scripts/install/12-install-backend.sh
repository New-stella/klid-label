#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 12-install-backend.sh — [대상 서버] backend 산출물 배치 + env 템플릿
#
#   ★ 확정 배포 형상 = <외부 WAS 에 api.war 반입> (@design DEPLOY-001 · RUNBOOK-001,
#     2026-08-30 사용자 확정). 대상 장비에는 관제지원시스템 WAS 와 동일 사양인
#     JBoss EAP 8.1 + Java 17 이 이미 돌고 있고, 백엔드 프로세스의 수명주기는 그 WAS 가 갖는다.
#     따라서 이 스크립트는 <배치까지만> 하고 기동하지 않는다. WAS 배포 디렉터리로의 복사와
#     WAS 설정 이관(docs/10-was-settings.md)은 <사람이> 한다.
#
#   ⚠ 구 동작 폐기(2026-08-30) — "klid-backend.jar 를 배치하고 systemd 유닛을 설치해
#     java -jar 로 기동한다". 그 형상(베어메탈)은 아래 토글로만 남는다.
#
#   토글:
#     INSTALL_BACKEND_SYSTEMD_UNIT=1  베어메탈 형상용 systemd 유닛도 설치(기본 0)
#       ⚠ 이 토글은 klid-backend.jar 를 요구한다. 그 jar 은 <반입 대상이 아니라> 기본 매체에
#         없으므로, 빌드머신에서 WITH_BACKEND_JAR=1 ./scripts/package.sh 로 수집한 매체가
#         있어야 한다. 없으면 이 스크립트가 사유와 함께 <실패>한다(조용히 넘기지 않는다).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
require_root

: "${KLID_PREFIX:?install.sh 에서 호출되어야 합니다}"
ONPREM="$(onprem_root)"
APP_DIR="${KLID_PREFIX}/app"
ensure_dir "${APP_DIR}"

# ---- WAR 배치 (반입 정본) ----
#   ★ 파일 이름이 곧 웹 컨텍스트다(@design DEPLOY-001). WAR 배포에서는
#     server.servlet.context-path 가 적용되지 않고 컨테이너가 WAR 파일명으로 컨텍스트를 정한다.
#     현재 API 주소가 전부 /api 하위이므로 api.war 라는 이름을 <절대 바꾸지 않는다>.
WAR_SRC="${ONPREM}/artifacts/backend/api.war"
[[ -f "${WAR_SRC}" ]] || die "backend WAR 없음: ${WAR_SRC} (빌드머신에서 package.sh 를 실행했나요?)"

install -m 0644 "${WAR_SRC}" "${APP_DIR}/api.war"
chown "${KLID_USER}:${KLID_GROUP}" "${APP_DIR}/api.war"
ok "[backend] WAR 배치: ${APP_DIR}/api.war  ($(du -h "${APP_DIR}/api.war" | cut -f1))"

# ---- 실행 가능 JAR — 반입 대상이 아니다 ----
#   @design DEPLOY-001 의 build_artifacts 가 klid-backend.jar 를 <개발 환경 전용>으로 규정한다.
#   ★ 2026-08-30 부터 매체에 <아예 담기지 않는다> — 수집 단계(10-build-backend.sh)가 기본으로
#     만들지도 복사하지도 않는다(WITH_BACKEND_JAR=1 일 때만 담긴다). 그래서 아래 부재 처리는
#     "있을 수도 있는 파일"이 아니라 <기본 상태>를 다룬다.
#   WAR 형상에서는 있더라도 설치하지 않는다(설치하면 WAS 가 띄운 것과 별개의 두 번째 백엔드를
#   실수로 기동할 여지가 생긴다 — 같은 DB 를 두 형상이 물게 된다).
#   베어메탈 형상으로 갈 때만 아래 토글로 배치한다.
#   ★ 부재는 <조용히 넘기지 않는다>. 넘기면 설치는 끝났는데 기동만 안 되는 상태가 되고,
#     운영자는 systemd 의 사유 없는 종료만 보게 된다. 여기서 사유와 복구 명령을 함께 준다.
#   ⚠ WAR 형상(기본)에는 영향이 없다 — 아래 die 는 INSTALL_BACKEND_SYSTEMD_UNIT=1 안에만 있다.
JAR_SRC="${ONPREM}/artifacts/backend/klid-backend.jar"
if [[ "${INSTALL_BACKEND_SYSTEMD_UNIT:-0}" == "1" ]]; then
  if [[ ! -f "${JAR_SRC}" ]]; then
    warn "[backend] 베어메탈 형상(INSTALL_BACKEND_SYSTEMD_UNIT=1)인데 실행 jar 이 매체에 없습니다:"
    warn "            ${JAR_SRC}"
    warn "          손상이 아니라 <형상>입니다 — 이 jar 은 반입 대상이 아니라서 기본 수집에서 빠집니다."
    warn "          빌드머신에서 아래 한 줄로 다시 수집한 뒤 매체를 재전송하고 설치를 다시 하십시오:"
    warn "            WITH_BACKEND_JAR=1 ./scripts/package.sh"
    die   "[backend] 베어메탈 형상용 실행 jar 없음: ${JAR_SRC} (위 명령으로 재수집하세요)"
  fi
  install -m 0644 "${JAR_SRC}" "${APP_DIR}/klid-backend.jar"
  chown "${KLID_USER}:${KLID_GROUP}" "${APP_DIR}/klid-backend.jar"
  warn "[backend] 베어메탈 형상 jar 배치: ${APP_DIR}/klid-backend.jar (WAR 형상에서는 쓰지 않는다)"
elif [[ -f "${JAR_SRC}" ]]; then
  info "[backend] klid-backend.jar 는 개발 환경 전용이라 설치하지 않습니다(WAR 반입 형상)."
fi

# ---- 설정 파일(없을 때만 생성 — 재실행 시 사용자 편집 보존) ----
#   두 형상이 서로 다른 파일을 읽는다. 둘 다 배치하되 <어느 쪽이 쓰이는지>를 명확히 안내한다.
#     · WAR 반입(정본) : /etc/klid/application.properties
#         → WAS 기동 옵션 -Dspring.config.additional-location=file:/etc/klid/ 로 읽힌다.
#         ★ 스프링 자체 설정(SPRING_*)은 이 파일에서 <환경변수 이름 변환이 일어나지 않는다> —
#           점 표기로 적어야 한다(예 SPRING_FLYWAY_ENABLED=false ✗ / spring.flyway.enabled=false ○).
#           틀리면 조용히 무시되어 DBA 가 선적용한 스키마 위에서 마이그레이션이 또 돈다(파일 머리말 실측).
#     · 베어메탈      : /etc/klid/backend.env (systemd EnvironmentFile)
install_config_once() {  # install_config_once <템플릿> <설치경로> <라벨>
  local src="$1" dst="$2" label="$3"
  [[ -f "${src}" ]] || { warn "[backend] ${label} 템플릿 없음(건너뜀): ${src}"; return 0; }
  if [[ -f "${dst}" ]]; then
    info "[backend] ${label} 이미 존재(보존): ${dst}"
  else
    install -m 0640 "${src}" "${dst}"
    chown root:"${KLID_GROUP}" "${dst}"
    ok "[backend] ${label} 템플릿 설치: ${dst}  (★ 설치 후 필수 편집)"
  fi
}

PROPS_DST="${KLID_ETC}/application.properties"
ENV_DST="${KLID_ETC}/backend.env"
install_config_once "${ONPREM}/config/backend/application.properties.template" "${PROPS_DST}" "설정(WAR 형상)"
install_config_once "${ONPREM}/config/backend/env.template"                    "${ENV_DST}"   "env(베어메탈 형상)"

# ---- WAS 현장값 기록 파일(/etc/klid/was.env) ----
#   ★ 앱 설정이 아니다. 운영 런북(docs/09-operations-runbook.md)의 백엔드 조작 명령이
#       source /etc/klid/was.env 2>/dev/null || WAS_UNIT='<WAS 유닛명>'
#     형태로 <셸에서> 읽는 값이다. 백엔드는 systemd 유닛이 아니라 외부 WAS 가 기동 주체라,
#     "백엔드를 재기동하라"가 장비마다 다른 명령이 된다. 그 차이를 흡수하는 자리다.
#   ★ 파일을 <실제로 설치>하는 이유: 이게 없으면 런북의 모든 명령이 자리표시자로 남는다
#     (관례만 문서에 있고 파일은 아무도 만들지 않는 상태였다).
#   ★ 권한을 조이지 않는다(0644) — 비밀값을 담는 파일이 아니고, 운영자가 읽고 고쳐야 한다.
#     ⚠ 그래서 반대로 <여기에 비밀값을 넣으면 안 된다>. 템플릿 주석이 그 구분을 설명한다.
WAS_ENV_DST="${KLID_ETC}/was.env"
WAS_ENV_SRC="${ONPREM}/config/backend/was.env.template"
if [[ -f "${WAS_ENV_DST}" ]]; then
  info "[backend] WAS 현장값 파일 이미 존재(보존): ${WAS_ENV_DST}"
elif [[ -f "${WAS_ENV_SRC}" ]]; then
  install -m 0644 "${WAS_ENV_SRC}" "${WAS_ENV_DST}"
  chown root:"${KLID_GROUP}" "${WAS_ENV_DST}"
  ok "[backend] WAS 현장값 템플릿 설치: ${WAS_ENV_DST}  (★ 설치 후 필수 편집)"
  warn "[backend] ${WAS_ENV_DST} 는 <빈 값>으로 설치됩니다 — 채우지 않으면 운영 런북"
  warn "          (docs/09-operations-runbook.md)의 백엔드 조작 명령이 그대로 동작하지 않습니다."
else
  warn "[backend] WAS 현장값 템플릿 없음(건너뜀): ${WAS_ENV_SRC}"
fi

if [[ "${INSTALL_BACKEND_SYSTEMD_UNIT:-0}" == "1" ]]; then
  info "[backend] 이 형상에서 실제로 쓰이는 것은 ${ENV_DST} 다(systemd EnvironmentFile)."
else
  info "[backend] 이 형상에서 실제로 쓰이는 것은 ${PROPS_DST} 다 — WAS 기동 옵션에 아래를 넣는다:"
  info "            -Dspring.config.additional-location=file:${KLID_ETC}/"
  info "            -Dspring.profiles.active=prd"
  info "          (${ENV_DST} 는 베어메탈 형상용이며 WAS 는 읽지 않는다 — 값은 두 파일이 같아야 한다)"
fi

# ---- systemd 유닛(베어메탈 형상 전용 — 기본 미설치) ----
UNIT_SRC="${ONPREM}/config/systemd/klid-backend.service"
UNIT_DST="${SYSTEMD_DIR}/klid-backend.service"
if [[ "${INSTALL_BACKEND_SYSTEMD_UNIT:-0}" == "1" ]]; then
  sed \
    -e "s#@KLID_USER@#${KLID_USER}#g" \
    -e "s#@KLID_GROUP@#${KLID_GROUP}#g" \
    -e "s#@KLID_PREFIX@#${KLID_PREFIX}#g" \
    -e "s#@KLID_ETC@#${KLID_ETC}#g" \
    -e "s#@KLID_LOG@#${KLID_LOG}#g" \
    -e "s#@KLID_DATA@#${KLID_DATA}#g" \
    -e "s#@STORAGE_RAW_PATH@#${STORAGE_RAW_PATH:-/nas-storage}#g" \
    -e "s#@STORAGE_DEIDENTIFIED_PATH@#${STORAGE_DEIDENTIFIED_PATH:-/nas-storage}#g" \
    "${UNIT_SRC}" > "${UNIT_DST}"
  chmod 0644 "${UNIT_DST}"
  ok "[backend] systemd 유닛 설치(베어메탈 형상): ${UNIT_DST}"
  warn "[backend] 이 유닛은 자바를 @KLID_PREFIX@/runtime/jre 에서 찾는다 — 패키지는 JRE 를 반입하지"
  warn "          않으므로 그 경로에 자바를 따로 마련하지 않으면 기동이 실패한다(유닛 헤더 주석 참조)."
else
  info "[backend] systemd 유닛은 설치하지 않습니다(WAR 반입 형상 — 기동 주체는 WAS)."
  info "          베어메탈 형상이 필요하면 INSTALL_BACKEND_SYSTEMD_UNIT=1 로 재실행하세요."
  info "          ⚠ 그 형상은 klid-backend.jar 를 요구하며, 그 jar 은 반입 대상이 아니라 기본 매체에"
  info "            없습니다 — 빌드머신에서 WITH_BACKEND_JAR=1 ./scripts/package.sh 로 재수집하세요."
fi

# ---- 사람이 해야 하는 남은 단계 (건너뛰면 조용히 깨진다) ----
info "----------------------------------------------------------------"
info "[backend] ★ 남은 필수 단계 — 이 스크립트가 대신 할 수 없다:"
info "  1) ${APP_DIR}/api.war 를 WAS 배포 디렉터리로 복사"
info "     · JBoss EAP: <JBOSS_HOME>/standalone/deployments/ 로 복사 후 api.war.dodeploy 마커 생성"
info "     · 컨텍스트 /api 는 WAR 안 WEB-INF/jboss-web.xml 이 고정한다(EAP). 그 밖 컨테이너는 파일명이 정한다"
info "  2) WAS 기동 옵션 배선: -Dspring.config.additional-location=file:${KLID_ETC}/ · -Dspring.profiles.active=prd"
info "     · JVM 옵션(MaxRAMPercentage·G1GC·egd)도 WAS 의 JAVA_OPTS 로 옮긴다(EAP: bin/standalone.conf)"
info "  3) WAS 설정 이관: docs/10-was-settings.md 의 점검 체크리스트를 끝까지 수행"
info "     · 설정 예시 파일: ${ONPREM}/config/was/ (README.md · standalone.conf.example ·"
info "       standalone-undertow.xml.example)"
info "     · undertow max-post-size · io worker task-max-threads · proxy-address-forwarding=false"
info "       (⚠ 톰캣의 RemoteIpValve 에 해당하는 것이 proxy-address-forwarding 이다 — 이름이 다르다)"
info "     · 빠뜨려도 기동과 일반 요청은 정상이라 배포 시점에는 아무 신호가 없다"
info "       (대용량 업로드에서만 실패한다 — 실제로 1회 올려서 확인할 것)"
info "  4) ${WAS_ENV_DST} 에 WAS 현장값(유닛명·WAS_HOME·로그 경로·실행 계정)을 적는다"
info "     · 운영 런북(docs/09-operations-runbook.md)의 명령이 이 값을 읽는다 —"
info "       비워 두면 런북 명령이 전부 자리표시자로 남는다"
info "     · 앱 설정이 아니다. 비밀값(DB 비밀번호·JWT_SECRET)을 여기 넣지 말 것"
info "----------------------------------------------------------------"

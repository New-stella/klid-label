#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 12-install-backend.sh — [대상 서버] backend jar 배치 + env 템플릿 + systemd 유닛
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
require_root

: "${KLID_PREFIX:?install.sh 에서 호출되어야 합니다}"
ONPREM="$(onprem_root)"
APP_DIR="${KLID_PREFIX}/app"
ensure_dir "${APP_DIR}"

JAR_SRC="${ONPREM}/artifacts/backend/klid-backend.jar"
[[ -f "${JAR_SRC}" ]] || die "backend jar 없음: ${JAR_SRC} (빌드머신에서 package.sh 를 실행했나요?)"

install -m 0644 "${JAR_SRC}" "${APP_DIR}/klid-backend.jar"
chown -R "${KLID_USER}:${KLID_GROUP}" "${APP_DIR}"
ok "[backend] jar 배치: ${APP_DIR}/klid-backend.jar"

# ---- env 파일(없을 때만 생성 — 재실행 시 사용자 편집 보존) ----
ENV_DST="${KLID_ETC}/backend.env"
if [[ -f "${ENV_DST}" ]]; then
  info "[backend] env 이미 존재(보존): ${ENV_DST}"
else
  install -m 0640 "${ONPREM}/config/backend/env.template" "${ENV_DST}"
  chown root:"${KLID_GROUP}" "${ENV_DST}"
  ok "[backend] env 템플릿 설치: ${ENV_DST}  (★ 설치 후 필수 편집)"
fi

# ---- systemd 유닛(런타임 경로/사용자 치환) ----
UNIT_SRC="${ONPREM}/config/systemd/klid-backend.service"
UNIT_DST="${SYSTEMD_DIR}/klid-backend.service"
sed \
  -e "s#@KLID_USER@#${KLID_USER}#g" \
  -e "s#@KLID_GROUP@#${KLID_GROUP}#g" \
  -e "s#@KLID_PREFIX@#${KLID_PREFIX}#g" \
  -e "s#@KLID_ETC@#${KLID_ETC}#g" \
  -e "s#@KLID_LOG@#${KLID_LOG}#g" \
  -e "s#@KLID_DATA@#${KLID_DATA}#g" \
  "${UNIT_SRC}" > "${UNIT_DST}"
chmod 0644 "${UNIT_DST}"
ok "[backend] systemd 유닛 설치: ${UNIT_DST}"

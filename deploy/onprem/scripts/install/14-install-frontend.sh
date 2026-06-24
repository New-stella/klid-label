#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 14-install-frontend.sh — [대상 서버] frontend dist 배치 + Caddy 설정 + systemd 유닛
#
#   기본은 번들된 Caddy 로 정적 서빙 + /api 리버스프록시.
#   기존 nginx 를 쓰려면 USE_NGINX=1 로 nginx.conf 템플릿만 배치(서비스는 미등록).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
require_root

: "${KLID_PREFIX:?install.sh 에서 호출되어야 합니다}"
ONPREM="$(onprem_root)"
WEB_DIR="${KLID_PREFIX}/web"
ensure_dir "${WEB_DIR}"

DIST_SRC="${ONPREM}/artifacts/frontend/dist"
[[ -d "${DIST_SRC}" ]] || die "frontend dist 없음: ${DIST_SRC} (빌드머신에서 package.sh 를 실행했나요?)"

info "[frontend] 정적 자산 배치..."
rm -rf "${WEB_DIR}/dist"
cp -R "${DIST_SRC}" "${WEB_DIR}/dist"
ok "[frontend] dist: ${WEB_DIR}/dist"

if [[ "${USE_NGINX:-0}" == "1" ]]; then
  install -m 0644 "${ONPREM}/config/frontend/nginx.conf.template" "${WEB_DIR}/nginx.conf"
  warn "[frontend] USE_NGINX=1 — nginx.conf 템플릿만 배치했습니다."
  warn "  기존 nginx 사이트에 root=${WEB_DIR}/dist, proxy_pass http://127.0.0.1:8080 으로 반영하세요."
  warn "  klid-frontend(Caddy) systemd 유닛은 등록하지 않습니다."
  chown -R "${KLID_USER}:${KLID_GROUP}" "${WEB_DIR}"
  exit 0
fi

# Caddyfile 배치(root 경로 치환)
sed -e "s#@WEB_ROOT@#${WEB_DIR}/dist#g" \
    "${ONPREM}/config/frontend/Caddyfile.template" > "${WEB_DIR}/Caddyfile"
chmod 0644 "${WEB_DIR}/Caddyfile"
ok "[frontend] Caddyfile: ${WEB_DIR}/Caddyfile"

chown -R "${KLID_USER}:${KLID_GROUP}" "${WEB_DIR}"

# Caddy data/config 디렉토리(비루트 사용자용)
ensure_dir "${KLID_PREFIX}/web/.caddy-data" "${KLID_PREFIX}/web/.caddy-config"
chown -R "${KLID_USER}:${KLID_GROUP}" "${KLID_PREFIX}/web/.caddy-data" "${KLID_PREFIX}/web/.caddy-config"

# systemd 유닛(런타임 caddy 경로/사용자 치환)
# shellcheck source=/dev/null
source "${KLID_PREFIX}/runtime/runtime.env"
UNIT_SRC="${ONPREM}/config/systemd/klid-frontend.service"
UNIT_DST="${SYSTEMD_DIR}/klid-frontend.service"
sed \
  -e "s#@KLID_USER@#${KLID_USER}#g" \
  -e "s#@KLID_GROUP@#${KLID_GROUP}#g" \
  -e "s#@KLID_PREFIX@#${KLID_PREFIX}#g" \
  -e "s#@KLID_CADDY@#${KLID_CADDY}#g" \
  "${UNIT_SRC}" > "${UNIT_DST}"
chmod 0644 "${UNIT_DST}"
ok "[frontend] systemd 유닛 설치: ${UNIT_DST}"

# 80 포트 바인딩 권한(비루트 Caddy 가 80 을 열 수 있도록 capability 부여)
if command -v setcap >/dev/null 2>&1; then
  setcap 'cap_net_bind_service=+ep' "${KLID_CADDY}" \
    && ok "[frontend] caddy 80포트 바인딩 권한 부여(setcap)" \
    || warn "[frontend] setcap 실패 — 80포트 사용 시 root 실행 또는 8080 등 비특권 포트로 변경 필요"
else
  warn "[frontend] setcap 없음 — Caddy 가 80포트를 열려면 추가 권한 설정이 필요할 수 있습니다."
fi

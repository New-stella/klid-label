#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 20-build-frontend.sh — [빌드머신] frontend(React+Vite) 정적 빌드 + 수집
#
#   ★ Vite 는 VITE_* 변수를 "빌드 시점"에 정적 치환한다. 따라서 API base 와
#     토큰 인입 모드는 여기서 주입해야 한다(대상 서버에서 변경 불가).
#       VITE_API_BASE_URL : 프론트가 호출할 API base. nginx/Caddy 가 /api 를
#                           backend(127.0.0.1:8080)로 프록시하므로 '/api/v1'.
#       VITE_TOKEN_INGRESS: 토큰 인입 모드(기본 all). 운영 정책에 맞게 조정.
#   결과: frontend/dist → artifacts/frontend/dist
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

REPO="$(repo_root)"
ONPREM="$(onprem_root)"
FE_SRC="${REPO}/frontend"
OUT="${ONPREM}/artifacts/frontend"

[[ -d "${FE_SRC}" ]] || die "frontend 디렉토리를 찾을 수 없습니다: ${FE_SRC}"
require_cmd node npm
ensure_dir "${OUT}"

# 빌드 시점 주입 변수(필요 시 환경변수로 override 가능)
export VITE_API_BASE_URL="${VITE_API_BASE_URL:-/api/v1}"
export VITE_TOKEN_INGRESS="${VITE_TOKEN_INGRESS:-all}"
# 온프렘 번들은 dev 라우트(/dev/login·/dev/autolabel-test)를 dist 에 포함하되,
# 실제 게이팅은 BE DEV_LOGIN_ENABLED / DEV_UPLOAD_ENABLED 런타임 토글이 결정한다
# (FE 라우트만 존재, BE off 면 /v1/dev/* 호출 시 404). 관제서버 미기동 브링업 대비.
export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
export VITE_DEV_UPLOAD_ENABLED="${VITE_DEV_UPLOAD_ENABLED:-true}"
info "[frontend] VITE_API_BASE_URL=${VITE_API_BASE_URL} VITE_TOKEN_INGRESS=${VITE_TOKEN_INGRESS} VITE_DEV_LOGIN_ENABLED=${VITE_DEV_LOGIN_ENABLED} VITE_DEV_UPLOAD_ENABLED=${VITE_DEV_UPLOAD_ENABLED}"

info "[frontend] 의존성 설치 (npm ci)..."
( cd "${FE_SRC}" && npm ci )

info "[frontend] 프로덕션 빌드 (npm run build)..."
( cd "${FE_SRC}" && npm run build )

[[ -d "${FE_SRC}/dist" ]] || die "빌드 결과(dist)를 찾을 수 없습니다: ${FE_SRC}/dist"

rm -rf "${OUT}/dist"
cp -R "${FE_SRC}/dist" "${OUT}/dist"
ok "[frontend] 수집: ${OUT}/dist  ($(du -sh "${OUT}/dist" | cut -f1))"

sha256_write "${OUT}/dist"

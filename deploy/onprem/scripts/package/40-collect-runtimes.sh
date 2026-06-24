#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 40-collect-runtimes.sh — [빌드머신] 대상 서버 런타임 바이너리 수집
#
#   대상 서버(폐쇄망)에 JRE/Python/Caddy 가 없다고 가정하고 전부 번들한다.
#   모두 linux x86_64(glibc) 빌드여야 한다(versions.sh 의 URL 참고).
#
#   수집물:
#     runtimes/jdk/    : Temurin JRE 17 (tar.gz)
#     runtimes/python/ : python-build-standalone 3.11 (tar.gz)
#     runtimes/caddy/  : caddy 정적 바이너리 (tar.gz)
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"

ONPREM="$(onprem_root)"
JDK_OUT="${ONPREM}/runtimes/jdk"
PY_OUT="${ONPREM}/runtimes/python"
CADDY_OUT="${ONPREM}/runtimes/caddy"
require_cmd curl
ensure_dir "${JDK_OUT}" "${PY_OUT}" "${CADDY_OUT}"

# download <url> <dest_dir> — 멱등(이미 있으면 생략)
download() {
  local url="$1" dest_dir="$2"
  local fname; fname="$(basename "${url%%\?*}")"
  local out="${dest_dir}/${fname}"
  if [[ -s "${out}" ]]; then
    info "이미 존재(생략): ${out}"
    return 0
  fi
  info "다운로드: ${url}"
  curl -fL --retry 3 --proto '=https' -o "${out}.part" "${url}" \
    && mv "${out}.part" "${out}" \
    || { rm -f "${out}.part"; die "다운로드 실패: ${url}"; }
  ok "수집: ${out}  ($(du -h "${out}" | cut -f1))"
}

info "[runtimes] Temurin JRE ${TEMURIN_JRE_VERSION}..."
download "${TEMURIN_JRE_URL}" "${JDK_OUT}"

info "[runtimes] CPython standalone ${PYTHON_STANDALONE_VERSION}..."
download "${PYTHON_STANDALONE_URL}" "${PY_OUT}"

info "[runtimes] Caddy ${CADDY_VERSION}..."
download "${CADDY_URL}" "${CADDY_OUT}"

sha256_write "${JDK_OUT}"
sha256_write "${PY_OUT}"
sha256_write "${CADDY_OUT}"

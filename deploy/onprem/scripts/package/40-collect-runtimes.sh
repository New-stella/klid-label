#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 40-collect-runtimes.sh — [빌드머신] 대상 서버 런타임 바이너리 수집
#
#   대상 서버(폐쇄망)에 JRE/Python 이 없다고 가정하고 번들한다.
#   ★ 웹 서버는 여기서 받지 않는다 — httpd 를 배포판 RPM(syspkgs)으로 설치한다(2026-08-19 전환).
#   모두 linux x86_64(glibc) 빌드여야 한다(versions.sh 의 URL 참고).
#
#   수집물:
#     runtimes/jdk/    : Temurin JRE 17 (tar.gz)
#     runtimes/python/ : python-build-standalone 3.11 (tar.gz)
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"

ONPREM="$(onprem_root)"
JDK_OUT="${ONPREM}/runtimes/jdk"
PY_OUT="${ONPREM}/runtimes/python"
require_cmd curl
ensure_dir "${JDK_OUT}" "${PY_OUT}"

# download <url> <dest_dir> — 멱등(이미 있으면 생략). 받은 파일 경로를 stdout 으로 출력.
download() {
  local url="$1" dest_dir="$2"
  local fname; fname="$(basename "${url%%\?*}")"
  local out="${dest_dir}/${fname}"
  if [[ -s "${out}" ]]; then
    info "이미 존재(생략): ${out}" >&2
    printf '%s\n' "${out}"
    return 0
  fi
  info "다운로드: ${url}" >&2
  curl -fL --retry 3 --proto '=https' -o "${out}.part" "${url}" \
    && mv "${out}.part" "${out}" \
    || { rm -f "${out}.part"; die "다운로드 실패: ${url}"; }
  ok "수집: ${out}  ($(du -h "${out}" | cut -f1))" >&2
  printf '%s\n' "${out}"
}

# 다운로드 직후 공식 체크섬과 대조한다(fail-closed). 미검증(빈 값)이면 강한 warn 후 진행.
info "[runtimes] Temurin JRE ${TEMURIN_JRE_VERSION}..."
JRE_FILE="$(download "${TEMURIN_JRE_URL}" "${JDK_OUT}")"
verify_file_sha256 "${JRE_FILE}" "${TEMURIN_JRE_SHA256:-}" "Temurin JRE ${TEMURIN_JRE_VERSION}"

info "[runtimes] CPython standalone ${PYTHON_STANDALONE_VERSION}..."
PY_FILE="$(download "${PYTHON_STANDALONE_URL}" "${PY_OUT}")"
verify_file_sha256 "${PY_FILE}" "${PYTHON_STANDALONE_SHA256:-}" "CPython ${PYTHON_STANDALONE_VERSION}"


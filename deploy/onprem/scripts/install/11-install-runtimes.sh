#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 11-install-runtimes.sh — [대상 서버] 번들 런타임 설치(JRE/Python/Caddy)
#   + 시스템 패키지(.deb) 오프라인 설치(ffmpeg/libgl1/libglib2.0-0/curl)
#
#   외부 네트워크 호출 없음. 모든 산출물은 runtimes/, syspkgs/ 번들에서 사용.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
require_root

: "${KLID_PREFIX:?install.sh 에서 호출되어야 합니다}"
ONPREM="$(onprem_root)"
RT="${KLID_PREFIX}/runtime"
ensure_dir "${RT}/jre" "${RT}/python" "${RT}/caddy"

# extract_single <src_dir> <dest_dir> — src_dir 의 단일 tar.gz 를 dest 로 풀고
#                                       최상위 1단계 디렉토리를 평탄화
extract_tar_flatten() {
  local src_dir="$1" dest="$2"
  shopt -s nullglob
  local tarballs=("${src_dir}"/*.tar.gz)
  shopt -u nullglob
  [[ "${#tarballs[@]}" -ge 1 ]] || die "압축 파일을 찾을 수 없습니다: ${src_dir}/*.tar.gz"
  local tmp; tmp="$(mktemp -d)"
  tar -xzf "${tarballs[0]}" -C "${tmp}"
  # 최상위가 단일 디렉토리면 그 내용을 dest 로, 아니면 그대로 dest 로
  local entries; entries=("${tmp}"/*)
  if [[ "${#entries[@]}" -eq 1 && -d "${entries[0]}" ]]; then
    rm -rf "${dest:?}"/*
    cp -R "${entries[0]}"/. "${dest}/"
  else
    rm -rf "${dest:?}"/*
    cp -R "${tmp}"/. "${dest}/"
  fi
  rm -rf "${tmp}"
}

# ---- JRE ----
info "[runtime] JRE 설치 → ${RT}/jre"
extract_tar_flatten "${ONPREM}/runtimes/jdk" "${RT}/jre"
[[ -x "${RT}/jre/bin/java" ]] || die "JRE 설치 실패: ${RT}/jre/bin/java 없음"
ok "[runtime] java: $("${RT}/jre/bin/java" -version 2>&1 | head -n1)"

# ---- Python (standalone) ----
info "[runtime] Python 설치 → ${RT}/python"
extract_tar_flatten "${ONPREM}/runtimes/python" "${RT}/python"
PYBIN=""
for cand in "${RT}/python/bin/python3.11" "${RT}/python/bin/python3" "${RT}/python/python/bin/python3"; do
  [[ -x "${cand}" ]] && { PYBIN="${cand}"; break; }
done
[[ -n "${PYBIN}" ]] || die "Python 설치 실패: ${RT}/python/bin/python3* 없음"
ok "[runtime] python: $("${PYBIN}" --version 2>&1)"

# ---- Caddy ----
info "[runtime] Caddy 설치 → ${RT}/caddy"
shopt -s nullglob
caddy_tars=("${ONPREM}/runtimes/caddy"/*.tar.gz)
shopt -u nullglob
if [[ "${#caddy_tars[@]}" -ge 1 ]]; then
  tmp="$(mktemp -d)"
  # tar/install 실패로 조기 종료해도 임시 디렉토리가 잔류하지 않도록 EXIT 트랩 등록.
  trap 'rm -rf "${tmp:-}"' EXIT
  tar -xzf "${caddy_tars[0]}" -C "${tmp}"
  install -m 0755 "${tmp}/caddy" "${RT}/caddy/caddy"
  rm -rf "${tmp}"
  trap - EXIT
elif [[ -f "${ONPREM}/runtimes/caddy/caddy" ]]; then
  install -m 0755 "${ONPREM}/runtimes/caddy/caddy" "${RT}/caddy/caddy"
else
  die "Caddy 바이너리를 찾을 수 없습니다: ${ONPREM}/runtimes/caddy/"
fi
ok "[runtime] caddy: $("${RT}/caddy/caddy" version 2>&1 | head -n1)"

# ---- 시스템 패키지(.deb) 오프라인 설치 ----
shopt -s nullglob
debs=("${ONPREM}/syspkgs/deb"/*.deb)
shopt -u nullglob
if [[ "${#debs[@]}" -ge 1 ]]; then
  if command -v dpkg >/dev/null 2>&1; then
    info "[runtime] 시스템 패키지(.deb) 오프라인 설치: ${#debs[@]} 개"
    # 의존성 순서 문제는 dpkg 반복 + apt-get -f(로컬 캐시) 없이도 -i 묶음 설치로 대개 해소.
    dpkg -i "${debs[@]}" || dpkg -i "${debs[@]}" || warn "[runtime] dpkg -i 일부 미해결 — 06-troubleshooting.md 참고"
  else
    warn "[runtime] dpkg 없음 — .deb 설치 생략. ffmpeg/libgl1/libglib2.0-0 를 수동 설치하세요."
  fi
else
  warn "[runtime] 번들된 .deb 없음 — 대상 OS 에 ffmpeg/ffprobe/libgl1/libglib2.0-0/curl 가"
  warn "          이미 설치돼 있어야 합니다(없으면 backend ffmpeg·ai-server opencv 가 실패)."
fi

# 런타임 위치 기록(다음 스크립트가 참조)
{
  echo "KLID_JAVA=${RT}/jre/bin/java"
  echo "KLID_PYTHON=${PYBIN}"
  echo "KLID_CADDY=${RT}/caddy/caddy"
} > "${KLID_PREFIX}/runtime/runtime.env"
ok "[runtime] 런타임 경로 기록: ${KLID_PREFIX}/runtime/runtime.env"

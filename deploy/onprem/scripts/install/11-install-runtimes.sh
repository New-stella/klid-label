#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 11-install-runtimes.sh — [대상 서버 / Rocky Linux 9] 번들 런타임 설치
#   JRE/Python/Caddy + ffmpeg 정적 바이너리 + 시스템 RPM(opencv 런타임 의존)
#
#   타깃 OS = Rocky Linux 9 (RHEL 9 계열, x86_64, glibc 2.34, dnf/rpm).
#     - ffmpeg/ffprobe : syspkgs/ffmpeg/ 의 정적 tarball 을 /opt/klid/runtime/ffmpeg 로 설치.
#     - RPM(mesa-libGL 등) : syspkgs/rpm/*.rpm 오프라인 설치(dnf 우선, rpm 폴백).
#
#   외부 네트워크 호출 없음. 모든 산출물은 runtimes/, syspkgs/ 번들에서 사용.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"
require_root

# verify_first_tarball <dir> <algo> <expected> <label>
#   설치 직전, 번들 디렉토리의 단일 tar.gz 무결성을 기대 체크섬과 대조(fail-closed).
#   기대값이 비어있으면(미검증) 강한 warn 후 진행. SHA256SUMS 묶음 검증과 독립적.
verify_first_tarball() {
  local dir="$1" algo="$2" expected="$3" label="$4"
  shopt -s nullglob; local t=("${dir}"/*.tar.gz); shopt -u nullglob
  [[ "${#t[@]}" -ge 1 ]] || return 0   # tar.gz 없으면(예: caddy 단일 바이너리) 건너뜀
  if [[ "${algo}" == "sha512" ]]; then
    verify_file_sha512 "${t[0]}" "${expected}" "${label}"
  else
    verify_file_sha256 "${t[0]}" "${expected}" "${label}"
  fi
}

: "${KLID_PREFIX:?install.sh 에서 호출되어야 합니다}"
ONPREM="$(onprem_root)"
RT="${KLID_PREFIX}/runtime"
ensure_dir "${RT}/jre" "${RT}/python" "${RT}/caddy" "${RT}/ffmpeg/bin"

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
verify_first_tarball "${ONPREM}/runtimes/jdk" sha256 "${TEMURIN_JRE_SHA256:-}" "Temurin JRE ${TEMURIN_JRE_VERSION}"
extract_tar_flatten "${ONPREM}/runtimes/jdk" "${RT}/jre"
[[ -x "${RT}/jre/bin/java" ]] || die "JRE 설치 실패: ${RT}/jre/bin/java 없음"
ok "[runtime] java: $("${RT}/jre/bin/java" -version 2>&1 | head -n1)"

# ---- Python (standalone) ----
info "[runtime] Python 설치 → ${RT}/python"
verify_first_tarball "${ONPREM}/runtimes/python" sha256 "${PYTHON_STANDALONE_SHA256:-}" "CPython ${PYTHON_STANDALONE_VERSION}"
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
  # Caddy 공식 SHA256 미발행 → tar.gz 는 SHA512(공식)로 검증.
  verify_file_sha512 "${caddy_tars[0]}" "${CADDY_SHA512:-}" "Caddy ${CADDY_VERSION}"
  tmp="$(mktemp -d)"
  # tar/install 실패로 조기 종료해도 임시 디렉토리가 잔류하지 않도록 EXIT 트랩 등록.
  trap 'rm -rf "${tmp:-}"' EXIT
  tar -xzf "${caddy_tars[0]}" -C "${tmp}"
  install -m 0755 "${tmp}/caddy" "${RT}/caddy/caddy"
  rm -rf "${tmp}"
  trap - EXIT
elif [[ -f "${ONPREM}/runtimes/caddy/caddy" ]]; then
  # 사전 배치된 단일 caddy 바이너리 경로는 공식 SHA512(tar.gz 기준)로 무결성 검증이 불가하다.
  # 수집(40-collect-runtimes.sh)은 항상 tar.gz 를 받으므로 이 경로는 정상 흐름에서 도달하지 않는다.
  # 검증 불가한 바이너리를 그대로 설치하지 않도록 fail-closed 로 거부한다.
  die "검증 불가한 단일 caddy 바이너리는 지원하지 않습니다(공식 SHA512 는 tar.gz 기준).
     → 공식 tar.gz(caddy_${CADDY_VERSION}_linux_amd64.tar.gz)를 runtimes/caddy/ 에 두고 다시 실행하세요."
else
  die "Caddy 바이너리를 찾을 수 없습니다: ${ONPREM}/runtimes/caddy/"
fi
ok "[runtime] caddy: $("${RT}/caddy/caddy" version 2>&1 | head -n1)"

# ---- ffmpeg/ffprobe (정적 바이너리) ----
# Rocky 9 base/AppStream 에 ffmpeg 가 없으므로 정적 바이너리를 번들·배치한다.
# BtbN linux64-lgpl tarball(LGPL — 지방정부 납품 GPL 회피, 디코드 전용으로 충분)은 .tar.xz 이며
# 내부 bin/ 에 ffmpeg·ffprobe 가 있다.
info "[runtime] ffmpeg 설치 → ${RT}/ffmpeg"
shopt -s nullglob
ff_tars=("${ONPREM}/syspkgs/ffmpeg"/*.tar.xz "${ONPREM}/syspkgs/ffmpeg"/*.tar.gz)
shopt -u nullglob
if [[ "${#ff_tars[@]}" -ge 1 ]]; then
  # 공식 SHA256 으로 무결성 검증(fail-closed; 미검증이면 강한 warn 후 진행).
  verify_file_sha256 "${ff_tars[0]}" "${FFMPEG_STATIC_SHA256:-}" "ffmpeg static ${FFMPEG_STATIC_VERSION:-}"
  ff_tmp="$(mktemp -d)"
  trap 'rm -rf "${ff_tmp:-}"' EXIT
  case "${ff_tars[0]}" in
    *.tar.xz) tar -xJf "${ff_tars[0]}" -C "${ff_tmp}" ;;
    *)        tar -xzf "${ff_tars[0]}" -C "${ff_tmp}" ;;
  esac
  # tarball 어디에 있든 ffmpeg/ffprobe 실행 파일을 찾아 배치(보통 <root>/bin/).
  ff_bin="$(find "${ff_tmp}" -type f -name ffmpeg  | head -n1)"
  fp_bin="$(find "${ff_tmp}" -type f -name ffprobe | head -n1)"
  [[ -n "${ff_bin}" && -n "${fp_bin}" ]] || die "ffmpeg/ffprobe 바이너리를 tarball 에서 찾지 못했습니다: ${ff_tars[0]}"
  install -m 0755 "${ff_bin}" "${RT}/ffmpeg/bin/ffmpeg"
  install -m 0755 "${fp_bin}" "${RT}/ffmpeg/bin/ffprobe"
  rm -rf "${ff_tmp}"; trap - EXIT
  ok "[runtime] ffmpeg: $("${RT}/ffmpeg/bin/ffmpeg" -version 2>&1 | head -n1)"
else
  warn "[runtime] 번들된 ffmpeg 정적 바이너리 없음(syspkgs/ffmpeg/*.tar.xz)."
  warn "          backend FFmpegStep(프레임추출·duration) 가 실패할 수 있습니다."
  warn "          빌드머신에서 50-collect-syspkgs.sh 를 다시 실행해 수집하세요."
fi

# ---- 시스템 RPM(opencv 런타임 의존: mesa-libGL/libglvnd-glx/glib2) 오프라인 설치 ----
shopt -s nullglob
rpms=("${ONPREM}/syspkgs/rpm"/*.rpm)
shopt -u nullglob
if [[ "${#rpms[@]}" -ge 1 ]]; then
  if command -v dnf >/dev/null 2>&1; then
    info "[runtime] 시스템 RPM 오프라인 설치(dnf): ${#rpms[@]} 개"
    # --disablerepo='*' 로 외부 네트워크 미접근. 의존성은 번들된 RPM 들로 로컬 해소.
    # --setopt=gpgcheck=0: 무결성은 번들 SHA256SUMS 로 이미 검증 — repo/GPG 메타가 부재한
    #   최소 Rocky 9 폐쇄망 이미지에서 GPG 키 부재로 dnf install 이 실패하지 않도록 비활성.
    # 폴백 rpm -Uvh 에는 --nodeps 를 쓰지 않는다(의존성 깨짐 위험 — 번들 RPM 로 의존 해소 기대).
    dnf install -y --disablerepo='*' --setopt=gpgcheck=0 "${rpms[@]}" \
      || rpm -Uvh --replacepkgs "${rpms[@]}" \
      || warn "[runtime] RPM 설치 일부 미해결 — 06-troubleshooting.md 참고"
  elif command -v rpm >/dev/null 2>&1; then
    info "[runtime] 시스템 RPM 오프라인 설치(rpm): ${#rpms[@]} 개"
    # 이미 설치돼 있어도 무해하도록 --replacepkgs. 멱등.
    rpm -Uvh --replacepkgs "${rpms[@]}" \
      || warn "[runtime] rpm -Uvh 일부 미해결 — 06-troubleshooting.md 참고"
  else
    warn "[runtime] dnf/rpm 없음 — RPM 설치 생략. mesa-libGL/glib2 를 수동 설치하세요."
  fi
else
  warn "[runtime] 번들된 RPM 없음 — 대상 OS 에 mesa-libGL/libglvnd-glx/glib2 가 이미 설치돼"
  warn "          있어야 합니다(없으면 ai-server opencv import: libGL.so.1 가 실패)."
fi

# 런타임 위치 기록(다음 스크립트가 참조)
{
  echo "KLID_JAVA=${RT}/jre/bin/java"
  echo "KLID_PYTHON=${PYBIN}"
  echo "KLID_CADDY=${RT}/caddy/caddy"
  echo "KLID_FFMPEG=${RT}/ffmpeg/bin/ffmpeg"
  echo "KLID_FFPROBE=${RT}/ffmpeg/bin/ffprobe"
} > "${KLID_PREFIX}/runtime/runtime.env"
ok "[runtime] 런타임 경로 기록: ${KLID_PREFIX}/runtime/runtime.env"

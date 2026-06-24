#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# package.sh — [빌드머신] klid-label 온프렘 설치 패키지 수집 오케스트레이터
#
#   인터넷이 되는 빌드머신에서 1회 실행한다. 모든 의존성(jar·FE dist·pip wheel·
#   런타임·시스템 패키지·모델)을 deploy/onprem/ 하위로 수집한다. 수집이 끝나면
#   deploy/onprem/ 폴더 전체를 폐쇄망 대상 서버로 전송한다.
#
#   타깃 OS = Rocky Linux 9 (RHEL 9 계열, x86_64, glibc 2.34, dnf/rpm).
#
#   ★ 빌드머신 제약(반드시 준수):
#     - pip wheel / RPM 은 대상(Rocky 9, x86_64, glibc 2.34)과 동일 환경에서 수집해야 한다.
#       manylinux wheel / RPM / glibc 정합 때문. → rockylinux:9 컨테이너/머신 권장.
#       (jar / FE dist / 런타임 tarball / ffmpeg 정적은 OS 무관 — mac 등에서도 가능)
#     - JDK17 / Node20 / Python3.11 / git 이 설치되어 있어야 한다.
#
#   사용법:
#     ./scripts/package.sh                 # 전체 단계 수집
#     SKIP_SYSPKGS=1 ./scripts/package.sh  # 시스템 의존성(RPM/ffmpeg) 수집 생략
#     PREFETCH_HF=1 ./scripts/package.sh   # HF 모델(rtdetr/sam2)도 사전 다운로드
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"
# shellcheck source=lib/versions.sh
source "${SELF_DIR}/lib/versions.sh"

ONPREM="$(onprem_root)"
PKG_DIR="${SELF_DIR}/package"

info "================================================================"
info " klid-label 온프렘 패키지 수집 (빌드머신)"
info "  onprem 루트 : ${ONPREM}"
info "  repo  루트 : $(repo_root)"
info "================================================================"

# ---- OS/아키텍처 정합 경고 ----
uname_s="$(uname -s)"
uname_m="$(uname -m)"
info "빌드머신: ${uname_s} ${uname_m}"
if [[ "${uname_s}" != "Linux" || "${uname_m}" != "x86_64" ]]; then
  warn "타깃은 Rocky Linux 9 x86_64 입니다. 현재 빌드머신(${uname_s} ${uname_m})은 다릅니다."
  warn "OS 종속 산출물(pip wheel / RPM)은 대상과 호환되지 않을 수 있습니다."
  warn "  → pip wheel / RPM 은 rockylinux:9 컨테이너/머신에서 수집하세요(02-build-package.md)."
  warn "  → jar / FE dist / 런타임 tarball / ffmpeg 정적은 OS 무관이라 여기서 만들어도 됩니다."
  warn "  (mac 등에서는 50-collect 의 RPM 단계가 graceful SKIP 되고 ffmpeg 정적만 수집됩니다.)"
  confirm "그래도 계속 진행하시겠습니까?" || die "중단합니다. 올바른 빌드머신에서 다시 실행하세요."
fi

# ---- 빌드 도구 확인 ----
require_cmd bash tar curl git

STEPS=(
  "10-build-backend.sh"
  "20-build-frontend.sh"
  "30-collect-ai-server.sh"
  "40-collect-runtimes.sh"
  "50-collect-syspkgs.sh"
)

for step in "${STEPS[@]}"; do
  script="${PKG_DIR}/${step}"
  [[ -f "${script}" ]] || die "수집 스크립트 누락: ${script}"
  info "---- 실행: ${step} ----"
  bash "${script}"
  ok "완료: ${step}"
done

# ---- 패키지 버전 메타 기록 ----
{
  echo "package_built_at=$(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "package_built_on=${uname_s} ${uname_m}"
  echo "git_commit=$(cd "$(repo_root)" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
} > "${ONPREM}/VERSION.built"
ok "패키지 메타 기록: ${ONPREM}/VERSION.built"

info "================================================================"
ok " 수집 완료. 다음 단계:"
info "   1) deploy/onprem/ 폴더 전체를 USB/전송 매체로 복사"
info "   2) 폐쇄망 대상 서버에서 sudo ./scripts/install.sh"
info "================================================================"

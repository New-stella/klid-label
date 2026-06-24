#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 50-collect-syspkgs.sh — [빌드머신] 런타임 시스템 패키지(.deb) 수집
#
#   런타임 의존성:
#     backend  : ffmpeg, ffprobe(=ffmpeg 패키지에 포함), curl
#     ai-server: libgl1, libglib2.0-0  (opencv 런타임 의존)
#
#   데비안/우분투 빌드머신에서만 .deb 를 수집한다(apt-get download).
#   비데비안(RHEL/Rocky 등)이면 SKIP 하고, 대상 OS 패키지 매니저로 별도 준비하도록 안내.
#
#   ★ 빌드머신과 대상 서버의 배포판/버전이 동일해야 .deb 가 호환된다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

ONPREM="$(onprem_root)"
DEB_OUT="${ONPREM}/syspkgs/deb"
ensure_dir "${DEB_OUT}"

if [[ "${SKIP_SYSPKGS:-0}" == "1" ]]; then
  info "[syspkgs] SKIP_SYSPKGS=1 — 시스템 패키지 수집 생략"
  exit 0
fi

if ! command -v apt-get >/dev/null 2>&1 || ! command -v dpkg >/dev/null 2>&1; then
  warn "[syspkgs] 데비안/우분투(apt) 빌드머신이 아닙니다 — .deb 수집을 건너뜁니다."
  warn "  대상 서버에 다음 런타임 패키지를 OS 패키지 매니저로 직접 설치하세요:"
  warn "    ffmpeg (ffprobe 포함), curl, libgl1, libglib2.0-0"
  warn "  RHEL 계열 예: dnf install ffmpeg mesa-libGL glib2  (EPEL/RPMFusion 미러 필요)"
  cat > "${DEB_OUT}/README-non-debian.txt" <<'TXT'
이 빌드머신은 데비안/우분투(apt)가 아니어서 .deb 를 수집하지 못했습니다.
대상 서버에 아래 런타임 패키지를 OS 패키지 매니저로 설치하세요(폐쇄망이면 미러 필요):
  - ffmpeg (ffprobe 포함)
  - curl
  - libgl1 (또는 mesa-libGL)
  - libglib2.0-0 (또는 glib2)
TXT
  exit 0
fi

# 수집 대상 패키지(의존성 포함하여 함께 받기)
PKGS=(ffmpeg curl libgl1 libglib2.0-0)

info "[syspkgs] apt 패키지 인덱스 갱신..."
apt-get update -qq || warn "apt-get update 경고(무시 가능) — 캐시로 진행"

info "[syspkgs] .deb 다운로드(의존성 포함): ${PKGS[*]}"
# apt-rdepends 가 있으면 전이 의존성까지, 없으면 직접 의존성만.
deplist=""
if command -v apt-rdepends >/dev/null 2>&1; then
  deplist="$(apt-rdepends "${PKGS[@]}" 2>/dev/null | grep -v '^ ' | sort -u || true)"
fi
[[ -n "${deplist}" ]] || deplist="${PKGS[*]}"

# shellcheck disable=SC2086
( cd "${DEB_OUT}" && apt-get download ${deplist} ) \
  || warn "[syspkgs] 일부 패키지 download 실패 — 03-install.md 의 수동 설치 안내 참고"

count="$(ls -1 "${DEB_OUT}"/*.deb 2>/dev/null | wc -l | tr -d ' ')"
ok "[syspkgs] .deb 수집: ${DEB_OUT}  (${count} 개)"

[[ "${count}" -gt 0 ]] && sha256_write "${DEB_OUT}" || true

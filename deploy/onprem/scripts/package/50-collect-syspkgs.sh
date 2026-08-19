#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 50-collect-syspkgs.sh — [빌드머신] 런타임 시스템 의존성 수집 (Rocky Linux 9)
#
#   타깃 OS = Rocky Linux 9 (RHEL 9 계열, x86_64, glibc 2.34, dnf/rpm).
#
#   런타임 의존성:
#     backend  : ffmpeg, ffprobe → ★ 정적 바이너리(번들). Rocky 9 base/AppStream 에는
#                ffmpeg 가 없어(RPM Fusion/EPEL 미러 필요) 폐쇄망에서 의존성 지옥에 빠진다.
#                따라서 RPM 이 아니라 정적 ffmpeg tarball 을 받는다(어디서든 curl 로 수집 가능).
#     ai-server: libGL.so.1 (opencv-python import) + libglib2.0 계열
#                → Rocky 9 AppStream 의 mesa-libGL / libglvnd-glx / glib2 RPM 으로 제공.
#
#   2가지 수집물:
#     A) ffmpeg 정적 tarball → syspkgs/ffmpeg/   (OS 무관, curl 만 있으면 됨 — mac 포함)
#     B) RPM (mesa-libGL 등)  → syspkgs/rpm/      (dnf/yum 환경에서만 — 없으면 graceful SKIP)
#
#   ★ 비-RHEL 빌드머신(mac 등 dnf/yum 없음): RPM 단계만 graceful SKIP 한다
#     — 이 경우 rockylinux:9 컨테이너에서 RPM 을 별도 수집해야 한다(02-build-package.md 참고).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"

ONPREM="$(onprem_root)"
RPM_OUT="${ONPREM}/syspkgs/rpm"
FFMPEG_OUT="${ONPREM}/syspkgs/ffmpeg"
ensure_dir "${RPM_OUT}" "${FFMPEG_OUT}"

if [[ "${SKIP_SYSPKGS:-0}" == "1" ]]; then
  info "[syspkgs] SKIP_SYSPKGS=1 — 시스템 의존성 수집 생략"
  exit 0
fi

require_cmd curl

# ----------------------------------------------------------------------------
# A) ffmpeg 정적 바이너리 (OS 무관 — curl 만 있으면 mac 에서도 수집)
# ----------------------------------------------------------------------------
info "[syspkgs] ffmpeg 정적 바이너리 ${FFMPEG_STATIC_VERSION} 수집..."
ff_fname="$(basename "${FFMPEG_STATIC_URL%%\?*}")"
ff_out="${FFMPEG_OUT}/${ff_fname}"
if [[ -s "${ff_out}" ]]; then
  info "[syspkgs] 이미 존재(생략): ${ff_out}"
else
  info "[syspkgs] 다운로드: ${FFMPEG_STATIC_URL}"
  curl -fL --retry 3 --proto '=https' -o "${ff_out}.part" "${FFMPEG_STATIC_URL}" \
    && mv "${ff_out}.part" "${ff_out}" \
    || { rm -f "${ff_out}.part"; die "[syspkgs] ffmpeg 정적 바이너리 다운로드 실패: ${FFMPEG_STATIC_URL}"; }
  ok "[syspkgs] 수집: ${ff_out}  ($(du -h "${ff_out}" | cut -f1))"
fi
# 공식 체크섬과 대조(fail-closed). 미검증(빈 값)이면 강한 warn 후 진행.
verify_file_sha256 "${ff_out}" "${FFMPEG_STATIC_SHA256:-}" "ffmpeg static ${FFMPEG_STATIC_VERSION}"
sha256_write "${FFMPEG_OUT}"

# ----------------------------------------------------------------------------
# B) RPM (Rocky 9) — opencv 런타임 의존. dnf/yum 환경에서만.
# ----------------------------------------------------------------------------
# Rocky 9 AppStream 패키지:
#   - mesa-libGL    : libGL.so.1 제공(opencv import)
#   - libglvnd-glx  : GLX 디스패치(mesa-libGL 의존 보강)
#   - glib2         : libglib-2.0.so.0 (opencv/그래픽 스택 의존)
# httpd — 프론트엔드 정적 서빙 + API 리버스프록시(관제지원시스템과 동일 사양).
#   mod_proxy·mod_headers·mod_deflate 는 httpd 본체 패키지에 포함된다.
#
# policycoreutils-python-utils — semanage 제공. SELinux Enforcing 장비에서 문서 루트에
#   httpd 읽기 문맥을 <영구> 부여하는 데 필요하다. restorecon 만 쓰면 정책에 규칙이 남지
#   않아 재라벨링·재부팅 후 초기화되고, 그때 화면이 403 으로 돌아간다.
#   ★ 폐쇄망에서는 없으면 설치할 방법이 없다 — 여기서 함께 받아 두지 않으면 설치 스크립트가
#     "semanage 없음" 을 경고해도 운영자가 할 수 있는 일이 없다.
RPM_PKGS=(mesa-libGL libglvnd-glx glib2 httpd policycoreutils-python-utils)

collect_rpm() {
  local dl_tool=""
  if command -v dnf >/dev/null 2>&1; then
    dl_tool="dnf"
  elif command -v yum >/dev/null 2>&1 && command -v yumdownloader >/dev/null 2>&1; then
    dl_tool="yumdownloader"
  fi
  [[ -n "${dl_tool}" ]] || return 1

  info "[syspkgs] RPM 수집(의존성 포함): ${RPM_PKGS[*]}  (tool=${dl_tool})"
  # 다운로드 실패는 부분/빈 세트를 성공으로 위장하지 않도록 fail 로 취급한다(호출부가 폴백·안내로 빠지게).
  if [[ "${dl_tool}" == "dnf" ]]; then
    # --resolve: 전이 의존성까지. --downloadonly 미사용(download 서브커맨드 자체가 다운로드만).
    dnf download --resolve --alldeps --downloaddir "${RPM_OUT}" "${RPM_PKGS[@]}" \
      || { warn "[syspkgs] dnf download 실패 — 부분 수집을 성공으로 위장하지 않고 폴백/안내로 전환합니다."; return 1; }
  else
    yumdownloader --resolve --destdir "${RPM_OUT}" "${RPM_PKGS[@]}" \
      || { warn "[syspkgs] yumdownloader 실패 — 부분 수집을 성공으로 위장하지 않고 폴백/안내로 전환합니다."; return 1; }
  fi

  local count
  count="$(ls -1 "${RPM_OUT}"/*.rpm 2>/dev/null | wc -l | tr -d ' ')"
  # 최솟값 가드: 요청 패키지 수(전이 의존 제외 최소 base)보다 적게 받혔으면 불완전 세트로 간주해 fail.
  # (정상 수집이면 전이 의존성까지 포함돼 요청 수 이상이어야 한다.)
  if [[ "${count}" -lt "${#RPM_PKGS[@]}" ]]; then
    warn "[syspkgs] RPM 수집 불완전: ${count} 개 < 요청 ${#RPM_PKGS[@]} 개 — 폴백/안내로 전환합니다."
    return 1
  fi
  ok "[syspkgs] RPM 수집: ${RPM_OUT}  (${count} 개)"
  sha256_write "${RPM_OUT}"
  return 0
}

if collect_rpm; then
  :
else
  warn "[syspkgs] dnf/yum 이 없는 빌드머신(예: macOS) — RPM 수집을 건너뜁니다(graceful SKIP)."
  warn "  타깃 Rocky 9 용 RPM 은 rockylinux:9 컨테이너에서 수집해야 합니다. 예:"
  warn "    docker run --rm -v \"\$PWD:/work\" -w /work rockylinux:9 \\"
  warn "      bash -c 'dnf -y install dnf-plugins-core && \\"
  warn "        dnf download --resolve --alldeps --downloaddir syspkgs/rpm ${RPM_PKGS[*]}'"
  warn "  (ffmpeg 정적 바이너리는 위에서 이미 수집됨 — RPM 만 채우면 됩니다.)"
  cat > "${RPM_OUT}/README-collect-on-rocky9.txt" <<TXT
이 빌드머신에는 dnf/yum 이 없어 Rocky 9 용 RPM 을 수집하지 못했습니다.
타깃(Rocky Linux 9, x86_64)용 RPM 을 아래처럼 rockylinux:9 컨테이너에서 수집하세요:

  docker run --rm -v "\$PWD:/work" -w /work rockylinux:9 \\
    bash -c 'dnf -y install dnf-plugins-core && \\
      dnf download --resolve --alldeps --downloaddir syspkgs/rpm ${RPM_PKGS[*]}'

수집 대상(opencv 런타임 의존): ${RPM_PKGS[*]}
  - mesa-libGL   : libGL.so.1
  - libglvnd-glx : GLX 디스패치
  - glib2        : libglib-2.0.so.0

ffmpeg 는 정적 바이너리로 별도 수집됩니다(syspkgs/ffmpeg/, RPM 불필요).
TXT
fi

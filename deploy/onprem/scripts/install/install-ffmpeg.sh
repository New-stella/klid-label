#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# install-ffmpeg.sh — [대상 서버 A / 수동 실행 전용] 번들 ffmpeg 오프라인 설치
#
#   ★★ 이 스크립트는 install.sh 가 <절대 호출하지 않는다> (2026-08-30 사용자 확정, 구속).
#     파일명에 번호 접두(10-·11-…)가 없는 것이 그 표식이다 — 번호가 붙은 것만 자동 단계다.
#
#   왜 자동이 아닌가:
#     이 장비는 관제지원시스템과 <공동 배치>다. ffmpeg 는 관제지원시스템 팀이 설치하는 것이
#     원칙이고, 우리가 자동으로 깔면 <관제가 쓰던 설치본을 덮어써 관제 기능이 깨질 수> 있다.
#     그 위험이 "자동이라 편하다"보다 크다. 그래서 설치는 사람이 명시적으로 부를 때만 일어난다.
#     ⚠ 이 판단을 뒤집어 install.sh 의 STEPS 에 넣지 말 것. 편의를 이유로 자동화하면
#       바로 그 사고가 난다.
#
#   기본 동작(안전 우선):
#     · 이미 설치돼 있으면 <아무것도 하지 않고> 현재 버전을 출력한 뒤 정상 종료한다.
#     · 덮어쓰려면 --force 를 명시해야 하고, 그때는 <현재 버전 → 대상 버전>을 먼저 출력한다.
#
#   사용법:
#     sudo ./scripts/install/install-ffmpeg.sh            # 없을 때만 설치(있으면 skip)
#     sudo ./scripts/install/install-ffmpeg.sh --force    # 이미 있어도 번들 판으로 교체
#     sudo ./scripts/install/install-ffmpeg.sh --dry-run  # 무엇을 할지만 출력
#
#   ★ 서버 A(백엔드) 전용이다. ai-server 장비에는 필요 없다
#     (ai-server 소스에 ffmpeg/ffprobe 호출이 0건 — 실측).
#
#   ⚠ 라이선스: 번들 ffmpeg 는 RPM Fusion 의 GPLv3+ 빌드다. 매체에 담아 반입한 시점에
#     이미 재배포이므로 대응 소스(syspkgs/ffmpeg-src/*.src.rpm)가 함께 반입되어 있어야 한다.
#     이 스크립트는 그 존재를 확인만 하고 설치하지 않는다(설치 대상이 아니다).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"

FORCE=0
DRY_RUN=0
for arg in "$@"; do
  case "${arg}" in
    --force)   FORCE=1 ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help)
      sed -n '1,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) die "알 수 없는 옵션: ${arg} (허용: --force | --dry-run | --help)" ;;
  esac
done

require_root
ONPREM="$(onprem_root)"
FF_DIR="${ONPREM}/syspkgs/ffmpeg"
FF_SRC_DIR="${ONPREM}/syspkgs/ffmpeg-src"
GPG_DIR="${ONPREM}/syspkgs/gpg"

info "================================================================"
info " ffmpeg 수동 설치 (서버 A / 백엔드 장비 전용)"
info "  번들 : ${FF_DIR}"
info "================================================================"

# ---- 현재 설치 상태 ----
CURRENT=""
if command -v rpm >/dev/null 2>&1; then
  CURRENT="$(rpm -q --qf '%{name}-%{version}-%{release}.%{arch}' ffmpeg 2>/dev/null || true)"
  case "${CURRENT}" in *"is not installed"*) CURRENT="" ;; esac
fi

# ---- 번들에 들어 있는 대상 버전 ----
TARGET=""
shopt -s nullglob
_ff_rpms=("${FF_DIR}"/ffmpeg-[0-9]*.rpm)
shopt -u nullglob
if [[ "${#_ff_rpms[@]}" -ge 1 ]] && command -v rpm >/dev/null 2>&1; then
  TARGET="$(rpm -qp --nosignature --qf '%{name}-%{version}-%{release}.%{arch}' "${_ff_rpms[0]}" 2>/dev/null || true)"
fi

# ---- 이미 설치돼 있으면 기본은 <아무것도 하지 않는다> ----
#   ★ 관제가 깔아 둔 것을 덮어쓰지 않기 위한 기본값이다. 버전이 낮아 보여도 자동으로
#     올리지 않는다 — 관제가 그 버전에 맞춰 동작하고 있을 수 있다.
if [[ -n "${CURRENT}" ]]; then
  ok "[ffmpeg] 이미 설치되어 있습니다: ${CURRENT}"
  if command -v ffmpeg >/dev/null 2>&1; then
    info "[ffmpeg] $(ffmpeg -hide_banner -version 2>&1 | head -n1)"
  fi
  if [[ "${FORCE}" -ne 1 ]]; then
    info "[ffmpeg] 아무것도 하지 않고 종료합니다."
    info "         이 장비는 관제지원시스템과 공동 배치라, 관제가 설치한 ffmpeg 를 덮어쓰면"
    info "         관제 기능이 깨질 수 있습니다. 교체가 정말 필요하면 --force 를 쓰세요."
    [[ -n "${TARGET}" ]] && info "         (참고 — 번들에 들어 있는 판: ${TARGET})"
    exit 0
  fi
  warn "----------------------------------------------------------------"
  warn "[ffmpeg] --force 지정 — 기존 설치본을 <덮어씁니다>."
  warn "         현재 : ${CURRENT}"
  warn "         대상 : ${TARGET:-(번들에서 판정 실패)}"
  warn "         ★ 이 장비의 ffmpeg 를 관제지원시스템이 함께 쓰고 있다면 그쪽 동작에"
  warn "           영향이 갑니다. 관제 담당자와 합의된 작업인지 확인하세요."
  warn "----------------------------------------------------------------"
  confirm "위 내용을 확인했습니다. 계속할까요?" || die "중단합니다."
else
  info "[ffmpeg] 설치되어 있지 않습니다 — 번들 판을 설치합니다: ${TARGET:-(판정 실패)}"
fi

# ---- 번들 존재·무결성 ----
if [[ "${#_ff_rpms[@]}" -lt 1 ]]; then
  die "[ffmpeg] 번들에 ffmpeg RPM 이 없습니다: ${FF_DIR}
     빌드머신에서 수집을 SKIP_FFMPEG=1 로 껐거나 매체가 불완전합니다.
     → 빌드머신에서 ./scripts/package.sh (SKIP_FFMPEG 없이) 를 다시 실행해 매체를 만드세요."
fi
if [[ -f "${FF_DIR}/SHA256SUMS" ]]; then
  sha256_verify "${FF_DIR}"
else
  warn "[ffmpeg] 체크섬 파일이 없어 전송 무결성을 검증하지 못했습니다: ${FF_DIR}/SHA256SUMS"
fi

# GPL 대응 소스 동봉 확인 — 설치하지는 않는다. 없으면 <반입 자체가 라이선스 의무 미충족>이다.
if ! ls "${FF_SRC_DIR}"/*.src.rpm >/dev/null 2>&1; then
  warn "[ffmpeg] GPL 대응 소스(SRPM)가 매체에 없습니다: ${FF_SRC_DIR}"
  warn "         번들 ffmpeg 는 RPM Fusion GPLv3+ 빌드라, 매체 반입 시 대응 소스를 함께"
  warn "         제공해야 합니다. 설치는 계속하되 매체 구성을 반드시 보완하세요."
else
  info "[ffmpeg] GPL 대응 소스 동봉 확인: $(ls -1 "${FF_SRC_DIR}"/*.src.rpm | wc -l | tr -d ' ') 개 (설치 대상 아님)"
fi

if [[ "${DRY_RUN}" -eq 1 ]]; then
  info "[ffmpeg] --dry-run — 실제 설치는 하지 않습니다."
  info "         설치했다면: GPG 키 등록(${GPG_DIR}) → 로컬 저장소(${FF_DIR}) → dnf install ${FFMPEG_RPM_PKGS[*]}"
  exit 0
fi

# ---- 설치 ----
#   ★ 새 설치 로직을 만들지 않는다 — 11 단계·14 단계와 <같은> 공통 함수를 쓴다.
#     로컬 yum 저장소 방식이어야 하는 이유(기반 패키지 충돌)는 common.sh 주석 참조.
klid_import_rpm_gpg_keys "${GPG_DIR}" || true

klid_dnf_install_from_bundle ffmpeg "${FF_DIR}" "${FFMPEG_RPM_PKGS[@]}" \
  || die "[ffmpeg] 오프라인 설치 실패 — docs/06-troubleshooting.md 의 ffmpeg 절을 참고하세요."

# ---- 설치 직후 실동작 확인 ----
#   설치 성공(rc=0)과 정상 동작은 다르다. 여기서 한 번 태워 본다.
klid_verify_ffmpeg_runtime "${FFMPEG_BIN_PATH}" "${FFPROBE_BIN_PATH}"

ok "================================================================"
ok " ffmpeg 설치 완료: $(rpm -q --qf '%{name}-%{version}-%{release}.%{arch}' ffmpeg 2>/dev/null || echo '(rpm 조회 실패)')"
info "   다음: sudo ${SELF_DIR}/../install.sh --role=app  (전제조건 검증 단계를 다시 통과시킨다)"
ok "================================================================"

#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# package.sh — [빌드머신] klid-label 온프렘 설치 패키지 수집 오케스트레이터
#
#   인터넷이 되는 빌드머신에서 1회 실행한다. 모든 의존성(jar·FE dist·pip wheel·
#   런타임·시스템 패키지·모델)을 deploy/onprem/ 하위로 수집한다. 수집이 끝나면
#   deploy/onprem/ 폴더 전체를 폐쇄망 대상 서버로 전송한다.
#
#   타깃 OS = 레드햇 엔터프라이즈 리눅스 8.9 (RHEL 8 계열, x86_64, glibc 2.28, dnf/rpm).
#   ⚠ 구 서술 폐기(2026-08-28) — "Rocky Linux 9 (RHEL 9 계열, glibc 2.34)".
#
#   ★ 빌드머신 제약(반드시 준수):
#     - pip wheel / RPM 은 대상(RHEL 8.9, x86_64, glibc 2.28)과 동일 세대에서 수집해야 한다.
#       manylinux wheel / RPM / glibc 정합 때문. → el8 컨테이너/머신 권장.
#       RPM 수집(50·55)은 dnf 가 없으면 docker 로 el8 컨테이너를 자동 기동한다.
#       (jar / FE dist / 런타임 tarball 은 OS 무관 — mac 등에서도 가능)
#       ⚠ ffmpeg 는 더 이상 OS 무관 정적 tarball 이 아니다 — el8 RPM 이라 위 경로를 탄다.
#     - JDK17 / Node20 / Python3.11 / git 이 설치되어 있어야 한다.
#
#   사용법:
#     ./scripts/package.sh                   # 전체 단계 수집(빌드 키트 제외 — 기본값)
#                                            (라이선스 고지 생성 70 단계는 항상 포함)
#     SKIP_SYSPKGS=1 ./scripts/package.sh    # 시스템 의존성(RPM/ffmpeg) 수집 생략
#     PREFETCH_HF=1 ./scripts/package.sh     # HF 모델(sam2)도 사전 다운로드
#     WITH_BUILDTOOLS=1 ./scripts/package.sh # 오프라인 빌드 키트(buildtools/+src/)도 수집
#     WITH_BACKEND_JAR=1 ./scripts/package.sh # 베어메탈 형상용 실행 가능 jar 도 빌드·수집
#     SKIP_COPYLEFT_SRC=1 ./scripts/package.sh # (L)GPL 대응 소스 동봉 생략(서면 확약만)
#
#   ★ 중간에 막혔을 때는 <처음부터> 다시 돌리지 않아도 된다 — 단계별 러너가 있다.
#       ./scripts/package-step.sh list        # 단계 목록(소요·선행조건·재실행 안전 여부)
#       ./scripts/package-step.sh 30          # 30 단계만
#       ./scripts/package-step.sh from 40     # 40 부터 끝까지 이어서
#     두 경로는 같은 목록(package/steps.sh)을 읽으므로 순서가 어긋나지 않는다.
#     상세는 docs/02-build-package.md 「단계별 실행」.
#
#   ★ 배포 형상 = <외부 WAS 에 api.war 반입> (@design DEPLOY-001 · RUNBOOK-001).
#     1단계가 만드는 api.war 가 반입 정본이고, klid-backend.jar 는 개발 환경 전용이라
#     <기본으로 만들지도 담지도 않는다>(2026-08-30 매체에서 제외). 베어메탈 형상으로 갈 때만
#     WITH_BACKEND_JAR=1 로 켠다 — 토글은 남겨 둔다.
#     자바 런타임은 반입하지 않는다 — 대상 장비의 WAS(JBoss EAP 8.1)가 Java 17 로 이미 돌고 있다.
#     ⚠ ai-server 의 Python 런타임·오프라인 휠·모델은 WAR 와 무관한 별도 프로세스라 그대로 반입한다.
#
#   ★ 빌드 키트(60단계)는 <기본 제외>다 (2026-08-30 사용자 확정). 폐쇄망 타깃에서 "소스 재빌드"를
#     가능케 하는 키트인데 현장 재빌드 요구가 없음을 확인했다. 빼면 함께 줄어드는 것:
#       · 라이선스 표면 — JDK full 한 벌이 더 실리지 않고, node_modules(645 패키지, MPL-2.0 3건)가
#         반입물에서 빠진다(그 목록의 고지 의무를 지지 않아도 된다)
#       · 매체 용량 — JDK full + gradle-home 캐시 + node_modules 는 GB 급이다
#       · 보안 표면 — 타깃에 컴파일러·빌드 도구 체인을 두지 않는다
#     필요해지면 WITH_BUILDTOOLS=1 로 켠다(토글은 남겨 둔다).
#     ⚠ 이 제외는 위 「자바 런타임 미반입」과 <다른 이유>다 — 여기 JDK17 full 은 소스 빌드용이고
#       그쪽 JRE 는 실행용이다. versions.sh 도 두 핀을 별개로 둔다(혼동 금지).
#     상세는 docs/02-build-package.md / docs/08-build-from-source.md 참고.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"
# shellcheck source=lib/versions.sh
source "${SELF_DIR}/lib/versions.sh"
# shellcheck source=package/steps.sh
source "${SELF_DIR}/package/steps.sh"

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
  warn "타깃은 레드햇 엔터프라이즈 리눅스 8.9 x86_64 입니다. 현재 빌드머신(${uname_s} ${uname_m})은 다릅니다."
  warn "OS 종속 산출물(pip wheel / RPM)은 대상과 호환되지 않을 수 있습니다."
  warn "  → pip wheel 은 el8 컨테이너/머신에서 수집하세요(02-build-package.md)."
  warn "  → RPM(50·55)은 dnf 가 없으면 docker 로 el8 컨테이너를 자동 기동해 수집합니다."
  warn "     (docker 도 없으면 graceful SKIP + 수동 수집 안내 파일을 남깁니다.)"
  warn "  → jar / FE dist / 런타임 tarball 은 OS 무관이라 여기서 만들어도 됩니다."
  warn "  ⚠ ffmpeg 는 정적 tarball 폐지(2026-08-28) 후 el8 RPM 이라 OS 무관이 아닙니다."
  confirm "그래도 계속 진행하시겠습니까?" || die "중단합니다. 올바른 빌드머신에서 다시 실행하세요."
fi

# ---- 빌드 도구 확인 ----
require_cmd bash tar curl git

# ---- 단계 목록 ----
#   ★ 목록·순서·토글 규칙은 package/steps.sh 가 단일 진실원이다. 여기에 다시 적지 않는다 —
#     단계별 러너(package-step.sh)가 같은 목록을 읽어야 "일괄로는 도는데 단계별로는 빠지는
#     단계"가 생기지 않는다.
package_step_explain
#   ⚠ mapfile 을 쓰지 않는다 — macOS 기본 bash 는 3.2 라 그 내장이 없다(이 저장소의
#     스크립트는 전부 `env bash` 로 돌므로 빌드머신이 맥이면 그쪽이 잡힐 수 있다).
STEPS=()
while IFS= read -r _s; do [[ -n "${_s}" ]] && STEPS+=("${_s}"); done < <(package_step_list)
unset _s

for step in "${STEPS[@]}"; do
  script="${PKG_DIR}/${step}"
  [[ -f "${script}" ]] || die "수집 스크립트 누락: ${script}"
  info "---- 실행: ${step} ----"
  bash "${script}"
  ok "완료: ${step}"
done

# ---- 반출 직전 마무리(위생 스윕 · VERSION.built) ----
#   ★ 위 단계 루프의 <마지막 단계>인 90-finalize-media.sh 가 이미 수행했다.
#     예전에는 그 두 가지가 이 파일 안의 블록이라, 단계별 러너(package-step.sh)로 돌면
#     한 번도 실행되지 않았다. 단계로 떼어내 두 경로가 같은 마무리를 돌게 했다(2026-08-30).
#     ⚠ 여기에 다시 적지 않는다 — 적는 순간 두 번째 진실원이 되어 한쪽만 갱신된다.

info "================================================================"
ok " 수집 완료. 다음 단계:"
info "   0) ★ 라이선스 고지 확인 — licenses/UNRESOLVED.md 가 비어 있어야 반출 가능"
info "        (남아 있으면 licenses/manual/OVERRIDES.tsv 를 채우고 70 단계를 다시 실행)"
info "        bash ./scripts/package/70-generate-notices.sh"
info "   1) deploy/onprem/ 폴더 전체를 USB/전송 매체로 복사"
info "   2) 폐쇄망 대상 서버에서 sudo ./scripts/install.sh"
info "================================================================"

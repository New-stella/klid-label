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
#     SKIP_POSTGRES=1 ./scripts/package.sh   # 번들 PG16 RPM 수집 생략(타깃에 이미 PG 있을 때)
#     PREFETCH_HF=1 ./scripts/package.sh     # HF 모델(sam2)도 사전 다운로드
#     WITH_BUILDTOOLS=1 ./scripts/package.sh # 오프라인 빌드 키트(buildtools/+src/)도 수집
#     SKIP_COPYLEFT_SRC=1 ./scripts/package.sh # (L)GPL 대응 소스 동봉 생략(서면 확약만)
#
#   ★ 배포 형상 = <외부 WAS 에 api.war 반입> (@design DEPLOY-001 · RUNBOOK-001).
#     1단계가 만드는 api.war 가 반입 정본이고, klid-backend.jar 는 개발 환경 전용이다.
#     자바 런타임은 반입하지 않는다 — 대상 장비의 WAS(Tomcat 10.1.x)가 Java 17 로 이미 돌고 있다.
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

STEPS=(
  "10-build-backend.sh"
  "20-build-frontend.sh"
  "30-collect-ai-server.sh"
  "40-collect-runtimes.sh"
  "50-collect-syspkgs.sh"
  "55-collect-postgresql.sh"
)
# 오프라인 빌드 키트(소스 재빌드용) — 기본 제외(2026-08-30 반전), WITH_BUILDTOOLS=1 로 켠다.
#   ⚠ 구 기본값 폐기(2026-08-30) — "기본 포함, SKIP_BUILDTOOLS=1 로 끈다".
#     SKIP_BUILDTOOLS 토글은 <계속 존중>한다 — 그 값으로 돌리던 기존 호출/문서가 깨지지 않게.
#     둘이 충돌하면(WITH=1 + SKIP=1) 제외가 이긴다(안전한 쪽).
if [[ "${WITH_BUILDTOOLS:-0}" == "1" && "${SKIP_BUILDTOOLS:-0}" != "1" ]]; then
  STEPS+=("60-collect-buildtools.sh")
  info "오프라인 빌드 키트 수집 포함(WITH_BUILDTOOLS=1) — buildtools/ 와 src/ 가 채워집니다."
else
  info "오프라인 빌드 키트 수집 생략(기본값) — 사전 빌드 아티팩트만 번들합니다."
  info "  타깃에서 소스 재빌드가 필요하면 WITH_BUILDTOOLS=1 ./scripts/package.sh 로 다시 수집하세요."
fi

# 카피레프트 대응 소스 수집 — 고지 생성 <앞>에 둔다. 70 단계가 그 결과를 근거로
#   "실었는지 / 서면 확약으로 대신하는지"를 판정하기 때문이다.
STEPS+=("65-collect-copyleft-sources.sh")

# 라이선스 고지 집합 생성 — <항상 마지막>이다. 앞 단계들이 각자 떨군 고지를 모아
#   NOTICE·INVENTORY·UNRESOLVED 를 만든다. 중간에 두면 그 시점에 없던 수집물이 빠진다.
#   ⚠ 이 단계를 끄는 토글을 두지 않는다 — 고지 없는 매체는 반출 자체가 위반이다.
STEPS+=("70-generate-notices.sh")

for step in "${STEPS[@]}"; do
  script="${PKG_DIR}/${step}"
  [[ -f "${script}" ]] || die "수집 스크립트 누락: ${script}"
  info "---- 실행: ${step} ----"
  bash "${script}"
  ok "완료: ${step}"
done

# ---- 반입물 위생 스윕(파이썬 바이트코드) ----
#   매체는 deploy/onprem/ 폴더 <통째로> 복사한 것이라 이 트리의 상태가 곧 매체의 상태다.
#   수집 단계(30)가 자기 복사물은 이미 정리하지만, 그것만으로는 부족하다 — 빌드머신에서
#   누가 파이썬 모듈을 한 번 import 하기만 해도 그 자리에 __pycache__ 가 생기고 그대로
#   매체에 실린다(실제로 scripts/lib/__pycache__ 가 그렇게 들어가 있었다. 패키징이 만든 것이
#   아니다 — 스크립트로 실행하면 캐시를 안 쓰고, import 해야 생긴다).
#   빌드머신 파이썬이 대상(3.11)과 다르면 cpython-314 같은 남의 태그가 반입물에 남는다.
#   동작에는 무해하지만 심의에서 설명해야 할 자리이므로 반출 직전에 쓸어낸다.
if [[ -n "${ONPREM:-}" && -d "${ONPREM}" ]]; then
  _pyc_n="$(find "${ONPREM}" -name '*.pyc' -o -name '*.pyo' | wc -l | tr -d ' ')"
  _pyd_n="$(find "${ONPREM}" -type d -name '__pycache__' | wc -l | tr -d ' ')"
  if [[ "${_pyc_n}" != "0" || "${_pyd_n}" != "0" ]]; then
    warn "[위생] 파이썬 바이트코드 잔재 제거: __pycache__ ${_pyd_n}개 / 파일 ${_pyc_n}개"
    find "${ONPREM}" -type d -name '__pycache__' -prune -exec rm -rf {} +
    find "${ONPREM}" \( -name '*.pyc' -o -name '*.pyo' \) -delete
  fi
  ok "[위생] 파이썬 바이트코드 잔재 없음(반입물 확인)"
  unset _pyc_n _pyd_n
fi

# ---- 패키지 버전 메타 기록 ----
{
  echo "package_built_at=$(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "package_built_on=${uname_s} ${uname_m}"
  echo "git_commit=$(cd "$(repo_root)" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
} > "${ONPREM}/VERSION.built"
ok "패키지 메타 기록: ${ONPREM}/VERSION.built"

info "================================================================"
ok " 수집 완료. 다음 단계:"
info "   0) ★ 라이선스 고지 확인 — licenses/UNRESOLVED.md 가 비어 있어야 반출 가능"
info "        (남아 있으면 licenses/manual/OVERRIDES.tsv 를 채우고 70 단계를 다시 실행)"
info "        bash ./scripts/package/70-generate-notices.sh"
info "   1) deploy/onprem/ 폴더 전체를 USB/전송 매체로 복사"
info "   2) 폐쇄망 대상 서버에서 sudo ./scripts/install.sh"
info "================================================================"

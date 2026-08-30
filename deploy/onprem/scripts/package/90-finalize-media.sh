#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 90-finalize-media.sh — [빌드머신] 반입물 위생 스윕 + 빌드 메타 기록 (반출 직전 마무리)
# @step 인터넷=불필요 | 소요=수 초 | 선행=앞 단계 전부 | 재실행=안전(멱등 — 지울 것이 없으면 아무것도 바꾸지 않는다)
#
#   매체는 deploy/onprem/ 폴더를 <통째로> 복사한 것이라, 이 트리의 상태가 곧 매체의 상태다.
#   그래서 반출 직전에 두 가지를 한다.
#     1) 위생 스윕 — 파이썬 바이트코드(__pycache__/*.pyc) · macOS 잔재(.DS_Store 등) 제거
#     2) VERSION.built 기록 — 빌드 시각 · 빌드머신 · git 커밋
#
#   ★ 왜 단계 스크립트인가 (2026-08-30 분리)
#     구 형상에서는 이 둘이 package.sh <파일 안의 블록>이라, 단계별 러너(package-step.sh)로
#     돌면 마무리가 <한 번도 실행되지 않았다>. 러너는 그 사실을 경고로만 알리고 "반출 전에
#     package.sh 를 한 번 더 돌리라"고 안내했는데, 그 재실행은 10·20 단계의 재빌드를 동반해
#     비용이 컸다. 단계로 떼어내면 두 경로가 <같은 마무리>를 돈다.
#     ⚠ package.sh 의 일괄 실행 결과는 이 분리 전후로 동일하다 — 목록(package/steps.sh)의
#       맨 끝에 등록돼 있어 앞 단계가 모두 끝난 뒤 같은 자리에서 실행된다.
#
#   ★ 반드시 <마지막>이다. 앞 단계(특히 70-generate-notices.sh)가 파일을 더 떨구므로,
#     중간에 두면 그 뒤에 생긴 잔재를 놓치고 VERSION.built 도 실제 내용보다 이르게 찍힌다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

ONPREM="$(onprem_root)"
[[ -n "${ONPREM}" && -d "${ONPREM}" ]] || die "[finalize] onprem 루트를 찾을 수 없습니다: ${ONPREM:-<빈 값>}"

# ---- 1) 반입물 위생 스윕(파이썬 바이트코드 · macOS 잔재) ----
#   수집 단계(30)가 자기 복사물은 이미 정리하지만, 그것만으로는 부족하다 — 빌드머신에서
#   누가 파이썬 모듈을 한 번 import 하기만 해도 그 자리에 __pycache__ 가 생기고 그대로
#   매체에 실린다(실제로 scripts/lib/__pycache__ 가 그렇게 들어가 있었다. 패키징이 만든 것이
#   아니다 — 스크립트로 실행하면 캐시를 안 쓰고, import 해야 생긴다).
#   빌드머신 파이썬이 대상(3.11)과 다르면 cpython-314 같은 남의 태그가 반입물에 남는다.
#   동작에는 무해하지만 심의에서 설명해야 할 자리이므로 반출 직전에 쓸어낸다.
_pyc_n="$(find "${ONPREM}" -name '*.pyc' -o -name '*.pyo' | wc -l | tr -d ' ')"
_pyd_n="$(find "${ONPREM}" -type d -name '__pycache__' | wc -l | tr -d ' ')"
if [[ "${_pyc_n}" != "0" || "${_pyd_n}" != "0" ]]; then
  warn "[위생] 파이썬 바이트코드 잔재 제거: __pycache__ ${_pyd_n}개 / 파일 ${_pyc_n}개"
  find "${ONPREM}" -type d -name '__pycache__' -prune -exec rm -rf {} +
  find "${ONPREM}" \( -name '*.pyc' -o -name '*.pyo' \) -delete
fi
ok "[위생] 파이썬 바이트코드 잔재 없음(반입물 확인)"
unset _pyc_n _pyd_n

# macOS 잔재 — 빌드머신이 맥이면 Finder 가 폴더를 한 번 열기만 해도 .DS_Store 가 생기고,
#   매체는 이 트리를 통째로 복사한 것이라 그대로 반입물이 된다(실제로 최상위 .DS_Store 가
#   실려 있었다). ★ 저장소 루트 .gitignore 가 .DS_Store 를 무시하므로 git status 에 뜨지
#   않는다 — 커밋 검토로는 영영 안 잡히고 반출 직전 스윕만이 유일한 방어선이다.
#   ._* / __MACOSX 는 맥이 비-HFS 매체(USB exFAT 등)에 복사할 때 만드는 사이드카다.
_mac_d="$(find "${ONPREM}" -type d \( -name '__MACOSX' -o -name '.AppleDouble' -o -name '.Spotlight-V100' -o -name '.Trashes' -o -name '.fseventsd' \) | wc -l | tr -d ' ')"
_mac_f="$(find "${ONPREM}" -type f \( -name '.DS_Store' -o -name '._*' \) | wc -l | tr -d ' ')"
if [[ "${_mac_d}" != "0" || "${_mac_f}" != "0" ]]; then
  warn "[위생] macOS 잔재 제거: 디렉터리 ${_mac_d}개 / 파일 ${_mac_f}개"
  find "${ONPREM}" -type d \( -name '__MACOSX' -o -name '.AppleDouble' -o -name '.Spotlight-V100' -o -name '.Trashes' -o -name '.fseventsd' \) -prune -exec rm -rf {} +
  find "${ONPREM}" -type f \( -name '.DS_Store' -o -name '._*' \) -delete
fi
ok "[위생] macOS 잔재 없음(반입물 확인)"
unset _mac_d _mac_f

# ---- 2) 패키지 버전 메타 기록 ----
{
  echo "package_built_at=$(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "package_built_on=$(uname -s) $(uname -m)"
  echo "git_commit=$(cd "$(repo_root)" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
} > "${ONPREM}/VERSION.built"
ok "패키지 메타 기록: ${ONPREM}/VERSION.built"

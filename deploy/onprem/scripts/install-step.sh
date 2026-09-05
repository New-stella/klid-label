#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# install-step.sh — 설치를 <한 단계씩> 수동으로 진행한다
#
#   왜 있나: 일괄 설치(install.sh)는 중간에 막히면 어디까지 됐는지 알기 어렵고,
#   고친 뒤 이어서 돌리기도 번거롭다. 폐쇄망에서는 현장에서 고쳐 가며 진행해야 한다.
#   ★ install.sh 일괄 실행은 <그대로 남아 있다>. 값을 다 아는 현장에서는 그쪽이 빠르다.
#     두 경로는 공존하며, 어느 쪽으로 돌려도 결과가 같다(모든 단계가 멱등).
#
#   사용법:
#     ./scripts/install-step.sh list                 # 단계 목록 보기(권한 불필요)
#     sudo ./scripts/install-step.sh 14              # 14 단계만 실행
#     sudo ./scripts/install-step.sh 14-install-frontend.sh
#     sudo KLID_ROLE=web ./scripts/install-step.sh 14
#
#   ★ 이 스크립트는 <단계 목록을 자기가 들고 있지 않다>. install/ 디렉터리에서 그때그때
#     찾는다 — 목록을 여기에 또 적으면 install.sh 와 어긋나는 두 번째 진실원이 된다.
#   ★ 역할·선행조건·재실행 안전 여부는 docs/03-install.md 「단계별 수동 실행」 표가 정본이다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"

STEP_DIR="${SELF_DIR}/install"

usage() {
  sed -n '1,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

list_steps() {
  info "설치 단계 (${STEP_DIR})"
  info "  역할·선행조건·재실행 안전 여부는 docs/03-install.md 「단계별 수동 실행」 표를 보세요."
  echo
  local f base
  # 번호 접두가 있는 것만 = install.sh 가 부르는 단계. 번호 없는 것(install-ffmpeg 등)은
  # 사람이 명시적으로 부르는 수동 명령이라 목록에 넣지 않는다.
  for f in "${STEP_DIR}"/[0-9][0-9]-*.sh; do
    [[ -e "${f}" ]] || continue
    base="$(basename "${f}")"
    # 스크립트 머리말의 첫 설명 줄을 그대로 보여준다(설명을 여기에 복제하지 않는다).
    printf '  %-34s %s\n' "${base}" \
      "$(sed -n 's/^# \{0,1\}[0-9][0-9]-[a-z-]*\.sh — \(.*\)$/\1/p' "${f}" | head -1)"
  done
  echo
  info "실행:  sudo KLID_ROLE=<was|web|ai|app|all> ${BASH_SOURCE[0]} <번호>"
}

[[ $# -ge 1 ]] || { usage; exit 1; }

case "${1}" in
  -h|--help|help) usage; exit 0 ;;
  list|--list)    list_steps; exit 0 ;;
esac

# ---- 대상 단계 찾기 ----
want="${1}"
matches=()
for f in "${STEP_DIR}"/[0-9][0-9]-*.sh; do
  [[ -e "${f}" ]] || continue
  base="$(basename "${f}")"
  if [[ "${base}" == "${want}" || "${base}" == "${want}-"* ]]; then
    matches+=("${f}")
  fi
done

if [[ "${#matches[@]}" -eq 0 ]]; then
  die "그런 단계가 없습니다: ${want}
     목록 보기:  ${BASH_SOURCE[0]} list"
fi
if [[ "${#matches[@]}" -gt 1 ]]; then
  die "이름이 여러 단계와 겹칩니다: ${want} → ${matches[*]}
     스크립트 이름을 그대로 주세요."
fi
target="${matches[0]}"

# ---- 역할을 눈에 보이게 알린다 ----
#   2대 구성에서 역할을 빠뜨리면 <그 장비에서 돌면 안 되는 단계>가 돈다. 다만 단일 서버
#   구성에서는 all 이 맞으므로 강제하지 않고, 무엇으로 도는지 반드시 보여 준다.
info "역할: ${KLID_ROLE}  (단일 서버면 all|app, 장비가 갈렸으면 was|web|ai 를 명시하세요)"
info "실행: $(basename "${target}")"
echo

bash "${target}"

ok "완료: $(basename "${target}")"
info "다음 단계는 docs/03-install.md 「단계별 수동 실행」 표를 보세요."

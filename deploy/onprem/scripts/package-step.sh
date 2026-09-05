#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# package-step.sh — 매체 생성(패키징)을 <한 단계씩> 진행한다
#
#   왜 있나: 일괄 수집(package.sh)은 중간에 한 단계가 깨지면 `set -e` 로 그 자리에서 죽고,
#   <뒤 단계가 통째로 실행되지 않는다>. 실제로 그 일이 났다 — torch 다운로드가 실패해
#   런타임·시스템 패키지·데이터베이스 수집이 한 번도 돌지 않았고, 사람이 손으로 나눠 다시
#   돌려야 했다. 매체 생성은 수 시간짜리라 처음부터 다시 돌리는 비용이 크다.
#   ★ package.sh 일괄 실행은 <그대로 남아 있다>. 처음부터 끝까지 갈 때는 그쪽이 맞다.
#     두 경로는 같은 목록(package/steps.sh)을 읽으므로 어느 쪽으로 돌려도 결과가 같다.
#
#   사용법:
#     ./scripts/package-step.sh list          # 단계 목록 보기
#     ./scripts/package-step.sh 30            # 30 단계만 실행
#     ./scripts/package-step.sh 30-collect-ai-server.sh
#     ./scripts/package-step.sh from 40       # 40 부터 끝까지 이어서 실행  ← 막혔을 때의 주 동선
#     SKIP_FFMPEG=1 ./scripts/package-step.sh from 40      # 토글은 그대로 먹는다
#
#   ★ 이 스크립트는 <단계 목록을 자기가 들고 있지 않다>. 순서·토글 규칙은
#     package/steps.sh 가, 각 단계의 설명·소요·선행조건은 각 스크립트 머리말의 `# @step`
#     줄이 정본이다 — 여기에 또 적으면 package.sh 와 어긋나는 두 번째 진실원이 된다.
#   ★ 재실행 안전 여부(멱등)는 docs/02-build-package.md 「단계별 실행」 표가 정본이다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"
# shellcheck source=package/steps.sh
source "${SELF_DIR}/package/steps.sh"

STEP_DIR="${SELF_DIR}/package"

usage() {
  sed -n '1,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# step_desc <파일> — 머리말의 첫 설명 줄(설명을 여기에 복제하지 않는다)
step_desc() {
  sed -n 's/^# \{0,1\}[0-9][0-9]-[a-z0-9-]*\.sh — \(.*\)$/\1/p' "$1" | head -1
}
# step_meta <파일> — `# @step ...` 한 줄(인터넷/소요/선행/재실행)
step_meta() {
  sed -n 's/^# \{0,1\}@step \(.*\)$/\1/p' "$1" | head -1
}

# in_batch <파일명> — 이번 실행 조건에서 package.sh 가 부르는 단계인가
in_batch() {
  local want="$1" s
  while IFS= read -r s; do
    [[ "${s}" == "${want}" ]] && return 0
  done < <(package_step_list)
  return 1
}

list_steps() {
  info "매체 생성 단계 (${STEP_DIR})"
  info "  재실행 안전 여부·상세는 docs/02-build-package.md 「단계별 실행」 표를 보세요."
  echo
  local f base
  for f in "${STEP_DIR}"/[0-9][0-9]-*.sh; do
    [[ -e "${f}" ]] || continue
    base="$(basename "${f}")"
    if in_batch "${base}"; then
      printf '  %-32s %s\n' "${base}" "$(step_desc "${f}")"
    else
      # 일괄 실행 목록 밖 — 30 이 안에서 부르거나(35), 토글로 꺼져 있는(60) 단계다.
      printf '  %-32s %s  [일괄 목록 밖]\n' "${base}" "$(step_desc "${f}")"
    fi
    printf '  %-32s   %s\n' "" "$(step_meta "${f}")"
  done
  echo
  package_step_explain
  echo
  info "한 단계만:   ${BASH_SOURCE[0]} <번호>"
  info "이어서:      ${BASH_SOURCE[0]} from <번호>      # 그 번호부터 끝까지"
}

# resolve_step <번호|파일명> — 단계 파일 경로를 stdout 으로. 없거나 모호하면 die.
resolve_step() {
  local want="$1" f base
  local matches=()
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
  printf '%s\n' "${matches[0]}"
}

# run_step <파일경로> — 실행 + 소요 시간. 실패하면 <이어서 돌리는 법>을 알려 주고 죽는다.
run_step() {
  local script="$1" base t0 t1
  base="$(basename "${script}")"
  info "---- 실행: ${base} ----"
  t0="$(date +%s)"
  if ! bash "${script}"; then
    t1="$(date +%s)"
    warn "실패: ${base}  ($((t1 - t0))초 경과)"
    warn "  원인을 고친 뒤 <이 단계부터> 이어서 돌리세요:"
    warn "    ${BASH_SOURCE[0]} from ${base%%-*}"
    return 1
  fi
  t1="$(date +%s)"
  ok "완료: ${base}  ($((t1 - t0))초)"
  return 0
}

[[ $# -ge 1 ]] || { usage; exit 1; }

case "${1}" in
  -h|--help|help) usage; exit 0 ;;
  list|--list)    list_steps; exit 0 ;;
esac

# ---- 빌드머신 정합은 <막지 않고 알린다> ----
#   package.sh 는 여기서 confirm 으로 멈춰 세운다(처음부터 끝까지 도는 실행이라 그게 맞다).
#   이 러너는 <이미 돌던 작업을 고쳐서 이어가는> 자리라, 매 호출마다 대화형 확인을 요구하면
#   그 고쳐-이어가기 반복이 도리어 힘들어진다. 그래서 경고만 하고 진행한다.
_us="$(uname -s)"; _um="$(uname -m)"
info "빌드머신: ${_us} ${_um}"
if [[ "${_us}" != "Linux" || "${_um}" != "x86_64" ]]; then
  warn "타깃은 레드햇 엔터프라이즈 리눅스 8.9 x86_64 입니다 — OS 종속 산출물(pip wheel / RPM)은"
  warn "  el8 컨테이너/머신에서 수집하세요(02-build-package.md). RPM(50·55)은 docker 로 자동 기동합니다."
fi

case "${1}" in
  from|--from|resume)
    [[ $# -ge 2 ]] || die "이어서 시작할 단계 번호를 주세요.  예: ${BASH_SOURCE[0]} from 40"
    start="$(basename "$(resolve_step "${2}")")"
    # ★ 이어 돌릴 순서는 package.sh 와 <같은 목록>에서 얻는다(package/steps.sh).
    steps=()
    while IFS= read -r s; do [[ -n "${s}" ]] && steps+=("${s}"); done < <(package_step_list)

    # 시작 단계가 일괄 목록 안에 있어야 "그 뒤"가 정의된다.
    found=0
    for s in "${steps[@]}"; do [[ "${s}" == "${start}" ]] && found=1; done
    if [[ "${found}" -eq 0 ]]; then
      die "${start} 은 이번 실행의 일괄 목록에 없어 <이어서> 돌 수 없습니다.
     · 35 는 30 이 안에서 부릅니다 → 단독으로만 실행하세요: ${BASH_SOURCE[0]} 35
     · 60 은 기본 제외입니다 → WITH_BUILDTOOLS=1 을 주고 다시 부르세요.
     목록 보기: ${BASH_SOURCE[0]} list"
    fi

    run=0
    for s in "${steps[@]}"; do
      [[ "${s}" == "${start}" ]] && run=1
      [[ "${run}" -eq 1 ]] || continue
      run_step "${STEP_DIR}/${s}"
    done

    echo
    ok "이어서 실행 완료: ${start} → ${steps[${#steps[@]}-1]}"
    # ---- 마무리는 이제 <단계>다 ----
    #   반입물 위생 스윕과 VERSION.built 기록은 목록의 맨 끝 단계(90-finalize-media.sh)이므로
    #   `from` 실행은 그 단계까지 도달해 마무리를 <스스로> 끝낸다.
    #   ⚠ 구 동작 폐기(2026-08-30) — 그 둘이 package.sh 파일 안의 블록이라 이 러너로는 부를 수
    #     없었고, "반출 전에 package.sh 를 한 번 더 돌리라"고 경고만 했다(10·20 재빌드 동반).
    ok "마무리(위생 스윕 · VERSION.built)까지 끝났습니다 — 이 매체는 반출 준비 상태입니다."
    ;;
  *)
    target="$(resolve_step "${1}")"
    run_step "${target}"
    echo
    info "다음 단계는 목록에서 확인하세요:  ${BASH_SOURCE[0]} list"
    info "여기부터 끝까지 이어가려면:      ${BASH_SOURCE[0]} from $(basename "${target}" | cut -d- -f1)"
    # 단일 단계 실행은 <그 단계만> 돈다 — 마무리 단계(90)에 도달하지 않는다.
    if [[ "$(basename "${target}")" != "90-finalize-media.sh" ]]; then
      info "반출 전 마무리(위생 스윕 · VERSION.built)는 아직입니다: ${BASH_SOURCE[0]} 90"
    fi
    ;;
esac

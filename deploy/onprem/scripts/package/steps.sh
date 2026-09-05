#!/usr/bin/env bash
# ============================================================================
# steps.sh — 매체 생성 <단계 목록과 그 순서>의 단일 진실원
#
#   package.sh(일괄 실행)와 package-step.sh(단계별 실행)가 <같은 목록·같은 순서>로 돌아야
#   한다. 두 곳에 각각 적으면 어느 한쪽만 갱신되어 "일괄로는 되는데 단계별로는 빠지는 단계"가
#   생긴다. 그래서 목록은 여기 한 곳에만 있고 둘 다 이 파일을 읽는다.
#
#   ★ 이 파일은 <목록만> 안다. 각 단계가 무엇을 하는지·얼마나 걸리는지는 각 스크립트
#     머리말의 `# @step` 줄에 있고, 러너가 거기서 읽는다(설명을 여기에 복제하지 않는다).
#
#   사용:
#     source "${SELF_DIR}/package/steps.sh"
#     package_step_explain            # 토글 상태를 사람에게 알린다(stdout)
#     while IFS= read -r s; do STEPS+=("${s}"); done < <(package_step_list)
# ============================================================================

# package_step_buildtools_on — 오프라인 빌드 키트(60)를 이번 실행에 포함하는가
#   기본 제외(2026-08-30 반전). WITH_BUILDTOOLS=1 로 켜고, 구 토글 SKIP_BUILDTOOLS=1 도
#   계속 존중한다. 둘이 충돌하면 제외가 이긴다(안전한 쪽).
package_step_buildtools_on() {
  [[ "${WITH_BUILDTOOLS:-0}" == "1" && "${SKIP_BUILDTOOLS:-0}" != "1" ]]
}

# package_step_list — 실행 순서대로 단계 스크립트 파일명을 한 줄에 하나씩 출력한다.
#   ★ 이 함수는 <파일명만> 출력한다. 로그를 섞으면 호출부의 $( ) 캡처가 오염된다.
package_step_list() {
  printf '%s\n' \
    "10-build-backend.sh" \
    "20-build-frontend.sh" \
    "30-collect-ai-server.sh" \
    "40-collect-runtimes.sh" \
    "50-collect-syspkgs.sh" \

  # 오프라인 빌드 키트(소스 재빌드용) — 기본 제외, WITH_BUILDTOOLS=1 로 켠다.
  if package_step_buildtools_on; then
    printf '%s\n' "60-collect-buildtools.sh"
  fi

  # 카피레프트 대응 소스 수집 — 고지 생성 <앞>에 둔다. 70 단계가 그 결과를 근거로
  #   "실었는지 / 서면 확약으로 대신하는지"를 판정하기 때문이다.
  printf '%s\n' "65-collect-copyleft-sources.sh"

  # 라이선스 고지 집합 생성 — 수집 단계 중 <가장 마지막>이다. 앞 단계들이 각자 떨군 고지를
  #   모아 NOTICE·INVENTORY·UNRESOLVED 를 만든다. 중간에 두면 그 시점에 없던 수집물이 빠진다.
  #   ⚠ 이 단계를 끄는 토글을 두지 않는다 — 고지 없는 매체는 반출 자체가 위반이다.
  printf '%s\n' "70-generate-notices.sh"

  # 반출 직전 마무리 — 위생 스윕 + VERSION.built 기록. <목록의 맨 끝>이어야 한다.
  #   70 단계가 고지 파일을 더 떨구므로 그보다 앞에 두면 그 뒤 잔재를 놓치고 빌드 메타도
  #   실제 내용보다 이르게 찍힌다.
  #   ★ 구 형상에서는 이 마무리가 package.sh <파일 안의 블록>이라 단계별 러너로 돌면 한 번도
  #     실행되지 않았다. 단계로 등록해 두 경로가 같은 마무리를 돌게 한다(2026-08-30).
  #   ⚠ 끄는 토글을 두지 않는다 — 위생 스윕을 건너뛴 매체는 심의에서 설명할 잔재를 안고 나간다.
  printf '%s\n' "90-finalize-media.sh"
}

# package_step_explain — 토글로 갈리는 부분을 사람에게 알린다.
package_step_explain() {
  if package_step_buildtools_on; then
    info "오프라인 빌드 키트 수집 포함(WITH_BUILDTOOLS=1) — buildtools/ 와 src/ 가 채워집니다."
  else
    info "오프라인 빌드 키트 수집 생략(기본값) — 사전 빌드 아티팩트만 번들합니다."
    info "  타깃에서 소스 재빌드가 필요하면 WITH_BUILDTOOLS=1 로 다시 수집하세요."
  fi
}

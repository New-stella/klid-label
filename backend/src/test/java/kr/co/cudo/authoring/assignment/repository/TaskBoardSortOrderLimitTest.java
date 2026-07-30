package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.common.util.SortAllowlist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업목록 리포지토리의 정렬 항목 개수 상한이 <b>allowlist 에서 파생</b>되는지 고정한다 (CWE-770 드리프트 가드).
 *
 * <p>{@code TaskBoardQueryRepository} 는 컨트롤러({@code SortAllowlist.resolve})가 이미 검증한 정렬을
 * 리포지토리에서도 fail-closed 로 재검증한다. 상한을 리포지토리에 <b>하드코딩</b>하면 allowlist 를 확장할 때
 * 두 정의가 조용히 어긋나 "컨트롤러는 통과시키는데 리포지토리가 400" 이 된다 —
 * {@code SortAllowlist#maxOrders} javadoc 이 "상한은 이 메서드에서만 파생" 을 명시하는 이유다.
 *
 * <p>이 테스트는 그 파생 관계를 고정한다. 누군가 상수를 숫자로 되돌리면(예: {@code = 3}) allowlist 에
 * 4번째 고유 필드가 추가되는 순간 RED 가 된다.
 */
class TaskBoardSortOrderLimitTest {

    @Test
    @DisplayName("작업목록_리포지토리_정렬_상한은_TASK_BOARD_allowlist에서_파생된다")
    void repositoryLimitDerivedFromTaskBoardAllowlist() {
        assertThat(TaskBoardQueryRepository.MAX_SORT_ORDERS)
                .as("리포지토리 상한이 allowlist 고유 엔티티 필드 수와 어긋나면 컨트롤러 통과분이 "
                        + "리포지토리에서 400 으로 죽는다")
                .isEqualTo(SortAllowlist.maxOrders(SortAllowlist.TASK_BOARD));
    }

    @Test
    @DisplayName("현재_작업목록_정렬_가능_필드는_regDt_shtDt_rawSn_3종이다")
    void taskBoardSortableFieldsAreThree() {
        // 파생값의 현재 실값 기록 — 리포지토리 orderSpecifiers 의 switch(regDt/shtDt/rawSn)와 일치해야 한다.
        assertThat(SortAllowlist.TASK_BOARD.values()).containsOnly("regDt", "shtDt", "rawSn");
        assertThat(TaskBoardQueryRepository.MAX_SORT_ORDERS).isEqualTo(3);
    }
}

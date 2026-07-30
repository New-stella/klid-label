package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.common.util.SortAllowlist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배정 목록 리포지토리의 정렬 항목 개수 상한이 <b>allowlist 에서 파생</b>되는지 고정한다 (CWE-770 드리프트 가드).
 *
 * <p>{@code AssignmentQueryRepository} 는 컨트롤러({@code SortAllowlist.resolve})가 이미 검증한 정렬을
 * 리포지토리에서도 fail-closed 로 재검증한다. 상한을 하드코딩하면 allowlist 확장 시 두 정의가 조용히
 * 어긋나 "컨트롤러는 통과시키는데 리포지토리가 400" 이 된다({@code TaskBoardSortOrderLimitTest} 와 동일 취지).
 */
class AssignmentSortOrderLimitTest {

    @Test
    @DisplayName("배정목록_리포지토리_정렬_상한은_ASSIGNMENT_allowlist에서_파생된다")
    void repositoryLimitDerivedFromAssignmentAllowlist() {
        assertThat(AssignmentQueryRepository.MAX_SORT_ORDERS)
                .as("리포지토리 상한이 allowlist 고유 엔티티 필드 수와 어긋나면 컨트롤러 통과분이 "
                        + "리포지토리에서 400 으로 죽는다")
                .isEqualTo(SortAllowlist.maxOrders(SortAllowlist.ASSIGNMENT));
    }

    @Test
    @DisplayName("현재_배정목록_정렬_가능_필드는_regDt_rawDataId_assignmentId_3종이다")
    void assignmentSortableFieldsAreThree() {
        // 파생값의 현재 실값 기록 — 리포지토리 orderSpecifiers 의 switch 와 일치해야 한다.
        assertThat(SortAllowlist.ASSIGNMENT.values())
                .containsOnly("regDt", "rawDataId", "assignmentId");
        assertThat(AssignmentQueryRepository.MAX_SORT_ORDERS).isEqualTo(3);
    }
}

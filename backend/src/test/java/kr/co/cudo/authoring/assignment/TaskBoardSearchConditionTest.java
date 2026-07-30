package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.domain.BoardWorkStatus;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaskBoardSearchCondition} 단위 검증 — 특히 이벤트유형 옵션 조회의 방어({@code statusOnly()}).
 *
 * <p><b>왜 컨트롤러 경유 테스트로 대체할 수 없는가</b> — 옵션 엔드포인트는 {@code status} 만 파라미터로
 * 선언하므로, MockMvc 로 {@code q}/{@code workerId} 를 보내도 그 값은 조건 객체에 <b>애초에 도달하지
 * 못한다</b>. 그런 테스트는 방어를 제거해도 GREEN 이라 "옵션이 다른 필터로 좁혀지면 안 된다"는 요구를
 * 지키지 못한다. 여기서는 조건 객체를 직접 만들어 방어 자체를 단언한다.
 */
class TaskBoardSearchConditionTest {

    @Test
    @DisplayName("statusOnly_는_status_외_전_필드를_null_로_만든다")
    void statusOnlyClearsEveryNonStatusField() {
        // given — 모든 축이 채워진 조건
        TaskBoardSearchCondition full =
                new TaskBoardSearchCondition("COMPLETED", "REJECTED", "강남", "EVT-FIRE", 100L);

        // when
        TaskBoardSearchCondition stripped = full.statusOnly();

        // then — status 만 남고 나머지는 전부 사라진다
        assertThat(stripped.status()).isEqualTo("COMPLETED");
        assertThat(stripped.workStatus()).isNull();
        assertThat(stripped.q()).isNull();
        assertThat(stripped.eventTypeCd()).isNull();
        assertThat(stripped.workerId()).isNull();
        // 파생 접근자도 필터 미적용 상태여야 한다(조립기가 이 값들을 읽는다).
        assertThat(stripped.workStatusFilter()).isEmpty();
    }

    @Test
    @DisplayName("statusOnly_는_status_해석_기본값과_UNASSIGNED_가상값을_보존한다")
    void statusOnlyPreservesStatusSemantics() {
        // given/when — 기본값(미지정) · 명시값 · 가상값 세 갈래
        TaskBoardSearchCondition defaults =
                new TaskBoardSearchCondition(null, "PENDING", "강남", "EVT-FIRE", 100L).statusOnly();
        TaskBoardSearchCondition explicit =
                new TaskBoardSearchCondition("PENDING", null, "강남", null, 1L).statusOnly();
        TaskBoardSearchCondition virtual =
                new TaskBoardSearchCondition("UNASSIGNED", "COMPLETED", "강남", "EVT-FIRE", 100L).statusOnly();

        // then
        assertThat(defaults.status()).isEqualTo(TaskBoardSearchCondition.DEFAULT_BATCH_STATUS);
        assertThat(defaults.batchStatusFilter()).isEqualTo(TaskBoardSearchCondition.DEFAULT_BATCH_STATUS);

        assertThat(explicit.batchStatusFilter()).isEqualTo("PENDING");
        assertThat(explicit.unassignedOnly()).isFalse();

        assertThat(virtual.unassignedOnly()).isTrue();
        assertThat(virtual.batchStatusFilter()).as("미배정 가상 status 는 배치 상태를 거르지 않는다").isNull();
    }

    @Test
    @DisplayName("빈문자열_공백만_status_는_기본값으로_정규화된다")
    void blankStatusFallsBackToDefault() {
        // given/when/then — 신규 엔드포인트가 `status=` 를 400 이 아니라 기본값으로 받도록 하는 근거
        for (String blank : new String[] {"", "   ", "\t"}) {
            TaskBoardSearchCondition condition =
                    new TaskBoardSearchCondition(blank, null, null, null, null);
            assertThat(condition.status())
                    .as("blank status=%s", blank.isEmpty() ? "(empty)" : blank)
                    .isEqualTo(TaskBoardSearchCondition.DEFAULT_BATCH_STATUS);
        }
    }

    @Test
    @DisplayName("빈문자열_공백만_필터는_null_로_정규화되어_조건이_걸리지_않는다")
    void blankFiltersAreNormalizedToNull() {
        // given/when
        TaskBoardSearchCondition condition =
                new TaskBoardSearchCondition("COMPLETED", "  ", "", " \t ", null);

        // then — R8: 신규 필터가 실질적으로 비어 있으면 조건이 걸리지 않는다
        assertThat(condition.workStatus()).isNull();
        assertThat(condition.q()).isNull();
        assertThat(condition.eventTypeCd()).isNull();
        assertThat(condition.workStatusFilter()).isEmpty();
    }

    @Test
    @DisplayName("workStatus_는_정의된_코드만_파싱된다")
    void workStatusParsesDefinedCodesOnly() {
        // given/when/then
        assertThat(new TaskBoardSearchCondition("COMPLETED", " REJECTED ", null, null, null)
                .workStatusFilter())
                .contains(BoardWorkStatus.REJECTED);
        assertThat(new TaskBoardSearchCondition("COMPLETED", "rejected", null, null, null)
                .workStatusFilter())
                .as("소문자 표기는 미정의 코드 = 필터 미적용")
                .isEmpty();
    }
}

package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.domain.BoardWorkStatus;
import kr.co.cudo.authoring.common.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 (HIGH-3) — 작업목록 워크플로 상태(workStatus)의 <b>표시 매핑 ↔ 필터 조건 골든 교차 검증</b>.
 *
 * <p>{@code workStatus} 는 DB 컬럼이 아니라 (LS_RAW_DATA_STATUS.DATA_STTS_CD + LABELER 배정 유무) 로
 * 계산되는 값이다. 화면 뱃지를 만드는 정방향 매핑({@link BoardWorkStatus#of})과 WHERE 절을 만드는
 * 역매핑({@link BoardWorkStatus#matches} 가 표현하는 필터 조건)이 한 글자라도 어긋나면
 * "필터 결과와 화면 뱃지가 불일치"하는 결함이 된다. 두 방향이 <b>모든 상태 × 배정 조합</b>에서
 * 정확히 동치임을 전수 검증한다.
 */
class BoardWorkStatusTest {

    /** null(상태 row 없음) · 정의된 5종 · 미래에 추가될 수 있는 미지 코드까지 포함한다. */
    private static final List<String> ALL_STATUS_CODES = Arrays.asList(
            null, "ASSIGNED", "PENDING", "IN_REVIEW", "APPROVED", "REJECTED", "BATCH_QUEUED", "UNKNOWN_FUTURE");

    @Test
    @DisplayName("모든_상태조합에서_표시매핑과_필터조건이_정확히_일치한다")
    void goldenCrossMappingMatchesFilter() {
        List<String> mismatches = new ArrayList<>();

        for (String statusCd : ALL_STATUS_CODES) {
            for (boolean hasLabeler : new boolean[]{true, false}) {
                BoardWorkStatus displayed = BoardWorkStatus.of(statusCd, hasLabeler);
                assertThat(displayed).as("표시 매핑은 항상 하나로 확정돼야 한다").isNotNull();

                for (BoardWorkStatus candidate : BoardWorkStatus.values()) {
                    boolean matchedByFilter = candidate.matches(statusCd, hasLabeler);
                    boolean shouldMatch = candidate == displayed;
                    if (matchedByFilter != shouldMatch) {
                        mismatches.add("statusCd=" + statusCd + ", hasLabeler=" + hasLabeler
                                + ", filter=" + candidate + ", displayed=" + displayed
                                + ", matched=" + matchedByFilter);
                    }
                }
            }
        }

        assertThat(mismatches).as("필터 조건 ↔ 표시 매핑 불일치").isEmpty();
    }

    @Test
    @DisplayName("배정이_없으면_상태와_무관하게_미배정으로_매핑된다")
    void unassignedWinsOverStatus() {
        for (String statusCd : ALL_STATUS_CODES) {
            assertThat(BoardWorkStatus.of(statusCd, false)).isEqualTo(BoardWorkStatus.UNASSIGNED);
        }
    }

    @Test
    @DisplayName("상태row가_없고_배정이_있으면_PENDING으로_매핑된다")
    void nullStatusWithLabelerIsPending() {
        assertThat(BoardWorkStatus.of(null, true)).isEqualTo(BoardWorkStatus.PENDING);
        assertThat(BoardWorkStatus.PENDING.matches(null, true)).isTrue();
    }

    @Test
    @DisplayName("정의된_5종_코드는_그대로_파싱되고_빈값은_필터_미적용이다")
    void parseAcceptsDefinedCodes() {
        assertThat(BoardWorkStatus.parse("REVIEW_PENDING")).contains(BoardWorkStatus.REVIEW_PENDING);
        assertThat(BoardWorkStatus.parse("UNASSIGNED")).contains(BoardWorkStatus.UNASSIGNED);
        // 빈 값만 "필터 미적용" 을 뜻한다.
        assertThat(BoardWorkStatus.parse(null)).isEmpty();
        assertThat(BoardWorkStatus.parse("  ")).isEmpty();
    }

    @Test
    @DisplayName("미정의_코드는_400으로_거부한다_필터를_조용히_버리지_않는다")
    void parseRejectsUndefinedCodes() {
        // 계약 변경(2026-07-30) — 구 구현은 미정의 코드도 빈값으로 흘려 <필터가 사라진 전체 목록>을
        // 반환하는 fail-open 이었다. assignments 축(AssignmentWorkStatus)과 동일하게 fail-closed 로 맞춘다.
        assertThatThrownBy(() -> BoardWorkStatus.parse("'; DROP TABLE LS_DATA_RAW; --"))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> BoardWorkStatus.parse("pending"))
                .as("코드는 대문자 정규 표기만 허용")
                .isInstanceOf(CustomException.class);
    }
}

package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 실패 사유 → 사용자 문구 변환 단위 테스트. [@design API-043]
 *
 * <p>핵심 수용 기준은 <b>내부 원문이 절대 새지 않는다</b>는 것이다 — 이 정책은 {@code ERR_MSG_CN} 을
 * 입력으로 받지도 않으며, 모르는 {@code ERR_CD} 는 문구를 덧붙이지 않고 단계 기본값으로 폴백한다.
 */
class BatchFailureReasonPolicyTest {

    @Test
    @DisplayName("실패가_아니면_사유는_null이다")
    void nullWhenNotFailed() {
        // given / when / then — 진행 중(STARTED)·완료(COMPLETED)·상태 미상 모두 사유 없음.
        assertThat(BatchFailureReasonPolicy.describe(BatchStage.YOLO.name(), "STARTED", null)).isNull();
        assertThat(BatchFailureReasonPolicy.describe(BatchStage.COMPLETED.name(), "COMPLETED", null)).isNull();
        assertThat(BatchFailureReasonPolicy.describe(BatchStage.YOLO.name(), null, null)).isNull();
    }

    @Test
    @DisplayName("프레임추출_실패는_프레임_추출_문구를_돌려준다")
    void frameExtractMessage() {
        // given / when
        String reason = BatchFailureReasonPolicy.describe(
                BatchStage.FRAME_EXTRACT.name(), "FAILED", null);

        // then
        assertThat(reason).isEqualTo("영상에서 프레임을 추출하지 못했습니다.");
    }

    @Test
    @DisplayName("단계를_특정할_수_없는_실패에도_일반_사유가_내려간다")
    void unknownStageFallsBackToGeneric() {
        // given — dev 실측: PROC_STEP_CD='FAILED' (표시 단계가 아님) 인 실패 행이 실재한다.
        //   이 경우 stages 배열은 비어 있으므로, 사유마저 없으면 화면이 아무것도 보여줄 수 없다.
        // when / then
        assertThat(BatchFailureReasonPolicy.describe("FAILED", "FAILED", null))
                .isEqualTo(BatchFailureReasonPolicy.GENERIC);
        assertThat(BatchFailureReasonPolicy.describe(null, "FAILED", null))
                .isEqualTo(BatchFailureReasonPolicy.GENERIC);
        assertThat(BatchFailureReasonPolicy.describe("듣도보도못한단계", "FAILED", null))
                .isEqualTo(BatchFailureReasonPolicy.GENERIC);
    }

    @Test
    @DisplayName("알려진_원인유형이면_단계문구에_원인문구가_덧붙는다")
    void knownCauseAppended() {
        // given / when — dev 실측 ERR_CD 중 하나.
        String reason = BatchFailureReasonPolicy.describe(
                BatchStage.FRAME_EXTRACT.name(), "FAILED", "DataIntegrityViolationException");

        // then
        assertThat(reason)
                .startsWith("영상에서 프레임을 추출하지 못했습니다.")
                .contains("데이터 제약 조건 위반");
    }

    @Test
    @DisplayName("모르는_원인유형은_단계_기본문구로_폴백하고_원문을_덧붙이지_않는다")
    void unknownCauseFallsBack() {
        // given — dev 실측상 가장 흔한 ERR_CD 는 CustomException 이며 매핑 대상이 아니다.
        // when
        String reason = BatchFailureReasonPolicy.describe(
                BatchStage.YOLO.name(), "FAILED", "CustomException");

        // then — 단계 기본 문구 그대로. 원인 문구가 붙지 않는다.
        assertThat(reason).isEqualTo("AI 추론 서버 호출에 실패했습니다.");
    }

    @Test
    @DisplayName("사유_문구에는_DB제약명이나_SQL원문이_들어가지_않는다")
    void neverLeaksInternals() {
        // given — 정책은 ERR_MSG_CN 을 인자로 받지도 않으므로 구조적으로 샐 수 없다.
        //   여기서는 모든 단계·모든 매핑 조합의 산출물이 상수 문구뿐임을 확인한다.
        for (BatchStage stage : BatchStage.values()) {
            // when
            String reason = BatchFailureReasonPolicy.describe(
                    stage.name(), "FAILED", "DataIntegrityViolationException");

            // then
            assertThat(reason)
                    .doesNotContain("constraint")
                    .doesNotContain("uk_")
                    .doesNotContain("ERROR:")
                    .doesNotContain("select ")
                    .doesNotContain("/")
                    .doesNotContain("Exception");
        }
    }
}

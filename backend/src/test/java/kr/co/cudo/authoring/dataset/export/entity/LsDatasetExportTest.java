package kr.co.cudo.authoring.dataset.export.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LsDatasetExport} 상태 전이 단위 테스트.
 *
 * <p>상태 변경은 {@code @Setter} 가 아닌 의미 있는 비즈니스 메서드로만 수행되며,
 * 각 전이가 {@code EXPORT_STTS_CD} 와 산출 프레임 수를 올바르게 반영하는지 검증한다.
 */
class LsDatasetExportTest {

    @Test
    @DisplayName("생성직후_상태는_PENDING이다")
    void createStartsPending() {
        LsDatasetExport export = LsDatasetExport.create(7L, 1, "/root/7/v1", "h1");

        assertThat(export.getExportSttsCd()).isEqualTo(LsDatasetExport.STATUS_PENDING);
    }

    @Test
    @DisplayName("markSucceeded는_SUCCEEDED로_전이하고_프레임수를_반영한다")
    void markSucceededTransitions() {
        LsDatasetExport export = LsDatasetExport.create(7L, 1, "/root/7/v1", "h1");

        export.markSucceeded(10);

        assertThat(export.getExportSttsCd()).isEqualTo(LsDatasetExport.STATUS_SUCCEEDED);
        assertThat(export.getFrameCnt()).isEqualTo(10);
    }

    @Test
    @DisplayName("markPartial은_PARTIAL로_전이하고_프레임수를_반영한다")
    void markPartialTransitions() {
        // given — PENDING 예약 레코드
        LsDatasetExport export = LsDatasetExport.create(7L, 1, "/root/7/v1", "h1");

        // when — 일부 프레임만 산출됨
        export.markPartial(6);

        // then — PARTIAL 전이 + 정상 기록된 프레임 수 반영
        assertThat(export.getExportSttsCd()).isEqualTo(LsDatasetExport.STATUS_PARTIAL);
        assertThat(export.getFrameCnt()).isEqualTo(6);
    }

    @Test
    @DisplayName("markFailed는_FAILED로_전이한다")
    void markFailedTransitions() {
        LsDatasetExport export = LsDatasetExport.create(7L, 1, "/root/7/v1", "h1");

        export.markFailed();

        assertThat(export.getExportSttsCd()).isEqualTo(LsDatasetExport.STATUS_FAILED);
    }
}

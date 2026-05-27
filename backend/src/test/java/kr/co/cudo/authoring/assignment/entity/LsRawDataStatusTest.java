package kr.co.cudo.authoring.assignment.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LsRawDataStatus 엔티티 단위 테스트.
 */
class LsRawDataStatusTest {

    @Test
    @DisplayName("markBatchQueued_호출시_BATCH_QUEUED_전이")
    void markBatchQueued() {
        // given
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);
        stts.markAssigned();

        // when
        stts.markBatchQueued();

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
        assertThat(stts.getUpdDt()).isNotNull();
    }

    @Test
    @DisplayName("initial_생성시_PENDING_상태")
    void initialStatus() {
        // given / when
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_PENDING);
    }

    @Test
    @DisplayName("markAssigned_호출시_ASSIGNED_전이")
    void markAssigned() {
        // given
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);

        // when
        stts.markAssigned();

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
    }
}

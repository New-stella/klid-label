package kr.co.cudo.authoring.augment.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 — LS_DATA_AUG 비동기 표준 컬럼 4종 단위 테스트.
 *
 * <p>{@link kr.co.cudo.authoring.batch.entity.LsDataMeta} 와 동일한 패턴.
 */
class LsDataAugAsyncStandardColumnsTest {

    @Test
    @DisplayName("LsDataAug_생성_직후_idempotencyKey_externalJobId_NULL_retryCount_0_deadLetterAt_NULL")
    void newAugHasNullAsyncColumnsAndZeroRetry() {
        LsDataAug aug = LsDataAug.createPending(1L, "WINTER", BigDecimal.valueOf(0.95), "registrar");

        assertThat(aug.getIdempotencyKey()).isNull();
        assertThat(aug.getExternalJobId()).isNull();
        assertThat(aug.getRetryCount()).isZero();
        assertThat(aug.getDeadLetterAt()).isNull();
    }

    @Test
    @DisplayName("LsDataAug_assignIdempotencyKey_호출_후_idempotencyKey_저장됨")
    void assignIdempotencyKeyStores() {
        LsDataAug aug = LsDataAug.createPending(1L, "WINTER", BigDecimal.valueOf(0.95), "registrar");

        aug.assignIdempotencyKey("aug-2026-05-19-winter-1");

        assertThat(aug.getIdempotencyKey()).isEqualTo("aug-2026-05-19-winter-1");
    }

    @Test
    @DisplayName("LsDataAug_assignExternalJobId_호출_후_externalJobId_저장됨")
    void assignExternalJobIdStores() {
        LsDataAug aug = LsDataAug.createPending(1L, "WINTER", BigDecimal.valueOf(0.95), "registrar");

        aug.assignExternalJobId("EXT-AUG-9001");

        assertThat(aug.getExternalJobId()).isEqualTo("EXT-AUG-9001");
    }

    @Test
    @DisplayName("LsDataAug_incrementRetryCount_호출_시_retryCount_증가")
    void incrementRetryCountIncreases() {
        LsDataAug aug = LsDataAug.createPending(1L, "WINTER", BigDecimal.valueOf(0.95), "registrar");

        aug.incrementRetryCount();
        aug.incrementRetryCount();
        aug.incrementRetryCount();

        assertThat(aug.getRetryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("LsDataAug_markDeadLetter_호출_후_deadLetterAt_세팅")
    void markDeadLetterSetsTimestamp() {
        LsDataAug aug = LsDataAug.createPending(1L, "WINTER", BigDecimal.valueOf(0.95), "registrar");
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        aug.markDeadLetter();

        assertThat(aug.getDeadLetterAt()).isNotNull();
        assertThat(aug.getDeadLetterAt()).isAfter(before);
    }
}

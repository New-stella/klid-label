package kr.co.cudo.authoring.batch.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 — LS_DATA_META 비동기 표준 컬럼 4종 단위 테스트.
 *
 * <p>idempotencyKey / externalJobId / retryCount / deadLetterAt 의 set 경로는
 * setter 없이 비즈니스 메서드로만 노출되어야 한다. ({@code @Setter} 금지)
 */
class LsDataMetaAsyncStandardColumnsTest {

    @Test
    @DisplayName("LsDataMeta_생성_직후_idempotencyKey_externalJobId_NULL_retryCount_0_deadLetterAt_NULL")
    void newMetaHasNullAsyncColumnsAndZeroRetry() {
        // given/when
        LsDataMeta meta = LsDataMeta.create(1L, "scene.summary", "야간");

        // then — 신규 표준 컬럼은 명시적 할당 전까지 기본값
        assertThat(meta.getIdempotencyKey()).isNull();
        assertThat(meta.getExternalJobId()).isNull();
        assertThat(meta.getRetryCount()).isZero();
        assertThat(meta.getDeadLetterAt()).isNull();
    }

    @Test
    @DisplayName("LsDataMeta_assignIdempotencyKey_호출_후_idempotencyKey_저장됨")
    void assignIdempotencyKeyStores() {
        LsDataMeta meta = LsDataMeta.create(1L, "scene.summary", "야간");

        meta.assignIdempotencyKey("vlm-2026-05-19-001");

        assertThat(meta.getIdempotencyKey()).isEqualTo("vlm-2026-05-19-001");
    }

    @Test
    @DisplayName("LsDataMeta_assignExternalJobId_호출_후_externalJobId_저장됨")
    void assignExternalJobIdStores() {
        LsDataMeta meta = LsDataMeta.create(1L, "scene.summary", "야간");

        meta.assignExternalJobId("EXT-VLM-7788");

        assertThat(meta.getExternalJobId()).isEqualTo("EXT-VLM-7788");
    }

    @Test
    @DisplayName("LsDataMeta_incrementRetryCount_호출_시_retryCount_1씩_증가")
    void incrementRetryCountIncreases() {
        LsDataMeta meta = LsDataMeta.create(1L, "scene.summary", "야간");

        meta.incrementRetryCount();
        meta.incrementRetryCount();

        assertThat(meta.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("LsDataMeta_markDeadLetter_호출_후_deadLetterAt_세팅")
    void markDeadLetterSetsTimestamp() {
        LsDataMeta meta = LsDataMeta.create(1L, "scene.summary", "야간");
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        meta.markDeadLetter();

        assertThat(meta.getDeadLetterAt()).isNotNull();
        assertThat(meta.getDeadLetterAt()).isAfter(before);
    }
}

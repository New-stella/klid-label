package kr.co.cudo.authoring.augment.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 ExternalAugmentClient 구현(no-op)의 동작 검증.
 * 외부 호출 없이 로그만 남기고 ack(true)를 반환한다 (회귀 격리 — Phase 2에서 dev 실제 콜백으로 교체).
 */
class NoopExternalAugmentClientTest {

    private final NoopExternalAugmentClient client = new NoopExternalAugmentClient();

    @Test
    @DisplayName("운영_ExternalAugmentClient구현은_외부호출없이_noop이다")
    void noopReturnsAckWithoutExternalCall() {
        // when — 콜백 컨텍스트 전달 (실제 외부 호출 없음)
        boolean ack = client.requestAugment(
                10L, "WINTER", "abc-123_KEY", "job-001", "http://localhost:8080/api/v1/augments/result");

        // then — ack true, 부수효과 없음
        assertThat(ack).isTrue();
    }
}

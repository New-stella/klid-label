package kr.co.cudo.authoring.augment.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 미연동 ExternalAugmentClient 구현(no-op)의 동작 검증.
 *
 * <p>Phase 7-A1: 외부 호출을 하지 않으므로 <b>가짜 job_id 를 만들지 않는다</b>
 * (외부가 발급하는 값을 우리가 지어내면 추적 불가한 유령 job 이 생긴다).
 */
class NoopExternalAugmentClientTest {

    private final NoopExternalAugmentClient client = new NoopExternalAugmentClient();

    @Test
    @DisplayName("운영_ExternalAugmentClient구현은_외부호출없이_noop이다")
    void noopSkipsWithoutExternalCall() {
        AugmentSubmitResult result = client.requestAugment(new AugmentSubmitCommand(
                        10L, "WINTER", "abc-123_KEY", "FIRE", "1",
                        "http://localhost:8080/api/v1/genai/callback",
                        List.of(new AugmentInputFile(1, "/storage/deidentified/1.jpg")), 1, 1))
                .block();

        assertThat(result.status()).isEqualTo(AugmentSubmitResult.STATUS_SKIPPED);
        assertThat(result.externalJobId()).as("외부 미호출이면 job_id 를 지어내지 않는다").isNull();
    }

    @Test
    @DisplayName("검수_결정_통보도_외부호출없이_ack_한다")
    void decisionSyncIsNoop() {
        assertThat(client.syncDecision(10L, "ACCEPTED", null)).isTrue();
    }
}

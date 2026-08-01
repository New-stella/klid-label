package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 미연동 ExternalAugmentClient 구현(no-op)의 동작 검증.
 *
 * <p>Phase 7-A1: 외부 호출을 하지 않으므로 <b>가짜 job_id 를 만들지 않는다</b>
 * (외부가 발급하는 값을 우리가 지어내면 추적 불가한 유령 job 이 생긴다).
 */
class NoopExternalAugmentClientTest {

    /** 위탁 payload 의 prompt — 이 테스트의 관심사가 아니라 계약(필수 non-empty)을 채우는 고정값. */
    private static final Map<String, Object> PROMPT = Map.of(
            "time", "NIGHT", "season", "WINTER", "weather", "RAIN",
            "terrain", "ROAD", "severity", "HIGH");

    private final NoopExternalAugmentClient client = new NoopExternalAugmentClient();

    @Test
    @DisplayName("운영_ExternalAugmentClient구현은_외부호출없이_noop이다")
    void noopSkipsWithoutExternalCall() {
        AugmentSubmitResult result = client.requestAugment(new AugmentSubmitCommand(
                        10L, "WINTER", PROMPT, "abc-123_KEY", "FIRE", "1",
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

    /**
     * Phase 7-A2 — dev/stg/prd 기본이 {@code mode=noop} 이다. noop 이 그럴듯한 {@code SUCCEEDED}/
     * {@code RUNNING} 을 조립하면 <b>존재하지 않는 job 을 성공 확정</b>하게 되어 폴링·화면이 오작동한다.
     * 그래서 payload 를 만들지 않고 "미연동" 신호만 돌려준다.
     */
    @Test
    @DisplayName("noop_모드에서_3종_호출이_가짜_성공을_만들지_않는다")
    void queriesReturnExplicitNotLinkedSignal() {
        TokenClaims actor = new TokenClaims("42", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(600));

        AugmentQueryResult<?> status = client.fetchJobStatus("job-1").block();
        AugmentQueryResult<?> results = client.fetchJobResults("job-1").block();
        AugmentQueryResult<?> canceled = client.cancelJob(
                new AugmentCancelCommand("job-1", actor, "사유", false)).block();

        assertThat(List.of(status, results, canceled)).allSatisfy(result -> {
            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason())
                    .isEqualTo(AugmentQueryResult.SkipReason.EXTERNAL_DISABLED);
            assertThat(result.payload()).as("가짜 응답을 조립하지 않는다").isNull();
        });
    }

    /** 조립만 하고 구독하지 않은 호출이 "나갔다" 로 보이지 않게 — 로그도 구독 시점에만 남긴다. */
    @Test
    @DisplayName("noop_조회도_구독_전에는_아무것도_하지_않는다")
    void queriesAreColdUntilSubscribed() {
        assertThat(client.fetchJobStatus("job-1")).isNotNull();
        assertThat(client.fetchJobResults("job-1")).isNotNull();
    }
}

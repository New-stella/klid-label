package kr.co.cudo.authoring.augment.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AugmentQueryResult}/{@link AugmentSubmitResult} 불변식 가드 — DEV_FIX MED-6 (CWE-476).
 *
 * <p>구 구현은 정준 생성자가 public 이고 compact 검증이 없어
 * {@code new AugmentQueryResult<>(null, null)} 이 만들어졌다. 그 값은 {@code isSkipped()=false} 인데
 * {@code payload()=null} 이라 <b>"스킵을 먼저 보고 분기한다"</b>는 소비 규약을 지킨 소비처에서도
 * 확정 NPE 가 난다(Phase 3 조회/취소 소비처). 반대로 둘 다 non-null 인 모순 상태도 만들 수 있었다.
 *
 * <p>이 타입의 존재 이유가 "가짜 데이터를 조립하지 않는 것" 이므로, 조립 불가능성을
 * <b>타입이 스스로</b> 강제해야 한다.
 */
class AugmentQueryResultTest {

    @Test
    @DisplayName("payload_와_skipReason_이_모두_null_이면_생성이_거부된다")
    void rejectsBothNull() {
        assertThatThrownBy(() -> new AugmentQueryResult<String>(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("payload_와_skipReason_이_동시에_있으면_생성이_거부된다")
    void rejectsBothPresent() {
        assertThatThrownBy(() -> new AugmentQueryResult<>(
                "payload", AugmentQueryResult.SkipReason.EXTERNAL_DISABLED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("팩토리로_만든_값은_상호배타_불변식을_만족한다")
    void factoriesSatisfyInvariant() {
        AugmentQueryResult<String> ok = AugmentQueryResult.of("v");
        assertThat(ok.isSkipped()).isFalse();
        assertThat(ok.payload()).isEqualTo("v");

        AugmentQueryResult<String> skipped =
                AugmentQueryResult.skipped(AugmentQueryResult.SkipReason.LOCAL_TERMINAL);
        assertThat(skipped.isSkipped()).isTrue();
        assertThat(skipped.payload()).isNull();
    }

    @Test
    @DisplayName("위탁_결과도_같은_방식으로_불변식이_강제된다")
    void submitResultEnforcesInvariant() {
        // RECEIVED 인데 job_id 가 없으면 "성공했는데 추적 불가" 상태다.
        assertThatThrownBy(() -> new AugmentSubmitResult(null, AugmentSubmitResult.STATUS_RECEIVED))
                .isInstanceOf(IllegalArgumentException.class);
        // SKIPPED 인데 job_id 가 있으면 지어낸 값이다(외부가 발급 주체).
        assertThatThrownBy(() -> new AugmentSubmitResult("job-1", AugmentSubmitResult.STATUS_SKIPPED))
                .isInstanceOf(IllegalArgumentException.class);
        // 계약 밖 status 도 거부한다.
        assertThatThrownBy(() -> new AugmentSubmitResult("job-1", "WHATEVER"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(AugmentSubmitResult.accepted("job-1").externalJobId()).isEqualTo("job-1");
        assertThat(AugmentSubmitResult.skipped().externalJobId()).isNull();
    }
}

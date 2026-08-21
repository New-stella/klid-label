package kr.co.cudo.authoring.augment.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 증강 연동 모드 판정 단위 검증 — R8 · D4 ({@code @design API-060}).
 *
 * <p>이 판정은 <b>빈 배선과 짝</b>이다. {@code NoopExternalAugmentClient} 가
 * {@code @ConditionalOnProperty(havingValue="noop")}(= 명시 지정 전용, {@code matchIfMissing} 없음)라
 * <b>미설정 = http 클라이언트</b>이므로, 판정 기본값도 반드시 http(=연동됨)여야 한다. 기본값을
 * 뒤집으면 미설정 환경에서 "빈은 http 인데 접수는 503" 이라는 모순이 생긴다.
 */
class AugmentExternalModePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"noop", "NOOP", "NoOp", " noop ", "\tnoop\n"})
    @DisplayName("noop은_대소문자와_앞뒤공백에_무관하게_미연동으로_판정한다")
    void noop은_미연동이다(String mode) {
        assertThat(new AugmentExternalModePolicy(mode).isNotLinked()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "HTTP", " http ", "", "  ", "mock", "disabled", "none", "no-op"})
    @DisplayName("noop이_아닌_값은_모두_연동됨으로_판정한다")
    void noop이_아니면_연동이다(String mode) {
        assertThat(new AugmentExternalModePolicy(mode).isNotLinked())
                .as("알 수 없는 값을 미연동으로 해석하면 오타 하나가 정상 요청을 전건 503 으로 막는다"
                        + " (미지의 값은 어느 구현체도 활성되지 않아 기동 단계에서 걸러진다 —"
                        + " ExternalAugmentClientBeanConditionTest)")
                .isFalse();
    }

    @Test
    @DisplayName("미설정이면_연동됨이다_빈_배선_기본값과_같다")
    void 미설정은_연동이다() {
        assertThat(new AugmentExternalModePolicy(null).isNotLinked()).isFalse();
    }
}

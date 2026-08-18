package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AsyncRequestTimeoutGuard} 순수 판정 검증 — 컨테이너 없이 고정한다.
 *
 * <p>배선(= {@code @PostConstruct} 로 실제 기동 경로에 걸려 있는지, 그리고 <b>환경변수 축</b>이
 * 실제로 덮이는지)은 {@link AsyncRequestTimeoutBootGuardTest} 가 컨텍스트 refresh 로 별도 검증한다.
 */
class AsyncRequestTimeoutGuardTest {

    @Test
    @DisplayName("하한_이상이면_단위_표기와_무관하게_통과한다")
    void atOrAboveFloorPasses() {
        // 단위 없는 숫자 = ms (WebMvcProperties 바인딩과 같은 규칙)
        assertThatCode(() -> AsyncRequestTimeoutGuard.verify("1800000")).doesNotThrowAnyException();
        // 접미사·ISO-8601 표기도 같은 값으로 읽혀야 한다 — 표기만 바꿔도 거부되면 운영이 우회 수단을 찾는다
        assertThatCode(() -> AsyncRequestTimeoutGuard.verify("30m")).doesNotThrowAnyException();
        assertThatCode(() -> AsyncRequestTimeoutGuard.verify("PT30M")).doesNotThrowAnyException();
        // 하한보다 긴 값은 허용한다(상한은 두지 않는다)
        assertThatCode(() -> AsyncRequestTimeoutGuard.verify("45m")).doesNotThrowAnyException();
        assertThatCode(() -> AsyncRequestTimeoutGuard.verify("  1800000  ")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("미설정이면_기동이_실패한다_컨테이너_기본값_30초로_떨어지기_때문이다")
    void absentValueIsRejected() {
        assertThatThrownBy(() -> AsyncRequestTimeoutGuard.verify(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AsyncRequestTimeoutGuard.KEY);
        assertThatThrownBy(() -> AsyncRequestTimeoutGuard.verify("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("하한보다_짧으면_기동이_실패한다")
    void belowFloorIsRejected() {
        // 원래 결함값(컨테이너 기본 30초)
        assertThatThrownBy(() -> AsyncRequestTimeoutGuard.verify("30000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AsyncRequestTimeoutGuard.KEY);
        // 경계 — 1ms 모자라도 거부한다
        assertThatThrownBy(() -> AsyncRequestTimeoutGuard.verify(
                String.valueOf(AsyncRequestTimeoutGuard.MIN_TIMEOUT.toMillis() - 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AsyncRequestTimeoutGuard.verify("29m"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("0_이하_제한없음도_거부한다_풀_스레드를_영원히_붙잡기_때문이다")
    void unlimitedIsRejected() {
        // Servlet 규약상 0 이하는 "제한 없음" 이라 하한 판정만으로는 통과해 버린다. 그러나 경계 있는
        // 풀을 세운 취지를 정반대로 되돌리므로(멈춘 연결이 스레드를 영원히 점유) 명시적으로 막는다.
        assertThatThrownBy(() -> AsyncRequestTimeoutGuard.verify("0"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AsyncRequestTimeoutGuard.verify("-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("해석할_수_없는_값도_거부한다")
    void unparseableIsRejected() {
        assertThatThrownBy(() -> AsyncRequestTimeoutGuard.verify("삼십분"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AsyncRequestTimeoutGuard.verify("30 minutes"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("거부_메시지가_설정된_값_자체를_되비추지_않는다")
    void messageDoesNotEchoConfiguredValue() {
        // 값 echo 는 오류 메시지를 설정 오라클로 만든다(CWE-209). 무엇을 고쳐야 하는지만 말한다.
        assertThatThrownBy(() -> AsyncRequestTimeoutGuard.verify("31337"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("31337");
    }
}

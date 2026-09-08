package kr.co.cudo.authoring.aiserver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원장 식별자 검증의 <b>순수 판정</b> 시험 — 컨텍스트 없이 돈다. [@design ADR-057] [@design ADR-062]
 *
 * <p>★ <b>이 판정은 더 이상 기동을 막지 않는다</b>(2026-09-03 확정). 규칙은 그대로이고 걸리는 자리만
 * 옮겼다 — 여기서는 위반을 <b>알리기 위해 모으고</b>, 실제 차단은 장비를 고르는 시점
 * ({@code AiSrvrSelector})이 한다. 그래서 이 시험이 고정하는 것은 셋이다:
 * <ol>
 *   <li>규칙이 바뀌지 않았다(같은 값이 위반이고 같은 값이 통과한다).</li>
 *   <li>위반은 <b>전부</b> 모인다 — 하나씩 알려주면 고치고 다시 보기를 반복하게 된다.</li>
 *   <li>판정이 <b>예외를 던지지 않는다</b> — 던지면 그 자체로 기동 차단이 되살아난다.</li>
 * </ol>
 */
class AiSrvrBootstrapGuardTest {

    @Test
    @DisplayName("형식을_지킨_아이디만_있으면_위반이_없다")
    void 형식을_지킨_아이디만_있으면_위반이_없다() {
        assertThat(AiSrvrBootstrapGuard.srvrIdViolations(List.of("gpu01", "gpu02"))).isEmpty();
    }

    @Test
    @DisplayName("원장이_비어_있어도_위반이_아니다")
    void 원장이_비어_있어도_위반이_아니다() {
        // 빈 원장은 「형식 위반」이 아니다 — 부트스트랩이 채운다.
        assertThat(AiSrvrBootstrapGuard.srvrIdViolations(List.of())).isEmpty();
    }

    @Test
    @DisplayName("형식을_위반한_아이디를_집어낸다 — 규칙은 그대로다")
    void 형식을_위반한_아이디를_집어낸다() {
        // ⚠구 시험 폐기(2026-09-08): 위반 표본이 "klid-ai-gpu-01" 이었다. 하이픈이 허용되면서
        //   그 값은 <정상>이 됐다. 표본을 여전히 위반인 것(대문자·점)으로 바꾼다.
        assertThat(AiSrvrBootstrapGuard.srvrIdViolations(List.of("gpu-01", "klid.ai.gpu.01")))
                .containsExactly("klid.ai.gpu.01");
    }

    @Test
    @DisplayName("★하이픈_밑줄_아이디는_위반이_아니다_구_표본_폐기")
    void 하이픈_밑줄_아이디는_위반이_아니다() {
        assertThat(AiSrvrBootstrapGuard.srvrIdViolations(
                List.of("klid-ai-gpu-01", "infer_gpu2", "gpu01"))).isEmpty();
    }

    @Test
    @DisplayName("위반_아이디를_전부_모은다")
    void 위반_아이디를_전부_모은다() {
        // 하나만 알려주면 고치고 다시 보기를 반복하게 된다.
        assertThat(AiSrvrBootstrapGuard.srvrIdViolations(List.of("GPU01", "gpu 02", "gpu03")))
                .containsExactly("GPU01", "gpu 02");
    }

    /**
     * ★ 이 값은 <b>DB 에서 온 신뢰할 수 없는 문자열</b>이고 그대로 기록으로 나간다. 개행이 섞이면
     * 기록 한 줄에 여러 줄이 들어가 위조가 된다(CWE-117).
     */
    @Test
    @DisplayName("★위반_표기에_개행과_제어문자가_남지_않는다")
    void 위반_표기에_개행이_남지_않는다() {
        List<String> violations =
                AiSrvrBootstrapGuard.srvrIdViolations(Arrays.asList("gpu\n01 INFO fake", null));

        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).doesNotContain("\n").doesNotContain("\r");
        assertThat(violations.get(1)).isEqualTo("(null)");
    }

    /**
     * ★★ <b>기동 차단을 되살리지 못하게 못 박는다</b>.
     *
     * <p>구 형태 {@code verifySrvrIds} 는 위반이 하나라도 있으면 {@code IllegalStateException} 을 던져
     * <b>운영 데이터 한 행이 앱 전체를 못 뜨게</b> 했다. 그 이름이 다시 나타나면 이 시험이 죽는다 —
     * 이름을 그대로 되살리는 것이 가장 있음직한 회귀 경로이기 때문이다.
     */
    @Test
    @DisplayName("★기동을_막던_구_판정이_되살아나지_않았다")
    void 기동을_막던_구_판정이_되살아나지_않았다() {
        assertThat(Arrays.stream(AiSrvrBootstrapGuard.class.getDeclaredMethods())
                .map(Method::getName))
                .as("verifySrvrIds 는 예외를 던져 기동을 중단시키던 형태다 — 되살리지 말 것")
                .doesNotContain("verifySrvrIds");
    }
}

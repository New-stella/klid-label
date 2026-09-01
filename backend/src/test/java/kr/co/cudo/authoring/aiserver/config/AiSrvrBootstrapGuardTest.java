package kr.co.cudo.authoring.aiserver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원장 식별자 검증의 <b>순수 판정</b> 시험 — 컨텍스트 없이 돈다. [@design ADR-057]
 *
 * <p>{@code QuartzClusteringGuard} 와 같은 골격이다: 판정을 순수 함수로 떼어 단위로 고정하고,
 * 실제 배선(기동 실패)은 별도 통합 시험이 고정한다.
 */
class AiSrvrBootstrapGuardTest {

    @Test
    @DisplayName("형식을_지킨_아이디만_있으면_통과한다")
    void 형식을_지킨_아이디만_있으면_통과한다() {
        assertThatCode(() -> AiSrvrBootstrapGuard.verifySrvrIds(List.of("gpu01", "gpu02")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("원장이_비어_있어도_검증은_통과한다")
    void 원장이_비어_있어도_검증은_통과한다() {
        // 빈 원장은 「형식 위반」이 아니다 — 부트스트랩이 채운다.
        assertThatCode(() -> AiSrvrBootstrapGuard.verifySrvrIds(List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("형식을_위반한_아이디가_하나라도_있으면_기동을_거부한다")
    void 형식을_위반한_아이디가_하나라도_있으면_기동을_거부한다() {
        assertThatThrownBy(() -> AiSrvrBootstrapGuard.verifySrvrIds(List.of("gpu01", "klid-ai-gpu-01")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("klid-ai-gpu-01");
    }

    @Test
    @DisplayName("위반_아이디를_전부_알려준다")
    void 위반_아이디를_전부_알려준다() {
        // 하나만 알려주면 고치고 다시 기동하기를 반복하게 된다.
        assertThatThrownBy(() -> AiSrvrBootstrapGuard.verifySrvrIds(List.of("GPU01", "gpu-02", "gpu03")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GPU01")
                .hasMessageContaining("gpu-02");
    }
}

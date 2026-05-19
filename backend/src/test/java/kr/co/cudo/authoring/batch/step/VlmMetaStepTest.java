package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.common.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VlmMetaStep NO-OP 단위 테스트 (Phase 1 — 2026-05-19 외부 위탁 전환 이후).
 *
 * <p>본 Step 은 Phase 1 부터 NO-OP 으로 보류되었으므로 외부 호출 없이 0 을 반환해야 한다.
 * 시계열 메타 책임은 {@link VlmTimeseriesStep} + Phase 2 webhook 로 이관되었다.
 * 본 Step 의 완전 폐기는 Phase 5 cleanup 으로 미뤘다.
 */
class VlmMetaStepTest {

    private VlmMetaStep step;

    @BeforeEach
    void setUp() {
        step = new VlmMetaStep();
    }

    @Test
    @DisplayName("VlmMetaStep_run_호출_시_외부_호출_없이_0_반환")
    void noOpReturnsZero() {
        // given / when
        int saved = step.run(123L);

        // then — NO-OP 으로 0 반환
        assertThat(saved).isZero();
    }

    @Test
    @DisplayName("VlmMetaStep_rawSn_null_시_INVALID_INPUT_예외")
    void nullRawSnThrows() {
        assertThatThrownBy(() -> step.run(null))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("rawSn");
    }
}

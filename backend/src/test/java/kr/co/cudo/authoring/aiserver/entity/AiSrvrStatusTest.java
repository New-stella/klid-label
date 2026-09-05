package kr.co.cudo.authoring.aiserver.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 서버 상태 전이 규칙 검증. [@design ADR-057]
 *
 * <h3>금지 전이 두 개가 이 시험의 핵심이다</h3>
 * <ul>
 *   <li>{@code UNAVAILABLE -> DRAINING} — 죽은 노드는 정비 대상이 아니다(이미 신규 배정에서 배제됐다).
 *       허용하면 관리자가 "정비 중"이라는 잘못된 상태를 보게 되고, 정비 완료 판정(잔여 배정 0)이
 *       장애 노드에도 걸린다.</li>
 *   <li>{@code DRAINING -> UNAVAILABLE} — 정비 중 헬스 실패는 상태를 바꾸지 않고 기록만 한다.
 *       허용하면 관리자가 의도적으로 세운 정비 상태를 폴러가 덮어써, 정비가 끝나기 전에 노드가
 *       장애로 분류된다.</li>
 * </ul>
 */
class AiSrvrStatusTest {

    @Test
    @DisplayName("죽은_노드는_정비중으로_전이할_수_없다")
    void 죽은_노드는_정비중으로_전이할_수_없다() {
        assertThat(AiSrvrStatus.UNAVAILABLE.canTransitionTo(AiSrvrStatus.DRAINING)).isFalse();
    }

    @Test
    @DisplayName("정비중_노드는_이용불가로_전이할_수_없다")
    void 정비중_노드는_이용불가로_전이할_수_없다() {
        assertThat(AiSrvrStatus.DRAINING.canTransitionTo(AiSrvrStatus.UNAVAILABLE)).isFalse();
    }

    @Test
    @DisplayName("가용_노드는_이용불가_정비중_비활성_모두로_전이한다")
    void 가용_노드는_이용불가_정비중_비활성_모두로_전이한다() {
        assertThat(AiSrvrStatus.AVAILABLE.canTransitionTo(AiSrvrStatus.UNAVAILABLE)).isTrue();
        assertThat(AiSrvrStatus.AVAILABLE.canTransitionTo(AiSrvrStatus.DRAINING)).isTrue();
        assertThat(AiSrvrStatus.AVAILABLE.canTransitionTo(AiSrvrStatus.DISABLED)).isTrue();
    }

    @Test
    @DisplayName("이용불가_노드는_가용과_비활성으로만_전이한다")
    void 이용불가_노드는_가용과_비활성으로만_전이한다() {
        assertThat(AiSrvrStatus.UNAVAILABLE.canTransitionTo(AiSrvrStatus.AVAILABLE)).isTrue();
        assertThat(AiSrvrStatus.UNAVAILABLE.canTransitionTo(AiSrvrStatus.DISABLED)).isTrue();
    }

    @Test
    @DisplayName("정비중_노드는_가용과_비활성으로만_전이한다")
    void 정비중_노드는_가용과_비활성으로만_전이한다() {
        assertThat(AiSrvrStatus.DRAINING.canTransitionTo(AiSrvrStatus.AVAILABLE)).isTrue();
        assertThat(AiSrvrStatus.DRAINING.canTransitionTo(AiSrvrStatus.DISABLED)).isTrue();
    }

    @Test
    @DisplayName("비활성_노드는_가용으로만_전이한다")
    void 비활성_노드는_가용으로만_전이한다() {
        assertThat(AiSrvrStatus.DISABLED.canTransitionTo(AiSrvrStatus.AVAILABLE)).isTrue();
        assertThat(AiSrvrStatus.DISABLED.canTransitionTo(AiSrvrStatus.UNAVAILABLE)).isFalse();
        assertThat(AiSrvrStatus.DISABLED.canTransitionTo(AiSrvrStatus.DRAINING)).isFalse();
    }

    @Test
    @DisplayName("같은_상태로의_전이는_전이가_아니다")
    void 같은_상태로의_전이는_전이가_아니다() {
        // 상태가 안 바뀌는 갱신은 「전이」가 아니라 no-op 이다. 호출측이 그것을 구분해야 하므로
        // 여기서 참을 돌려주면 무변경 갱신이 전이 감사·통지 경로를 타게 된다.
        for (AiSrvrStatus status : AiSrvrStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }

    @Test
    @DisplayName("전이_대상이_널이면_거부한다")
    void 전이_대상이_널이면_거부한다() {
        for (AiSrvrStatus status : AiSrvrStatus.values()) {
            assertThat(status.canTransitionTo(null)).isFalse();
        }
    }
}

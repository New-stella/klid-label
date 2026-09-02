package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부하 평활 검증. [@design ADR-057]
 *
 * <h3>왜 평균이 아니라 최대인가</h3>
 * <p>폴링은 5초에 한 번이라 표본이 성기다. 평균을 쓰면 <b>방금 밀리기 시작한 노드</b>가 한동안
 * 한가해 보여 그쪽으로 요청이 더 간다. 최대는 반대로 <b>이미 풀린 혼잡</b>을 잠깐 더 기억한다 —
 * 둘 중 틀렸을 때 손해가 작은 쪽을 고른 것이다(과대평가는 요청을 옆으로 보낼 뿐이지만,
 * 과소평가는 밀린 노드에 요청을 더 얹는다).
 */
class AiSrvrLoadSmootherTest {

    @Test
    @DisplayName("평활은_최근_N회의_최대값을_쓴다")
    void 평활은_최근_N회의_최대값을_쓴다() {
        // given — 창 3
        AiSrvrLoadSmoother smoother = new AiSrvrLoadSmoother(3);

        // when
        smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 1);
        smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 9);
        int smoothed = smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 2);

        // then — 마지막 표본이 2 여도 창 안의 최대 9 를 쓴다
        assertThat(smoothed).isEqualTo(9);
    }

    @Test
    @DisplayName("창을_벗어난_표본은_평활에서_빠진다")
    void 창을_벗어난_표본은_평활에서_빠진다() {
        // given
        AiSrvrLoadSmoother smoother = new AiSrvrLoadSmoother(3);
        smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 9);

        // when — 창 3 을 채워 9 를 밀어낸다
        smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 1);
        smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 2);
        int smoothed = smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 3);

        // then — 혼잡은 영원히 기억되지 않는다
        assertThat(smoothed).isEqualTo(3);
    }

    @Test
    @DisplayName("같은_노드라도_용도가_다르면_표본이_섞이지_않는다")
    void 같은_노드라도_용도가_다르면_표본이_섞이지_않는다() {
        // given — 일괄 처리가 밀려 있는 노드
        AiSrvrLoadSmoother smoother = new AiSrvrLoadSmoother(3);
        smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 12);

        // when — 같은 노드의 화면용 슬롯은 비어 있다
        int interactive = smoother.smooth("gpu01", AiSrvrUsageType.INTERACTIVE, 0);

        // then — ★두 용도는 장비 안에서 실행이 격리돼 있다. 합쳐 보면 일괄이 밀린 장비를
        //        화면 요청이 피할 이유가 없는데도 피하게 된다.
        assertThat(interactive).isZero();
    }

    @Test
    @DisplayName("노드가_다르면_표본이_섞이지_않는다")
    void 노드가_다르면_표본이_섞이지_않는다() {
        AiSrvrLoadSmoother smoother = new AiSrvrLoadSmoother(3);
        smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 12);

        assertThat(smoother.smooth("gpu02", AiSrvrUsageType.BATCH, 0)).isZero();
    }

    @Test
    @DisplayName("창_크기가_0이하로_설정되면_1로_눌러_평활을_끈다")
    void 창_크기가_0이하로_설정되면_1로_눌러_평활을_끈다() {
        // given — 오설정. 0 을 그대로 쓰면 표본이 하나도 남지 않아 부하가 항상 0 으로 보인다.
        AiSrvrLoadSmoother smoother = new AiSrvrLoadSmoother(0);

        // when
        smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 7);
        int smoothed = smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 2);

        // then — 평활이 꺼진 것과 같다(마지막 표본 그대로). 0 으로 보이는 것보다 낫다.
        assertThat(smoothed).isEqualTo(2);
    }

    @Test
    @DisplayName("원장에서_사라진_노드의_표본은_버린다")
    void 원장에서_사라진_노드의_표본은_버린다() {
        // given — 노드를 지웠다 같은 식별자로 다시 세우는 동선이 있다
        AiSrvrLoadSmoother smoother = new AiSrvrLoadSmoother(3);
        smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 12);
        smoother.smooth("gpu02", AiSrvrUsageType.BATCH, 4);

        // when — 살아 있는 노드만 남긴다
        smoother.retainOnly(java.util.Set.of("gpu02"));

        // then — 죽은 노드의 혼잡 기억이 새 노드로 전이되지 않는다(메모리 누수도 함께 막는다)
        assertThat(smoother.smooth("gpu01", AiSrvrUsageType.BATCH, 0)).isZero();
        assertThat(smoother.smooth("gpu02", AiSrvrUsageType.BATCH, 0)).isEqualTo(4);
    }
}

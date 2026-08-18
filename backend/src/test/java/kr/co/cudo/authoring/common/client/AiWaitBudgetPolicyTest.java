package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.sysconfig.dto.AiWaitBudget;
import kr.co.cudo.authoring.sysconfig.dto.AiWaitBudgets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 대기 예산 도출 — <b>재시도 예산이 바뀌면 값이 따라 움직이는가</b>를 고정한다.
 *
 * <h3>왜 「기본값과 다르다」로 단언하지 않는가</h3>
 * <p>"바뀐 값이 원래 값과 다르다" 식 단언은 두 값이 <b>우연히 겹치는 순간 조용히 참</b>이 된다.
 * 그래서 이 테스트는 전부 <b>기대값과 같다</b>로 쓴다 — 기대값은 손으로 다시 계산해 적는다.
 */
class AiWaitBudgetPolicyTest {

    /** 운영 형상과 같은 재시도 예산 — {@code resilience4j.retry.instances.ai} (3회 · 1s · ×2). */
    private static AiWaitBudgetPolicy productionShape() {
        return policy(3, Duration.ofSeconds(1), 2.0);
    }

    private static AiWaitBudgetPolicy policy(int maxAttempts, Duration wait, double multiplier) {
        RetryRegistry registry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialBackoff(wait, multiplier))
                .build());
        return new AiWaitBudgetPolicy(registry);
    }

    @Test
    @DisplayName("호출_1회_최악은_호출당_상한x시도횟수_더하기_백오프_합이다")
    void worstCallIsTimeoutTimesAttemptsPlusBackoff() {
        // 60s × 3 + (1s + 2s) = 183s — 손계산과 같은 값이어야 한다.
        assertThat(productionShape().worstCallSeconds()).isEqualTo(183);
    }

    @Test
    @DisplayName("재시도_횟수를_늘리면_최악_소요가_따라_늘어난다")
    void worstCallFollowsAttemptCount() {
        // 60×4 + (1+2+4) = 247 — 설정을 바꾸면 도출값이 실제로 따라 움직이는지 고정한다.
        assertThat(policy(4, Duration.ofSeconds(1), 2.0).worstCallSeconds()).isEqualTo(247);
    }

    @Test
    @DisplayName("백오프_초기대기를_늘리면_최악_소요가_따라_늘어난다")
    void worstCallFollowsBackoff() {
        // 60×3 + (5+10) = 195
        assertThat(policy(3, Duration.ofSeconds(5), 2.0).worstCallSeconds()).isEqualTo(195);
    }

    @Test
    @DisplayName("오토라벨_예산은_YOLO_블로킹_상한과_폴리곤_배치_예산의_합에서_나온다")
    void autolabelBudget() {
        AiWaitBudgets budgets = productionShape().budgets(300);
        // min(183, 70) + 60 = 130, + 고정 오버헤드 10 = 140. 프레임을 훑지 않으므로 가산분 0.
        assertThat(budgets.autolabel()).isEqualTo(new AiWaitBudget(140, 0, 300));
    }

    @Test
    @DisplayName("분할_예산은_호출당_상한이_없어_재시도_체인_전체다")
    void segmentBudget() {
        // Sam2SegmentService 는 block() 에 인자가 없어 체인 전체(183)를 쓴다. +10 = 193.
        assertThat(productionShape().budgets(300).segment()).isEqualTo(new AiWaitBudget(193, 0, 300));
    }

    @Test
    @DisplayName("SAM2_추적은_요청_단위_예산으로_잘려_프레임_수에_비례하지_않는다")
    void sam2TrackBudget() {
        // 루프가 요청 단위 wall-clock 예산(240) 안에서만 돈다 → 고정분 = 10 + 240 + 2(마지막 프레임
        // 부대 작업 초과분) = 252, 가산분 0. 프레임을 더 실어도 서버는 예산 안에서 할 수 있는 만큼만 한다.
        assertThat(productionShape().budgets(300).sam2Track()).isEqualTo(new AiWaitBudget(252, 0, 300));
    }

    @Test
    @DisplayName("AI_자동_추적도_같은_요청_단위_예산에서_나온다")
    void autoTrackBudget() {
        // 시작 프레임도 같은 루프·같은 예산 안에 있으므로 SAM2 추적과 같은 값이다.
        assertThat(productionShape().budgets(300).autoTrack()).isEqualTo(new AiWaitBudget(252, 0, 300));
    }

    @Test
    @DisplayName("추적_두_종류의_가산분이_0이라_화면은_서버_상한까지_한_요청에_실을_수_있다")
    void trackKindsHaveNoPerFrameShare() {
        // 가산분이 0 이 아니면 화면이 «상한 안에 들어가는 프레임 수» 로 요청을 쪼갠다. 요청 단위
        // 예산이 생긴 뒤에도 가산분을 남겨 두면, 서버는 안 쪼개도 되는데 화면만 계속 쪼갠다.
        AiWaitBudgets budgets = productionShape().budgets(300);
        assertThat(budgets.sam2Track().perFrameSec()).isEqualTo(0);
        assertThat(budgets.autoTrack().perFrameSec()).isEqualTo(0);
    }

    @Test
    @DisplayName("추적_요청_예산은_한_프레임의_최악_호출이_통째로_들어가는_크기다")
    void trackBatchBudgetFitsOneWorstCall() {
        // 이보다 작으면 첫 프레임조차 재시도 체인을 다 쓰지 못하고 잘린다 — 예산을 둔 목적이 무너진다.
        // 재시도 설정을 크게 늘리면 이 관계가 먼저 깨져 경고한다.
        assertThat(AiWaitBudgetPolicy.TRACK_BATCH_BUDGET.getSeconds())
                .isGreaterThanOrEqualTo(productionShape().worstCallSeconds());
    }

    @Test
    @DisplayName("추적_예산은_부대_몫까지_더해도_기본_절대_상한_안에_들어간다")
    void trackBudgetFitsDefaultCeiling() {
        // 넘으면 화면이 기다릴 수 있는 시간보다 서버가 오래 일하게 되어, 앞단이 먼저 끊는다.
        assertThat(productionShape().budgets(300).sam2Track().baseSec())
                .isLessThanOrEqualTo(AiWaitBudgetPolicy.DEFAULT_CEILING_SEC);
    }

    @Test
    @DisplayName("절대_상한의_하한은_한_프레임짜리_요청이_들어갈_수_있는_최댓값이다")
    void minimumCeilingFitsSingleFrameRequestOfEveryKind() {
        // 종류별 (고정분 + 가산분×1) 의 최댓값 = 추적 두 종류의 252(가산분 0).
        assertThat(productionShape().minimumCeilingSeconds()).isEqualTo(252);
    }

    @Test
    @DisplayName("재시도_예산이_늘면_절대_상한의_하한도_함께_올라간다")
    void minimumCeilingFollowsRetryBudget() {
        // 4회로 늘리면 호출 최악이 247 이 되고, 재시도 체인 전체를 쓰는 «분할» 이 247+10 = 257 로
        // 추적(252)을 넘어서 하한을 끌어올린다.
        assertThat(policy(4, Duration.ofSeconds(1), 2.0).minimumCeilingSeconds()).isEqualTo(257);
    }

    @Test
    @DisplayName("기본_절대_상한은_앞단_프록시_읽기_제한시간과_같다")
    void defaultCeilingMatchesEdge() {
        // 앞단이 끊는 지점보다 큰 계획은 어차피 이룰 수 없다 — 값의 근거를 상수로 고정한다.
        assertThat(AiWaitBudgetPolicy.DEFAULT_CEILING_SEC).isEqualTo(300);
    }
}

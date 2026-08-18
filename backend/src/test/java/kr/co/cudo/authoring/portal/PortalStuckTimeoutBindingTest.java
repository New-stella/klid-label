package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고착 커트라인({@code portal.upload.stuck-timeout-minutes}) 의 <b>비정상 값 처리 축</b> 고정.
 *
 * <h3>왜 이 축이 중요한가</h3>
 * <p>이 값은 <b>삭제의 기준</b>이다 — 커트라인을 넘긴 자산은 FAILED 로 내려가고, 실패 보존기간이
 * 지나면 파일과 DB 행이 비가역으로 지워진다. 그래서 값이 비정상일 때 <b>조용히 아무 값으로나
 * 흘러가면 안 된다</b>.
 *
 * <h3>두 축이 나눠 막는다 — 문서가 한쪽만 적고 있었다</h3>
 * <ol>
 *   <li><b>0 이하</b> — 잡({@code PortalUploadSweepJob#failStuckUploads})이 그 회차를 건너뛰고 경고.</li>
 *   <li><b>숫자가 아니거나 빈 값</b> — 잡까지 오지 못한다. {@code long} 성분 바인딩이 실패해
 *       <b>기동 자체가 막힌다</b>. 오타 하나가 삭제 기준을 조용히 바꾸는 것보다 안전한 방향이다.</li>
 * </ol>
 * <p>설정 주석은 한동안 이 둘을 뭉뚱그려 "해석 불가면 그 회차를 건너뛴다"고 적고 있었는데, 잡의
 * 판정에는 그런 분기가 없다. 이 시험은 <b>실제 동작</b> 쪽을 진실원으로 고정한다.
 */
class PortalStuckTimeoutBindingTest {

    @Configuration
    @EnableConfigurationProperties(PortalUploadProperties.class)
    static class PropsConfig {
    }

    private static ApplicationContextRunner runnerWith(String rawValue) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropsConfig.class)
                .withPropertyValues("portal.upload.stuck-timeout-minutes=" + rawValue);
    }

    @Test
    @DisplayName("고착_커트라인이_숫자가_아니면_기동이_실패한다_조용히_기본값으로_흐르지_않는다")
    void nonNumericValueFailsStartup() {
        runnerWith("abc").run(ctx -> assertThat(ctx)
                .as("삭제 기준이 오타 하나로 조용히 달라지면 안 된다")
                .hasFailed());
    }

    @Test
    @DisplayName("고착_커트라인이_빈_값이면_기동이_실패한다")
    void emptyValueFailsStartup() {
        // 환경변수를 빈 문자열로 두면 ${KEY:default} 의 기본값이 «적용되지 않는다» —
        // 빈 값이 그대로 흘러 들어오므로 이 경로가 실재한다.
        runnerWith("").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("고착_커트라인_0과_음수는_바인딩을_통과하고_잡이_그_회차를_건너뛴다")
    void zeroAndNegativeBindAndAreHandledByTheJob() {
        // 이 둘은 바인딩 축이 아니라 «잡» 축이 막는다(회귀 고정: PortalUploadSweepJobTest).
        runnerWith("0").run(ctx -> assertThat(
                ctx.getBean(PortalUploadProperties.class).stuckTimeoutMinutes()).isZero());
        runnerWith("-1").run(ctx -> assertThat(
                ctx.getBean(PortalUploadProperties.class).stuckTimeoutMinutes()).isEqualTo(-1L));
    }
}

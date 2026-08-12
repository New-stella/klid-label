package kr.co.cudo.authoring.batch.reclaim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 고착 회수 설정의 <b>fail-closed 검증</b> — 오설정이면 기동을 거부한다.
 *
 * <p>이 설정이 무너지는 방향은 하나다. 임계가 짧아지면 회수가 <b>정상 실행 중인 파이프라인</b>의 선점을
 * 되돌리고, 그 파이프라인은 나중에 끝나면서 상태를 덮어쓴다. 그사이 다른 진입이 같은 영상을 선점하면
 * 파이프라인이 2벌 돌며 외부 위탁이 중복으로 나간다. 회수는 상태를 <b>되돌리는 조작</b>이라 증강 파생
 * 폐기 유예와 같은 부류이고, 경고 로그는 배포 로그에 묻히므로 기동 자체를 실패시킨다.
 *
 * <p>순수 판정({@link ProcessingStaleReclaimProperties#validate()})과 실제 바인딩 경로 두 축을 모두
 * 고정한다 — 판정만 두면 배선이 빠져도 통과하고, 배선만 두면 판정 규칙이 흔들려도 통과한다.
 */
class ProcessingStaleReclaimPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ProcessingStaleReclaimConfig.class);

    @Test
    @DisplayName("임계가_0이거나_음수거나_하한미만이면_기동에_실패한다")
    void tooShortStaleTimeoutFailsStartup() {
        runner.withPropertyValues("authoring.batch.stuck-reclaim.stale-timeout-minutes=0")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("authoring.batch.stuck-reclaim.stale-timeout-minutes=-1")
                .run(ctx -> assertThat(ctx).hasFailed());
        // 하한(60분) 바로 아래도 막힌다 — 조용한 clamp 로 보정하지 않는다.
        runner.withPropertyValues("authoring.batch.stuck-reclaim.stale-timeout-minutes=59")
                .run(ctx -> assertThat(ctx).hasFailed());

        assertThatThrownBy(() -> props(0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale-timeout-minutes");
    }

    @Test
    @DisplayName("파싱이_불가능한_임계값이면_기동에_실패한다")
    void unparsableStaleTimeoutFailsStartup() {
        // .env 의 빈 값·오타가 ${KEY:default} 를 무력화한 실사고가 있어, 바인딩 실패도 기동 실패여야 한다.
        runner.withPropertyValues("authoring.batch.stuck-reclaim.stale-timeout-minutes=abc")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("기본값은_임계1440분_24시간_주기15분이며_정상_기동한다")
    void defaultsAreSaneAndStart() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ProcessingStaleReclaimProperties props = ctx.getBean(ProcessingStaleReclaimProperties.class);
            assertThat(props.enabled()).isTrue();
            assertThat(props.staleTimeoutMinutes())
                    .isEqualTo(ProcessingStaleReclaimProperties.DEFAULT_STALE_TIMEOUT_MINUTES);
            assertThat(props.intervalMs()).isEqualTo(900_000L);
            assertThat(props.batchSize()).isEqualTo(50);
        });
    }

    @Test
    @DisplayName("주기와_배치크기_하한을_어기면_기동에_실패한다")
    void otherLowerBoundsAreEnforced() {
        runner.withPropertyValues("authoring.batch.stuck-reclaim.interval-ms=1000")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("authoring.batch.stuck-reclaim.batch-size=0")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("authoring.batch.stuck-reclaim.initial-delay-ms=-1")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    private static ProcessingStaleReclaimProperties props(int staleTimeoutMinutes) {
        return new ProcessingStaleReclaimProperties(true, 900_000L, 600_000L, staleTimeoutMinutes, 50);
    }
}

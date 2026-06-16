package kr.co.cudo.authoring.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * dev-seed 자동 적재 Runner 의 이중 게이팅 검증 — 운영(prd/dev/stg) DB 오염 방지.
 *
 * <p>게이팅: {@code @Profile("local")} + {@code authoring.dev.seed.enabled}(기본 true).
 * 운영 프로파일에서는 빈 자체가 미등록되어야 하고, local 이라도 토글 off 면 미등록되어야 한다.
 * 실제 DB 부팅을 피하기 위해 {@link ApplicationContextRunner} 슬림 컨텍스트로 빈 등록만 본다.
 */
class DevSeedRunnerGuardTest {

    private ApplicationContextRunner runnerWith(String... props) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DevSeedRunner.class)
                .withBean("controlDataSource", DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues(props);
    }

    @Test
    @DisplayName("local_프로파일_기본값에서는_DevSeedRunner_빈_등록")
    void runnerPresentWhenLocalDefault() {
        runnerWith("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).hasSingleBean(DevSeedRunner.class));
    }

    @Test
    @DisplayName("seed_enabled_false면_시드가_적재되지_않는다")
    void runnerAbsentWhenToggleOff() {
        runnerWith("spring.profiles.active=local", "authoring.dev.seed.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevSeedRunner.class));
    }

    @Test
    @DisplayName("prd_프로파일에서는_DevSeedRunner_빈이_등록되지_않는다")
    void runnerAbsentWhenPrd() {
        runnerWith("spring.profiles.active=prd")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevSeedRunner.class));
    }

    @Test
    @DisplayName("dev_stg_프로파일에서도_DevSeedRunner_빈이_등록되지_않는다")
    void runnerAbsentWhenDevStg() {
        runnerWith("spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevSeedRunner.class));
        runnerWith("spring.profiles.active=stg")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevSeedRunner.class));
    }
}

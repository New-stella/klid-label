package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QuartzClusteringGuard} 기동 assert 의 <b>실제 배선</b> 검증 — 컨텍스트 러너로 확인한다.
 *
 * <p>{@link QuartzClusteringGuardTest} 는 판정 로직을 직접 호출해 고정하지만, 그 assert 가
 * {@code @PostConstruct} 로 기동 경로에 걸려 있는지는 증명하지 못한다(어노테이션이 빠지면 조용히 통과).
 * 또한 실제 Quartz 프로퍼티 키({@code spring.quartz.properties.org.quartz.jobStore.isClustered})를
 * Environment 에서 제대로 읽는지도 여기서 함께 고정한다 — 키 오타는 "항상 false" 로 보여 운영 배포를
 * 무조건 죽이거나(과차단) 반대로 판정을 무력화할 수 있다.
 */
class QuartzClusteringBootGuardTest {

    private static final String CLUSTERED = QuartzClusteringGuard.KEY_CLUSTERED + "=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(QuartzClusteringGuard.class);

    @Test
    @DisplayName("prd_프로파일에서_클러스터링이_꺼져있으면_컨텍스트_기동이_실패한다")
    void prdWithoutClusteringFailsContextStartup() {
        runner.withPropertyValues("spring.profiles.active=prd", CLUSTERED + "false")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        // 빈 생성 실패로 감싸이므로 원인 체인에서 확인한다.
                        .hasStackTraceContaining("isClustered")
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class));
    }

    @Test
    @DisplayName("stg_프로파일에서_클러스터링_설정이_아예_없으면_컨텍스트_기동이_실패한다")
    void stgWithoutClusteringPropertyFailsContextStartup() {
        // 프로퍼티 자체가 없는 경우(=암묵 기본값 false) 도 동일하게 거부해야 한다.
        runner.withPropertyValues("spring.profiles.active=stg")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("prd_라도_클러스터링이_켜져있으면_정상_기동한다")
    void prdWithClusteringStartsUp() {
        runner.withPropertyValues("spring.profiles.active=prd", CLUSTERED + "true")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(QuartzClusteringGuard.class));
    }

    @Test
    @DisplayName("local_dev_는_클러스터링_없이도_컨텍스트가_정상_기동한다")
    void singleNodeProfilesStartUp() {
        runner.withPropertyValues("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(QuartzClusteringGuard.class));
        runner.withPropertyValues("spring.profiles.active=dev", CLUSTERED + "false")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(QuartzClusteringGuard.class));
    }

    @Test
    @DisplayName("dev_프로파일이라도_배포표식_ENV가_prd면_기동이_실패한다")
    void deployedEnvMarkerFailsEvenOnDevProfile() {
        runner.withPropertyValues("spring.profiles.active=dev", "ENV=prd", CLUSTERED + "false")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}

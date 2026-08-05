package kr.co.cudo.authoring.dev;

import kr.co.cudo.authoring.batch.runner.DevPipelineRunner;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.dev.controller.DevAutolabelTestController;
import kr.co.cudo.authoring.dev.service.DevAutolabelTestService;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * dev 업로드(/v1/dev/autolabel-test) 빈은 {@code authoring.dev.upload.enabled} 프로퍼티로만 게이팅된다.
 *
 * <p>{@code @Profile("!prd")} → {@code @ConditionalOnProperty} 전환. 기본값 false(fail-closed) 라
 * 미설정/false 면 prd·dev 무관하게 빈 부재. true 면 prd 에서도 등록(폐쇄망 bring-up).
 *
 * <p>{@link DevPipelineRunner} 도 같은 키로 게이팅 — dev 업로드의 afterCommit 비식별 러너이므로
 * 함께 켜지고 함께 꺼져야 컨텍스트 의존이 깨지지 않는다.
 */
class DevUploadPropertyGuardTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        DevAutolabelTestController.class,
                        DevAutolabelTestService.class,
                        DevPipelineRunner.class)
                .withBean(VideoRepository.class, () -> mock(VideoRepository.class))
                .withBean(EventTypeService.class, () -> mock(EventTypeService.class))
                .withBean(DeidentifyStep.class, () -> mock(DeidentifyStep.class))
                .withBean(BatchTransitionService.class, () -> mock(BatchTransitionService.class));
    }

    @Test
    @DisplayName("dev_upload_enabled_미설정이면_업로드_빈_미등록_failClosed")
    void devUploadBeansAbsentWhenPropertyMissing() {
        runner()
                .withPropertyValues("spring.profiles.active=prd")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(DevAutolabelTestController.class);
                    assertThat(ctx).doesNotHaveBean(DevAutolabelTestService.class);
                    assertThat(ctx).doesNotHaveBean(DevPipelineRunner.class);
                });
    }

    @Test
    @DisplayName("dev_upload_enabled_false면_업로드_빈_미등록")
    void devUploadBeansAbsentWhenFalse() {
        runner()
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "authoring.dev.upload.enabled=false"
                )
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(DevAutolabelTestController.class);
                    assertThat(ctx).doesNotHaveBean(DevAutolabelTestService.class);
                    assertThat(ctx).doesNotHaveBean(DevPipelineRunner.class);
                });
    }

    @Test
    @DisplayName("dev_upload_enabled_true면_prd_프로파일에서도_업로드_빈_등록")
    void devUploadBeansPresentWhenEnabledEvenInPrd() {
        runner()
                .withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.dev.upload.enabled=true"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DevAutolabelTestController.class);
                    assertThat(ctx).hasSingleBean(DevAutolabelTestService.class);
                    assertThat(ctx).hasSingleBean(DevPipelineRunner.class);
                });
    }
}

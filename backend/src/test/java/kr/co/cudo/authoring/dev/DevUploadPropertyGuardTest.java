package kr.co.cudo.authoring.dev;

import kr.co.cudo.authoring.dev.controller.DevAutolabelTestController;
import kr.co.cudo.authoring.dev.service.DevAutolabelTestService;
import kr.co.cudo.authoring.dev.service.DevControlClipWriter;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.video.repository.MngClipMasterRepository;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * dev 업로드(/v1/dev/autolabel-test) 빈 게이팅 검증.
 *
 * <p><b>축이 둘이다</b>:
 * <ol>
 *   <li><b>업로드 endpoint/서비스</b> — {@code authoring.dev.upload.enabled} 프로퍼티로만 게이팅.
 *       기본값 false(fail-closed) 라 미설정/false 면 prd·dev 무관하게 빈 부재. true 면 prd 에서도
 *       등록된다(폐쇄망 bring-up).</li>
 *   <li><b>관제 쓰기 빈({@link DevControlClipWriter})</b> — 위 프로퍼티 <b>+ {@code @Profile("!prd")}</b>
 *       3중 게이팅. MNG_* 는 관제팀 소유 스키마라 <b>운영에서는 어떤 경로로도 쓰지 않는다</b>.
 *       그래서 prd 에서는 프로퍼티를 켜도 쓰기 빈이 없고, 업로드 호출은 서비스가 403 으로 거절한다
 *       ({@code DevAutolabelTestServiceTest.refusesUploadWhenWriterBeanAbsent}).</li>
 * </ol>
 *
 * <p>Phase 3 에서 {@code DevPipelineRunner}(dev 전용 비식별 러너)는 제거되었다 — 관제 INSERT 경로로
 * 전환되면서 적재가 {@code VideoIngestedEvent} 를 발행하고 운영 경로
 * ({@code IngestDeidentifyBridge → AsyncDeidentifyRunner})가 같은 비식별을 수행한다.
 */
class DevUploadPropertyGuardTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        DevAutolabelTestController.class,
                        DevAutolabelTestService.class,
                        DevControlClipWriter.class)
                .withBean("controlDataSource", DataSource.class, () -> mock(DataSource.class))
                .withBean(VideoRepository.class, () -> mock(VideoRepository.class))
                .withBean(MngResourceCctvRepository.class, () -> mock(MngResourceCctvRepository.class))
                .withBean(MngClipMasterRepository.class, () -> mock(MngClipMasterRepository.class))
                .withBean(EventTypeService.class, () -> mock(EventTypeService.class))
                .withBean(TrainingVideoIngestService.class, () -> mock(TrainingVideoIngestService.class));
    }

    @Test
    @DisplayName("dev_upload_enabled_미설정이면_업로드_빈_미등록_failClosed")
    void devUploadBeansAbsentWhenPropertyMissing() {
        runner()
                .withPropertyValues("spring.profiles.active=prd")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(DevAutolabelTestController.class);
                    assertThat(ctx).doesNotHaveBean(DevAutolabelTestService.class);
                    assertThat(ctx).doesNotHaveBean(DevControlClipWriter.class);
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
                    assertThat(ctx).doesNotHaveBean(DevControlClipWriter.class);
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
                });
    }

    @Test
    @DisplayName("prd_프로파일에서_관제쓰기_빈이_등록되지_않는다")
    void controlClipWriterAbsentInPrdEvenWhenEnabled() {
        runner()
                .withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.dev.upload.enabled=true"
                )
                // 프로퍼티를 켜도 prd 에서는 관제 테이블 쓰기 경로 자체가 없다(@Profile("!prd")).
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevControlClipWriter.class));
    }

    @Test
    @DisplayName("비prd_프로파일_enabled_true면_관제쓰기_빈_등록")
    void controlClipWriterPresentOutsidePrd() {
        runner()
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "authoring.dev.upload.enabled=true"
                )
                .run(ctx -> assertThat(ctx).hasSingleBean(DevControlClipWriter.class));
    }
}

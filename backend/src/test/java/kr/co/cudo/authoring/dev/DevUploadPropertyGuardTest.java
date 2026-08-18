package kr.co.cudo.authoring.dev;

import kr.co.cudo.authoring.batch.runner.DevPipelineRunner;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.dev.controller.DevAutolabelTestController;
import kr.co.cudo.authoring.dev.service.DevAutolabelTestService;
import kr.co.cudo.authoring.dev.dto.AutolabelTestRequest;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * dev 업로드(/v1/dev/upload) 빈은 {@code authoring.dev.upload.enabled} 프로퍼티로만 게이팅된다.
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
                .withBean(BatchTransitionService.class, () -> mock(BatchTransitionService.class))
                .withBean(VideoProbe.class, () -> mock(VideoProbe.class))
                .withBean(VideoMetaService.class, () -> mock(VideoMetaService.class));
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

    /**
     * 영상 기술메타 적재 토글({@code authoring.dev.upload.extract-technical-meta}) 기본값 검증 —
     * 서비스 하나만 띄우고 실제 upload 를 태워 «기본값이 실제로 켜져 동작하는지» 를 본다.
     *
     * <p>누락된 동작(이 경로는 {@code video.*} 메타가 아예 생성되지 않았다)을 메우는 것이므로 끄는 쪽이
     * 예외다. 값을 <b>지정하지 않은 채</b> 돌려 {@code @Value} 기본값이 true 로 바인딩되는지 고정한다.
     */
    @Test
    @DisplayName("옵션_기본값은_켜짐이다")
    void technicalMetaExtractionDefaultsToEnabled() {
        VideoRepository videoRepository = mock(VideoRepository.class);
        VideoMetaService videoMetaService = mock(VideoMetaService.class);
        EventTypeService eventTypeService = mock(EventTypeService.class);
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(rawWithSn(7L));
        given(eventTypeService.filterKeyOf(EV_CODE)).willReturn(Optional.of("020002"));

        new ApplicationContextRunner()
                .withUserConfiguration(DevAutolabelTestService.class)
                .withBean(VideoRepository.class, () -> videoRepository)
                .withBean(EventTypeService.class, () -> eventTypeService)
                .withBean(DevPipelineRunner.class, () -> mock(DevPipelineRunner.class))
                .withBean(VideoMetaService.class, () -> videoMetaService)
                // 60초 영상 + 기술메타 전 필드를 돌려주는 probe.
                .withBean(VideoProbe.class, () -> path ->
                        new VideoMeta(1920, 1080, "h264", 30.0, 4_500_000L, 60_000L, 1_024L))
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "authoring.dev.upload.enabled=true",
                        "authoring.storage.raw-path=" + storageRoot
                        // ★extract-technical-meta 를 «지정하지 않는다» — 기본값이 검증 대상이다.
                )
                .run(ctx -> {
                    ctx.getBean(DevAutolabelTestService.class).upload(
                            new MockMultipartFile("file", "sample.mp4", "video/mp4",
                                    new byte[]{1, 2, 3, 4}),
                            new AutolabelTestRequest("CLIP-DEFAULT-ON", "CCTV-001", EV_CODE,
                                    "1168000000", AutolabelTestRequest.PrvcType.ANONY,
                                    Instant.parse("2024-05-01T12:00:00Z")));

                    verify(videoMetaService).upsertVideoMeta(eq(7L), any(VideoMeta.class));
                });
    }

    @TempDir
    Path storageRoot;

    private static final String EV_CODE = "EV02000201";

    private static LsDataRaw rawWithSn(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-DEFAULT-ON", "CCTV-001", EV_CODE,
                "1168000000", "ANONY", "dev-upload/x.mp4", null, 60);
        ReflectionTestUtils.setField(raw, "rawSn", rawSn);
        return raw;
    }
}

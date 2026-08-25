package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.policy.PresetResolution;
import kr.co.cudo.authoring.batch.policy.PresetResolutionStatus;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector;
import kr.co.cudo.authoring.label.service.FrameBoundsResolver;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 오토라벨 <b>프리셋 게이트</b> — 보류 / 오토라벨 제외 / 수행 세 갈래.
 * [@design ADR-054] [@design AC-113] [@design AC-114] [@design AC-119]
 *
 * <p>구 동작(전량 저장 폴백)은 폐기됐다. 프리셋을 특정하지 못하면 <b>추론·적재를 시작하지 않고</b>
 * 사유를 배치 처리 이력에 남긴다. 그중 「라벨 0건 프리셋」만 보류가 아니라 <b>사람의 제외 선언</b>이라
 * 파이프라인이 계속 돌아 배치가 완료로 마감된다.
 *
 * <p>보류 표식은 {@code BatchContext} 에 서므로 이 시험군은 {@code execute(ctx)} 를 태운다 —
 * {@code run(rawSn)} 직접 호출로는 그 표식이 관측되지 않는다.
 */
class YoloAutolabelStepPresetGateTest {

    private static final Long RAW_SN = 77L;
    private static final String EVENT_TYPE_CD = "EV01000101";

    private AiServerClient aiServerClient;
    private LsDataSrcRepository srcRepository;
    private LsDataLblRepository lblRepository;
    private VideoRepository videoRepository;
    private PresetLabelLookupService presetLabelLookup;
    private BatchStatusService batchStatusService;
    private YoloAutolabelStep step;
    private LsDataRaw contextRaw;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        aiServerClient = mock(AiServerClient.class);
        srcRepository = mock(LsDataSrcRepository.class);
        lblRepository = mock(LsDataLblRepository.class);
        videoRepository = mock(VideoRepository.class);
        presetLabelLookup = mock(PresetLabelLookupService.class);
        batchStatusService = mock(BatchStatusService.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        LabelMasterService labelMasterService = mock(LabelMasterService.class);
        FrameBoundsResolver frameBoundsResolver = mock(FrameBoundsResolver.class);
        when(systemConfigService.getInt(any())).thenReturn(null);
        when(frameBoundsResolver.resolve(any())).thenReturn(Optional.of(new int[]{1280, 720}));
        contextRaw = rawWithEvent();
        when(videoRepository.findById(anyLong())).thenReturn(Optional.of(contextRaw));

        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        step = new YoloAutolabelStep(aiServerClient, srcRepository, lblRepository, videoRepository,
                presetLabelLookup, batchStatusService, systemConfigService, labelMasterService,
                frameBoundsResolver, new ObjectMapper(), rawDir.toString(),
                new DeployedEnvironmentDetector(env));
    }

    private static LsDataRaw rawWithEvent() {
        LsDataRaw raw = mock(LsDataRaw.class);
        when(raw.getEvntTypeCd()).thenReturn(EVENT_TYPE_CD);
        return raw;
    }

    private BatchContext execute(PresetResolutionStatus status) {
        when(presetLabelLookup.resolve(EVENT_TYPE_CD)).thenReturn(PresetResolution.of(status));
        BatchContext ctx = new BatchContext(RAW_SN, contextRaw);
        step.execute(ctx);
        return ctx;
    }

    // ── 보류 (AC-113 · AC-114) ────────────────────────────────────────────

    @Test
    @DisplayName("프리셋이_없으면_추론도_적재도_하지_않고_보류_표식이_선다")
    void presetAbsentWithholdsBundle() {
        BatchContext ctx = execute(PresetResolutionStatus.PRESET_ABSENT);

        assertThat(ctx.isWithheld()).isTrue();
        assertThat(ctx.getWithheldStage()).isEqualTo(BatchStage.YOLO);
        assertThat(ctx.getWithheldReason()).isEqualTo(YoloAutolabelStep.SKIP_REASON_PRESET_ABSENT);
        assertThat(ctx.getHints()).isEmpty();
        verify(aiServerClient, never()).predictYoloTrack(any(YoloTrackRequest.class));
        verify(lblRepository, never()).save(any());
        verify(lblRepository, never()).saveAll(any());
        // 프레임 조회조차 하지 않는다 — 게이트가 본체보다 앞이다.
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
    }

    @Test
    @DisplayName("이벤트유형을_특정할_수_없어도_통과가_아니라_보류다_fail_closed")
    void unregisteredEventTypeWithholds() {
        BatchContext ctx = execute(PresetResolutionStatus.EVENT_TYPE_UNREGISTERED);

        assertThat(ctx.isWithheld()).isTrue();
        assertThat(ctx.getWithheldReason())
                .isEqualTo(YoloAutolabelStep.SKIP_REASON_EVENT_TYPE_UNREGISTERED);
    }

    @Test
    @DisplayName("보류_사유는_배치_처리_이력에_단계와_함께_적재된다")
    void withholdIsPersistedAsSkippedAuditRow() {
        execute(PresetResolutionStatus.PRESET_ABSENT);

        verify(batchStatusService).recordStageSkipped(
                eq(RAW_SN), eq(BatchStage.YOLO), eq(YoloAutolabelStep.SKIP_REASON_PRESET_ABSENT));
    }

    @Test
    @DisplayName("★매핑없는_프리셋의_보류사유는_프리셋없음과_구분된다")
    void unmappedReasonIsDistinctFromAbsent() {
        BatchContext ctx = execute(PresetResolutionStatus.PRESET_UNMAPPED);

        assertThat(ctx.isWithheld()).isTrue();
        assertThat(ctx.getWithheldReason()).isEqualTo(YoloAutolabelStep.SKIP_REASON_PRESET_UNMAPPED);
        assertThat(YoloAutolabelStep.SKIP_REASON_PRESET_UNMAPPED)
                .isNotEqualTo(YoloAutolabelStep.SKIP_REASON_PRESET_ABSENT);
    }

    @Test
    @DisplayName("미연결_프리셋도_보류하며_사유가_따로_남는다")
    void unlinkedWithholdsWithOwnReason() {
        BatchContext ctx = execute(PresetResolutionStatus.PRESET_UNLINKED);

        assertThat(ctx.isWithheld()).isTrue();
        assertThat(ctx.getWithheldReason()).isEqualTo(YoloAutolabelStep.SKIP_REASON_PRESET_UNLINKED);
    }

    @Test
    @DisplayName("보류_사유_넷은_모두_재개_대상_목록에_들어_있다")
    void allWithholdReasonsAreResumable() {
        assertThat(YoloAutolabelStep.RESUMABLE_SKIP_REASONS).containsExactlyInAnyOrder(
                YoloAutolabelStep.SKIP_REASON_EVENT_TYPE_UNREGISTERED,
                YoloAutolabelStep.SKIP_REASON_PRESET_ABSENT,
                YoloAutolabelStep.SKIP_REASON_PRESET_UNLINKED,
                YoloAutolabelStep.SKIP_REASON_PRESET_UNMAPPED);
    }

    // ── 오토라벨 제외 선언 (AC-119) ───────────────────────────────────────

    @Test
    @DisplayName("★라벨을_담지_않은_프리셋은_보류가_아니라_제외라_배치가_계속_진행된다")
    void emptyPresetExcludesWithoutWithholding() {
        BatchContext ctx = execute(PresetResolutionStatus.PRESET_EMPTY);

        // 보류 표식이 서지 않아야 오케스트레이터가 파이프라인을 이어가 배치를 완료로 마감한다.
        assertThat(ctx.isWithheld()).isFalse();
        assertThat(ctx.getWithheldStage()).isNull();
        // 그럼에도 오토라벨 라벨은 만들지 않는다.
        assertThat(ctx.getHints()).isEmpty();
        verify(aiServerClient, never()).predictYoloTrack(any(YoloTrackRequest.class));
        verify(lblRepository, never()).save(any());
    }

    @Test
    @DisplayName("★제외_사유는_기록은_되지만_재개_대상_목록에는_없다")
    void exclusionIsRecordedButNotResumable() {
        execute(PresetResolutionStatus.PRESET_EMPTY);

        verify(batchStatusService).recordStageSkipped(
                eq(RAW_SN), eq(BatchStage.YOLO),
                eq(YoloAutolabelStep.SKIP_REASON_AUTOLABEL_EXCLUDED));
        // 사람이 일부러 뺀 것이라 프리셋을 고칠 때마다 되살아나면 안 된다.
        assertThat(YoloAutolabelStep.RESUMABLE_SKIP_REASONS)
                .doesNotContain(YoloAutolabelStep.SKIP_REASON_AUTOLABEL_EXCLUDED);
    }

    // ── 수행 (게이트가 정상 통과시키는지) ─────────────────────────────────

    @Test
    @DisplayName("실효_프리셋이면_보류하지_않고_본체를_수행한다")
    void resolvedPresetRunsBody() {
        when(presetLabelLookup.resolve(EVENT_TYPE_CD)).thenReturn(PresetResolution.resolved(
                Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(java.util.List.of());

        BatchContext ctx = new BatchContext(RAW_SN, contextRaw);
        step.execute(ctx);

        assertThat(ctx.isWithheld()).isFalse();
        verify(srcRepository).findByRawSnOrderByFrameNoAsc(RAW_SN);
        verify(batchStatusService, never()).recordStageSkipped(anyLong(), any(), any());
    }
}

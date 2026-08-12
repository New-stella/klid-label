package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.BatchStageSkipResponse;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 배치 <b>작업 묶음</b> 수동 스킵/해제 서비스 단위 테스트. [@design API-198] [@design API-200]
 *
 * <p>단위가 개별 단계에서 묶음(VLM / AUTOLABEL)으로 반전됐다 — 구 개별 단계값(YOLO·SAM2)은 400 이다.
 */
class BatchStageSkipServiceTest {

    private static final long RAW_SN = 12L;

    private VideoRepository videoRepository;
    private BatchStatusService batchStatusService;
    private BatchStageSkipService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        service = new BatchStageSkipService(videoRepository, batchStatusService);
    }

    private LsDataRaw originVideo() {
        return LsDataRaw.createFromIngest("clip-1", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4", null, 120);
    }

    private LsDataRaw derivativeVideo() throws Exception {
        LsDataRaw parent = originVideo();
        Field f = LsDataRaw.class.getDeclaredField("rawSn");
        f.setAccessible(true);
        f.set(parent, 100L);
        return LsDataRaw.createFromAugment(parent, "/storage/augment/winter.mp4", "WINTER", 7001L);
    }

    private void givenOriginVideo() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(originVideo()));
    }

    // ── 스킵 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("시계열묶음_스킵_성공하면_사유에_접두가_붙어_저장된다")
    void skipVlmStoresPrefixedReason() {
        // given
        givenOriginVideo();

        // when
        BatchStageSkipResponse response = service.skip(RAW_SN, "VLM", "외부 벤더 장애");

        // then
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(batchStatusService).recordManualStageSkip(
                eqLong(RAW_SN), eqBundle(BatchStageBundle.VLM), reason.capture(), anyString());
        assertThat(reason.getValue())
                .as("접두가 없으면 사용자 입력이 재개 사유와 정확 일치할 수 있다")
                .startsWith(ManualStageSkip.REASON_PREFIX)
                .endsWith("외부 벤더 장애");
        assertThat(response.skipped()).isTrue();
        assertThat(response.stage()).isEqualTo("VLM");
        assertThat(response.rawSn()).isEqualTo(RAW_SN);
    }

    @Test
    @DisplayName("스킵_묶음은_소문자로_들어와도_해석된다")
    void skipAcceptsLowerCaseBundle() {
        // given
        givenOriginVideo();

        // when
        BatchStageSkipResponse response = service.skip(RAW_SN, "autolabel", "오토라벨 생략");

        // then
        assertThat(response.stage()).isEqualTo(BatchStageBundle.AUTOLABEL.name());
    }

    @Test
    @DisplayName("★★오토라벨은_한_번의_기록으로_통째로_건너뛴다_단계별_표식을_남기지_않는다")
    void autolabelSkipIsASingleAtomicRecord() {
        // 부분 상태(3단계 중 일부만 스킵)가 표현 자체로 불가능해야 판정이 흔들리지 않는다.
        //   단계별로 3행을 남기는 구현이었다면 "2건만 있는 상태"가 생길 수 있다.
        givenOriginVideo();

        service.skip(RAW_SN, "AUTOLABEL", "AI 서버 점검");

        verify(batchStatusService, org.mockito.Mockito.times(1)).recordManualStageSkip(
                anyLong(), any(BatchStageBundle.class), anyString(), anyString());
        verify(batchStatusService).recordManualStageSkip(
                eqLong(RAW_SN), eqBundle(BatchStageBundle.AUTOLABEL), anyString(), anyString());
    }

    @Test
    @DisplayName("허용되지_않은_묶음은_400이고_메시지에_요청값을_되비추지_않는다")
    void unsupportedBundleRejected() {
        // given / when / then — 비식별·프레임추출은 뒤 작업의 전제라 스킵 대상이 아니고,
        //   구 단위였던 개별 단계값(YOLO·SAM2·INTERPOLATE)도 더 이상 수락하지 않는다.
        for (String bundle : new String[]{"DEIDENTIFY", "FRAME_EXTRACT", "INTERPOLATE",
                "YOLO", "SAM2", "COMPLETED", "", "  "}) {
            assertThatThrownBy(() -> service.skip(RAW_SN, bundle, "사유"))
                    .as("묶음=%s", bundle)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        verify(batchStatusService, never()).recordManualStageSkip(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("묶음_해석_실패_메시지는_요청값을_되비추지_않는다")
    void unsupportedBundleMessageDoesNotEchoInput() {
        // given
        String hostile = "<script>alert(1)</script>";

        // when / then
        assertThatThrownBy(() -> service.skip(RAW_SN, hostile, "사유"))
                .isInstanceOf(CustomException.class)
                .hasMessageNotContaining(hostile)
                .hasMessageContaining("VLM");
    }

    @Test
    @DisplayName("존재하지_않는_영상은_404다")
    void missingVideoNotFound() {
        // given
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.skip(RAW_SN, "VLM", "사유"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("파생영상은_스킵할_수_없고_400이며_미지원단계와_같은_상태코드다")
    void derivativeRejected() throws Exception {
        // given — 파생은 배치 파이프라인을 타지 않아 스킵이 의미가 없다(영구 조건 → 400).
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(derivativeVideo()));

        // when / then — 상태코드가 미지원 단계(400)와 같아야 응답이 영상 상태 오라클이 되지 않는다.
        assertThatThrownBy(() -> service.skip(RAW_SN, "VLM", "사유"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(batchStatusService, never()).recordManualStageSkip(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("제어문자만_있는_사유는_정제_후_비어_400이다")
    void controlCharOnlyReasonRejected() {
        // given — @NotBlank 는 통과하지만(공백이 아님) 정제하면 빈 문자열이 된다.
        givenOriginVideo();

        // when / then
        assertThatThrownBy(() -> service.skip(RAW_SN, "VLM", "\n\r\t "))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("사유의_개행문자는_저장_전에_제거된다")
    void reasonIsSanitized() {
        // given — CWE-117: 사유는 로그와 감사 행에 함께 실린다.
        givenOriginVideo();

        // when
        service.skip(RAW_SN, "VLM", "정상사유\n가짜로그라인");

        // then
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(batchStatusService).recordManualStageSkip(
                eqLong(RAW_SN), eqBundle(BatchStageBundle.VLM), reason.capture(), anyString());
        assertThat(reason.getValue()).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    @DisplayName("★상한_길이의_사유에_절단꼬리가_붙지_않는다")
    void maxLengthReasonHasNoTruncationSuffix() {
        // given — @Size(max=500) 이 허용하는 정확히 500자. 정제기 상한을 그대로 쓰면
        //   "...(truncated)" 가 덧붙어 정상 입력이 훼손된다.
        givenOriginVideo();
        String exactly500 = "가".repeat(ManualStageSkip.REASON_MAX_LENGTH);

        // when
        service.skip(RAW_SN, "VLM", exactly500);

        // then
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(batchStatusService).recordManualStageSkip(
                eqLong(RAW_SN), eqBundle(BatchStageBundle.VLM), reason.capture(), anyString());
        assertThat(reason.getValue())
                .doesNotContain("truncated")
                .isEqualTo(ManualStageSkip.REASON_PREFIX + exactly500);
    }

    // ── 해제 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("스킵상태를_해제하면_해제표식이_적재되고_작업을_실행하지_않는다")
    void clearRecordsMarkerOnly() {
        // given
        givenOriginVideo();
        when(batchStatusService.isBundleManuallySkipped(RAW_SN, BatchStageBundle.VLM)).thenReturn(true);

        // when
        service.clearSkip(RAW_SN, "VLM");

        // then — 해제 표식만 남는다. 이 서비스는 오케스트레이터를 알지도 못하므로 실행이 일어날 수 없다.
        verify(batchStatusService).recordManualStageSkipCleared(
                eqLong(RAW_SN), eqBundle(BatchStageBundle.VLM), anyString(), anyString());
    }

    @Test
    @DisplayName("★★오토라벨_해제도_한_번의_기록이다_묶음_일부만_풀리지_않는다")
    void autolabelClearIsASingleAtomicRecord() {
        givenOriginVideo();
        when(batchStatusService.isBundleManuallySkipped(RAW_SN, BatchStageBundle.AUTOLABEL))
                .thenReturn(true);

        service.clearSkip(RAW_SN, "AUTOLABEL");

        verify(batchStatusService, org.mockito.Mockito.times(1)).recordManualStageSkipCleared(
                anyLong(), any(BatchStageBundle.class), anyString(), anyString());
        verify(batchStatusService).recordManualStageSkipCleared(
                eqLong(RAW_SN), eqBundle(BatchStageBundle.AUTOLABEL), anyString(), anyString());
    }

    @Test
    @DisplayName("스킵상태가_아니면_해제는_아무_행도_남기지_않는다_멱등")
    void clearIsIdempotent() {
        // given
        givenOriginVideo();
        when(batchStatusService.isBundleManuallySkipped(RAW_SN, BatchStageBundle.VLM)).thenReturn(false);

        // when
        service.clearSkip(RAW_SN, "VLM");

        // then
        verify(batchStatusService, never())
                .recordManualStageSkipCleared(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("해제도_파생영상은_400이다")
    void clearRejectsDerivative() throws Exception {
        // given
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(derivativeVideo()));

        // when / then
        assertThatThrownBy(() -> service.clearSkip(RAW_SN, "VLM"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // Mockito matcher 헬퍼 — 가독성용(원시 타입 matcher 를 인라인하면 인자 순서가 헷갈린다).
    private static long eqLong(long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static BatchStageBundle eqBundle(BatchStageBundle bundle) {
        return org.mockito.ArgumentMatchers.eq(bundle);
    }
}

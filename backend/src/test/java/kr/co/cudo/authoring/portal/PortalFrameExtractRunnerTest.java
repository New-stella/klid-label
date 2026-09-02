package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.portal.service.PortalExtractionPlan;
import kr.co.cudo.authoring.portal.service.PortalFrameExtractRunner;
import kr.co.cudo.authoring.portal.service.PortalFrameExtractTxService;
import kr.co.cudo.authoring.portal.service.PortalVideoProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 포털 프레임 추출 러너 단위 테스트 — 성공 READY / 실패 FAILED+정리 / 진입 삭제 중단 +
 * 프레임 번호 계산(상한/최소) 순수 로직.
 */
class PortalFrameExtractRunnerTest {

    private static final long ULD_SN = 777L;

    @TempDir
    Path storageDir;

    /** 마킹이 정한 지점 — 러너는 이 목록을 그대로 뽑는다(스스로 계산하지 않는다). */
    private static final List<Integer> MARKED_FRAMES = List.of(0, 150);

    private PortalFrameExtractTxService txService;
    private FfmpegFrameExtractor.FrameWriter frameWriter;
    private PortalFrameExtractRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        txService = mock(PortalFrameExtractTxService.class);
        frameWriter = mock(FfmpegFrameExtractor.FrameWriter.class);
        PortalVideoProbe probe = path -> new PortalVideoProbe.Result(true, 10.0, 30.0);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        runner = new PortalFrameExtractRunner(txService, probe, frameWriter, props);
    }

    /** 추출 계획 — 자산 스냅샷 + 마킹이 정한 프레임 번호. */
    private PortalExtractionPlan plan() throws Exception {
        return new PortalExtractionPlan(processingUld(), MARKED_FRAMES);
    }

    /** 후처리 중 자산 스냅샷 — 상태는 이제 엔티티가 아니라 메타 원장이 소유한다. */
    private PortalUploadAsset processingUld() throws Exception {
        Path video = Files.createFile(storageDir.resolve("video.mp4"));
        return new PortalUploadAsset(ULD_SN, "u1", PortalUploadLedger.TYPE_VIDEO,
                "v.mp4", video.toString(), 1024L, "video/mp4",
                PortalUploadLedger.STATUS_PROCESSING, null, null, 0, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("프레임_추출_성공시_READY와_프레임행_생성")
    void successMarksReadyWithFrames() throws Exception {
        when(txService.beginExtraction(ULD_SN)).thenReturn(Optional.of(plan()));
        when(frameWriter.sourceExists(any())).thenReturn(true);
        // writeFrameByNumber — 더미 프레임 파일 생성.
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.createDirectories(out.getParent());
            Files.writeString(out, "frame");
            return null;
        }).when(frameWriter).writeFrameByNumber(any(), any(), anyInt());
        when(txService.touchProcessing(ULD_SN)).thenReturn(true);
        when(txService.completeReady(eq(ULD_SN), any(), any(), any())).thenReturn(true);

        runner.runAsync(ULD_SN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataSrc>> cap = ArgumentCaptor.forClass(List.class);
        verify(txService).completeReady(eq(ULD_SN), cap.capture(), any(), any());
        assertThat(cap.getValue()).isNotEmpty();
        verify(txService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("프레임_추출_실패시_FAILED와_부분_파일_정리")
    void failureMarksFailedAndCleansPartialFiles() throws Exception {
        when(txService.beginExtraction(ULD_SN)).thenReturn(Optional.of(plan()));
        when(frameWriter.sourceExists(any())).thenReturn(true);
        when(txService.touchProcessing(ULD_SN)).thenReturn(true);
        // 첫 프레임에서 IOException → 전체 실패.
        doThrow(new IOException("ffmpeg 실패")).when(frameWriter).writeFrameByNumber(any(), any(), anyInt());

        runner.runAsync(ULD_SN);

        verify(txService).markFailed(eq(ULD_SN), anyString());
        verify(txService, never()).completeReady(anyLong(), any(), any(), any());
        // 부분 파일 정리 — frames 디렉토리에 잔존 파일 없음.
        Path framesDir = storageDir.resolve("frames").resolve(String.valueOf(ULD_SN));
        assertThat(Files.exists(framesDir)).isFalse();
    }

    @Test
    @DisplayName("추출_계획이_없으면_중단 — 삭제·미전이·마킹부재")
    void abortsWhenAssetDeletedAtEntry() throws Exception {
        // 추출 계획이 empty → 삭제·미전이·마킹 부재 → 즉시 중단(프레임 추출·markFailed 없음).
        when(txService.beginExtraction(ULD_SN)).thenReturn(Optional.empty());

        runner.runAsync(ULD_SN);

        verifyNoInteractions(frameWriter);
        verify(txService, never()).completeReady(anyLong(), any(), any(), any());
        verify(txService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("스윕이_FAILED시킨_자산은_완료커밋이_부활시키지_않음")
    void sweepFailedAssetNotRevivedOnComplete() throws Exception {
        // adversarial #1: 조건부 READY 전이가 0행(이미 FAILED) → completeReady false →
        // 러너는 자신이 쓴 프레임 파일을 정리하고 종료(부활 없음, markFailed 도 하지 않음).
        when(txService.beginExtraction(ULD_SN)).thenReturn(Optional.of(plan()));
        when(frameWriter.sourceExists(any())).thenReturn(true);
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.createDirectories(out.getParent());
            Files.writeString(out, "frame");
            return null;
        }).when(frameWriter).writeFrameByNumber(any(), any(), anyInt());
        when(txService.touchProcessing(ULD_SN)).thenReturn(true);
        // 스윕이 이미 FAILED 전이 → 조건부 UPDATE 0행 → completeReady false.
        when(txService.completeReady(eq(ULD_SN), any(), any(), any())).thenReturn(false);

        runner.runAsync(ULD_SN);

        // 부활 금지 — READY 재전이·markFailed 없음, 프레임 파일 정리.
        verify(txService, never()).markFailed(anyLong(), anyString());
        Path framesDir = storageDir.resolve("frames").resolve(String.valueOf(ULD_SN));
        assertThat(Files.exists(framesDir)).isFalse();
    }

    @Test
    @DisplayName("하트비트_실패시_러너가_중단하고_파일_정리")
    void abortsAndCleansWhenHeartbeatFailsMidExtract() throws Exception {
        // adversarial #2 중단 경로: 추출 중 하트비트(touchProcessing)가 false 를 반환하면
        // (자산 삭제/전이 감지) 러너가 즉시 중단 + 부분 파일 정리해야 한다.
        //
        // ★ 하트비트 주기는 「프레임 개수」가 아니라 「경과 시간」이다(무갱신 경과 판정과 축을 맞춘
        //   것 — PortalFrameExtractHeartbeatTest 참조). 그래서 두 번째 하트비트를 일으키려면
        //   프레임 개수를 늘리는 것이 아니라 «시간을 흐르게» 해야 한다.
        PortalVideoProbe longProbe = path -> new PortalVideoProbe.Result(true, 100.0, 30.0);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        java.util.concurrent.atomic.AtomicLong fakeNanos = new java.util.concurrent.atomic.AtomicLong();
        PortalFrameExtractRunner longRunner = new PortalFrameExtractRunner(
                txService, longProbe, frameWriter, props, fakeNanos::get);

        // 마킹이 100지점을 정했다고 두면 하트비트가 여러 번 돌 만큼 긴 추출이 된다.
        List<Integer> many = java.util.stream.IntStream.range(0, 100).map(i -> i * 30).boxed().toList();
        when(txService.beginExtraction(ULD_SN))
                .thenReturn(Optional.of(new PortalExtractionPlan(processingUld(), many)));
        when(frameWriter.sourceExists(any())).thenReturn(true);
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.createDirectories(out.getParent());
            Files.writeString(out, "frame");
            // 프레임 1장마다 하트비트 간격(30분 커트라인 → 60초)을 넘겨 매 프레임 하트비트가 뜨게 한다.
            fakeNanos.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(90));
            return null;
        }).when(frameWriter).writeFrameByNumber(any(), any(), anyInt());
        // 하트비트: 첫 프레임 진입 시 true(정상 시작), 다음 하트비트에서 false(자산 소멸 감지) → 중단.
        when(txService.touchProcessing(ULD_SN)).thenReturn(true, false);

        longRunner.runAsync(ULD_SN);

        // 하트비트 중단 경로 — READY 커밋·markFailed 모두 없음(단순 중단), 부분 파일 정리.
        verify(txService, never()).completeReady(anyLong(), any(), any(), any());
        verify(txService, never()).markFailed(anyLong(), anyString());
        // 두 번째 하트비트에서 중단됐음을 확인(첫 프레임 진입 + 그다음 하트비트, 두 번 호출).
        verify(txService, times(2)).touchProcessing(ULD_SN);
        Path framesDir = storageDir.resolve("frames").resolve(String.valueOf(ULD_SN));
        assertThat(Files.exists(framesDir)).isFalse();
    }

    /**
     * ★ 되돌림 실증 — 러너가 <b>마킹이 정한 지점만</b> 뽑는다(스스로 간격으로 계산하지 않는다).
     *
     * <p>구 동작은 설정된 고정 간격으로 계산하는 것이었고, 그것이 되살아나면 사용자가 고르지 않은
     * 지점이 뽑힌다.
     */
    @Test
    @DisplayName("★마킹이_정한_프레임_번호만_그대로_뽑는다")
    void extractsExactlyMarkedFrameNumbers() throws Exception {
        when(txService.beginExtraction(ULD_SN)).thenReturn(Optional.of(plan()));
        when(frameWriter.sourceExists(any())).thenReturn(true);
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.createDirectories(out.getParent());
            Files.writeString(out, "frame");
            return null;
        }).when(frameWriter).writeFrameByNumber(any(), any(), anyInt());
        when(txService.touchProcessing(ULD_SN)).thenReturn(true);
        when(txService.completeReady(eq(ULD_SN), any(), any(), any())).thenReturn(true);

        runner.runAsync(ULD_SN);

        ArgumentCaptor<Integer> frameNos = ArgumentCaptor.forClass(Integer.class);
        verify(frameWriter, times(MARKED_FRAMES.size()))
                .writeFrameByNumber(any(), any(), frameNos.capture());
        assertThat(frameNos.getAllValues()).containsExactlyElementsOf(MARKED_FRAMES);
    }
}

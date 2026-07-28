package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.service.PortalFrameExtractRunner;
import kr.co.cudo.authoring.portal.service.PortalFrameExtractTxService;
import kr.co.cudo.authoring.portal.service.PortalVideoProbe;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private PortalFrameExtractTxService txService;
    private FfmpegFrameExtractor.FrameWriter frameWriter;
    private SystemConfigService systemConfigService;
    private PortalFrameExtractRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        txService = mock(PortalFrameExtractTxService.class);
        frameWriter = mock(FfmpegFrameExtractor.FrameWriter.class);
        systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getInt(anyString())).thenReturn(5);
        PortalVideoProbe probe = path -> new PortalVideoProbe.Result(true, 10.0, 30.0);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        runner = new PortalFrameExtractRunner(txService, probe, frameWriter, systemConfigService, props);
    }

    private LsPortalUld processingUld() throws Exception {
        Path video = Files.createFile(storageDir.resolve("video.mp4"));
        LsPortalUld uld = LsPortalUld.createVideo("u1", "v.mp4", video.toString(), 1024L, "video/mp4");
        setField(uld, "uldSn", ULD_SN);
        uld.markProcessing();
        return uld;
    }

    @Test
    @DisplayName("프레임_추출_성공시_READY와_프레임행_생성")
    void successMarksReadyWithFrames() throws Exception {
        when(txService.beginProcessing(ULD_SN)).thenReturn(Optional.of(processingUld()));
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
        ArgumentCaptor<List<LsPortalUldFrme>> cap = ArgumentCaptor.forClass(List.class);
        verify(txService).completeReady(eq(ULD_SN), cap.capture(), any(), any());
        assertThat(cap.getValue()).isNotEmpty();
        verify(txService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("프레임_추출_실패시_FAILED와_부분_파일_정리")
    void failureMarksFailedAndCleansPartialFiles() throws Exception {
        when(txService.beginProcessing(ULD_SN)).thenReturn(Optional.of(processingUld()));
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
    @DisplayName("러너_진입시_PROCESSING_전이_및_삭제된_자산이면_중단")
    void abortsWhenAssetDeletedAtEntry() throws Exception {
        // beginProcessing 이 empty → 삭제/중복 → 즉시 중단(프레임 추출·markFailed 없음).
        when(txService.beginProcessing(ULD_SN)).thenReturn(Optional.empty());

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
        when(txService.beginProcessing(ULD_SN)).thenReturn(Optional.of(processingUld()));
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
        // adversarial #2 중단 경로: 50프레임 초과 추출 중 하트비트(touchProcessing)가 false 를
        // 반환하면(자산 삭제/전이 감지) 러너가 즉시 중단 + 부분 파일 정리해야 한다.
        // 기존 러너(setUp)는 duration 10s → 2프레임(<50)이라 i=50 하트비트 분기가 실행되지 않으므로
        // 100프레임(duration 100s·interval 1s·fps 30 → 100프레임)이 나오는 러너를 별도로 구성한다.
        when(systemConfigService.getInt(anyString())).thenReturn(1); // interval 1s → 100프레임
        PortalVideoProbe longProbe = path -> new PortalVideoProbe.Result(true, 100.0, 30.0);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        PortalFrameExtractRunner longRunner =
                new PortalFrameExtractRunner(txService, longProbe, frameWriter, systemConfigService, props);

        when(txService.beginProcessing(ULD_SN)).thenReturn(Optional.of(processingUld()));
        when(frameWriter.sourceExists(any())).thenReturn(true);
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.createDirectories(out.getParent());
            Files.writeString(out, "frame");
            return null;
        }).when(frameWriter).writeFrameByNumber(any(), any(), anyInt());
        // 하트비트: i=0 진입 시 true(정상 시작), i=50 지점에서 false(자산 소멸 감지) → 중단.
        when(txService.touchProcessing(ULD_SN)).thenReturn(true, false);

        longRunner.runAsync(ULD_SN);

        // 하트비트 중단 경로 — READY 커밋·markFailed 모두 없음(단순 중단), 부분 파일 정리.
        verify(txService, never()).completeReady(anyLong(), any(), any(), any());
        verify(txService, never()).markFailed(anyLong(), anyString());
        // 두 번째 하트비트에서 중단됐음을 확인(i=0, i=50 두 번 호출).
        verify(txService, times(2)).touchProcessing(ULD_SN);
        Path framesDir = storageDir.resolve("frames").resolve(String.valueOf(ULD_SN));
        assertThat(Files.exists(framesDir)).isFalse();
    }

    // ======================== computeFrameNumbers 순수 로직 ========================

    @Test
    @DisplayName("프레임_수_상한_초과시_균등_샘플링으로_상한_이내")
    void frameCountCappedByUniformSampling() {
        // duration 1000s × 30fps, interval 1s → 후보 ~1000개 > cap 10 → 균등 재샘플링.
        List<Integer> frames = PortalFrameExtractRunner.computeFrameNumbers(1000.0, 30.0, 1, 10);
        assertThat(frames).hasSizeLessThanOrEqualTo(10);
        assertThat(frames).isNotEmpty();
        // 단조 증가.
        for (int i = 1; i < frames.size(); i++) {
            assertThat(frames.get(i)).isGreaterThan(frames.get(i - 1));
        }
    }

    @Test
    @DisplayName("영상길이가_간격보다_짧아도_최소_1프레임")
    void atLeastOneFrameWhenShorterThanInterval() {
        // duration 2s, interval 5s → 후보 0개 위험이지만 최소 1프레임(0번) 보장.
        List<Integer> frames = PortalFrameExtractRunner.computeFrameNumbers(2.0, 30.0, 5, 2000);
        assertThat(frames).containsExactly(0);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}

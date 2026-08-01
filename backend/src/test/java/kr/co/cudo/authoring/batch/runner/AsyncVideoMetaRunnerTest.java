package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AsyncVideoMetaRunner} 단위 테스트 — NIA export Phase 2.
 *
 * <p>영상 적재(AFTER_COMMIT) 후 비동기로 ffprobe 실행 → META 저장하는 오케스트레이션이 실패해도
 * 상위 적재/비식별 파이프라인을 중단시키지 않는(graceful) 격리를 검증한다(S1).
 */
@ExtendWith(MockitoExtension.class)
class AsyncVideoMetaRunnerTest {

    private static final Long RAW_SN = 100L;

    @Mock
    private VideoRepository videoRepository;
    @Mock
    private VideoProbe videoProbe;
    @Mock
    private VideoMetaService videoMetaService;
    @Mock
    private LsDeidentProcLogRepository deidentProcLogRepository;

    @InjectMocks
    private AsyncVideoMetaRunner runner;

    private LsDataRaw rawWithPath() {
        // 중첩 when() 회피 — 먼저 mock 을 완성한 뒤 반환한다.
        LsDataRaw raw = org.mockito.Mockito.mock(LsDataRaw.class);
        // 원본 영상 = 파생 참조 없음(ORGNL_RAW_SN null). 엔티티 mock 의 기본 반환에 의존하지 않고 명시한다.
        org.mockito.Mockito.doReturn(null).when(raw).getOrgnlRawSn();
        org.mockito.Mockito.doReturn("/nas-storage/videos/sample.mp4").when(raw).getRawFilePathNm();
        return raw;
    }

    /**
     * 파생영상(증강·해상도) RAW — {@code ORGNL_RAW_SN} 이 있고 {@code RAW_FILE_PATH_NM} 은 측정 대상이
     * 아니다. 측정 대상은 procLog 에 적재된 <b>자신의 비식별 사본</b>이다.
     */
    private LsDataRaw derivativeRaw() {
        LsDataRaw raw = org.mockito.Mockito.mock(LsDataRaw.class);
        org.mockito.Mockito.doReturn(100L).when(raw).getOrgnlRawSn();
        // RAW_FILE_PATH_NM 은 <일부러 stub 하지 않는다> — 파생 경로가 이 값을 읽으면 미stub(null)로
        // 측정이 불가해져 회귀가 즉시 드러난다(부모 원본을 열어 측정하는 회귀 가드).
        return raw;
    }

    private void stubDeidPath(String path) {
        LsDeidentProcLog procLog = org.mockito.Mockito.mock(LsDeidentProcLog.class);
        org.mockito.Mockito.doReturn(path).when(procLog).getDeIdntfFilePathNm();
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(RAW_SN))
                .thenReturn(Optional.of(procLog));
    }

    @Test
    @DisplayName("probe실패_graceful_적재지속")
    void probeFailureIsGracefulAndDoesNotStore() {
        // given: 원본 경로는 조회되나 probe 가 예외를 던진다
        LsDataRaw raw = rawWithPath();
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        when(videoProbe.probe(any(Path.class))).thenThrow(new RuntimeException("ffprobe not found"));

        // when / then: 예외가 상위로 전파되지 않고(적재/파이프라인 지속) META 저장은 호출되지 않는다
        assertThatCode(() -> runner.runAsync(RAW_SN)).doesNotThrowAnyException();
        verify(videoMetaService, never()).upsertVideoMeta(anyLong(), any(), any());
    }

    @Test
    @DisplayName("probe성공_video메타_저장호출")
    void probeSuccessDelegatesToStore() {
        // given: probe 정상 결과
        VideoMeta meta = new VideoMeta(1920, 1080, "h264", 29.97, 4_500_000L, 12_500L, 6_789_012L);
        LsDataRaw raw = rawWithPath();
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        when(videoProbe.probe(any(Path.class))).thenReturn(meta);

        // when
        runner.runAsync(RAW_SN);

        // then: 서비스 upsert 로 위임
        verify(videoMetaService).upsertVideoMeta(eq(RAW_SN), any(), eq(meta));
    }

    @Test
    @DisplayName("경로없으면_probe미호출_graceful")
    void blankPathSkipsProbe() {
        // given: 대상 영상 미존재(경로 원천 없음)
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        // when / then: probe/저장 호출 없이 정상 종료
        assertThatCode(() -> runner.runAsync(RAW_SN)).doesNotThrowAnyException();
        verify(videoProbe, never()).probe(any());
        verify(videoMetaService, never()).upsertVideoMeta(anyLong(), any(), any());
    }

    @Test
    @DisplayName("probe_null반환시_저장호출안함")
    void probeNullResultSkipsStore() {
        // given: 경로는 조회되나 probe 가 예외 없이 null 반환(추출 실패)
        LsDataRaw raw = rawWithPath();
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        when(videoProbe.probe(any(Path.class))).thenReturn(null);

        // when / then: 저장 미호출 + 정상 종료(graceful)
        assertThatCode(() -> runner.runAsync(RAW_SN)).doesNotThrowAnyException();
        verify(videoMetaService, never()).upsertVideoMeta(anyLong(), any(), any());
    }

    @Test
    @DisplayName("기술메타는_파생의_비식별_사본을_측정한_값이다")
    void derivativeProbesItsOwnDeidentifiedCopy(@TempDir Path tempDir) throws IOException {
        // given — 파생영상. RAW_FILE_PATH_NM(부모 원본으로 오염됐을 수 있는 값)이 아니라
        //         procLog 에 적재된 자신의 비식별 사본을 측정해야 한다.
        Path deidCopy = tempDir.resolve("WINTER.mp4");
        Files.writeString(deidCopy, "derivative-copy");
        String parentOriginal = "/nas-storage/raw/parent-original.mp4";
        LsDataRaw derivative = derivativeRaw();
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(derivative));
        stubDeidPath(deidCopy.toString());
        VideoMeta meta = new VideoMeta(1280, 720, "h264", 25.0, 2_000_000L, 30_000L, 1_234L);
        when(videoProbe.probe(any(Path.class))).thenReturn(meta);

        // when
        runner.runAsync(RAW_SN);

        // then — probe 대상이 사본이고, 부모 원본 경로는 열지 않는다(CWE-359).
        ArgumentCaptor<Path> probed = ArgumentCaptor.forClass(Path.class);
        verify(videoProbe).probe(probed.capture());
        assertThat(probed.getValue()).isEqualTo(deidCopy);
        assertThat(probed.getValue().toString()).isNotEqualTo(parentOriginal);
        verify(videoMetaService).upsertVideoMeta(eq(RAW_SN), any(), eq(meta));
    }

    @Test
    @DisplayName("사본_생성_전에는_probe_하지_않는다")
    void derivativeWithoutCopySkipsProbe(@TempDir Path tempDir) {
        // given — procLog 경로는 있으나 파일이 아직 없다(사본 생성 전)
        LsDataRaw derivative = derivativeRaw();
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(derivative));
        stubDeidPath(tempDir.resolve("not-copied-yet.mp4").toString());

        // when / then — probe·저장 모두 하지 않는다(엉뚱한 파일 측정·성공 위장 금지)
        assertThatCode(() -> runner.runAsync(RAW_SN)).doesNotThrowAnyException();
        verify(videoProbe, never()).probe(any());
        verify(videoMetaService, never()).upsertVideoMeta(anyLong(), any(), any());
    }

    @Test
    @DisplayName("파생영상은_비식별_사본경로가_없으면_원본으로_폴백하지_않는다")
    void derivativeWithoutDeidLogNeverFallsBackToOriginal() {
        // given — procLog 미적재(비식별 결과 경로 부재)
        LsDataRaw derivative = derivativeRaw();
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(derivative));
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(RAW_SN)).thenReturn(Optional.empty());

        // when / then — 부모 원본을 열어 측정하지 않고 skip
        assertThatCode(() -> runner.runAsync(RAW_SN)).doesNotThrowAnyException();
        verify(videoProbe, never()).probe(any());
        verify(videoMetaService, never()).upsertVideoMeta(anyLong(), any(), any());
    }

    // ================================================================= Phase 6 — 인입값 우선

    /** 인입이 {@code video.*} 전 키를 채운 상태(= probe 불요). */
    private static Map<String, String> fullIngestValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("video.fps", "25");
        values.put("video.codec", "hevc");
        values.put("video.bit_rate", "2000000");
        values.put("video.duration_ms", "30000");
        values.put("video.filesize", "1000");
        values.put("video.resolution", "1280x720");
        return values;
    }

    @Test
    @DisplayName("인입값이_전_키를_채우면_ffprobe를_호출하지_않고_그_값을_적재한다")
    void skipsProbeWhenIngestCoversAllKeys() {
        // given: 인입이 6키를 모두 채웠다
        Map<String, String> ingestValues = fullIngestValues();
        when(videoMetaService.loadIngestMeta(RAW_SN)).thenReturn(ingestValues);

        // when
        runner.runAsync(RAW_SN);

        // then: NAS 접근(경로 조회)·ffprobe 실행이 아예 없고, 인입값만으로 적재한다
        verify(videoProbe, never()).probe(any());
        verify(videoRepository, never()).findById(anyLong());
        verify(videoMetaService).upsertVideoMeta(RAW_SN, ingestValues, null);
    }

    @Test
    @DisplayName("인입값이_일부만_있으면_ffprobe로_나머지를_채운다")
    void probesWhenIngestIsPartial() {
        // given: 인입이 codec 만 채웠다
        Map<String, String> partial = new LinkedHashMap<>();
        partial.put("video.codec", "hevc");
        when(videoMetaService.loadIngestMeta(RAW_SN)).thenReturn(partial);
        LsDataRaw raw = rawWithPath();
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        VideoMeta meta = new VideoMeta(1920, 1080, "h264", 29.97, 4_500_000L, 12_500L, 6_789_012L);
        when(videoProbe.probe(any(Path.class))).thenReturn(meta);

        // when
        runner.runAsync(RAW_SN);

        // then: probe 를 돌리고 두 소스를 함께 서비스로 넘긴다(병합은 서비스 책임)
        verify(videoProbe).probe(any(Path.class));
        verify(videoMetaService).upsertVideoMeta(RAW_SN, partial, meta);
    }

    @Test
    @DisplayName("probe가_실패해도_인입값은_적재한다")
    void storesIngestValuesEvenWhenProbeFails() {
        // given: 인입 일부 보유 + probe 대상 파일 부재(경로 원천 없음)
        Map<String, String> partial = new LinkedHashMap<>();
        partial.put("video.codec", "hevc");
        when(videoMetaService.loadIngestMeta(RAW_SN)).thenReturn(partial);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        // when
        runner.runAsync(RAW_SN);

        // then: 부분 결손이 전량 결손보다 낫다 — 인입값만이라도 적재한다
        verify(videoMetaService).upsertVideoMeta(RAW_SN, partial, null);
    }

    @Test
    @DisplayName("잘못된경로_InvalidPathException_graceful")
    void invalidPathIsGracefulAndSkipsProbe() {
        // given: 원본 경로에 NUL 문자 포함 → Paths.get 이 InvalidPathException 을 던진다
        LsDataRaw raw = org.mockito.Mockito.mock(LsDataRaw.class);
        org.mockito.Mockito.doReturn(null).when(raw).getOrgnlRawSn();
        org.mockito.Mockito.doReturn("/nas-storage/videos/bad name.mp4")
                .when(raw).getRawFilePathNm();
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));

        // when / then: probe/저장 미호출 + 예외 전파 없음(상위 파이프라인 정상)
        assertThatCode(() -> runner.runAsync(RAW_SN)).doesNotThrowAnyException();
        verify(videoProbe, never()).probe(any());
        verify(videoMetaService, never()).upsertVideoMeta(anyLong(), any(), any());
    }
}

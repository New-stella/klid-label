package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FfmpegFrameExtractor 단위 테스트 (V2.1 — 비식별 분리: 마킹 기반 추출 + 비식별 경로 self-lookup).
 *
 * <p>Phase 1 (파이프라인 재정렬): extractByMarks 는 더 이상 비식별 경로를 인자로 받지 않고
 * {@link LsDeidentProcLogRepository#findLatestSuccessByDataRawSn} 로 저장된 비식별 결과를 조회한다.
 * 비식별 미완료(deIdntfYn != "Y") 영상은 INVALID_INPUT 으로 차단된다.
 */
class FfmpegFrameExtractorTest {

    @TempDir
    Path tmp;

    private LsDataSrcRepository srcRepository;
    private LsDataSrcHstryRepository hstryRepository;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private FfmpegFrameExtractor.FrameWriter frameWriter;
    private Path sourceVideo;
    private List<Long> recordedSeekMillis;

    @BeforeEach
    void setUp() throws IOException {
        srcRepository = mock(LsDataSrcRepository.class);
        hstryRepository = mock(LsDataSrcHstryRepository.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);

        AtomicLong seq = new AtomicLong(0);
        when(srcRepository.save(any(LsDataSrc.class))).thenAnswer(inv -> {
            LsDataSrc s = inv.getArgument(0);
            setField(s, "srcSn", seq.incrementAndGet());
            return s;
        });
        when(hstryRepository.save(any(LsDataSrcHstry.class))).thenAnswer(inv -> inv.getArgument(0));

        // 기본: 비식별 성공 로그 없음 (테스트별로 stub override).
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(any())).thenReturn(Optional.empty());

        recordedSeekMillis = new ArrayList<>();
        frameWriter = new FfmpegFrameExtractor.FrameWriter() {
            @Override
            public boolean sourceExists(Path sourceVideo) { return Files.exists(sourceVideo); }
            @Override
            public void writeFrame(Path sourceVideo, Path outputFrame, long seekMillis) throws IOException {
                if (outputFrame.getParent() != null && !Files.exists(outputFrame.getParent())) {
                    Files.createDirectories(outputFrame.getParent());
                }
                recordedSeekMillis.add(seekMillis);
                Files.write(outputFrame, ("frame-seek-" + seekMillis).getBytes());
            }
        };

        sourceVideo = tmp.resolve("clip.mp4");
        Files.write(sourceVideo, new byte[]{0, 0, 0});
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private FfmpegFrameExtractor newExtractor() {
        Path deidBase = tmp.resolve("deid");
        return new FfmpegFrameExtractor(srcRepository, hstryRepository, deidentProcLogRepository,
                frameWriter, tmp.toString(), deidBase.toString());
    }

    /** deIdntfYn 기본 "Y" (비식별 완료) 영상. */
    private LsDataRaw newRaw(int durationSec) {
        return newRaw(durationSec, "Y");
    }

    /** deIdntfYn 지정 가능 오버로드 — 비식별 가드 검증용. */
    private LsDataRaw newRaw(int durationSec, String deIdntfYn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, sourceVideo.toString(), null, durationSec);
        setField(raw, "rawSn", 9001L);
        raw.markDeidentified(deIdntfYn);
        return raw;
    }

    /** 비식별 성공 로그를 생성해 deIdntfFilePathNm 경로를 세팅. */
    private LsDeidentProcLog succeededLog(String deidPath) {
        LsDeidentProcLog log = LsDeidentProcLog.request(9001L, "req-1", sourceVideo.toString(), "tester");
        log.succeed(deidPath);
        return log;
    }

    // ============================================================
    // V2.0: extractByMarks — 마킹 위치 기반 프레임 추출
    // ============================================================

    @Test
    @DisplayName("V2_마킹_위치_기반_프레임_추출_정확한_frameIndex")
    void extractByMarks_extractsAtMarkPositions() {
        FfmpegFrameExtractor extractor = newExtractor();

        // 마킹: frameIndex 0, 150, 300 (30fps 기준 0초, 5초, 10초)
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"),
                new MarkItem(150, "00:05"),
                new MarkItem(300, "00:10")
        );
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(3);
        // frameNo 는 순차 인덱스 (0, 1, 2)
        assertThat(frames.get(0).getFrameNo()).isZero();
        assertThat(frames.get(1).getFrameNo()).isEqualTo(1);
        assertThat(frames.get(2).getFrameNo()).isEqualTo(2);
        // seekMillis: frameIndex * 1000 / 30 → 0ms, 5000ms, 10000ms
        assertThat(recordedSeekMillis).containsExactly(0L, 5000L, 10000L);
    }

    @Test
    @DisplayName("V2_마킹_기반_추출_비식별_영상_포함_2벌")
    void extractByMarks_withDeidVideo_attachesBothPaths() throws IOException {
        Path deidVideo = tmp.resolve("clip-deid.mp4");
        Files.write(deidVideo, new byte[]{0, 0, 0});
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(deidVideo.toString())));

        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"),
                new MarkItem(150, "00:05")
        );
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(2);
        // 원본 + 비식별 2벌 — 같은 row 의 deIdntfSrcFilePathNm 에 비식별 경로
        assertThat(frames).allMatch(f -> f.getDeIdntfSrcFilePathNm() != null);
        assertThat(frames).allMatch(f -> f.getSrcFilePathNm().contains("frames"));
        // raw 2회 + deid 2회 = 총 4회 writeFrame 호출
        assertThat(recordedSeekMillis).hasSize(4);
    }

    @Test
    @DisplayName("V2_마킹_비식별_영상_없으면_RAW_만_graceful")
    void extractByMarks_noDeidVideo_rawOnly() {
        // 비식별은 완료(deIdntfYn=Y) 됐으나 procLog 가 없음 → RAW only (비정상 WARN).
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNull();
    }

    @Test
    @DisplayName("비식별_미완료_영상은_프레임추출에서_차단된다")
    void extractByMarks_notDeidentified_rejected() {
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        assertThatThrownBy(() -> extractor.extractByMarks(newRaw(60, "N"), marks))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("프레임추출이_저장된_비식별경로를_읽어_2벌_추출한다")
    void extractByMarks_readsStoredDeidPath_extractsBoth() throws IOException {
        Path deidVideo = tmp.resolve("stored-deid.mp4");
        Files.write(deidVideo, new byte[]{0, 0, 0});
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(deidVideo.toString())));

        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNotNull();
        // raw 1회 + deid 1회 = 2회 writeFrame
        assertThat(recordedSeekMillis).hasSize(2);
    }

    @Test
    @DisplayName("V2_마킹_빈_배열_시_INVALID_INPUT")
    void extractByMarks_emptyMarks_rejected() {
        FfmpegFrameExtractor extractor = newExtractor();

        assertThatThrownBy(() -> extractor.extractByMarks(newRaw(60), List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("V2_마킹_null_시_INVALID_INPUT")
    void extractByMarks_nullMarks_rejected() {
        FfmpegFrameExtractor extractor = newExtractor();

        assertThatThrownBy(() -> extractor.extractByMarks(newRaw(60), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("V2_마킹_기반_추출_manifest_jsonl_생성")
    void extractByMarks_writesManifest() throws IOException {
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"),
                new MarkItem(150, "00:05")
        );

        extractor.extractByMarks(newRaw(60), marks);

        Path manifest = tmp.resolve("frames").resolve("9001").resolve("manifest.jsonl");
        assertThat(Files.exists(manifest)).isTrue();
        long lineCount = Files.readAllLines(manifest).size();
        // header 3 + key frames 2 = 5
        assertThat(lineCount).isEqualTo(5);
    }
}

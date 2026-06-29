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
            @Override
            public void writeFrameByNumber(Path sourceVideo, Path outputFrame, int frameNo) throws IOException {
                // FfmpegFrameExtractor(seek 경로)는 frame-exact 를 사용하지 않음 — no-op.
                if (outputFrame.getParent() != null && !Files.exists(outputFrame.getParent())) {
                    Files.createDirectories(outputFrame.getParent());
                }
                Files.write(outputFrame, ("frame-no-" + frameNo).getBytes());
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

    /** 운영 설정 재현: 원본·비식별 base 가 동일 경로(/nas-storage 등)로 주입된 extractor. */
    private FfmpegFrameExtractor newExtractorSameBase() {
        Path shared = tmp.resolve("nas-storage");
        return new FfmpegFrameExtractor(srcRepository, hstryRepository, deidentProcLogRepository,
                frameWriter, shared.toString(), shared.toString());
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
        // 스킴 A 회귀 탐지: 원본 프레임은 frames/raw 하위에 생성된다.
        assertThat(frames).allMatch(f -> f.getSrcFilePathNm().replace('\\', '/').contains("/frames/raw/"));
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

        // 스킴 A: 원본 manifest 는 frames/raw/{rawSn} 하위에 생성된다.
        Path manifest = tmp.resolve("frames").resolve("raw").resolve("9001").resolve("manifest.jsonl");
        assertThat(Files.exists(manifest)).isTrue();
        long lineCount = Files.readAllLines(manifest).size();
        // header 3 + key frames 2 = 5
        assertThat(lineCount).isEqualTo(5);
    }

    // ============================================================
    // 경로 충돌 버그 수정 (스킴 A): 원본=frames/raw, 비식별=frames/deid
    // ============================================================

    @Test
    @DisplayName("동일_base_주입돼도_원본과_비식별_프레임_경로가_달라_디스크_덮어쓰기_없음")
    void extractByMarks_sameBase_rawAndDeidPathsDoNotCollide() throws IOException {
        Path deidVideo = tmp.resolve("clip-deid.mp4");
        Files.write(deidVideo, new byte[]{0, 0, 0});
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(deidVideo.toString())));

        // 원본·비식별 base 가 동일(/nas-storage) — 버그 트리거 설정.
        FfmpegFrameExtractor extractor = newExtractorSameBase();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"), new MarkItem(150, "00:05"));

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(2);
        // 수용기준 2: 저장값 src_file_path_nm != de_idntf_src_file_path_nm
        for (LsDataSrc f : frames) {
            assertThat(f.getSrcFilePathNm()).isNotNull();
            assertThat(f.getDeIdntfSrcFilePathNm()).isNotNull();
            assertThat(f.getSrcFilePathNm()).isNotEqualTo(f.getDeIdntfSrcFilePathNm());
            // 스킴 A 세그먼트 분기 확인
            assertThat(f.getSrcFilePathNm().replace('\\', '/')).contains("/frames/raw/9001/");
            assertThat(f.getDeIdntfSrcFilePathNm().replace('\\', '/')).contains("/frames/deid/9001/");
        }

        // 수용기준 1: 실제 디스크에서도 두 파일이 별개로 공존(덮어쓰기 0).
        // FrameWriter stub 은 원본/비식별 모두 같은 더미 콘텐츠("frame-seek-{ms}")를 쓰므로,
        // 만약 두 경로가 같은 파일을 가리켰다면(스킴 충돌) 마지막 쓰기 1개만 남고 별개 파일이 아니게 된다.
        // → 디스크상 실경로(toRealPath)가 서로 다름을 단언해 "덮어쓰기 0" 을 실제로 입증한다.
        for (LsDataSrc f : frames) {
            Path rawFile = Path.of(f.getSrcFilePathNm());
            Path deidFile = Path.of(f.getDeIdntfSrcFilePathNm());
            assertThat(rawFile).exists();
            assertThat(deidFile).exists();
            // 같은 inode/실경로면 한쪽이 다른쪽을 덮어쓴 것 — 서로 다른 실제 파일이어야 한다.
            assertThat(rawFile.toRealPath()).isNotEqualTo(deidFile.toRealPath());
        }
        // 영상 2벌이 모두 별개 파일이므로 디스크상 프레임 파일 총수는 4개(raw 2 + deid 2)다(덮어쓰기 0).
        long distinctFrameFiles = frames.stream()
                .flatMap(f -> java.util.stream.Stream.of(f.getSrcFilePathNm(), f.getDeIdntfSrcFilePathNm()))
                .map(Path::of)
                .map(p -> {
                    try {
                        return p.toRealPath();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .distinct()
                .count();
        assertThat(distinctFrameFiles).isEqualTo(4);
    }

    @Test
    @DisplayName("동일_base_라도_원본_프레임은_frames_raw_하위에_생성된다")
    void extractByMarks_sameBase_rawFramesUnderFramesRaw() throws IOException {
        FfmpegFrameExtractor extractor = newExtractorSameBase();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(1);
        Path manifest = tmp.resolve("nas-storage").resolve("frames").resolve("raw")
                .resolve("9001").resolve("manifest.jsonl");
        assertThat(Files.exists(manifest)).isTrue();
        assertThat(frames.get(0).getSrcFilePathNm().replace('\\', '/')).contains("/frames/raw/9001/");
    }

    @Test
    @DisplayName("정상_rawSn은_base하위로_정규화되어_가드_통과_후_추출된다")
    void resolveSafeOutputDir_normalRawSn_passesGuardAndExtracts() {
        // rawSn 은 Long 이라 실제 "../" 경로 순회는 도달 불가하다(dead defense). 따라서 이 테스트는
        // 거부가 아니라 "정상 rawSn 이 base 하위로 정규화되어 가드를 통과한다"는 회귀 없음만 검증한다.
        // base 이탈(거부) 분기의 startsWith 가드 동작 자체는 resolveSafeOutputDir_bothKindsStayUnderBase 가
        // base 하위 정규화를 직접 단언해 커버한다.
        FfmpegFrameExtractor extractor = newExtractorSameBase();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);
        assertThat(frames).hasSize(1);
    }

    @Test
    @DisplayName("경로순회_가드_raw와_deid_세그먼트_모두_base_하위로_정규화되어_통과한다")
    void resolveSafeOutputDir_bothKindsStayUnderBase() throws Exception {
        FfmpegFrameExtractor extractor = newExtractorSameBase();
        java.lang.reflect.Method m = FfmpegFrameExtractor.class.getDeclaredMethod(
                "resolveSafeOutputDir", Path.class, Long.class, FrameKind.class);
        m.setAccessible(true);

        Path base = tmp.resolve("nas-storage").toAbsolutePath().normalize();

        // 스킴 A: frames/raw·frames/deid 모두 base 하위 → startsWith(base) 가드 통과(회귀 없음).
        Path rawDir = (Path) m.invoke(extractor, base, 9001L, FrameKind.RAW);
        Path deidDir = (Path) m.invoke(extractor, base, 9001L, FrameKind.DEID);

        assertThat(rawDir.startsWith(base)).isTrue();
        assertThat(deidDir.startsWith(base)).isTrue();
        assertThat(rawDir.toString().replace('\\', '/')).endsWith("/frames/raw/9001");
        assertThat(deidDir.toString().replace('\\', '/')).endsWith("/frames/deid/9001");
        // 두 출력 디렉토리는 동일 base 라도 서로 다르다(충돌 0).
        assertThat(rawDir).isNotEqualTo(deidDir);
    }

    @Test
    @DisplayName("비식별_소스_부재시_RAW만_저장하고_비식별경로_미저장_회귀없음_동일base")
    void extractByMarks_sameBase_noDeidVideo_rawOnlyNoDeidPath() {
        // deIdntfYn=Y 이나 procLog 없음 → RAW only graceful.
        FfmpegFrameExtractor extractor = newExtractorSameBase();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNull();
        assertThat(frames.get(0).getSrcFilePathNm().replace('\\', '/')).contains("/frames/raw/9001/");
    }

    // ============================================================
    // Phase 2: VDO_FRM_NO(실제 영상 프레임 위치) 배선 — FRM_NO(순번)와 의미 구분
    // ============================================================

    @Test
    @DisplayName("마킹추출시_VDO_FRM_NO에_mark_frameIndex가_저장된다")
    void extractByMarks_videoFrameNoStoresMarkFrameIndex() {
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"),
                new MarkItem(150, "00:05"),
                new MarkItem(300, "00:10")
        );

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(3);
        // videoFrameNo = mark.frameIndex() (실제 영상 위치) 그대로 저장
        assertThat(frames.get(0).getVideoFrameNo()).isEqualTo(0);
        assertThat(frames.get(1).getVideoFrameNo()).isEqualTo(150);
        assertThat(frames.get(2).getVideoFrameNo()).isEqualTo(300);
    }

    @Test
    @DisplayName("마킹추출시_FRM_NO는_여전히_추출순번이다")
    void extractByMarks_frameNoRemainsSequence() {
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"),
                new MarkItem(150, "00:05"),
                new MarkItem(300, "00:10")
        );

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // 회귀 보호: FRM_NO 는 루프 인덱스(0,1,2) — YoloAutolabelStep ordering 의존.
        assertThat(frames.get(0).getFrameNo()).isZero();
        assertThat(frames.get(1).getFrameNo()).isEqualTo(1);
        assertThat(frames.get(2).getFrameNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("듬성듬성한_마크_frameIndex_100과_250도_VDO_FRM_NO엔_실제값_FRM_NO엔_0과_1")
    void extractByMarks_sparseMarks_distinguishSeqFromActual() {
        FfmpegFrameExtractor extractor = newExtractor();
        // 순번(0,1) ≠ 실제 영상 위치(100,250) 를 명확히 구분.
        List<MarkItem> marks = List.of(
                new MarkItem(100, "00:03"),
                new MarkItem(250, "00:08")
        );

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).getFrameNo()).isZero();
        assertThat(frames.get(0).getVideoFrameNo()).isEqualTo(100);
        assertThat(frames.get(1).getFrameNo()).isEqualTo(1);
        assertThat(frames.get(1).getVideoFrameNo()).isEqualTo(250);
    }
}

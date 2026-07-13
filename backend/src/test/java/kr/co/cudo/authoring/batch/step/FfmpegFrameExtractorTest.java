package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private VideoFpsResolver fpsResolver;
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

        // 기본 fps: 미상 폴백(30.0) — 기존 seekMillis 테스트(frameIndex*1000/30)를 그대로 통과시킨다.
        // 실 fps 검증 테스트는 특정 rawSn 에 대해 override 한다.
        fpsResolver = mock(VideoFpsResolver.class);
        when(fpsResolver.resolveFps(anyLong())).thenReturn(30.0);

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
                frameWriter, fpsResolver, tmp.toString(), deidBase.toString());
    }

    /** 운영 설정 재현: 원본·비식별 base 가 동일 경로(/nas-storage 등)로 주입된 extractor. */
    private FfmpegFrameExtractor newExtractorSameBase() {
        Path shared = tmp.resolve("nas-storage");
        return new FfmpegFrameExtractor(srcRepository, hstryRepository, deidentProcLogRepository,
                frameWriter, fpsResolver, shared.toString(), shared.toString());
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
        // 프로덕션 현실: 비식별 영상은 비식별 base({deidBase}/videos/{rawSn}) 하위에 존재한다.
        Path deidVideo = tmp.resolve("deid").resolve("videos").resolve("9001").resolve("clip-deid.mp4");
        Files.createDirectories(deidVideo.getParent());
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
    @DisplayName("MEDsec_비식별경로가_base밖이면_fail_closed로_RAW만추출_비식별경로_미저장")
    void extractByMarks_deidPathOutsideBase_failClosedRawOnly() throws IOException {
        // MED-sec: DB/외부 오염으로 비식별 경로가 비식별 base 밖을 가리키면, 존재하더라도 비식별 입력으로
        // 쓰지 않고 RAW only(fail-closed). 원본 fallback(out-of-base 경로를 그대로 입력) 차단.
        Path outside = tmp.resolve("outside").resolve("evil-deid.mp4");
        Files.createDirectories(outside.getParent());
        Files.write(outside, new byte[]{0, 0, 0});
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(outside.toString())));

        FfmpegFrameExtractor extractor = newExtractor(); // 비식별 base = tmp/deid
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(1);
        // base 밖 비식별 경로는 채택되지 않는다 — 비식별 경로 미저장(RAW only).
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNull();
        // 비식별용 writeFrame 미수행 — raw 1회만.
        assertThat(recordedSeekMillis).hasSize(1);
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
        Path deidVideo = tmp.resolve("deid").resolve("videos").resolve("9001").resolve("stored-deid.mp4");
        Files.createDirectories(deidVideo.getParent());
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
        // 동일 base(/nas-storage) 하위에 비식별 영상 존재 — 프로덕션 현실 반영.
        Path deidVideo = tmp.resolve("nas-storage").resolve("videos").resolve("9001").resolve("clip-deid.mp4");
        Files.createDirectories(deidVideo.getParent());
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

    // ============================================================
    // M-3 수정: 실 fps(video.fps) 기반 seekMillis + 미상 폴백 무회귀 + 정합성(S1)
    // ============================================================

    @Test
    @DisplayName("M3_seekMillis_실fps25_정확_frameIndex50이_2000ms")
    void extractByMarks_realFps25_seekMillisAccurate() {
        // given — 저장된 실 fps=25 (resolver override)
        when(fpsResolver.resolveFps(9001L)).thenReturn(25.0);
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"), new MarkItem(50, "00:02"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then — seekMillis = round(frameIndex*1000/25): 0ms, 2000ms.
        // 30fps 고정이었다면 50*1000/30=1666ms 였을 것 — 실 fps 사용을 값으로 입증.
        assertThat(frames).hasSize(2);
        assertThat(recordedSeekMillis).containsExactly(0L, 2000L);
    }

    @Test
    @DisplayName("M3_seekMillis_fps미상_30폴백_기존과_동일_무회귀")
    void extractByMarks_fpsAbsent_fallback30_noRegression() {
        // given — resolver 기본 stub(30.0 폴백) 사용
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"), new MarkItem(150, "00:05"), new MarkItem(300, "00:10"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then — 기존 30fps 고정 결과(frameIndex*1000/30)와 동일: 0, 5000, 10000ms.
        assertThat(frames).hasSize(3);
        assertThat(recordedSeekMillis).containsExactly(0L, 5000L, 10000L);
    }

    @Test
    @DisplayName("S1_정합성_실fps25에서_마킹타임스탬프2초와_추출seekMillis2000ms가_일치")
    void extractByMarks_consistencyWithMarkingAt25Fps() {
        // given — S1: 마킹이 25fps 로 frameIndex=50 → timestamp "00:02"(2초)를 산출했다면,
        // 추출도 동일 resolveFps(25.0) 단일 경로로 seekMillis 를 계산하므로 2000ms(=2초)로 일치해야 한다.
        when(fpsResolver.resolveFps(9001L)).thenReturn(25.0);
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(50, "00:02"));

        // when
        extractor.extractByMarks(newRaw(60), marks);

        // then — frameIndex 50 @ 25fps = 2000ms. 마킹 timestamp(2초)와 왕복 정합.
        assertThat(recordedSeekMillis).containsExactly(2000L);
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

    // ============================================================
    // TOCTOU 근본 수정: 마킹이 pin 한 fps 사용 (추출이 재조회하지 않음)
    // ============================================================

    @Test
    @DisplayName("TOCTOU_마킹이pin한30fps_사용_그사이video_fps60적재돼도_재조회안하고_pin30으로_계산")
    void extractByMarks_usesPinnedFps_notReQueriedValue() {
        // given — 이전 S1 테스트는 resolver 단일 mock 값이라 TOCTOU 를 못 잡았다. 여기서는 pin(30)과
        // 재조회값(60)을 서로 다르게 두어 반증한다: 마킹은 30 폴백으로 frameIndex 를 산출했는데, 그 사이
        // video.fps=60 이 적재되어 resolveFps 가 60 을 반환하는 상황을 재현한다.
        when(fpsResolver.resolveFps(9001L)).thenReturn(60.0); // 마킹↔추출 사이 실 fps 적재됨
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"), new MarkItem(150, "00:05"));

        // when — 마킹이 pin 한 30.0 을 명시 전달
        extractor.extractByMarks(newRaw(60), marks, 30.0);

        // then — pin 30 으로 계산: 0ms, 5000ms. (60 으로 재조회했다면 0, 2500ms 였을 것.)
        assertThat(recordedSeekMillis).containsExactly(0L, 5000L);
        // 핵심: pin 이 유효하므로 resolveFps 재조회를 하지 않는다(구조적 TOCTOU 소멸).
        verify(fpsResolver, never()).resolveFps(anyLong());
    }

    @Test
    @DisplayName("TOCTOU_execute경로_마킹pin30이_video_fps60보다_우선_적용")
    void execute_usesPinnedFpsFromMarking_overResolver() {
        // given — 실제 파이프라인 경로(execute): ctx.markings 의 최신 마킹이 pin 한 fps 를 써야 한다.
        when(fpsResolver.resolveFps(9001L)).thenReturn(60.0); // 추출 시점에 실 fps 가 이미 적재됨
        FfmpegFrameExtractor extractor = newExtractor();
        LsDataRaw raw = newRaw(60);

        // 마킹은 과거 30 폴백으로 생성됐다고 가정 → fps=30 pin. marks 는 그 30fps 기준 frameIndex.
        LsMarking marking = LsMarking.createAuto(9001L, "EVT", 150, sourceVideo.toString(),
                "[{\"frameIndex\":0,\"timestamp\":\"00:00\"},{\"frameIndex\":150,\"timestamp\":\"00:05\"}]",
                1L, 30.0);
        BatchContext ctx = new BatchContext(9001L, raw);
        ctx.setMarkings(List.of(marking));
        ctx.setMarks(List.of(new MarkItem(0, "00:00"), new MarkItem(150, "00:05")));

        // when
        extractor.execute(ctx);

        // then — pin 30 적용: 0ms, 5000ms (60 재조회였다면 0, 2500ms).
        assertThat(recordedSeekMillis).containsExactly(0L, 5000L);
        verify(fpsResolver, never()).resolveFps(anyLong());
    }

    @Test
    @DisplayName("마킹pin이_null이면_resolveFps로_폴백한다_하위호환")
    void extractByMarks_nullPin_fallsBackToResolver() {
        // given — 구 데이터(pin 없음) → resolveFps 폴백. resolver 는 실 fps 25 반환.
        when(fpsResolver.resolveFps(9001L)).thenReturn(25.0);
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"), new MarkItem(50, "00:02"));

        // when — pin=null
        extractor.extractByMarks(newRaw(60), marks, null);

        // then — resolveFps(25) 폴백 적용: 0ms, 2000ms.
        assertThat(recordedSeekMillis).containsExactly(0L, 2000L);
        verify(fpsResolver).resolveFps(9001L);
    }

    @Test
    @DisplayName("60fps_마킹pin_frameIndex120이_2000ms로_정확계산")
    void extractByMarks_pinned60Fps_seekMillisAccurate() {
        // given — 60fps pin. resolver 는 다른 값(30)을 두어도 pin 이 우선임을 함께 검증.
        when(fpsResolver.resolveFps(9001L)).thenReturn(30.0);
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"), new MarkItem(120, "00:02"));

        // when — 60fps pin
        extractor.extractByMarks(newRaw(60), marks, 60.0);

        // then — frameIndex 120 @ 60fps = 2000ms. (30fps 였다면 4000ms.)
        assertThat(recordedSeekMillis).containsExactly(0L, 2000L);
        verify(fpsResolver, never()).resolveFps(anyLong());
    }
}

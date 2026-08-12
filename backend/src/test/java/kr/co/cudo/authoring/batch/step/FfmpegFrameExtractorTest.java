package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
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
import java.nio.file.Paths;
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
import static org.mockito.Mockito.times;
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
    /** writeFrame 의 <b>입력 영상</b> 기록 — 비식별 소스에서 뽑았는지 직접 판정한다. */
    private List<Path> recordedWriteSources;

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
        recordedWriteSources = new ArrayList<>();
        frameWriter = new FfmpegFrameExtractor.FrameWriter() {
            @Override
            public boolean sourceExists(Path sourceVideo) { return Files.exists(sourceVideo); }
            @Override
            public void writeFrame(Path sourceVideo, Path outputFrame, long seekMillis) throws IOException {
                if (outputFrame.getParent() != null && !Files.exists(outputFrame.getParent())) {
                    Files.createDirectories(outputFrame.getParent());
                }
                recordedSeekMillis.add(seekMillis);
                // ★ 어느 <b>입력 영상</b>에서 뽑았는지 기록한다 — "비식별 소스에서 뽑지 않았다" 를
                //   직접 단언하기 위함(seekMillis 만 보면 원본/비식별 write 가 구분되지 않는다).
                recordedWriteSources.add(sourceVideo);
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
        // 비식별 입력 영상의 읽기 허용 base 판정은 리졸버(구 위치 + co-locate 2-way)로 위임된다.
        return new FfmpegFrameExtractor(srcRepository, hstryRepository, deidentProcLogRepository,
                frameWriter, fpsResolver,
                ArtifactRootTestSupport.coLocate(tmp, tmp, deidBase),
                tmp.toString(), deidBase.toString());
    }

    /** 운영 설정 재현: 원본·비식별 base 가 동일 경로(/nas-storage 등)로 주입된 extractor. */
    private FfmpegFrameExtractor newExtractorSameBase() {
        Path shared = tmp.resolve("nas-storage");
        return new FfmpegFrameExtractor(srcRepository, hstryRepository, deidentProcLogRepository,
                frameWriter, fpsResolver,
                ArtifactRootTestSupport.coLocate(tmp, shared, shared),
                shared.toString(), shared.toString());
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

    // ============================================================
    // 결함#2 회귀 — co-locate(원본 옆) 비식별 영상도 채택되어 2벌이 추출된다
    // ============================================================

    @Test
    @DisplayName("결함2_원본옆_co_locate_비식별영상도_채택되어_비식별프레임경로가_저장된다")
    void extractByMarks_coLocateDeidVideo_attachesDeidPath() throws IOException {
        // given — Phase 5A 확정 구조: 비식별 영상은 {dirname(원본)}/{rawSn}/deid/ 에 산출된다.
        //   원본(sourceVideo)은 tmp 하위이므로 이 경로는 비식별 base(tmp/deid) <b>밖</b>이다.
        //   구 가드(deidentified-path 단독)는 이 경로를 신뢰불가로 판정해 항상 RAW only 였다(실기동 실측:
        //   DE_IDNTF_SRC_FILE_PATH_NM 전 행 NULL → export 비식별 벌 결손).
        Path coLocateDeid = sourceVideo.getParent().resolve("9001").resolve("deid")
                .resolve("clip-mask.mp4");
        Files.createDirectories(coLocateDeid.getParent());
        Files.write(coLocateDeid, new byte[]{0, 0, 0});
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(coLocateDeid.toString())));

        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"), new MarkItem(150, "00:05"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then — 비식별 프레임 경로가 채워지고(2벌) 출력은 여전히 frames/deid 하위다(쓰기 축 불변).
        assertThat(frames).hasSize(2);
        assertThat(frames).allMatch(f -> f.getDeIdntfSrcFilePathNm() != null);
        assertThat(frames).allMatch(f ->
                f.getDeIdntfSrcFilePathNm().replace('\\', '/').contains("/frames/deid/9001/"));
        // raw 2회 + deid 2회 = 4회 writeFrame (비식별 벌이 실제로 추출됐다는 증거)
        assertThat(recordedSeekMillis).hasSize(4);
    }

    @Test
    @DisplayName("결함2_경로순회_비식별경로가_상위참조로_허용루트를_벗어나면_여전히_거부된다")
    void extractByMarks_deidPathTraversal_stillRejected() throws IOException {
        // given — '..' 순회로 허용 base(비식별 저장소·co-locate) 밖을 가리키는 오염 경로.
        //   정규화하면 tmp/outside/traversed-deid.mp4 이며 어느 허용 base 하위도 아니다.
        Path escaped = tmp.resolve("deid").resolve("videos").resolve("..").resolve("..")
                .resolve("outside").resolve("traversed-deid.mp4");
        Files.createDirectories(escaped.normalize().getParent());
        Files.write(escaped.normalize(), new byte[]{0, 0, 0});
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(escaped.toString())));

        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then — 허용 base 전부의 밖 → 채택하지 않는다. 원본 폴백도 없다(RAW only, deid 경로 미저장).
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNull();
        assertThat(recordedSeekMillis).hasSize(1);
    }

    @Test
    @DisplayName("결함2_다른영상의_co_locate_디렉터리_경로는_거부된다_rawSn_교차차단")
    void extractByMarks_otherVideoColocateDir_rejected() throws IOException {
        // given — co-locate 후보는 <b>이 영상의 rawSn</b> 디렉터리 하나뿐이다. 같은 원본 디렉터리 아래라도
        //   다른 rawSn(9002) 의 deid 경로가 오면 채택되지 않아야 한다(영상 간 교차 참조 차단).
        Path otherVideoDeid = sourceVideo.getParent().resolve("9002").resolve("deid")
                .resolve("other-mask.mp4");
        Files.createDirectories(otherVideoDeid.getParent());
        Files.write(otherVideoDeid, new byte[]{0, 0, 0});
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(otherVideoDeid.toString())));

        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNull();
        assertThat(recordedSeekMillis).hasSize(1);
    }

    // ============================================================
    // 보안(CWE-59 → CWE-359) — 허용 base 안의 <b>심링크</b>가 base 밖(원본 PII)을 가리키면 거부
    //   허용 집합에 co-locate({dirname(원본)}/{rawSn}/deid) 가 들어오면서 판정 대상이 외부 비식별
    //   벤더(KPST)가 공유 마운트로 직접 쓰는 디렉터리로 넓어졌다. lexical startsWith 만 통과시키면
    //   원본(PII) 영상에서 추출한 프레임이 "비식별본"(DE_IDNTF_SRC_FILE_PATH_NM) 으로 적재되어
    //   V_COMPLETED_FRAME·export deid/ 벌·포털 프레임 서빙으로 새어 나간다.
    // ============================================================

    /**
     * 심링크 생성 가능 여부 — 불가 환경(권한/파일시스템)에서는 테스트를 명시 사유와 함께 skip 한다.
     * 조용한 통과가 되지 않도록 skip 사유에 실패 원인을 담는다.
     */
    private void assumeSymlinkSupported() {
        Path probeTarget = tmp.resolve("symlink-probe-target.txt");
        Path probeLink = tmp.resolve("symlink-probe-link.txt");
        try {
            Files.write(probeTarget, new byte[]{1});
            Files.createSymbolicLink(probeLink, probeTarget);
            Files.deleteIfExists(probeLink);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort(
                    "심볼릭 링크를 만들 수 없는 환경이라 CWE-59 회귀 케이스를 건너뛴다"
                            + " (원인=" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")."
                            + " Windows 개발자 모드 비활성/권한 부족 시 발생 — Linux CI 에서는 실행된다.");
        }
    }

    @Test
    @DisplayName("CWE59_co_locate_허용경로의_심링크가_원본영상을_가리키면_거부되고_RAW만_추출된다")
    void extractByMarks_deidSymlinkToOriginal_rejectedRawOnly() throws IOException {
        assumeSymlinkSupported();
        // given — 허용 base({dirname(원본)}/9001/deid) 안에 "비식별본" 이름의 심링크를 만들되, 실제로는
        //   base 밖의 원본(PII) 영상을 가리킨다. NAS 쓰기 권한 보유자의 링크 조작 재현.
        Path deidDir = sourceVideo.getParent().resolve("9001").resolve("deid");
        Files.createDirectories(deidDir);
        Path evilLink = deidDir.resolve("clip-mask.mp4");
        Files.createSymbolicLink(evilLink, sourceVideo);
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(evilLink.toString())));

        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then — 실경로가 base 밖(원본) 이므로 채택하지 않는다. 원본 폴백도 없다(RAW only).
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNull();
        assertThat(recordedSeekMillis).hasSize(1);
    }

    @Test
    @DisplayName("CWE59_경로중간_세그먼트가_심링크로_base밖을_가리키면_거부되고_RAW만_추출된다")
    void extractByMarks_deidIntermediateSegmentSymlink_rejectedRawOnly() throws IOException {
        assumeSymlinkSupported();
        // given — 비식별 저장소 base(tmp/deid) 안의 중간 세그먼트(videos)를 base 밖 디렉터리로 링크한다.
        //   경로 문자열은 tmp/deid/videos/9001/... 로 base 하위처럼 보이지만 실제 파일은 base 밖에 있다.
        Path outsideDir = tmp.resolve("outside-deid").resolve("9001");
        Files.createDirectories(outsideDir);
        Path realFile = outsideDir.resolve("clip-mask.mp4");
        Files.write(realFile, new byte[]{0, 0, 0});
        Path deidBase = tmp.resolve("deid");
        Files.createDirectories(deidBase);
        Files.createSymbolicLink(deidBase.resolve("videos"), tmp.resolve("outside-deid"));
        Path lexicallyInside = deidBase.resolve("videos").resolve("9001").resolve("clip-mask.mp4");
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(lexicallyInside.toString())));

        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNull();
        assertThat(recordedSeekMillis).hasSize(1);
    }

    @Test
    @DisplayName("CWE59보강_후에도_심링크아닌_정상_co_locate_비식별영상은_여전히_채택된다")
    void extractByMarks_realFileColocate_stillAccepted() throws IOException {
        // given — 결함#2 회귀 방지: 실경로 재검증 보강이 <b>정상</b> co-locate 경로를 막으면 안 된다.
        //   (막으면 DE_IDNTF_SRC_FILE_PATH_NM 전 행 NULL 로 되돌아간다.)
        Path coLocateDeid = sourceVideo.getParent().resolve("9001").resolve("deid")
                .resolve("clip-mask.mp4");
        Files.createDirectories(coLocateDeid.getParent());
        Files.write(coLocateDeid, new byte[]{0, 0, 0});
        // 실제 파일(심링크 아님)임을 명시 단언 — 픽스처가 조용히 바뀌는 것을 막는다.
        assertThat(Files.isSymbolicLink(coLocateDeid)).isFalse();
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(coLocateDeid.toString())));

        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then — 2벌 추출(raw 1 + deid 1) 유지.
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNotNull();
        assertThat(recordedSeekMillis).hasSize(2);
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

    // ============================================================
    // 재실행 멱등 (@req R1) — 자동 재시도가 파이프라인을 선두부터 다시 돌려도 견딘다
    // ============================================================

    /**
     * 이미 추출된 프레임 행을 심는다 — {@code (RAW_SN, FRM_NO)} + 영상 내 실제 위치({@code VDO_FRM_NO}).
     *
     * @param srcSn        보존되어야 하는 기존 PK(라벨 FK 가 이 값을 참조한다)
     * @param frameNo      추출 순번
     * @param videoFrameNo 영상 내 실제 프레임 위치(= 마킹의 frameIndex)
     */
    private LsDataSrc existingFrame(long srcSn, long frameNo, Long videoFrameNo) {
        LsDataSrc src = LsDataSrc.create(9001L, frameNo, videoFrameNo,
                tmp.resolve("frames").resolve("raw").resolve("9001")
                        .resolve("frame-" + frameNo + ".jpg").toString(), null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    /**
     * 시나리오 1 — 프레임추출 성공 → 뒷단계 실패 → 재시도. UNIQUE 제약
     * ({@code UK_LS_DATA_SRC_RAW_FRAME}) 위반 없이 통과하고 <b>기존 SRC_SN 이 보존</b>되어야 한다
     * (PK 가 재발급되면 그 프레임에 달린 라벨이 고아가 된다).
     */
    @Test
    @DisplayName("이미_추출된_프레임은_재추출하지_않고_기존_SRC_SN_을_그대로_돌려준다")
    void extractByMarks_allFramesExist_reusedWithoutInsert() {
        // given — 3개 마킹 위치가 이미 전부 추출된 상태.
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(
                existingFrame(501L, 0L, 0L),
                existingFrame(502L, 1L, 150L),
                existingFrame(503L, 2L, 300L)));
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"), new MarkItem(150, "00:05"), new MarkItem(300, "00:10"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then — 결과는 3건이지만 신규 INSERT·이력 기록·ffmpeg 추출은 0건이다.
        assertThat(frames).hasSize(3);
        assertThat(frames).extracting(LsDataSrc::getSrcSn).containsExactly(501L, 502L, 503L);
        verify(srcRepository, never()).save(any(LsDataSrc.class));
        verify(hstryRepository, never()).save(any(LsDataSrcHstry.class));
        assertThat(recordedSeekMillis)
                .as("이미 있는 프레임은 ffmpeg 를 다시 돌리지 않는다")
                .isEmpty();
    }

    /**
     * 시나리오 2 — 부분 추출(2/3) 후 재시도. 이미 있는 프레임은 skip 하고 <b>없는 프레임만</b> 새로 추출해야
     * 한다. 스텝 단위로 판정하면(하나라도 있으면 전체 skip) 남은 프레임이 영구 결손된다.
     */
    @Test
    @DisplayName("부분_추출된_상태에서_재시도하면_없는_프레임만_새로_추출한다")
    void extractByMarks_partiallyExtracted_extractsOnlyMissing() {
        // given — 3개 중 0·1번만 추출된 상태(2번 없음).
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(
                existingFrame(511L, 0L, 0L),
                existingFrame(512L, 1L, 150L)));
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"), new MarkItem(150, "00:05"), new MarkItem(300, "00:10"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then — 3건 반환(기존 2 + 신규 1). 신규 1건만 INSERT·추출된다.
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).getSrcSn()).isEqualTo(511L);
        assertThat(frames.get(1).getSrcSn()).isEqualTo(512L);
        verify(srcRepository, times(1)).save(any(LsDataSrc.class));
        // frameIndex 300 @ 30fps = 10000ms — 빠진 그 프레임만 추출됐다.
        assertThat(recordedSeekMillis).containsExactly(10000L);
        assertThat(frames.get(2).getFrameNo()).isEqualTo(2L);
        assertThat(frames.get(2).getVideoFrameNo()).isEqualTo(300L);
    }

    /**
     * 시나리오 6(회귀 — 가장 중요) — 멱등 가드가 <b>정상 최초 실행</b>을 막아서는 안 된다.
     * 기존 프레임이 0건이면 종전과 동일하게 전량 추출·INSERT 된다.
     */
    @Test
    @DisplayName("기존_프레임이_0건이면_멱등_가드가_최초_전량_추출을_막지_않는다")
    void extractByMarks_noExistingFrames_extractsAll() {
        // given — 기존 프레임 없음(최초 실행). 기본 stub 이 빈 리스트.
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"), new MarkItem(150, "00:05"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then
        assertThat(frames).hasSize(2);
        verify(srcRepository, times(2)).save(any(LsDataSrc.class));
        assertThat(recordedSeekMillis).containsExactly(0L, 5000L);
    }

    /**
     * fail-closed — 같은 {@code FRM_NO} 인데 영상 내 위치({@code VDO_FRM_NO})가 다른 행은 다른 마킹으로 뽑힌
     * 프레임이므로 재사용하지 않고 <b>건너뛴다</b>(덮어쓰지도, 새로 INSERT 하지도 않는다).
     *
     * <p>이 상태는 코드 실측상 도달 불가로 판단했다(마킹 생성이 {@code MARKING_READY} 를 요구하고, 프레임이
     * 있는 영상을 그 상태로 되감는 통로는 {@code DeidentStageResumeService} 가 fail-closed 로 막는다).
     * 그럼에도 조용히 통과시키지 않는 것을 고정한다 — 통과시키면 좌표가 어긋난 프레임에 라벨이 붙는다.
     */
    @Test
    @DisplayName("영상_내_위치가_다른_기존_행은_재사용하지_않고_건너뛴다")
    void extractByMarks_videoFramePositionMismatch_skippedFailClosed() {
        // given — FRM_NO 0 행이 있으나 VDO_FRM_NO 가 이번 마킹(0)과 다르다(999).
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L))
                .thenReturn(List.of(existingFrame(521L, 0L, 999L)));
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"), new MarkItem(150, "00:05"));

        // when
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        // then — 0번은 건너뛰고 1번만 신규 추출된다(기존 행은 손대지 않는다).
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getFrameNo()).isEqualTo(1L);
        verify(srcRepository, times(1)).save(any(LsDataSrc.class));
        assertThat(recordedSeekMillis).containsExactly(5000L);
    }

    /**
     * 레거시 행({@code VDO_FRM_NO} NULL)은 위치를 검증할 수 없지만 <b>그대로 재사용</b>한다.
     *
     * <p>⚠ <b>기대값 정정(DEV_FIX 2라운드)</b>: 구 테스트명은 {@code …위치_확인_불가로_건너뛴다} 였고
     * {@code frames} 가 <b>비어 있는 것을 정상으로 승인</b>했다. 그 동작은 폐기됐다 — skip 하면 <b>전
     * 프레임이 NULL 인 영상</b>(dev 실측: 632프레임 중 115건 NULL, {@code rawSn} 1~10 은 전 프레임 NULL)이
     * {@code execute} 의 "추출 결과 0건" INTERNAL_ERROR 로 떨어져 <b>재진입마다 영구 실패</b>한다.
     * 재사용은 위치를 추측하는 것이 아니라 이미 존재하는 산출물을 그대로 쓰는 것이라 "순번 폴백 금지"
     * 경계와 성질이 다르다. 재추출로 갱신하지도 않는다(라벨 좌표가 무의미해진다).
     */
    @Test
    @DisplayName("VDO_FRM_NO_가_없는_레거시_행은_위치_미검증이어도_그대로_재사용한다")
    void extractByMarks_legacyRowWithoutVideoFrameNo_reused() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L))
                .thenReturn(List.of(existingFrame(531L, 0L, null)));
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), marks);

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getSrcSn()).isEqualTo(531L);
        // 재추출·재INSERT 없음 — 기존 산출물을 그대로 쓴다.
        verify(srcRepository, never()).save(any(LsDataSrc.class));
        assertThat(recordedSeekMillis).isEmpty();
        // VDO_FRM_NO 는 참값을 모르므로 NULL 로 남긴다(지어내지 않는다 · 백필도 하지 않는다).
        assertThat(frames.get(0).getVideoFrameNo()).isNull();
    }

    /**
     * ★ DEV_FIX 2라운드 핵심 가드 — <b>전 프레임이 레거시(VDO_FRM_NO NULL)인 영상</b>도 재진입에 성공한다.
     *
     * <p>구 동작(NULL skip)에서는 {@code saved} 가 빈 리스트가 되어 {@code execute} 가
     * "프레임 추출 결과가 0건입니다" INTERNAL_ERROR 를 던지고, 재시도 큐가 재무장돼 매 회차 같은 지점에서
     * 실패했다(영구 미완주). 이 테스트는 {@code extractByMarks} 결과와 {@code execute} 계약을 함께 고정한다
     * — 둘을 따로 보면 각각 절반만 증명해 조합을 놓친다(실제로 그렇게 놓쳤다).
     */
    @Test
    @DisplayName("레거시_행만_있는_영상도_재진입에_성공한다")
    void execute_allLegacyRows_reenterSucceeds() {
        // given — 3프레임 전부 VDO_FRM_NO NULL (dev 의 rawSn 1~10 형상).
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(
                existingFrame(561L, 0L, null),
                existingFrame(562L, 1L, null),
                existingFrame(563L, 2L, null)));
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"), new MarkItem(150, "00:05"), new MarkItem(300, "00:10"));
        LsDataRaw raw = newRaw(60);
        BatchContext ctx = new BatchContext(9001L, raw, null);
        ctx.setMarks(marks);

        // when — 파이프라인 진입점. 예외가 나오면 실패(구 동작이 여기서 INTERNAL_ERROR 였다).
        extractor.execute(ctx);

        // then — 전량 재사용되어 결과가 비지 않고, INSERT 는 0건이다.
        List<LsDataSrc> frames = extractor.extractByMarks(raw, marks);
        assertThat(frames).extracting(LsDataSrc::getSrcSn).containsExactly(561L, 562L, 563L);
        verify(srcRepository, never()).save(any(LsDataSrc.class));
    }

    /**
     * ★ DEV_FIX 2라운드 — 재사용 프레임의 <b>비식별 경로가 비어 있으면</b> 비식별 이미지를 붙이고 경로를
     * 갱신한다({@code SRC_SN} 보존).
     *
     * <p>1회차에 비식별 영상이 아직 보이지 않아 RAW only 로 빠진 프레임은
     * {@code DE_IDNTF_SRC_FILE_PATH_NM} 이 null 로 남는다. 재사용만 하고 넘어가면 재시도는 <b>성공하는데</b>
     * 그 프레임의 비식별 이미지는 영구 부재가 되어 export PARTIAL · {@code /deid-image} 404 ·
     * {@code V_COMPLETED_FRAME.DEIDENTIFIED_PATH} NULL 로 이어진다(복구 경로 0). 같은 계열의 과거 사고가
     * 있어 반복을 막는 가드다.
     */
    @Test
    @DisplayName("재사용_프레임의_비식별_경로가_비어_있으면_비식별_이미지를_붙이고_경로를_갱신한다")
    void extractByMarks_reusedFrameWithoutDeidPath_backfilled() throws IOException {
        // given — 비식별 영상이 이제는 보인다.
        Path deidVideo = tmp.resolve("deid").resolve("videos").resolve("9001").resolve("clip-deid.mp4");
        Files.createDirectories(deidVideo.getParent());
        Files.write(deidVideo, new byte[]{0, 0, 0});
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(deidVideo.toString())));
        // …그런데 기존 행은 1회차에 RAW only 로 적재돼 비식별 경로가 비어 있다.
        LsDataSrc legacyRawOnly = existingFrame(571L, 0L, 0L);
        assertThat(legacyRawOnly.getDeIdntfSrcFilePathNm()).isNull();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(legacyRawOnly));

        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), List.of(new MarkItem(0, "00:00")));

        // then — SRC_SN 은 보존되고(라벨 FK 유지) 비식별 경로만 채워진다.
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getSrcSn()).isEqualTo(571L);
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm())
                .as("재사용 프레임의 비식별 경로가 영구 부재로 남으면 export 가 PARTIAL 이 된다")
                .isNotNull()
                .endsWith("frame-0.jpg");
        assertThat(Files.exists(Paths.get(frames.get(0).getDeIdntfSrcFilePathNm()))).isTrue();
        // 새 행을 만들지 않는다(dirty-update) — INSERT 로 새 SRC_SN 이 발급되면 라벨이 고아가 된다.
        verify(srcRepository, never()).save(any(LsDataSrc.class));
        // 비식별을 붙인 사실은 이력으로 남는다(최초 추출의 DEID_ATTACHED 와 같은 의미).
        verify(hstryRepository).save(any(LsDataSrcHstry.class));
        // 원본 프레임은 재추출하지 않고 비식별 1벌만 쓴다.
        assertThat(recordedSeekMillis).containsExactly(0L);
    }

    /**
     * ★★ DEV_FIX 3라운드 핵심 가드 — <b>위치를 검증할 수 없는 레거시 행에는 비식별 이미지를 붙이지 않는다</b>.
     *
     * <p>이 조합(<b>{@code VDO_FRM_NO} NULL ∩ deid 경로 NULL ∩ deid 소스 존재</b>)이 dev 의 실제 모집단이다
     * (레거시 115프레임, 그 위에 라벨 1,680건). 2라운드 테스트는 백필 2건이 전부 <b>위치 검증 행</b>이었고
     * 레거시 테스트는 <b>deid 소스를 stub 하지 않아</b> 백필이 발동조차 하지 않아서 이 교차가 커버 밖이었다
     * (변이가 각 축을 따로 무력화하므로 구조적으로 놓친다 — "각각 절반씩만 증명"의 재발).
     *
     * <h3>왜 붙이면 안 되는가</h3>
     * <p>붙일 위치는 이번 실행의 <b>최신 마킹</b>에서 계산되는데({@code MarkingLoadStep} 은 최신 1건 사용)
     * 재사용하는 원본은 <b>옛 마킹</b>의 산물일 수 있다 — dev 실측: {@code rawSn} 1 은 프레임 21건이
     * {@code marking_sn=1} 로 생성된 뒤 14일 지나 {@code marking_sn=15}(간격 60)가 추가됐다. 그 위치로
     * 비식별 프레임을 뽑아 붙이면 <b>원본과 비식별이 서로 다른 순간</b>이 되고, 출력이
     * {@code frames/deid/{rawSn}/frame-{i}.jpg} 제자리 덮어쓰기라 <b>비가역</b>이다. CLAUDE.md 의
     * "{@code VDO_FRM_NO} NULL 은 순번 폴백 없이 skip" 구속 정책과 같은 축이다.
     */
    @Test
    @DisplayName("위치를_검증할_수_없는_레거시_행에는_비식별_이미지를_붙이지_않는다")
    void extractByMarks_legacyRowWithDeidSourceAvailable_doesNotAttachDeid() throws IOException {
        // given — 비식별 영상은 <b>실재</b>한다(백필 조건이 충족된 상태).
        Path deidVideo = tmp.resolve("deid").resolve("videos").resolve("9001").resolve("clip-deid.mp4");
        Files.createDirectories(deidVideo.getParent());
        Files.write(deidVideo, new byte[]{0, 0, 0});
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(9001L))
                .thenReturn(Optional.of(succeededLog(deidVideo.toString())));
        // …그런데 기존 행은 레거시다: 영상 내 위치(VDO_FRM_NO)를 모르고 비식별 경로도 비어 있다.
        LsDataSrc legacy = existingFrame(551L, 0L, null);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(legacy));

        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), List.of(new MarkItem(0, "00:00")));

        // then ① 재사용 자체는 성공한다(영구 실패 없음 — 2라운드 수정 유지).
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getSrcSn()).isEqualTo(551L);
        // then ② 비식별 소스에서 프레임을 뽑지 않는다 — 위치를 추측해 붙이면 비가역 손상이다.
        assertThat(recordedWriteSources)
                .as("위치 미검증 행에 비식별 이미지를 붙이면 원본과 비식별이 서로 다른 순간이 된다")
                .doesNotContain(deidVideo);
        assertThat(recordedWriteSources).isEmpty();
        // then ③ 경로도 이력도 남기지 않는다(참값을 모르므로 지어내지 않는다).
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNull();
        verify(hstryRepository, never()).save(any(LsDataSrcHstry.class));
        verify(srcRepository, never()).save(any(LsDataSrc.class));
    }

    /**
     * ★ DEV_FIX 2라운드 — 비식별 소스가 <b>여전히</b> 안 보이면 조용히 넘어가되 그 사실을 남긴다.
     * (예외로 실패시키지 않는다 — 비식별 부재는 이 단계가 해결할 수 있는 조건이 아니다.)
     */
    @Test
    @DisplayName("비식별_소스가_여전히_없으면_경고만_남기고_넘어간다")
    void extractByMarks_reusedFrameDeidSourceStillMissing_warnsAndContinues() {
        // given — 비식별 성공 로그 없음(기본 stub) + 기존 행의 비식별 경로도 비어 있다.
        LsDataSrc rawOnly = existingFrame(581L, 0L, 0L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(rawOnly));

        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), List.of(new MarkItem(0, "00:00")));

        // then — 재사용은 성공하고(영구 실패 없음) 비식별 경로는 여전히 비어 있다.
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getSrcSn()).isEqualTo(581L);
        assertThat(frames.get(0).getDeIdntfSrcFilePathNm()).isNull();
        verify(srcRepository, never()).save(any(LsDataSrc.class));
        // 붙일 것이 없으므로 DEID_ATTACHED 이력도 남기지 않는다.
        verify(hstryRepository, never()).save(any(LsDataSrcHstry.class));
        assertThat(recordedSeekMillis).isEmpty();
    }

    /**
     * 파이프라인 진입점 계약 유지 — 전부 재사용(신규 추출 0건)이어도 {@code execute} 는 성공해야 한다.
     * 결과 목록에 기존 행을 싣지 않으면 "추출 결과 0건" INTERNAL_ERROR 로 오판해 재시도가 영구 실패한다.
     */
    /**
     * {@code execute} 계약의 <b>반대쪽</b> 고정 — 전 프레임이 위치 불일치로 skip 되면 결과가 0건이므로
     * INTERNAL_ERROR 로 실패해야 한다(조용한 성공 금지).
     *
     * <p>이 단언이 없으면 "전량 재사용 → 성공" 쪽만 고정돼 mutation 2방향이 이 조합을 구조적으로 놓친다
     * (DEV_FIX 2라운드에서 실제로 놓쳤다 — 레거시 NULL 이 이 분기로 들어가 영구 실패했는데도 두 테스트가
     * 각각 절반씩만 증명했다).
     */
    @Test
    @DisplayName("전부_위치불일치로_스킵되면_execute_가_INTERNAL_ERROR_로_실패한다")
    void execute_allFramesSkippedByConflict_failsAsEmpty() {
        // given — 기존 행의 VDO_FRM_NO 가 이번 마킹과 전부 다르다(마킹 교체 신호).
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(
                existingFrame(591L, 0L, 777L),
                existingFrame(592L, 1L, 888L)));
        FfmpegFrameExtractor extractor = newExtractor();
        BatchContext ctx = new BatchContext(9001L, newRaw(60), null);
        ctx.setMarks(List.of(new MarkItem(0, "00:00"), new MarkItem(150, "00:05")));

        // when / then — 0건을 성공으로 오인하지 않는다.
        assertThatThrownBy(() -> extractor.execute(ctx))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("0건");
        verify(srcRepository, never()).save(any(LsDataSrc.class));
    }

    @Test
    @DisplayName("전부_재사용된_재시도에서도_execute_가_0건_오판으로_실패하지_않는다")
    void execute_allFramesReused_doesNotFailAsEmpty() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L))
                .thenReturn(List.of(existingFrame(541L, 0L, 0L)));
        FfmpegFrameExtractor extractor = newExtractor();
        LsDataRaw raw = newRaw(60);
        BatchContext ctx = new BatchContext(9001L, raw, null);
        ctx.setMarks(List.of(new MarkItem(0, "00:00")));

        extractor.execute(ctx);   // 예외가 나오면 실패

        verify(srcRepository, never()).save(any(LsDataSrc.class));
    }
}

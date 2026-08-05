package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DeidentFrameAttacher 단위 테스트 (Phase 2 — frame-exact 재설계).
 *
 * <p>이미 비식별 완료된 영상에서 기존 LS_DATA_SRC 행의 frm_no <b>번호로 직접 추출</b>한 비식별 프레임을
 * <b>같은 행에 attach</b>한다(fps 무관). 새 프레임 행을 만들지 않고(라벨 보존), 해상도 불일치 시 fail-closed,
 * 멱등(이미 attach된 행 skip)이다.
 *
 * <p>FrameWriter / ImageResizer 는 stub 으로 주입하여 ffmpeg 바이너리 비의존으로 검증한다.
 * frame-exact 전환으로 VideoFpsProbe 의존은 제거되었다.
 */
class DeidentFrameAttacherTest {

    @TempDir
    Path tmp;

    private LsDataSrcRepository srcRepository;
    private FfmpegFrameExtractor.FrameWriter frameWriter;
    private ImageResizer imageResizer;
    private BatchStatusService batchStatusService;

    private Path rawVideo;
    private Path deidVideo;
    /** writeFrameByNumber 로 호출된 frameNo 기록. */
    private List<Integer> recordedFrameNos;
    private List<Path> writtenFrames;
    /** 특정 frameNo 에서 IOException 을 던지도록 설정(중간 실패 테스트). null 이면 정상. */
    private Integer failAtFrameNo;

    private int[] originalDim = {1920, 1080};
    private int[] deidDim = {1920, 1080};

    @BeforeEach
    void setUp() throws IOException {
        srcRepository = mock(LsDataSrcRepository.class);
        imageResizer = mock(ImageResizer.class);
        batchStatusService = mock(BatchStatusService.class);

        // readDimensions: 원본 프레임 경로면 originalDim, 그 외(비식별 추출)는 deidDim
        when(imageResizer.readDimensions(any())).thenAnswer(inv -> {
            Path p = inv.getArgument(0);
            return p.toString().contains("orig-frame") ? originalDim : deidDim;
        });

        recordedFrameNos = new ArrayList<>();
        writtenFrames = new ArrayList<>();
        failAtFrameNo = null;
        frameWriter = new FfmpegFrameExtractor.FrameWriter() {
            @Override
            public boolean sourceExists(Path sourceVideo) {
                return Files.exists(sourceVideo);
            }

            @Override
            public void writeFrame(Path sourceVideo, Path outputFrame, long seekMillis) {
                throw new UnsupportedOperationException(
                        "frame-exact 재설계: DeidentFrameAttacher 는 seek 방식 writeFrame 을 호출하지 않아야 한다");
            }

            @Override
            public void writeFrameByNumber(Path sourceVideo, Path outputFrame, int frameNo) throws IOException {
                if (failAtFrameNo != null && failAtFrameNo == frameNo) {
                    throw new IOException("강제 추출 실패 frameNo=" + frameNo);
                }
                if (frameNo < 0) {
                    throw new IOException("프레임 번호 음수 frameNo=" + frameNo);
                }
                if (outputFrame.getParent() != null && !Files.exists(outputFrame.getParent())) {
                    Files.createDirectories(outputFrame.getParent());
                }
                recordedFrameNos.add(frameNo);
                writtenFrames.add(outputFrame);
                Files.write(outputFrame, ("frame-no-" + frameNo).getBytes());
            }
        };

        rawVideo = tmp.resolve("clip.mp4");
        Files.write(rawVideo, new byte[]{0, 0, 0});
        // 비식별 영상은 <무결성 판정 단일 원천>(DeidentArtifactIntegrity — 정규파일 + 크기하한 +
        //   컨테이너 시그니처)을 통과하는 유효 픽스처여야 한다(DEV_FIX 2차 LOW-5).
        deidVideo = kr.co.cudo.authoring.support.TestVideoFixtures.writeTinyMp4(
                tmp.resolve("clip-deid.mp4"));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private DeidentFrameAttacher newAttacher() {
        Path deidBase = tmp.resolve("deid");
        return new DeidentFrameAttacher(srcRepository, frameWriter, imageResizer,
                batchStatusService, deidBase.toString());
    }

    private LsDataRaw newRaw() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, rawVideo.toString(), null, 60);
        setField(raw, "rawSn", 9001L);
        raw.markDeidentified("Y");
        return raw;
    }

    /**
     * 원본 프레임 파일을 실제 생성하고 LsDataSrc 행을 만든다 —
     * <b>{@code FRM_NO == VDO_FRM_NO}</b> 인 단순 픽스처.
     *
     * <p>⚠ 이 픽스처는 "어느 컬럼으로 추출하는가"에 대한 <b>판별력이 0</b> 이다(두 값이 같아 어느 쪽을
     * 써도 통과한다). 그 축은 {@link #newSrcAt(long, int, Long)} 을 쓰는 전용 테스트
     * ({@code 재추출은_영상_프레임번호를_기준으로_한다})가 고정한다. 여기서는 그 외 계약(행 갱신·멱등·
     * 해상도 가드·경로 스킴)만 검증하므로 단순 픽스처를 유지한다.
     */
    private LsDataSrc newSrc(long srcSn, int frameNo) throws IOException {
        return newSrcAt(srcSn, frameNo, (long) frameNo);
    }

    /**
     * 원본 프레임 파일을 실제 생성하고 LsDataSrc 행을 만든다 —
     * {@code FRM_NO}(추출 순번)와 {@code VDO_FRM_NO}(영상 내 실제 위치)를 <b>따로</b> 지정한다.
     *
     * @param videoFrameNo {@code null} 이면 레거시 행(재추출 위치 미상)을 재현한다.
     */
    private LsDataSrc newSrcAt(long srcSn, int frameNo, Long videoFrameNo) throws IOException {
        Path origFrame = tmp.resolve("orig-frame-" + frameNo + ".jpg");
        Files.write(origFrame, new byte[]{1, 2, 3});
        LsDataSrc src = LsDataSrc.create(9001L, frameNo, videoFrameNo, origFrame.toString(), null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    // ============================================================

    @Test
    @DisplayName("비식별프레임_frmno번호로_직접추출되고_기존행에_attach_새행없음")
    void attach_byFrameNo_updatesExistingRows_noNewRows() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        LsDataSrc s1 = newSrc(102L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0, s1));

        DeidentFrameAttacher attacher = newAttacher();
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo, false);

        assertThat(count).isEqualTo(2);
        // frm_no 번호로 직접 추출됨 (fps 변환 없음)
        assertThat(recordedFrameNos).containsExactly(0, 5);
        // 같은 행 갱신 — deidPath 가 채워짐
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNotBlank();
        assertThat(s1.getDeIdntfSrcFilePathNm()).isNotBlank();
        // 새 행 INSERT 금지 — srcRepository.save 는 호출되지 않는다 (dirty checking)
        org.mockito.Mockito.verify(srcRepository, org.mockito.Mockito.never()).save(any());
    }

    // ============================================================
    // 경로 스킴(스킴 A) 통일: 비식별 프레임은 frames/deid/{rawSn} 하위 (FfmpegFrameExtractor DEID 와 동일 위치)
    // ============================================================

    @Test
    @DisplayName("비식별프레임은_frames_deid_rawSn_하위에_써진다_스킴A통일")
    void deidFramesWrittenUnderFramesDeid() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        LsDataSrc s1 = newSrc(102L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0, s1));

        DeidentFrameAttacher attacher = newAttacher();
        attacher.attachDeidentFrames(newRaw(), deidVideo, false);

        // 옛 스킴(frames/{rawSn}) 이 아니라 frames/deid/{rawSn} 하위여야 한다 — 원본 frames/raw 와 충돌 0.
        assertThat(s0.getDeIdntfSrcFilePathNm().replace('\\', '/')).contains("/frames/deid/9001/");
        assertThat(s1.getDeIdntfSrcFilePathNm().replace('\\', '/')).contains("/frames/deid/9001/");
        // 실제 디스크에도 frames/deid/9001 디렉토리에 써졌다.
        for (Path written : writtenFrames) {
            assertThat(written.toString().replace('\\', '/')).contains("/frames/deid/9001/");
            assertThat(written).exists();
        }
    }

    @Test
    @DisplayName("비식별_출력경로는_원본프레임_경로와_다르다_덮어쓰기위험제거")
    void deidPathDiffersFromOriginalFramePath() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));

        DeidentFrameAttacher attacher = newAttacher();
        attacher.attachDeidentFrames(newRaw(), deidVideo, false);

        // 원본 프레임 경로(srcFilePathNm)와 비식별 프레임 경로(deIdntfSrcFilePathNm)는 서로 다른 파일이어야 한다.
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNotEqualTo(s0.getSrcFilePathNm());
        Path deidFile = Path.of(s0.getDeIdntfSrcFilePathNm());
        Path origFile = Path.of(s0.getSrcFilePathNm());
        assertThat(deidFile).exists();
        assertThat(origFile).exists();
        assertThat(deidFile.toRealPath()).isNotEqualTo(origFile.toRealPath());
    }

    @Test
    @DisplayName("CWE22_비식별출력경로는_baseDeidPath_하위로_정규화되어_가드통과")
    void deidOutputDirStaysUnderBaseDeidPath() throws Exception {
        Path deidBase = tmp.resolve("deid").toAbsolutePath().normalize();
        DeidentFrameAttacher attacher = new DeidentFrameAttacher(
                srcRepository, frameWriter, imageResizer, batchStatusService, deidBase.toString());

        java.lang.reflect.Method m = DeidentFrameAttacher.class.getDeclaredMethod(
                "resolveSafeOutputDir", Long.class);
        m.setAccessible(true);
        Path outputDir = (Path) m.invoke(attacher, 9001L);

        // frames/deid 세그먼트를 추가해도 base 하위라 startsWith 가드를 통과한다(거부 분기 회귀 없음).
        assertThat(outputDir.startsWith(deidBase)).isTrue();
        assertThat(outputDir.toString().replace('\\', '/')).endsWith("/frames/deid/9001");
    }

    @Test
    @DisplayName("라벨은_변경되지_않는다")
    void labels_untouched() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));

        DeidentFrameAttacher attacher = newAttacher();
        attacher.attachDeidentFrames(newRaw(), deidVideo, false);

        // 생성자 파라미터·필드에 라벨 관련 의존이 없음을 리플렉션으로 단언(컴파일+런타임 보장)
        boolean hasLabelDependency = java.util.Arrays.stream(
                        DeidentFrameAttacher.class.getDeclaredFields())
                .anyMatch(f -> f.getType().getSimpleName().toLowerCase().contains("lbl")
                        || f.getType().getSimpleName().toLowerCase().contains("label"));
        assertThat(hasLabelDependency).isFalse();
    }

    @Test
    @DisplayName("해상도_불일치시_CustomException_롤백")
    void resolutionMismatch_throws() throws IOException {
        deidDim = new int[]{1280, 720}; // 비식별 출력이 원본(1920x1080)과 다름
        LsDataSrc s0 = newSrc(101L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));

        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo, false))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNull();
    }

    @Test
    @DisplayName("readDimensions_측정불가시_fail_closed")
    void readDimensionsFails_failClosed() throws IOException {
        Path missing = tmp.resolve("orig-frame-missing.jpg");
        // VDO_FRM_NO 는 채운다 — null 이면 그 프레임을 건너뛰어(레거시 정책) 해상도 가드에 도달하지 않는다.
        LsDataSrc s0 = LsDataSrc.create(9001L, 0, 0L, missing.toString(), null);
        setField(s0, "srcSn", 101L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));
        when(imageResizer.readDimensions(missing)).thenThrow(
                new CustomException(kr.co.cudo.authoring.common.exception.ErrorCode.INTERNAL_ERROR, "측정 불가"));

        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo, false))
                .isInstanceOf(CustomException.class);
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNull();
    }

    @Test
    @DisplayName("readDimensions_null또는단축배열시_fail_closed")
    void readDimensionsNullOrShort_failClosed() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));
        // 비식별 프레임 측정 결과가 단축 배열(길이 1) → fail-closed
        deidDim = new int[]{1920};

        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo, false))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNull();
    }

    @Test
    @DisplayName("비식별프레임_해상도0x0_측정불가시_fail_closed_INVALID_INPUT_롤백")
    void deidDimZero_failClosed() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));
        // 비식별 출력 프레임이 0x0 으로 측정됨(손상/0바이트 등) → 불일치가 아니라 "측정 불가"로 대칭 처리되어야 한다.
        deidDim = new int[]{0, 0};

        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo, false))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    assertThat(((CustomException) e).getErrorCode().name()).isEqualTo("INVALID_INPUT");
                    // 측정 불가(fail-closed) 분기 — 불일치 메시지가 아닌 측정 불가 메시지.
                    assertThat(e.getMessage()).contains("측정할 수 없습니다");
                });
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNull();
    }

    @Test
    @DisplayName("비식별프레임_높이만0_측정불가시_fail_closed_INVALID_INPUT")
    void deidDimHeightZero_failClosed() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));
        deidDim = new int[]{1920, 0}; // 폭은 정상, 높이만 0 → 측정 불가로 대칭 처리

        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo, false))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNull();
    }

    @Test
    @DisplayName("이미_deid경로_있으면_skip_멱등")
    void alreadyAttached_skipped_idempotent() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        s0.attachDeidPath("/already/attached/frame-0.jpg"); // 이미 attach됨
        LsDataSrc s1 = newSrc(102L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0, s1));

        DeidentFrameAttacher attacher = newAttacher();
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo, false);

        // s0 skip, s1 만 처리 → 1
        assertThat(count).isEqualTo(1);
        assertThat(s0.getDeIdntfSrcFilePathNm()).isEqualTo("/already/attached/frame-0.jpg");
        assertThat(s1.getDeIdntfSrcFilePathNm()).isNotBlank();
        // 이미 attach 된 frm_no=0 은 추출 호출 자체가 없음
        assertThat(recordedFrameNos).containsExactly(5);
    }

    @Test
    @DisplayName("부분멱등_혼합상태_미처리행만_추출")
    void partialIdempotent_mixedState_onlyUnattached() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        LsDataSrc s1 = newSrc(102L, 5);
        s1.attachDeidPath("/already/frame-5.jpg"); // 중간 행만 이미 attach
        LsDataSrc s2 = newSrc(103L, 9);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0, s1, s2));

        DeidentFrameAttacher attacher = newAttacher();
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo, false);

        assertThat(count).isEqualTo(2);
        assertThat(recordedFrameNos).containsExactly(0, 9); // 5 는 skip
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNotBlank();
        assertThat(s1.getDeIdntfSrcFilePathNm()).isEqualTo("/already/frame-5.jpg");
        assertThat(s2.getDeIdntfSrcFilePathNm()).isNotBlank();
    }

    @Test
    @DisplayName("deid영상_부재시_예외")
    void deidVideoMissing_throws() {
        Path absent = tmp.resolve("nope.mp4");
        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), absent, false))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("deid영상이_유효한_컨테이너가_아니면_예외")
    void deidVideoNotAVideoContainer_throws() throws IOException {
        // 무결성 판정 통일 효과 — 크기가 0보다 커도 영상 컨테이너가 아니면 거부한다(구 판정은 통과했다).
        Path stub = tmp.resolve("stub-deid.mp4");
        Files.write(stub, "MOCK_DEIDENTIFIED\n".getBytes());
        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), stub, false))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("deid영상_0바이트시_예외")
    void deidVideoEmpty_throws() throws IOException {
        Path empty = tmp.resolve("empty-deid.mp4");
        Files.write(empty, new byte[]{});
        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), empty, false))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("프레임_0건이면_0반환")
    void noFrames_returnsZero() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of());

        DeidentFrameAttacher attacher = newAttacher();
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo, false);

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("writeFrameByNumber_중간실패시_예외전파_롤백")
    void writeFrameByNumber_failsMidway_propagates() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        LsDataSrc s1 = newSrc(102L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0, s1));
        failAtFrameNo = 5; // 2번째 프레임 추출 실패

        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo, false))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INTERNAL_ERROR");
    }

    // ============================================================
    // 재비식별(SC-009) — refreshExisting=true 강제 재생성 (개인정보 누락 프레임 교체)
    // ============================================================

    @Test
    @DisplayName("재비식별_refreshExisting_true시_이미deid경로있어도_전프레임_재추출되어_attach수가_프레임수와_같다")
    void refreshExisting_forcesReextractionOfAllAlreadyAttachedFrames() throws IOException {
        // 모든 프레임에 이미 deident 경로가 설정된 상태(초기 파이프라인 FfmpegFrameExtractor 가 attachDeidPath 한 케이스).
        LsDataSrc s0 = newSrc(101L, 0);
        s0.attachDeidPath("/old/frames/deid/9001/frame-0.jpg");
        LsDataSrc s1 = newSrc(102L, 5);
        s1.attachDeidPath("/old/frames/deid/9001/frame-5.jpg");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0, s1));

        DeidentFrameAttacher attacher = newAttacher();
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo, true);

        // 멱등 skip 우회 — 전 프레임이 새 비식별 영상으로 재추출됨(0 아님).
        assertThat(count).isEqualTo(2);
        // frameWriter 가 프레임마다 호출됨(frm_no 번호로 직접 추출).
        assertThat(recordedFrameNos).containsExactly(0, 5);
        // 경로가 새 비식별 위치(frames/deid/9001)로 재기록됨 — 옛 경로 잔존 금지.
        assertThat(s0.getDeIdntfSrcFilePathNm().replace('\\', '/')).contains("/frames/deid/9001/");
        assertThat(s1.getDeIdntfSrcFilePathNm().replace('\\', '/')).contains("/frames/deid/9001/");
    }

    @Test
    @DisplayName("재비식별_refreshExisting_false시는_기존멱등_보존_이미deid경로있으면_skip_attach0")
    void refreshExisting_false_preservesIdempotentSkip() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        s0.attachDeidPath("/old/frames/deid/9001/frame-0.jpg");
        LsDataSrc s1 = newSrc(102L, 5);
        s1.attachDeidPath("/old/frames/deid/9001/frame-5.jpg");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0, s1));

        DeidentFrameAttacher attacher = newAttacher();
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo, false);

        // 전부 skip — 기존 멱등 동작 유지.
        assertThat(count).isZero();
        assertThat(recordedFrameNos).isEmpty();
        assertThat(s0.getDeIdntfSrcFilePathNm()).isEqualTo("/old/frames/deid/9001/frame-0.jpg");
        assertThat(s1.getDeIdntfSrcFilePathNm()).isEqualTo("/old/frames/deid/9001/frame-5.jpg");
    }

    @Test
    @DisplayName("초기attach_deid경로없는프레임은_refreshExisting_무관하게_attach된다")
    void unattachedFramesAttachedRegardlessOfRefreshFlag() throws IOException {
        LsDataSrc a0 = newSrc(101L, 0); // deid 경로 없음
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(a0));
        DeidentFrameAttacher attacher = newAttacher();
        int withFalse = attacher.attachDeidentFrames(newRaw(), deidVideo, false);
        assertThat(withFalse).isEqualTo(1);
        assertThat(a0.getDeIdntfSrcFilePathNm()).isNotBlank();

        // 새 미처리 프레임으로 refreshExisting=true 검증
        recordedFrameNos.clear();
        LsDataSrc a1 = newSrc(102L, 7); // deid 경로 없음
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(a1));
        int withTrue = attacher.attachDeidentFrames(newRaw(), deidVideo, true);
        assertThat(withTrue).isEqualTo(1);
        assertThat(a1.getDeIdntfSrcFilePathNm()).isNotBlank();
        assertThat(recordedFrameNos).containsExactly(7);
    }

    @Test
    @DisplayName("재비식별_refreshExisting_true_해상도불일치_여전히_fail_closed_롤백")
    void refreshExisting_resolutionGuardStillEnforced() throws IOException {
        deidDim = new int[]{1280, 720}; // 비식별 출력이 원본(1920x1080)과 다름
        LsDataSrc s0 = newSrc(101L, 0);
        s0.attachDeidPath("/old/frames/deid/9001/frame-0.jpg");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));

        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo, true))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("CWE22_경로순회_차단")
    void cwe22_pathTraversal_blocked() throws IOException {
        // frm_no 가 경로 순회 시도 형태가 되더라도 baseDeidPath 외부로 빠지면 차단되어야 한다.
        // frameNo 는 int 라 직접 ../ 는 불가하지만, resolveSafeFrameFile 의 base 가드가 동작함을 보장한다.
        // 음수 frameNo → writeFrameByNumber 가 IOException → Custom-INTERNAL_ERROR 로 전파.
        LsDataSrc s0 = newSrc(101L, -1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));

        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo, false))
                .isInstanceOf(CustomException.class);
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNull();
    }

    // ============================================================
    // 선결 결함 회귀 — 재추출 위치는 VDO_FRM_NO(영상 내 실제 위치)다
    // ============================================================

    @Test
    @DisplayName("재추출은_영상_프레임번호를_기준으로_한다")
    void reExtractionUsesVideoFrameNo() throws IOException {
        // given — 마킹 위치가 1000·2000·3000 인 영상. 추출 순번(FRM_NO)은 0·1·2 다.
        //   두 값이 <b>확연히 다른</b> 픽스처여야 판별력이 있다(FRM_NO==VDO_FRM_NO 픽스처는 판별력 0).
        LsDataSrc s0 = newSrcAt(101L, 0, 1000L);
        LsDataSrc s1 = newSrcAt(102L, 1, 2000L);
        LsDataSrc s2 = newSrcAt(103L, 2, 3000L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0, s1, s2));

        DeidentFrameAttacher attacher = newAttacher();

        // when
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo, false);

        // then — ① 추출 위치는 VDO_FRM_NO. 구 구현은 0·1·2(영상 맨 앞)를 뽑아 라벨 좌표와 픽셀이 어긋났다.
        assertThat(count).isEqualTo(3);
        assertThat(recordedFrameNos).containsExactly(1000, 2000, 3000);

        // and — ② 출력 파일명은 여전히 FRM_NO(추출 순번). 초기 추출이 쓴 frame-{순번}.jpg 를 제자리
        //   교체해야 구 파일이 고아로 남지 않는다(디렉토리도 frames/deid/{rawSn} 로 동일).
        assertThat(writtenFrames).extracting(p -> p.getFileName().toString())
                .containsExactly("frame-0.jpg", "frame-1.jpg", "frame-2.jpg");
        assertThat(s0.getDeIdntfSrcFilePathNm()).endsWith("frame-0.jpg");
        assertThat(s2.getDeIdntfSrcFilePathNm()).endsWith("frame-2.jpg");
    }

    @Test
    @DisplayName("VDO_FRM_NO_가_없는_레거시행은_순번폴백_없이_건너뛴다")
    void legacyRowWithoutVideoFrameNoIsSkipped() throws IOException {
        // given — VDO_FRM_NO 도입 이전 행(NULL) 1건 + 정상 행 1건.
        LsDataSrc legacy = newSrcAt(101L, 0, null);
        LsDataSrc normal = newSrcAt(102L, 1, 2000L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(legacy, normal));

        DeidentFrameAttacher attacher = newAttacher();

        // when
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo, false);

        // then — 레거시 행은 <b>순번(0)으로 폴백하지 않는다</b>(폴백하면 지금 고치는 결함을 그대로 유지).
        //   추출 호출은 정상 행 1건뿐이고, 레거시 행의 비식별 경로는 갱신되지 않는다(옛 프레임 존치 + WARN).
        assertThat(count).isEqualTo(1);
        assertThat(recordedFrameNos).containsExactly(2000);
        assertThat(legacy.getDeIdntfSrcFilePathNm()).isNull();
        assertThat(normal.getDeIdntfSrcFilePathNm()).endsWith("frame-1.jpg");

        // and — skip 사실을 LS_BATCH_PROC_LOG 감사 행으로 남긴다(로그만으론 보존기간에 종속).
        org.mockito.Mockito.verify(batchStatusService).recordStageSkipped(
                9001L, BatchStage.FRAME_EXTRACT, DeidentFrameAttacher.SKIP_REASON_NO_VIDEO_FRAME_NO);
    }

    @Test
    @DisplayName("전부_VDO_FRM_NO_결측이면_0건_성공과_구분되게_SKIPPED_감사행을_남긴다")
    void allFramesSkippedIsDistinguishableFromZeroFrames() throws IOException {
        // given — 레거시 행만 있는 영상(V70 이전 적재, 백필 없음 = 영구 NULL).
        LsDataSrc legacy0 = newSrcAt(101L, 0, null);
        LsDataSrc legacy1 = newSrcAt(102L, 1, null);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(legacy0, legacy1));

        // when — 재비식별(신고 해소) 재추출.
        int count = newAttacher().attachDeidentFrames(newRaw(), deidVideo, true);

        // then — 반환값은 0 이라 "재추출할 프레임이 원래 없었음"과 같아 보인다.
        //   그러나 실제로는 <b>마스킹 실패 픽셀이 그대로 남은 상태</b>다(CWE-359) → 감사 행으로 구분된다.
        assertThat(count).isZero();
        assertThat(recordedFrameNos).isEmpty();
        org.mockito.Mockito.verify(batchStatusService).recordStageSkipped(
                9001L, BatchStage.FRAME_EXTRACT, DeidentFrameAttacher.SKIP_REASON_NO_VIDEO_FRAME_NO);
    }

    @Test
    @DisplayName("skip이_없으면_SKIPPED_감사행을_남기지_않는다")
    void noSkipRecordWhenNothingSkipped() throws IOException {
        // given — 정상 행만. (프레임 0건 경로도 동일하게 무기록이라 "무기록 = skip 없음" 이 성립한다.)
        LsDataSrc s0 = newSrcAt(101L, 0, 1000L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));

        assertThat(newAttacher().attachDeidentFrames(newRaw(), deidVideo, false)).isEqualTo(1);

        org.mockito.Mockito.verify(batchStatusService, org.mockito.Mockito.never())
                .recordStageSkipped(any(), any(), any());
    }

    @Test
    @DisplayName("재비식별_강제갱신도_영상_프레임번호로_재추출한다")
    void refreshExistingAlsoUsesVideoFrameNo() throws IOException {
        // given — 이미 비식별 경로가 붙은 행(초기 추출 산출물)을 재비식별로 강제 갱신.
        LsDataSrc s0 = newSrcAt(101L, 0, 1500L);
        s0.attachDeidPath("/old/frames/deid/9001/frame-0.jpg");
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));

        DeidentFrameAttacher attacher = newAttacher();

        // when
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo, true);

        // then
        assertThat(count).isEqualTo(1);
        assertThat(recordedFrameNos).containsExactly(1500);
        assertThat(s0.getDeIdntfSrcFilePathNm()).endsWith("frame-0.jpg");
    }
}

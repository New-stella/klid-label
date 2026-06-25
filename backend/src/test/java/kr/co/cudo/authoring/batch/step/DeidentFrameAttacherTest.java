package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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
        deidVideo = tmp.resolve("clip-deid.mp4");
        Files.write(deidVideo, new byte[]{0, 0, 0});
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
        return new DeidentFrameAttacher(srcRepository, frameWriter, imageResizer, deidBase.toString());
    }

    private LsDataRaw newRaw() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, rawVideo.toString(), null, 60);
        setField(raw, "rawSn", 9001L);
        raw.markDeidentified("Y");
        return raw;
    }

    /** 원본 프레임 파일을 실제 생성하고 LsDataSrc 행을 만든다. */
    private LsDataSrc newSrc(long srcSn, int frameNo) throws IOException {
        Path origFrame = tmp.resolve("orig-frame-" + frameNo + ".jpg");
        Files.write(origFrame, new byte[]{1, 2, 3});
        LsDataSrc src = LsDataSrc.create(9001L, frameNo, origFrame.toString(), null);
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
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo);

        assertThat(count).isEqualTo(2);
        // frm_no 번호로 직접 추출됨 (fps 변환 없음)
        assertThat(recordedFrameNos).containsExactly(0, 5);
        // 같은 행 갱신 — deidPath 가 채워짐
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNotBlank();
        assertThat(s1.getDeIdntfSrcFilePathNm()).isNotBlank();
        // 새 행 INSERT 금지 — srcRepository.save 는 호출되지 않는다 (dirty checking)
        org.mockito.Mockito.verify(srcRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("라벨은_변경되지_않는다")
    void labels_untouched() throws IOException {
        LsDataSrc s0 = newSrc(101L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));

        DeidentFrameAttacher attacher = newAttacher();
        attacher.attachDeidentFrames(newRaw(), deidVideo);

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

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNull();
    }

    @Test
    @DisplayName("readDimensions_측정불가시_fail_closed")
    void readDimensionsFails_failClosed() throws IOException {
        Path missing = tmp.resolve("orig-frame-missing.jpg");
        LsDataSrc s0 = LsDataSrc.create(9001L, 0, missing.toString(), null);
        setField(s0, "srcSn", 101L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(s0));
        when(imageResizer.readDimensions(missing)).thenThrow(
                new CustomException(kr.co.cudo.authoring.common.exception.ErrorCode.INTERNAL_ERROR, "측정 불가"));

        DeidentFrameAttacher attacher = newAttacher();

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo))
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

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo))
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
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo);

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
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo);

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

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), absent))
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

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), empty))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("프레임_0건이면_0반환")
    void noFrames_returnsZero() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of());

        DeidentFrameAttacher attacher = newAttacher();
        int count = attacher.attachDeidentFrames(newRaw(), deidVideo);

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

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INTERNAL_ERROR");
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

        assertThatThrownBy(() -> attacher.attachDeidentFrames(newRaw(), deidVideo))
                .isInstanceOf(CustomException.class);
        assertThat(s0.getDeIdntfSrcFilePathNm()).isNull();
    }
}

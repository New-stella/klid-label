package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.dto.MarkingImportScanRequest;
import kr.co.cudo.authoring.transfer.dto.MarkingImportScanResponse;
import kr.co.cudo.authoring.transfer.parser.MarkingDocumentParser;
import kr.co.cudo.authoring.transfer.parser.MarkingImportWarningCode;
import kr.co.cudo.authoring.transfer.service.MarkingFolderScanner;
import kr.co.cudo.authoring.transfer.service.MarkingImportAssessor;
import kr.co.cudo.authoring.transfer.service.MarkingImportScanService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 폴더 검사가 <b>무엇을 적재 가능으로 판정하고 무엇을 막는지</b>를 고정한다(API-216 · AC-1032/1033).
 *
 * <h3>★판정은 {@code importable} 하나가 한다</h3>
 * <p>경고 목록에는 <b>막는 것과 막지 않는 것이 섞여 있다</b>. 그래서 이 시험은 경고의 존재가 아니라
 * 그 값을 단언한다 — 경고 개수로 판정하면 「바로가기를 건너뛰었다」 같은 알림 하나에 정상 항목이
 * 적재 불가로 보인다.
 *
 * <h3>영상 판독기는 갈아 끼운다</h3>
 * <p>운영 구현은 외부 실행 파일에 기대므로 있는 환경과 없는 환경에서 결과가 달라진다. 여기서 보려는
 * 것은 <b>읽은 값으로 무엇을 판정하는가</b>이므로 값을 직접 준다.
 *
 * @design DOMAIN-017
 * @design API-216
 * @design AC-1032
 * @design AC-1033
 */
class MarkingImportScanServiceTest {

    /** 문서가 역산해 내는 속도 — 아래 표본이 프레임 100↔10초, 400↔20초라 30 이 나온다. */
    private static final double DECLARED_FPS = 30.0;

    /** 허용 오차 — 이 시험이 「어긋남」과 「같음」을 가르는 폭. */
    private static final double FPS_TOLERANCE = 0.5;

    @TempDir
    Path tempRoot;

    /**
     * 허용 루트 — 임시 폴더의 <b>실경로</b>다.
     *
     * <p>표기 그대로 쓰면 안 된다. 임시 폴더 자체가 바로가기 뒤에 있는 플랫폼이 있어(맥의
     * {@code /var} → {@code /private/var}) 판정기의 표기 기준 검사가 실경로를 범위 밖으로 본다.
     * 그 성질은 판정기의 것이지 이 창구의 것이 아니다.
     */
    private Path root;

    private VideoRepository videoRepository;
    private FakeProbe probe;
    private MarkingImportScanService service;

    @BeforeEach
    void setUp() throws IOException {
        root = tempRoot.toRealPath();
        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                root.toString(), "", root.toString(), root.toString(), root.toString(), "co-locate");
        ImportSourcePolicy policy = new ImportSourcePolicy(resolver);
        MarkingImportProperties properties = new MarkingImportProperties(
                8, 1000, 1000, 2, 500, List.of("mp4"), 2, 3, FPS_TOLERANCE, 30);
        videoRepository = mock(VideoRepository.class);
        probe = new FakeProbe(DECLARED_FPS);
        MarkingDocumentParser parser = new MarkingDocumentParser(new ObjectMapper());
        MarkingImportAssessor assessor =
                new MarkingImportAssessor(parser, probe, videoRepository, properties);
        service = new MarkingImportScanService(policy, new MarkingFolderScanner(properties),
                assessor, properties);
    }

    // ------------------------------------------------------------------ 정상

    @Test
    @DisplayName("짝이_맞고_속도가_같으면_적재할_수_있다고_판정한다")
    void 짝이_맞고_속도가_같으면_적재할_수_있다고_판정한다() throws IOException {
        writePair("a");

        MarkingImportScanResponse response = scan();

        assertThat(response.items()).hasSize(1);
        MarkingImportScanResponse.Item item = response.items().get(0);
        assertThat(item.importable()).isTrue();
        assertThat(item.videoFound()).isTrue();
        assertThat(item.clipId()).isEqualTo("a");
        assertThat(item.videoFileName()).isEqualTo("a.mp4");
        assertThat(item.segmentCount()).isEqualTo(1);
        assertThat(item.markCount()).isEqualTo(2);
        assertThat(item.declaredFps()).isEqualTo(DECLARED_FPS);
        assertThat(item.probedFps()).isEqualTo(DECLARED_FPS);
        assertThat(item.warnings()).isEmpty();

        assertThat(response.matchedCount()).isEqualTo(1);
        assertThat(response.importableCount()).isEqualTo(1);
        assertThat(response.unmatchedVideoCount()).isZero();
        assertThat(response.truncated()).isFalse();
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    @DisplayName("검사는_아무것도_저장하지_않아_여러_번_보내도_결과가_같다")
    void 검사는_아무것도_저장하지_않아_여러_번_보내도_결과가_같다() throws IOException {
        writePair("a");

        MarkingImportScanResponse first = scan();
        MarkingImportScanResponse second = scan();

        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------------ 짝짓기 실패

    @Test
    @DisplayName("가리키는_이름의_영상이_없으면_적재할_수_없다고_판정하고_사유를_남긴다")
    void 가리키는_이름의_영상이_없으면_적재할_수_없다고_판정하고_사유를_남긴다() throws IOException {
        writeDocument("a.json", "a.mp4");

        MarkingImportScanResponse.Item item = scan().items().get(0);

        assertThat(item.importable()).isFalse();
        assertThat(item.videoFound()).isFalse();
        assertThat(item.warnings()).extracting(MarkingImportScanResponse.Warning::code)
                .contains(MarkingImportWarningCode.VIDEO_NOT_FOUND);
    }

    @Test
    @DisplayName("같은_이름의_영상이_둘_이상이면_짐작하지_않고_건너뛸_대상으로_남긴다")
    void 같은_이름의_영상이_둘_이상이면_짐작하지_않고_건너뛸_대상으로_남긴다() throws IOException {
        writeDocument("a.json", "a.mp4");
        Files.writeString(Files.createDirectories(root.resolve("x")).resolve("a.mp4"), "x");
        Files.writeString(Files.createDirectories(root.resolve("y")).resolve("a.mp4"), "y");

        MarkingImportScanResponse.Item item = scan().items().get(0);

        assertThat(item.importable()).isFalse();
        assertThat(item.warnings()).extracting(MarkingImportScanResponse.Warning::code)
                .contains(MarkingImportWarningCode.AMBIGUOUS_VIDEO_NAME);
    }

    // ------------------------------------------------------------------ 속도 대조

    @Test
    @DisplayName("역산_속도와_실측값이_허용_오차를_넘어_다르면_적재할_수_없다고_판정한다")
    void 역산_속도와_실측값이_허용_오차를_넘어_다르면_적재할_수_없다고_판정한다() throws IOException {
        writePair("a");
        probe.fps = DECLARED_FPS + FPS_TOLERANCE + 0.1;

        MarkingImportScanResponse.Item item = scan().items().get(0);

        // 어긋난 채로 진행하면 이벤트가 없는 엉뚱한 자리의 프레임을 뽑는다(SEQ-030).
        assertThat(item.importable()).isFalse();
        assertThat(item.warnings()).extracting(MarkingImportScanResponse.Warning::code)
                .contains(MarkingImportWarningCode.FPS_MISMATCH);
    }

    @Test
    @DisplayName("허용_오차_안의_차이는_적재를_막지_않는다")
    void 허용_오차_안의_차이는_적재를_막지_않는다() throws IOException {
        // 역산은 나눗셈이라 실측값과 정확히 같을 수 없다. 오차를 인정하지 않으면 정상 묶음이 전건 막힌다.
        writePair("a");
        probe.fps = DECLARED_FPS - FPS_TOLERANCE + 0.1;

        assertThat(scan().items().get(0).importable()).isTrue();
    }

    @Test
    @DisplayName("영상에서_속도를_읽지_못하면_대조를_건너뛰고_적재를_막지_않는다")
    void 영상에서_속도를_읽지_못하면_대조를_건너뛰고_적재를_막지_않는다() throws IOException {
        // 막으면 영상 판독 도구가 없는 환경에서 묶음 전체가 통째로 잠긴다.
        writePair("a");
        probe.fps = null;

        MarkingImportScanResponse.Item item = scan().items().get(0);

        assertThat(item.probedFps()).isNull();
        assertThat(item.videoFrameCount()).isNull();
        assertThat(item.importable()).isTrue();
        assertThat(item.warnings()).extracting(MarkingImportScanResponse.Warning::code)
                .containsExactly(MarkingImportWarningCode.VIDEO_PROBE_FAILED);
    }

    @Test
    @DisplayName("판독기가_예외를_던져도_검사_전체가_오류로_끝나지_않는다")
    void 판독기가_예외를_던져도_검사_전체가_오류로_끝나지_않는다() throws IOException {
        // 영상 하나를 읽지 못했다고 폴더 검사가 오류로 끝나면 사람은 아무것도 보지 못한다.
        writePair("a");
        probe.explode = true;

        MarkingImportScanResponse.Item item = scan().items().get(0);

        assertThat(item.probedFps()).isNull();
        assertThat(item.importable()).isTrue();
    }

    // ------------------------------------------------------------------ 중복

    @Test
    @DisplayName("이미_쓰이고_있는_식별자는_적재할_수_없다고_판정한다")
    void 이미_쓰이고_있는_식별자는_적재할_수_없다고_판정한다() throws IOException {
        writePair("a");
        when(videoRepository.findByVmsClipId(anyString())).thenReturn(Optional.of(mock(LsDataRaw.class)));

        MarkingImportScanResponse.Item item = scan().items().get(0);

        // 조용히 덮어쓰면 검수 중이거나 승인된 내용이 사라진다(AC-1033).
        assertThat(item.importable()).isFalse();
        assertThat(item.warnings()).extracting(MarkingImportScanResponse.Warning::code)
                .contains(MarkingImportWarningCode.DUPLICATE_CLIP_ID);
    }

    // ------------------------------------------------------------------ 묶음 온전성

    @Test
    @DisplayName("어느_문서도_가리키지_않은_영상의_수를_함께_알린다")
    void 어느_문서도_가리키지_않은_영상의_수를_함께_알린다() throws IOException {
        writePair("a");
        Files.writeString(root.resolve("orphan1.mp4"), "x");
        Files.writeString(root.resolve("orphan2.mp4"), "x");

        MarkingImportScanResponse response = scan();

        // 대상은 아니지만 몇 건인지 알아야 사람이 묶음이 온전한지 판단할 수 있다(SEQ-030).
        assertThat(response.unmatchedVideoCount()).isEqualTo(2);
        assertThat(response.unmatchedVideoNames()).containsExactlyInAnyOrder("orphan1.mp4", "orphan2.mp4");
        assertThat(response.warnings()).extracting(MarkingImportScanResponse.Warning::code)
                .contains(MarkingImportWarningCode.UNMATCHED_VIDEO_PRESENT);
    }

    @Test
    @DisplayName("미참조_영상_이름이_상한을_넘어도_전체_수는_줄지_않는다")
    void 미참조_영상_이름이_상한을_넘어도_전체_수는_줄지_않는다() throws IOException {
        // 담긴 수를 세어 판단하면 상한에 걸린 순간부터 사람이 보는 미참조 영상 수가 실제보다 적어진다.
        for (int i = 0; i < 5; i++) {
            Files.writeString(root.resolve("orphan" + i + ".mp4"), "x");
        }

        MarkingImportScanResponse response = scan();

        assertThat(response.unmatchedVideoCount()).isEqualTo(5);
        assertThat(response.unmatchedVideoNames()).hasSize(2);
    }

    @Test
    @DisplayName("바로가기를_건너뛴_사실을_묶음_알림으로_남긴다")
    void 바로가기를_건너뛴_사실을_묶음_알림으로_남긴다() throws IOException {
        writePair("a");
        try {
            Files.createSymbolicLink(root.resolve("link.mp4"), root.resolve("a.mp4"));
        } catch (IOException | UnsupportedOperationException e) {
            return; // 바로가기를 만들 수 없는 파일시스템 — 이 축은 훑기 시험이 따로 덮는다.
        }

        MarkingImportScanResponse response = scan();

        assertThat(response.warnings()).extracting(MarkingImportScanResponse.Warning::code)
                .contains(MarkingImportWarningCode.SYMBOLIC_LINK_SKIPPED);
        // ★그럼에도 정상 항목은 그대로 적재 가능이다 — 묶음 알림이 항목 판정을 오염시키지 않는다.
        assertThat(response.items().get(0).importable()).isTrue();
    }

    // ------------------------------------------------------------------ 경로 판정

    @Test
    @DisplayName("허용된_저장소_범위_밖의_폴더는_거부한다")
    void 허용된_저장소_범위_밖의_폴더는_거부한다() {
        assertThatThrownBy(() -> service.scan(new MarkingImportScanRequest("/etc")))
                .isInstanceOf(CustomException.class);
    }

    // ------------------------------------------------------------------ 보조

    private MarkingImportScanResponse scan() {
        return service.scan(new MarkingImportScanRequest(root.toString()));
    }

    /** 문서와 영상을 한 쌍 만든다 — 프레임 100↔10초, 400↔20초라 역산 속도가 30 이다. */
    private void writePair(String stem) throws IOException {
        writeDocument(stem + ".json", stem + ".mp4");
        Files.writeString(root.resolve(stem + ".mp4"), "video");
    }

    private void writeDocument(String fileName, String videoName) throws IOException {
        Files.writeString(root.resolve(fileName), """
                [{"id":1,"video_name":"%s","video_path":"C:\\\\cctv\\\\%s","notes":"이벤트",
                  "images":[{"filename":"f1.jpg","frame":100,"time":"00:00:10.000"},
                            {"filename":"f2.jpg","frame":400,"time":"00:00:20.000"}]}]
                """.formatted(videoName, videoName));
    }

    /** 갈아 끼운 영상 판독기 — 읽은 값으로 무엇을 판정하는지만 본다. */
    private static final class FakeProbe implements VideoProbe {
        private Double fps;
        private boolean explode;

        private FakeProbe(Double fps) {
            this.fps = fps;
        }

        @Override
        public VideoMeta probe(Path video) {
            if (explode) {
                throw new IllegalStateException("판독기 없음");
            }
            return new VideoMeta(1920, 1080, "h264", fps, null, 60_000L, null);
        }
    }
}

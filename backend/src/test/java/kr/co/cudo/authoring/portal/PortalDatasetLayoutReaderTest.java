package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetFrame;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetLayout;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetVideo;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.LayoutMismatch;
import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationFailureReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.doc;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.frame;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 해제본 파서 — <b>가정한 구성</b>(ADR-068)을 읽는 규칙과, 어긋날 때 추측하지 않고 멈추는 규칙.
 *
 * <p>⚠ 실제 배포 압축본 샘플로 검증한 것이 <b>아니다</b>. 가정 구조를 시험 안에서 만든다.
 *
 * @design ADR-068
 * @design AC-1118
 */
class PortalDatasetLayoutReaderTest {

    @TempDir Path tmp;

    private Path content;
    private PortalDatasetLayoutReader reader;

    @BeforeEach
    void setUp() throws IOException {
        content = Files.createDirectories(tmp.resolve("content"));
        reader = new PortalDatasetLayoutReader(new ObjectMapper());
    }

    private static PortalDatasetRegistrationFailureReason reasonOf(Throwable t) {
        return ((LayoutMismatch) t).reason();
    }

    @Test
    @DisplayName("가정한_구성이면_영상마다_가장_큰_버전의_비식별_프레임과_도형을_읽는다")
    void readsLatestVersionDeidFramesAndShapes() throws IOException {
        Path a1 = version(content, "cam-a", 1);
        frame(a1, 0, doc("old.mp4", 0, "구버전", "3"));
        Path a2 = version(content, "cam-a", 2);
        frame(a2, 0, doc("a.mp4", 100, "첫 장", "3"));
        frame(a2, 5, doc("a.mp4", 150, "둘째 장", "3"));
        Path b1 = version(content, "cam-b", 1);
        frame(b1, 1, doc("b.mp4", 7, "b 장", "3"));

        DatasetLayout layout = reader.read(content);

        assertThat(layout.videos()).extracting(DatasetVideo::videoKey).containsExactly("cam-a", "cam-b");
        DatasetVideo a = layout.videos().get(0);
        assertThat(a.frames()).as("★버전이 둘이면 번호가 큰 것만").extracting(DatasetFrame::frameNo)
                .containsExactly(0L, 5L);
        assertThat(a.frames().get(0).description()).isEqualTo("첫 장");
        assertThat(a.frames().get(0).videoFrameNo()).isEqualTo(100L);
        assertThat(a.frames().get(0).anonymity()).isEqualTo("Y");
        assertThat(a.meta().originalFilename()).isEqualTo("a.mp4");
        assertThat(a.meta().fps()).isEqualTo("30");
        assertThat(a.meta().width()).isEqualTo(1920);

        DatasetFrame f = a.frames().get(0);
        assertThat(f.shapes()).as("★키포인트는 옮기지 않는다").extracting(s -> s.lblTypeCd())
                .containsExactly(LsDataLbl.TYPE_BBOX, LsDataLbl.TYPE_POLYGON);
        assertThat(f.skippedShapes()).isEqualTo(1);
        assertThat(f.shapes().get(0).pointsJson()).as("bbox [x,y,w,h] → 대각 두 점")
                .isEqualTo("[[10.0,20.0],[40.0,60.0]]");
        assertThat(f.shapes().get(0).trackId()).isEqualTo("t-1");
        assertThat(f.shapes().get(0).labelId()).isEqualTo(3L);
        assertThat(f.shapes().get(0).labelName()).isEqualTo("사람");
        assertThat(f.shapes().get(1).pointsJson()).isEqualTo("[[0.0,0.0],[10.0,0.0],[10.0,10.0]]");
        assertThat(f.image().getFileName().toString()).isEqualTo("0000.jpg");
        assertThat(f.image().getParent().getFileName().toString()).as("★비식별 폴더의 이미지다").isEqualTo("deid");
    }

    @Test
    @DisplayName("★원본_폴더만_있는_영상은_등록하지_않고_건너뛴_수로_센다")
    void videoWithoutDeidIsSkipped() throws IOException {
        Path a = version(content, "cam-a", 1);
        frame(a, 0, doc("a.mp4", 0, "d", "3"));
        Path onlyOrgnl = content.resolve("bundle").resolve("cam-z").resolve("v1").resolve("orgnl");
        Files.createDirectories(onlyOrgnl);
        Files.write(onlyOrgnl.resolve("0000.jpg"), PortalDatasetLayoutFixture.ORGNL_JPEG);

        DatasetLayout layout = reader.read(content);

        assertThat(layout.videos()).extracting(DatasetVideo::videoKey).containsExactly("cam-a");
        assertThat(layout.skippedVideos()).isEqualTo(1);
    }

    @Test
    @DisplayName("★이미지와_문서의_짝이_없으면_구성_불일치로_멈춘다")
    void missingPairFails() throws IOException {
        Path a = version(content, "cam-a", 1);
        frame(a, 0, doc("a.mp4", 0, "d", "3"));
        Files.write(a.resolve("0001.jpg"), PortalDatasetLayoutFixture.DEID_JPEG); // 문서 없음

        assertThatThrownBy(() -> reader.read(content))
                .isInstanceOf(LayoutMismatch.class)
                .satisfies(t -> assertThat(reasonOf(t)).isEqualTo(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH));
    }

    @Test
    @DisplayName("같은_번호가_두_이름으로_있으면_고르지_않고_멈춘다")
    void duplicateFrameNumberFails() throws IOException {
        Path a = version(content, "cam-a", 1);
        frame(a, 1, doc("a.mp4", 0, "d", "3"));
        Files.write(a.resolve("1.jpg"), PortalDatasetLayoutFixture.DEID_JPEG);

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t)).isEqualTo(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH));
    }

    @Test
    @DisplayName("★문서를_NIA_어노테이션_문서로_읽을_수_없으면_멈춘다")
    void unreadableDocumentFails() throws IOException {
        Path a = version(content, "cam-a", 1);
        frame(a, 0, "{ not json");

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.DOCUMENT_UNREADABLE));
    }

    @Test
    @DisplayName("★영상_폴더가_하나도_없으면_멈춘다")
    void noVideoFails() throws IOException {
        Files.writeString(content.resolve("readme.txt"), "x");

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t)).isEqualTo(PortalDatasetRegistrationFailureReason.NO_VIDEO));
    }

    @Test
    @DisplayName("해제본_자리가_없으면_멈춘다")
    void missingContentFails() {
        assertThatThrownBy(() -> reader.read(tmp.resolve("nope")))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.CONTENT_MISSING));
    }

    @Test
    @DisplayName("★해제본_안에_링크가_있으면_따라가지_않고_멈춘다")
    void symlinkFails() throws IOException {
        Path a = version(content, "cam-a", 1);
        frame(a, 0, doc("a.mp4", 0, "d", "3"));
        Path outside = Files.write(tmp.resolve("outside.jpg"), PortalDatasetLayoutFixture.ORGNL_JPEG);
        Files.delete(a.resolve("0000.jpg"));
        Files.createSymbolicLink(a.resolve("0000.jpg"), outside);

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.SYMLINK_REJECTED));
    }

    @Test
    @DisplayName("프레임_이미지가_JPEG_가_아니면_멈춘다")
    void nonJpegFails() throws IOException {
        Path a = version(content, "cam-a", 1);
        frame(a, 0, doc("a.mp4", 0, "d", "3"));
        Files.writeString(a.resolve("0000.jpg"), "<html>not an image</html>");

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.IMAGE_NOT_JPEG));
    }

    @Test
    @DisplayName("같은_영상_키가_다른_자리에_또_있으면_고르지_않고_멈춘다")
    void ambiguousVideoKeyFails() throws IOException {
        frame(version(content, "cam-a", 1), 0, doc("a.mp4", 0, "d", "3"));
        Path other = Files.createDirectories(content.resolve("other").resolve("cam-a").resolve("v1").resolve("deid"));
        Files.createDirectories(other.getParent().resolve("orgnl"));
        frame(other, 0, doc("a.mp4", 0, "d", "3"));

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.AMBIGUOUS_VIDEO_KEY));
    }

    @Test
    @DisplayName("좌표_형식이_어긋나면_고쳐_넣지_않고_멈춘다")
    void malformedBboxFails() throws IOException {
        Path a = version(content, "cam-a", 1);
        frame(a, 0, doc("a.mp4", 0, "d", "3").replace("[10, 20, 30, 40]", "[10, 20, 30]"));

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.INVALID_VALUE));
    }

    @Test
    @DisplayName("숫자가_아닌_분류_식별자는_라벨_마스터_후보가_아니다_이름으로_역매핑하지_않는다")
    void nonNumericCategoryIsNotLabelId() throws IOException {
        Path a = version(content, "cam-a", 1);
        frame(a, 0, doc("a.mp4", 0, "d", "person"));

        DatasetLayout layout = reader.read(content);

        assertThat(layout.videos().get(0).frames().get(0).shapes())
                .allSatisfy(s -> assertThat(s.labelId()).isNull());
    }
}

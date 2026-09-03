package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.transfer.parser.MarkingDocument;
import kr.co.cudo.authoring.transfer.parser.MarkingDocumentParser;
import kr.co.cudo.authoring.transfer.parser.MarkingImportWarningCode;
import kr.co.cudo.authoring.transfer.parser.MarkingWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 마킹 문서 읽기를 <b>실물 표본</b>과 합성 표본 양쪽에 고정한다.
 *
 * <h3>왜 실물에 고정하는가</h3>
 * <p>이 형식은 우리가 만든 것이 아니라 받는 것이다. 손으로 지어낸 표본만 쓰면 지어낼 때의 짐작이
 * 그대로 기대값이 되어, 실제 산출물이 다를 때 시험만 통과하고 적재가 깨진다.
 *
 * <h3>왜 합성 표본도 필요한가</h3>
 * <p>실물 표본은 <b>이벤트 구간이 하나뿐</b>이라 여러 구간을 합치는 축이 통째로 덮이지 않는다.
 * 그 축을 실물로만 검증하면 「다구간을 지원한다」는 사양이 <b>검증 대상 0</b> 으로 남는다.
 *
 * @design DOMAIN-017
 * @design ADR-052
 * @design API-216
 * @design AC-1032
 */
class MarkingDocumentParserTest {

    /** 실물 표본이 담은 이벤트 구간 수. */
    private static final int SAMPLE_SEGMENTS = 1;

    /** 실물 표본의 시점 수 — 정렬하고 중복을 없앤 뒤의 값이다. */
    private static final int SAMPLE_MARKS = 21;

    /** 실물 표본에서 역산되는 프레임 재생 속도. */
    private static final double SAMPLE_DECLARED_FPS = 29.994;

    private final MarkingDocumentParser parser = new MarkingDocumentParser(new ObjectMapper());

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ 실물 표본

    @Test
    @DisplayName("실물_표본을_읽으면_구간수_시점수_역산속도가_그대로_나온다")
    void 실물_표본을_읽으면_구간수_시점수_역산속도가_그대로_나온다() {
        MarkingDocument document = parser.parse(MarkingSampleDocument.path());

        assertThat(document.segmentCount()).isEqualTo(SAMPLE_SEGMENTS);
        assertThat(document.markCount()).isEqualTo(SAMPLE_MARKS);
        assertThat(document.declaredFps()).isCloseTo(SAMPLE_DECLARED_FPS, within(0.0005));
        assertThat(document.videoFileName()).isEqualTo(MarkingSampleDocument.VIDEO_FILE_NAME);
        assertThat(document.clipId()).isEqualTo(MarkingSampleDocument.CLIP_ID);
        assertThat(document.hasUsableContent()).isTrue();
    }

    @Test
    @DisplayName("문서가_담은_다른_체계의_절대경로는_위치로_쓰지_않고_원문으로만_보관한다")
    void 문서가_담은_다른_체계의_절대경로는_위치로_쓰지_않고_원문으로만_보관한다() {
        MarkingDocument document = parser.parse(MarkingSampleDocument.path());

        // 원문은 그대로 남는다 — 어디서 왔는지를 되짚을 수 있어야 한다.
        assertThat(document.rawVideoPath()).contains("\\");
        // 짝짓기에 쓰는 값에는 구분자가 남지 않는다(CWE-22).
        assertThat(document.videoFileName()).doesNotContain("\\").doesNotContain("/");
        assertThat(document.clipId()).doesNotContain("\\").doesNotContain("/");
    }

    @Test
    @DisplayName("비고_항목을_이벤트_유형으로_읽지_않는다")
    void 비고_항목을_이벤트_유형으로_읽지_않는다() {
        // 표본의 비고 값은 「이벤트」라는 일반 문장이다. 읽어 낸 결과 어디에도 그 값이 실리지 않는다 —
        // 실리면 그 짐작이 곧 저장된 이벤트 유형이 된다.
        MarkingDocument document = parser.parse(MarkingSampleDocument.path());

        assertThat(document.videoFileName()).doesNotContain("이벤트");
        assertThat(document.clipId()).doesNotContain("이벤트");
        assertThat(document.warnings()).extracting(MarkingWarning::message)
                .noneMatch(m -> m.contains("이벤트 유형"));
    }

    // ------------------------------------------------------------------ 다구간 (합성 표본)

    @Test
    @DisplayName("구간이_여럿이면_시점을_합쳐_프레임_번호순으로_정렬하고_중복을_없앤다")
    void 구간이_여럿이면_시점을_합쳐_프레임_번호순으로_정렬하고_중복을_없앤다() throws IOException {
        Path document = write("multi.json", """
                [
                  {"id":1,"video_name":"a.mp4","notes":"이벤트",
                   "images":[{"filename":"x2.jpg","frame":200,"time":"00:00:20.000"},
                             {"filename":"x1.jpg","frame":100,"time":"00:00:10.000"}]},
                  {"id":2,"video_name":"a.mp4","notes":"이벤트",
                   "images":[{"filename":"x3.jpg","frame":300,"time":"00:00:30.000"},
                             {"filename":"x1b.jpg","frame":100,"time":"00:00:10.000"}]}
                ]
                """);

        MarkingDocument parsed = parser.parse(document);

        assertThat(parsed.segmentCount()).isEqualTo(2);
        // 100 이 두 번 나왔지만 한 번만 담긴다. 순서는 프레임 번호순이다.
        assertThat(parsed.marks()).extracting(MarkItem::frameIndex).containsExactly(100, 200, 300);
        // 역산은 합쳐 정리한 시점의 처음과 끝으로 한다 — 첫 구간만 보면 여기서 10 이 나온다.
        assertThat(parsed.declaredFps()).isCloseTo(10.0, within(0.0005));
    }

    @Test
    @DisplayName("구간이_여럿이어도_영상_이름은_하나이며_첫_구간이_적은_값을_쓴다")
    void 구간이_여럿이어도_영상_이름은_하나이며_첫_구간이_적은_값을_쓴다() throws IOException {
        Path document = write("multi-name.json", """
                [
                  {"id":1,"video_name":"first.mp4","images":[{"frame":10,"time":"00:00:01.000"}]},
                  {"id":2,"video_name":"second.mp4","images":[{"frame":40,"time":"00:00:02.000"}]}
                ]
                """);

        // 영상 하나가 마킹 문서 하나다 — 구간마다 다른 영상을 가리키는 문서는 형식 밖이며,
        // 그 경우 첫 구간을 따르고 짝짓기는 그 이름으로만 한다(짐작으로 나누지 않는다).
        assertThat(parser.parse(document).videoFileName()).isEqualTo("first.mp4");
    }

    // ------------------------------------------------------------------ 읽을 수 없는 문서

    @Test
    @DisplayName("최상위가_구간_목록이_아니면_그_문서만_읽지_못한_것으로_다룬다")
    void 최상위가_구간_목록이_아니면_그_문서만_읽지_못한_것으로_다룬다() throws IOException {
        Path document = write("object.json", "{\"segments\":[]}");

        MarkingDocument parsed = parser.parse(document);

        assertThat(parsed.hasUsableContent()).isFalse();
        assertThat(parsed.warnings()).extracting(MarkingWarning::code)
                .containsExactly(MarkingImportWarningCode.UNREADABLE_DOCUMENT);
    }

    @Test
    @DisplayName("깨진_문서는_예외가_아니라_읽지_못했다는_알림으로_끝난다")
    void 깨진_문서는_예외가_아니라_읽지_못했다는_알림으로_끝난다() throws IOException {
        // 예외로 올리면 문서 하나 때문에 묶음 전체 검사가 멈춘다.
        Path document = write("broken.json", "[{\"id\":1,");

        assertThat(parser.parse(document).warnings()).extracting(MarkingWarning::code)
                .containsExactly(MarkingImportWarningCode.UNREADABLE_DOCUMENT);
    }

    @Test
    @DisplayName("시점이_하나도_없으면_예약할_내용이_없다고_알린다")
    void 시점이_하나도_없으면_예약할_내용이_없다고_알린다() throws IOException {
        Path document = write("empty.json", "[{\"id\":1,\"video_name\":\"a.mp4\",\"images\":[]}]");

        MarkingDocument parsed = parser.parse(document);

        assertThat(parsed.markCount()).isZero();
        assertThat(parsed.hasUsableContent()).isFalse();
        assertThat(parsed.warnings()).extracting(MarkingWarning::code)
                .contains(MarkingImportWarningCode.NO_MARK_FOUND);
    }

    @Test
    @DisplayName("시점이_하나뿐이면_속도를_역산하지_않고_비운다")
    void 시점이_하나뿐이면_속도를_역산하지_않고_비운다() throws IOException {
        // 점 하나로는 기울기를 구할 수 없다. 지어내면 그 값으로 대조가 이뤄져 정상 항목이 막힌다.
        Path document = write("single.json",
                "[{\"id\":1,\"video_name\":\"a.mp4\",\"images\":[{\"frame\":10,\"time\":\"00:00:01.000\"}]}]");

        MarkingDocument parsed = parser.parse(document);

        assertThat(parsed.declaredFps()).isNull();
        assertThat(parsed.warnings()).extracting(MarkingWarning::code)
                .contains(MarkingImportWarningCode.DECLARED_FPS_UNAVAILABLE);
        // 그래도 시점은 있으므로 적재할 내용은 있다 — 역산 불가가 적재를 막지 않는다.
        assertThat(parsed.hasUsableContent()).isTrue();
    }

    @Test
    @DisplayName("시각이_없는_시점도_프레임_번호로_자리를_잡는다")
    void 시각이_없는_시점도_프레임_번호로_자리를_잡는다() throws IOException {
        Path document = write("no-time.json",
                "[{\"id\":1,\"video_name\":\"a.mp4\",\"images\":[{\"frame\":10},{\"frame\":20}]}]");

        MarkingDocument parsed = parser.parse(document);

        // 자리를 정하는 것은 프레임 번호다 — 시각이 없다고 그 시점을 버리지 않는다.
        assertThat(parsed.marks()).extracting(MarkItem::frameIndex).containsExactly(10, 20);
        assertThat(parsed.marks()).extracting(MarkItem::timestamp).containsOnlyNulls();
        assertThat(parsed.declaredFps()).isNull();
    }

    // ------------------------------------------------------------------ 영상 이름·식별자

    @Test
    @DisplayName("이름_항목이_비면_경로_원문에서_마지막_이름_조각만_꺼내_쓴다")
    void 이름_항목이_비면_경로_원문에서_마지막_이름_조각만_꺼내_쓴다() throws IOException {
        Path document = write("path-only.json", """
                [{"id":1,"video_path":"C:\\\\cctv\\\\20260706\\\\clip.mp4",
                  "images":[{"frame":10,"time":"00:00:01.000"},{"frame":40,"time":"00:00:02.000"}]}]
                """);

        MarkingDocument parsed = parser.parse(document);

        assertThat(parsed.videoFileName()).isEqualTo("clip.mp4");
        assertThat(parsed.clipId()).isEqualTo("clip");
    }

    @Test
    @DisplayName("영상_이름이_아예_없으면_짝을_찾을_축이_없다고_알린다")
    void 영상_이름이_아예_없으면_짝을_찾을_축이_없다고_알린다() throws IOException {
        Path document = write("no-video.json",
                "[{\"id\":1,\"images\":[{\"frame\":10,\"time\":\"00:00:01.000\"}]}]");

        MarkingDocument parsed = parser.parse(document);

        assertThat(parsed.videoFileName()).isNull();
        assertThat(parsed.hasUsableContent()).isFalse();
        assertThat(parsed.warnings()).extracting(MarkingWarning::code)
                .contains(MarkingImportWarningCode.UNUSABLE_VIDEO_FILE_NAME);
    }

    @Test
    @DisplayName("확장자를_떼면_비는_이름은_식별자를_만들지_않는다")
    void 확장자를_떼면_비는_이름은_식별자를_만들지_않는다() {
        // 「..mp4」의 확장자를 떼면 「.」 이 남는다. 그 값이 디렉터리 이름이 되면 자기 자신을 가리킨다.
        assertThat(MarkingDocumentParser.clipIdOf("..mp4")).isNull();
        assertThat(MarkingDocumentParser.clipIdOf(".mp4")).isEqualTo(".mp4");
        assertThat(MarkingDocumentParser.clipIdOf("clip.mp4")).isEqualTo("clip");
        // 확장자가 없으면 이름 그대로다.
        assertThat(MarkingDocumentParser.clipIdOf("clip")).isEqualTo("clip");
    }

    @Test
    @DisplayName("식별자가_컬럼_폭을_넘으면_잘라_담지_않고_쓸_수_없다고_답한다")
    void 식별자가_컬럼_폭을_넘으면_잘라_담지_않고_쓸_수_없다고_답한다() {
        // 자르면 서로 다른 영상이 같은 식별자가 되어, 뒤에 온 영상이 「이미 있음」으로 조용히 건너뛰어진다.
        String tooLong = "a".repeat(200) + ".mp4";

        assertThat(MarkingDocumentParser.clipIdOf(tooLong)).isNull();
    }

    @Test
    @DisplayName("경로_구분자가_섞인_이름은_식별자로_쓰지_않는다")
    void 경로_구분자가_섞인_이름은_식별자로_쓰지_않는다() {
        // 이 값이 디렉터리 이름이 되므로 구분자가 남으면 저장소 밖을 가리킬 수 있다(CWE-22).
        assertThat(MarkingDocumentParser.clipIdOf("../etc/passwd.mp4")).isNull();
        assertThat(MarkingDocumentParser.clipIdOf("a\\b.mp4")).isNull();
    }

    // ------------------------------------------------------------------ 시각 변환

    @Test
    @DisplayName("시각_표기는_시분초와_분초와_초를_모두_읽는다")
    void 시각_표기는_시분초와_분초와_초를_모두_읽는다() {
        assertThat(MarkingDocumentParser.parseSeconds("00:01:00.305")).isCloseTo(60.305, within(1e-6));
        assertThat(MarkingDocumentParser.parseSeconds("01:00.5")).isCloseTo(60.5, within(1e-6));
        assertThat(MarkingDocumentParser.parseSeconds("12.25")).isCloseTo(12.25, within(1e-6));
        assertThat(MarkingDocumentParser.parseSeconds("알수없음")).isNull();
        assertThat(MarkingDocumentParser.parseSeconds(null)).isNull();
    }

    @Test
    @DisplayName("담을_수_없는_긴_시각은_형식을_어기지_않고_비운다")
    void 담을_수_없는_긴_시각은_형식을_어기지_않고_비운다() {
        // 저장 형식의 분 자리는 네 자리까지다. 어기고 채우면 그 마킹을 읽는 쪽이 통째로 거부한다.
        assertThat(MarkingDocumentParser.timestampOf(60.0)).isEqualTo("01:00");
        assertThat(MarkingDocumentParser.timestampOf(59.9)).isEqualTo("00:59");
        assertThat(MarkingDocumentParser.timestampOf(10_000 * 60.0)).isNull();
        assertThat(MarkingDocumentParser.timestampOf(null)).isNull();
    }

    private Path write(String name, String content) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}

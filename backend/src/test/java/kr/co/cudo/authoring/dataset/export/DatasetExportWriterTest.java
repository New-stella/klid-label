package kr.co.cudo.authoring.dataset.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dataset.export.json.NiaAnnotationDoc;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.FrameContext;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DatasetExportWriter} 단위 테스트 — 실제 파일 쓰기(이미지 복사 + JSON) / 부분성공 집계 /
 * 종류별 디렉토리 분리 / 덮어쓰기 / CWE-22 전파 / 원자적 JSON 쓰기를 @TempDir 로 검증한다.
 *
 * <p>FrameSource·NiaJsonBuilder 는 mock — Writer 의 파일 IO 책임만 격리 검증한다.
 */
class DatasetExportWriterTest {

    @TempDir
    Path labelingRoot;
    @TempDir
    Path srcDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FrameSource frameSource;
    private NiaJsonBuilder niaJsonBuilder;
    private DatasetExportPathResolver resolver;
    private DatasetExportWriter writer;
    private VideoExportContext ctx;

    @BeforeEach
    void setUp() {
        resolver = new DatasetExportPathResolver(labelingRoot.toString());
        frameSource = mock(FrameSource.class);
        niaJsonBuilder = mock(NiaJsonBuilder.class);
        writer = new DatasetExportWriter(resolver, frameSource, niaJsonBuilder, objectMapper);
        ctx = new VideoExportContext(null, null, "7", null, null, null, null, null);
    }

    private LsDataSrc frame(long frameNo) {
        return LsDataSrc.create(7L, frameNo, "/nas/frames/raw/7/frame-" + frameNo + ".jpg", null);
    }

    private FrameContext frameCtx(long frameNo) {
        return new FrameContext(frame(frameNo), List.of());
    }

    private Path srcImage(String name, String content) throws IOException {
        Path img = srcDir.resolve(name);
        Files.writeString(img, content);
        return img;
    }

    private NiaAnnotationDoc stubDoc() {
        return new NiaAnnotationDoc(null, null, null, null, null, null, null, null, "instances");
    }

    @Test
    @DisplayName("프레임당_이미지와_동일이름_json이_생성된다")
    void writesImageAndJsonPairPerFrame() throws IOException {
        // given — 2프레임 모두 원천 이미지 존재
        Path img0 = srcImage("s0.jpg", "img0");
        Path img1 = srcImage("s1.jpg", "img1");
        when(frameSource.resolveImage(anyLong(), eq(ExportKind.ORIGINAL), any()))
                .thenReturn(Optional.of(img0), Optional.of(img1));
        when(niaJsonBuilder.build(any(), any(), any())).thenReturn(stubDoc());

        // when
        ExportResult result = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of(frameCtx(0), frameCtx(1)));

        // then — frame-{i}.jpg + frame-{i}.json 페어 2쌍, 집계 2/0
        Path dir = result.dir();
        assertThat(dir.resolve("frame-0.jpg")).exists();
        assertThat(dir.resolve("frame-0.json")).exists();
        assertThat(dir.resolve("frame-1.jpg")).exists();
        assertThat(dir.resolve("frame-1.json")).exists();
        assertThat(result.writtenCnt()).isEqualTo(2);
        assertThat(result.skippedCnt()).isZero();
    }

    @Test
    @DisplayName("이미지_부재_프레임은_skip되고_집계된다")
    void skipsFramesWithMissingImage() throws IOException {
        // given — frame0 이미지 존재, frame1 부재(empty)
        Path img0 = srcImage("s0.jpg", "img0");
        when(frameSource.resolveImage(anyLong(), any(), any()))
                .thenReturn(Optional.of(img0), Optional.empty());
        when(niaJsonBuilder.build(any(), any(), any())).thenReturn(stubDoc());

        // when
        ExportResult result = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of(frameCtx(0), frameCtx(1)));

        // then — 1건 기록, 1건 skip(부분성공). frame-1 산출물 없음
        assertThat(result.writtenCnt()).isEqualTo(1);
        assertThat(result.skippedCnt()).isEqualTo(1);
        assertThat(result.dir().resolve("frame-1.jpg")).doesNotExist();
        assertThat(result.dir().resolve("frame-1.json")).doesNotExist();
    }

    @Test
    @DisplayName("orgnl과_deid는_각각_다른_디렉토리에_쓴다")
    void writesToSeparateDirsPerKind() throws IOException {
        // given
        Path img = srcImage("s.jpg", "img");
        when(frameSource.resolveImage(anyLong(), any(), any())).thenReturn(Optional.of(img));
        when(niaJsonBuilder.build(any(), any(), any())).thenReturn(stubDoc());

        // when
        ExportResult orgnl = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of(frameCtx(0)));
        ExportResult deid = writer.write(7L, ExportKind.DEIDENTIFIED, 1, ctx, List.of(frameCtx(0)));

        // then — 마지막 세그먼트가 orgnl / deid 로 상이하고 각각 산출물 존재
        assertThat(orgnl.dir().getFileName().toString()).isEqualTo("orgnl");
        assertThat(deid.dir().getFileName().toString()).isEqualTo("deid");
        assertThat(orgnl.dir()).isNotEqualTo(deid.dir());
        assertThat(orgnl.dir().resolve("frame-0.jpg")).exists();
        assertThat(deid.dir().resolve("frame-0.jpg")).exists();
    }

    @Test
    @DisplayName("json_내용이_NiaJsonBuilder_결과와_일치한다")
    void jsonContentMatchesBuilderOutput() throws IOException {
        // given
        Path img = srcImage("s.jpg", "img");
        NiaAnnotationDoc doc = stubDoc();
        when(frameSource.resolveImage(anyLong(), any(), any())).thenReturn(Optional.of(img));
        when(niaJsonBuilder.build(any(), any(), any())).thenReturn(doc);

        // when
        ExportResult result = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of(frameCtx(0)));

        // then — 파일을 다시 파싱해 9키 유효(event_annotation 포함) + 직렬화 바이트가 빌더 결과와 동일
        byte[] onDisk = Files.readAllBytes(result.dir().resolve("frame-0.json"));
        assertThat(onDisk).isEqualTo(objectMapper.writeValueAsBytes(doc));
        JsonNode tree = objectMapper.readTree(onDisk);
        assertThat(tree.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "info", "dataset", "licences", "video", "event_annotation",
                "image", "annotations", "categories", "type");
        assertThat(tree.get("type").asText()).isEqualTo("instances");
    }

    @Test
    @DisplayName("동일_frameNo_재쓰기시_덮어쓴다")
    void overwritesOnSameFrameNo() throws IOException {
        // given — 같은 frameNo 를 서로 다른 원천 이미지로 두 번 쓴다
        Path img1 = srcImage("first.jpg", "first-content");
        Path img2 = srcImage("second.jpg", "second-content");
        when(frameSource.resolveImage(anyLong(), any(), any()))
                .thenReturn(Optional.of(img1), Optional.of(img2));
        when(niaJsonBuilder.build(any(), any(), any())).thenReturn(stubDoc());

        // when
        writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of(frameCtx(0)));
        ExportResult result = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of(frameCtx(0)));

        // then — REPLACE_EXISTING 으로 두 번째 내용이 남는다
        assertThat(Files.readString(result.dir().resolve("frame-0.jpg"))).isEqualTo("second-content");
        assertThat(result.writtenCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("경로순회_videoId_주입은_거부된다")
    void rejectsNonPositiveRawSn() {
        // when / then — 리졸버 CWE-20/22 가드 전파(음수/0 videoId)
        assertThatThrownBy(() -> writer.write(0L, ExportKind.ORIGINAL, 1, ctx, List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> writer.write(-9L, ExportKind.ORIGINAL, 1, ctx, List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("json은_임시파일_거쳐_원자적으로_쓰인다")
    void jsonWrittenAtomicallyWithoutTmpResidue() throws IOException {
        // given
        Path img = srcImage("s.jpg", "img");
        when(frameSource.resolveImage(anyLong(), any(), any())).thenReturn(Optional.of(img));
        when(niaJsonBuilder.build(any(), any(), any())).thenReturn(stubDoc());

        // when
        ExportResult result = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of(frameCtx(0)));

        // then — 최종 json 은 있고 .tmp 잔여물은 남지 않는다(부분쓰기 방지)
        assertThat(result.dir().resolve("frame-0.json")).exists();
        try (Stream<Path> entries = Files.list(result.dir())) {
            assertThat(entries).noneMatch(p -> p.getFileName().toString().endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("ctx가_null이면_INVALID_INPUT")
    void rejectsNullContext() {
        // when / then — 영상 컨텍스트 null 은 입력 검증 실패
        assertThatThrownBy(() -> writer.write(7L, ExportKind.ORIGINAL, 1, null, List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("디렉토리생성실패시_INTERNAL_ERROR_예외래핑")
    void wrapsDirectoryCreationFailure() throws IOException {
        // given — 산출 디렉토리의 상위 세그먼트(rawSn=7)를 파일로 선점 → createDirectories IOException 유발
        Files.writeString(labelingRoot.resolve("7"), "occupied-as-file");

        // when / then — IOException 이 INTERNAL_ERROR 로 래핑되어 전파(원문 미노출)
        assertThatThrownBy(() -> writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("프레임쓰기중_IO오류시_예외래핑되고_tmp잔여없음")
    void wrapsFrameWriteFailureWithoutTmpResidue() throws IOException {
        // given — 산출 디렉토리를 미리 만들고 imageTarget(frame-0.jpg)을 비어있지 않은 디렉토리로 선점
        //         → Files.copy 가 DirectoryNotEmptyException(IOException) 을 던지도록 유도
        Path dir = resolver.resolve(7L, ExportKind.ORIGINAL, 1);
        Files.createDirectories(dir.resolve("frame-0.jpg").resolve("child"));
        Path img = srcImage("s.jpg", "img");
        when(frameSource.resolveImage(anyLong(), any(), any())).thenReturn(Optional.of(img));
        when(niaJsonBuilder.build(any(), any(), any())).thenReturn(stubDoc());

        // when / then — IO 오류가 INTERNAL_ERROR 로 래핑
        assertThatThrownBy(() -> writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of(frameCtx(0))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        // then — .tmp 잔여물이 남지 않는다(cleanupQuietly)
        try (Stream<Path> entries = Files.list(dir)) {
            assertThat(entries).noneMatch(p -> p.getFileName().toString().endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("frameCtx가_null이면_skip")
    void skipsNullFrameContext() {
        // when — 프레임 컨텍스트 null 요소
        ExportResult result = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, singletonListOfNull());

        // then — resolveImage 미호출, skip 집계
        assertThat(result.writtenCnt()).isZero();
        assertThat(result.skippedCnt()).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(frameSource);
    }

    @Test
    @DisplayName("frame엔티티가_null이면_skip")
    void skipsNullFrameEntity() {
        // given — FrameContext 는 있으나 내부 frame 이 null
        FrameContext nullFrame = new FrameContext(null, List.of());

        // when
        ExportResult result = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of(nullFrame));

        // then
        assertThat(result.writtenCnt()).isZero();
        assertThat(result.skippedCnt()).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(frameSource);
    }

    @Test
    @DisplayName("frameNo가_null이면_skip")
    void skipsNullFrameNo() {
        // given — frameNo 가 null 인 프레임(builder 로 생성)
        LsDataSrc noFrameNo = LsDataSrc.builder().rawSn(7L).frameNo(null)
                .srcFilePathNm("/nas/frames/raw/7/frame.jpg").build();
        FrameContext frameCtx = new FrameContext(noFrameNo, List.of());

        // when
        ExportResult result = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of(frameCtx));

        // then — frameNo null 가드로 skip, resolveImage 미호출
        assertThat(result.writtenCnt()).isZero();
        assertThat(result.skippedCnt()).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(frameSource);
    }

    @Test
    @DisplayName("frames가_null이면_writtenCnt0")
    void handlesNullFrames() {
        // when — frames 리스트 자체가 null
        ExportResult result = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, null);

        // then — 아무것도 쓰지 않고 정상 종료
        assertThat(result.writtenCnt()).isZero();
        assertThat(result.skippedCnt()).isZero();
    }

    @Test
    @DisplayName("frames가_빈리스트면_writtenCnt0")
    void handlesEmptyFrames() {
        // when
        ExportResult result = writer.write(7L, ExportKind.ORIGINAL, 1, ctx, List.of());

        // then
        assertThat(result.writtenCnt()).isZero();
        assertThat(result.skippedCnt()).isZero();
    }

    /** null 원소 하나를 담은 리스트(List.of 는 null 불가). */
    private static List<FrameContext> singletonListOfNull() {
        List<FrameContext> list = new java.util.ArrayList<>();
        list.add(null);
        return list;
    }
}

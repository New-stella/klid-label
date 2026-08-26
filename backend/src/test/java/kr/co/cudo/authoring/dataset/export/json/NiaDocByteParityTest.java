package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.ExportKind;
import kr.co.cudo.authoring.dataset.export.SourcePrivacyMeta;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.FrameContext;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>산출 바이트 고정(golden) 가드</b> — 한 프레임의 NIA COCO 확장 어노테이션 문서를 실제 산출 경로와
 * 같은 직렬화기({@code writerWithDefaultPrettyPrinter})로 찍어 골든 리소스와 <b>문자 단위 완전일치</b>를
 * 단언한다.
 *
 * <p>이 테스트의 존재 이유는 리팩토링이다 — NIA 컨텍스트 조달 블록을 공유 조립기로 빼고 라벨 매퍼에
 * 좁은 입력 형태를 추가하는 변경은 <b>동작 변경이 아니어야</b> 하며, "기존 export 산출물이 한 바이트도
 * 달라지지 않는다"는 그 리팩토링의 수용 조건이다. 골든은 리팩토링 <b>이전</b> 코드에서 캡처했다.
 *
 * <p>{@code info.date_created} 만 {@code LocalDate.now()} 라 날짜마다 값이 바뀐다 — 그 한 필드는 고정
 * 토큰으로 치환한 뒤 비교한다(치환 대상이 실제로 오늘 날짜였는지도 함께 단언해 치환이 다른 값을
 * 덮지 않도록 한다). 그 밖의 모든 필드·키 순서·들여쓰기는 있는 그대로 비교한다.
 */
class NiaDocByteParityTest {

    /**
     * 골든 재생성 스위치 — <b>평소에는 반드시 {@code false}</b>. {@code true} 로 두면 이 테스트가 골든을
     * 덮어써 <b>무엇을 바꾸든 항상 통과</b>하게 되어 가드가 무력화된다.
     *
     * <p>사양이 실제로 바뀌어 골든을 다시 떠야 할 때만 일시적으로 {@code true} 로 바꿔 1회 실행하고
     * (테스트 워커 작업 디렉터리가 {@code backend/} 라 소스 리소스가 직접 갱신된다) 곧바로 되돌린 뒤,
     * 골든 diff 를 사람이 검토한다. {@code -D} 시스템 프로퍼티로는 이 스위치를 켤 수 없다 — 이
     * 저장소의 테스트 워커에는 {@code -D} 가 도달하지 않는다(build.gradle 주석 참조).
     */
    private static final boolean REGENERATE_GOLDEN = false;
    private static final String GOLDEN_RESOURCE = "/dataset-export/nia-doc-golden.json";
    private static final Path GOLDEN_SOURCE_PATH =
            Path.of("src/test/resources/dataset-export/nia-doc-golden.json");
    private static final String FIXED_DATE_TOKEN = "<TODAY>";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NiaJsonBuilder builder = new NiaJsonBuilder(
            new LabelToAnnotationMapper(objectMapper), new VideoMetaMapper(), new CategoryMapper());

    @Test
    @DisplayName("한_프레임_산출_문서의_바이트가_골든과_문자단위로_완전히_일치한다")
    void frameDocBytesMatchGolden() throws IOException {
        String actual = renderPretty(ExportKind.DEIDENTIFIED);

        if (REGENERATE_GOLDEN) {
            Files.createDirectories(GOLDEN_SOURCE_PATH.getParent());
            Files.writeString(GOLDEN_SOURCE_PATH, actual, StandardCharsets.UTF_8);
        }

        assertThat(actual)
                .as("산출 문서 바이트가 골든과 달라졌다 — 리팩토링이 동작을 바꿨는지 확인할 것")
                .isEqualTo(readGolden());
    }

    @Test
    @DisplayName("원본_산출_문서의_바이트도_골든과_문자단위로_완전히_일치한다")
    void originalKindFrameDocBytesMatchGolden() throws IOException {
        String actual = renderPretty(ExportKind.ORIGINAL);

        if (REGENERATE_GOLDEN) {
            Files.writeString(Path.of("src/test/resources/dataset-export/nia-doc-golden-original.json"),
                    actual, StandardCharsets.UTF_8);
        }

        assertThat(actual).isEqualTo(readGolden("/dataset-export/nia-doc-golden-original.json"));
    }

    @Test
    @DisplayName("저장소_중립_경로로_만든_문서도_엔티티_경로와_바이트가_동일하다")
    void storageNeutralPathProducesIdenticalBytes() throws IOException {
        // given — 같은 라벨을 ①엔티티(FrameContext) ②좁은 입력(AnnotationSource) 두 경로로 넣는다.
        //   포털은 ②만 쓸 수 있으므로, 두 경로가 같은 바이트를 내야 "구조를 통일했다"가 성립한다.
        VideoExportContext ctx = context();
        FrameContext entityPath = frameContext();
        List<AnnotationSource> sources = entityPath.labels().stream()
                .map(AnnotationSource::of)
                .toList();

        // when
        String viaEntity = pretty(builder.build(ctx, entityPath, ExportKind.DEIDENTIFIED));
        String viaSource = pretty(builder.build(
                ctx, entityPath.frame(), sources, ExportKind.DEIDENTIFIED));

        // then — 골든과도 같아야 한다(엔티티 경로가 리팩토링 전과 같음은 위 두 시험이 고정한다).
        assertThat(viaSource)
                .as("좁은 입력 경로가 엔티티 경로와 다른 문서를 낸다 — 판정이 갈렸다는 뜻")
                .isEqualTo(viaEntity)
                .isEqualTo(readGolden());
    }

    // ── 산출 ───────────────────────────────────────────────────────────────────

    private String renderPretty(ExportKind kind) throws IOException {
        return pretty(builder.build(context(), frameContext(), kind));
    }

    /** 실제 산출 경로와 <b>같은 직렬화기</b>(pretty writer)로 찍는다 — 들여쓰기까지 비교 대상이다. */
    private String pretty(NiaAnnotationDoc doc) throws IOException {
        return maskToday(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
    }

    /** {@code info.date_created} 만 고정 토큰으로 치환한다(그 값이 실제로 오늘인지 함께 확인). */
    private String maskToday(String json) {
        String today = java.time.LocalDate.now().toString();
        assertThat(json)
                .as("date_created 가 오늘 날짜여야 치환이 안전하다")
                .contains("\"date_created\" : \"" + today + "\"");
        return json.replace("\"date_created\" : \"" + today + "\"",
                "\"date_created\" : \"" + FIXED_DATE_TOKEN + "\"");
    }

    // ── 고정 입력 ──────────────────────────────────────────────────────────────

    private VideoExportContext context() throws IOException {
        JsonNode eventAnnotation = objectMapper.readTree(
                "{\"c1\":{\"caption_text\":\"보행자가 넘어진다\",\"obj_id\":[\"t-1\"]}}");
        return builder.prepareContext(
                meta(), raw(), List.of(labelMaster(11L, "사람", "BBOX"),
                        labelMaster(12L, "차량", "POLYGON"),
                        labelMaster(13L, "포즈", "SKELETON")),
                eventAnnotation,
                "/nas/deid/videos/77/077-mask.mp4",
                SourcePrivacyMeta.ofIngest("N", "N", "Y"),
                "ABA_0001",
                "야간 도로에서 보행자가 넘어지는 상황");
    }

    private FrameContext frameContext() {
        return new FrameContext(frame(), List.of(
                label(501L, LsDataLbl.TYPE_BBOX, "[[10.5,20.5],[110.5,220.5]]", 11L, "trk-1"),
                label(502L, LsDataLbl.TYPE_POLYGON, "[[1,2],[3,4],[5,6]]", 12L, null),
                label(503L, LsDataLbl.TYPE_SKELETON, keypointsJson(), 13L, "trk-2")));
    }

    private static String keypointsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("[").append(i).append(",").append(i * 2).append(",").append(i % 3).append("]");
        }
        return sb.append("]").toString();
    }

    private static LsDatasetVideoMeta meta() {
        return LsDatasetVideoMeta.builder()
                .rawSn(77L)
                .rawFilePathNm("/nas/raw/77/original.mp4")
                .shtDt(LocalDateTime.of(2026, 3, 3, 10, 0))
                .rvwCmplDt(LocalDateTime.of(2026, 4, 1, 9, 30))
                .vdoWdth(1920)
                .vdoHgt(1080)
                .vdoLenSec(30)
                .resl("1920x1080")
                .fps(new BigDecimal("30.00"))
                .fileFmt("mp4")
                .fileSz(123456L)
                .sidoNm("서울특별시")
                .sggNm("강남구")
                .evntNm("화재")
                .prvcYn("N")
                .build();
    }

    private static LsDataRaw raw() {
        return LsDataRaw.builder()
                .vmsClipId("clip-77")
                .rawFilePathNm("/nas/raw/77/original.mp4")
                .shtDt(LocalDateTime.of(2026, 3, 3, 10, 0))
                .durationSec(30)
                .build();
    }

    private static LsDataSrc frame() {
        LsDataSrc src = mock(LsDataSrc.class);
        when(src.getSrcSn()).thenReturn(9001L);
        when(src.getFrameNo()).thenReturn(7L);
        when(src.getVideoFrameNo()).thenReturn(210L);
        when(src.getShtDt()).thenReturn(LocalDateTime.of(2026, 3, 3, 10, 0, 7));
        when(src.getFrmExpln()).thenReturn("보행자 2인");
        when(src.getAnonyInclYn()).thenReturn("Y");
        when(src.getPsdoInclYn()).thenReturn("N");
        when(src.getPrvcInclYn()).thenReturn("N");
        return src;
    }

    private static LsDataLbl label(Long lblSn, String type, String pointCn, Long labelId, String trackId) {
        LsDataLbl lbl = mock(LsDataLbl.class);
        when(lbl.getLblSn()).thenReturn(lblSn);
        when(lbl.getLblTypeCd()).thenReturn(type);
        when(lbl.getPointCn()).thenReturn(pointCn);
        when(lbl.getLabelId()).thenReturn(labelId);
        when(lbl.getTrackId()).thenReturn(trackId);
        return lbl;
    }

    private static LsLabel labelMaster(Long labelId, String labelNm, String typeCd) {
        LsLabel master = mock(LsLabel.class);
        when(master.getLabelId()).thenReturn(labelId);
        when(master.getLabelNm()).thenReturn(labelNm);
        when(master.getLabelTypeCd()).thenReturn(typeCd);
        return master;
    }

    // ── 골든 로딩 ──────────────────────────────────────────────────────────────

    private String readGolden() throws IOException {
        return readGolden(GOLDEN_RESOURCE);
    }

    private String readGolden(String resource) throws IOException {
        try (InputStream in = NiaDocByteParityTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("골든 리소스가 없다: %s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

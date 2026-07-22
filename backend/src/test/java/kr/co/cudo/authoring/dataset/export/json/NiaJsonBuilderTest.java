package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.KeypointSkeleton;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.ExportKind;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 — COCO-NIA JSON 직렬화기(파일 IO 없는 순수 매핑 빌더) 단위 테스트.
 *
 * <p>Spring 컨텍스트 없이 매퍼/빌더를 직접 조립하여 xlsx v1.3 스키마 정합을 검증한다.
 */
class NiaJsonBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LabelToAnnotationMapper labelMapper;
    private VideoMetaMapper videoMapper;
    private CategoryMapper categoryMapper;
    private NiaJsonBuilder builder;

    @BeforeEach
    void setUp() {
        labelMapper = new LabelToAnnotationMapper(objectMapper);
        videoMapper = new VideoMetaMapper();
        categoryMapper = new CategoryMapper();
        builder = new NiaJsonBuilder(labelMapper, videoMapper, categoryMapper);
    }

    // ---------- LabelToAnnotationMapper ----------

    @Test
    @DisplayName("bbox라벨이_xywh형식_annotation으로_변환된다")
    void bboxToXywh() {
        // given — 2점 대각 [[10,20],[40,60]]
        LsDataLbl lbl = bbox("[[10,20],[40,60]]", 7L, null);

        // when
        NiaAnnotation ann = labelMapper.toAnnotation(lbl, 1);

        // then — [x=min, y=min, w=|x2-x1|, h=|y2-y1|]
        assertThat(ann.bbox()).containsExactly(10.0, 20.0, 30.0, 40.0);
        assertThat(ann.polygon()).isNull();
        assertThat(ann.keypoints()).isNull();
        assertThat(ann.categoryId()).isEqualTo("7");
    }

    @Test
    @DisplayName("키포인트_17점_삼중값이_keypoints_배열로_직렬화된다")
    void keypoints17Triplets() {
        // given
        LsDataLbl lbl = skeleton(keypointJson(), 9L);

        // when
        NiaAnnotation ann = labelMapper.toAnnotation(lbl, 3);

        // then — 17개의 [x,y,v]
        assertThat(ann.keypoints()).hasSize(17);
        assertThat(ann.keypoints().get(0)).containsExactly(1.0, 2.0, 2);
        assertThat(ann.bbox()).isNull();
        assertThat(ann.polygon()).isNull();
    }

    @Test
    @DisplayName("폴리곤_좌표가_xlsx예시형식으로_직렬화된다")
    void polygonFlatRing() {
        // given
        LsDataLbl lbl = polygon("[[100,150],[300,350],[120,400]]", 5L);

        // when
        NiaAnnotation ann = labelMapper.toAnnotation(lbl, 2);

        // then — 단일 ring, flat [x,y,x,y,...]
        assertThat(ann.polygon()).hasSize(1);
        assertThat(ann.polygon().get(0)).containsExactly(100.0, 150.0, 300.0, 350.0, 120.0, 400.0);
        assertThat(ann.bbox()).isNull();
        assertThat(ann.keypoints()).isNull();
    }

    @Test
    @DisplayName("TRACK라벨은_bbox와_track_id를_가진다")
    void trackHasBboxAndTrackId() {
        // given
        LsDataLbl lbl = LsDataLbl.builder()
                .srcSn(1L).lblTypeCd(LsDataLbl.TYPE_TRACK).labelId(4L)
                .label("car").pointsJson("[[0,0],[20,10]]").trackId("trk-1").build();

        // when
        NiaAnnotation ann = labelMapper.toAnnotation(lbl, 1);

        // then
        assertThat(ann.bbox()).containsExactly(0.0, 0.0, 20.0, 10.0);
        assertThat(ann.trackId()).isEqualTo("trk-1");
    }

    @Test
    @DisplayName("malformed_POINT_CN은_INVALID_INPUT으로_거부된다")
    void malformedPointRejected() {
        // given — 홀수 길이 평탄 좌표(파싱 실패)
        LsDataLbl lbl = bbox("[10,20,30]", 1L, null);

        // when / then — 좌표 원문 미노출 + INVALID_INPUT
        assertThatThrownBy(() -> labelMapper.toAnnotation(lbl, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ---------- CategoryMapper ----------

    @Test
    @DisplayName("SKELETON카테고리에_COCO17_keypoints와_skeleton이_포함된다")
    void skeletonCategoryHasCoco17() {
        // given
        LsLabel person = label(11L, "person", "SKELETON");
        LsLabel car = label(12L, "car", "BBOX");

        // when
        List<NiaCategory> categories = categoryMapper.toCategories(List.of(person, car));

        // then
        NiaCategory skel = categories.get(0);
        assertThat(skel.id()).isEqualTo("11");
        assertThat(skel.type()).isEqualTo("keypoints");
        assertThat(skel.keypoints()).isEqualTo(KeypointSkeleton.KEYPOINT_NAMES);
        assertThat(skel.skeleton()).isEqualTo(KeypointSkeleton.SKELETON_EDGES);

        NiaCategory bboxCat = categories.get(1);
        assertThat(bboxCat.type()).isEqualTo("bbox");
        assertThat(bboxCat.keypoints()).isNull();
        assertThat(bboxCat.skeleton()).isNull();
    }

    // ---------- VideoMetaMapper / NiaJsonBuilder ----------

    @Test
    @DisplayName("미보유_필수video필드(pixel·cctv_azimuth·weather)는_null키로_유지된다")
    void missingVideoFieldsRetainedAsNullKeys() throws Exception {
        // given
        NiaAnnotationDoc doc = buildDoc(ExportKind.ORIGINAL);

        // when
        JsonNode video = objectMapper.valueToTree(doc).get("video");

        // then — 키는 존재하되 값은 null
        assertThat(video.has("pixel")).isTrue();
        assertThat(video.get("pixel").isNull()).isTrue();
        assertThat(video.has("cctv_azimuth")).isTrue();
        assertThat(video.get("cctv_azimuth").isNull()).isTrue();
        assertThat(video.has("weather")).isTrue();
        assertThat(video.get("weather").isNull()).isTrue();
        assertThat(video.has("cto")).isTrue();
        assertThat(video.get("cto").isNull()).isTrue();
    }

    @Test
    @DisplayName("original_JSON_anonymity_N_deid_JSON_anonymity_Y")
    void anonymityDiffersByKind() {
        // given / when
        NiaAnnotationDoc original = buildDoc(ExportKind.ORIGINAL);
        NiaAnnotationDoc deid = buildDoc(ExportKind.DEIDENTIFIED);

        // then — anonymity 만 다르고 annotations 는 동일
        assertThat(original.video().anonymity()).isEqualTo("N");
        assertThat(original.image().anonymity()).isEqualTo("N");
        assertThat(deid.video().anonymity()).isEqualTo("Y");
        assertThat(deid.image().anonymity()).isEqualTo("Y");
        assertThat(original.annotations()).isEqualTo(deid.annotations());
    }

    @Test
    @DisplayName("최상위_8키_info부터_type까지_모두_존재한다")
    void topLevelEightKeys() {
        // given / when
        JsonNode json = objectMapper.valueToTree(buildDoc(ExportKind.ORIGINAL));

        // then
        assertThat(json.has("info")).isTrue();
        assertThat(json.has("dataset")).isTrue();
        assertThat(json.has("licences")).isTrue();
        assertThat(json.has("video")).isTrue();
        assertThat(json.has("image")).isTrue();
        assertThat(json.has("annotations")).isTrue();
        assertThat(json.has("categories")).isTrue();
        assertThat(json.get("type").asText()).isEqualTo("instances");
    }

    @Test
    @DisplayName("event_annotation이_각_프레임_최상위에_c1cn_형태로_pass_through된다")
    void eventAnnotationPassThroughAsC1Form() throws Exception {
        // given — 동결 event_annotation payload(위키 §24.3.1 — 후보 키 c1..cn, caption/evidence)
        String frozen = "{"
                + "\"event_class\":\"assault\","
                + "\"question\":\"무슨 일이 일어나는가?\","
                + "\"caption\":{\"c1\":{\"caption_text\":\"두 사람이 다툰다\",\"cot\":[\"1단계\",\"2단계\"]}},"
                + "\"answer\":\"폭행\","
                + "\"evidence\":{\"c1\":{\"evidence_text\":\"주먹\",\"obj_id\":[\"o1\",\"o2\"]}}"
                + "}";
        JsonNode frozenNode = objectMapper.readTree(frozen);
        LsDataSrc src = frame();
        LsDataLbl lbl = bbox("[[10,20],[40,60]]", 7L, src.getSrcSn());
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), raw(), List.of(label(7L, "person", "BBOX")), frozenNode);

        // when — 프레임 문서 조립 후 직렬화(파일 산출과 동일 경로)
        NiaAnnotationDoc doc = builder.build(ctx,
                new NiaJsonBuilder.FrameContext(src, List.of(lbl)), ExportKind.ORIGINAL);
        JsonNode json = objectMapper.valueToTree(doc);

        // then — 최상위 event_annotation 키 + 원문 형태(c1/caption_text/cot/evidence) 보존
        assertThat(json.has("event_annotation")).isTrue();
        JsonNode ea = json.get("event_annotation");
        assertThat(ea.path("event_class").asText()).isEqualTo("assault");
        assertThat(ea.path("caption").has("c1")).isTrue();
        assertThat(ea.path("caption").path("c1").path("caption_text").asText()).isEqualTo("두 사람이 다툰다");
        assertThat(ea.path("caption").path("c1").path("cot").isArray()).isTrue();
        assertThat(ea.path("caption").path("c1").path("cot")).hasSize(2);
        assertThat(ea.path("evidence").path("c1").path("evidence_text").asText()).isEqualTo("주먹");
        JsonNode objId = ea.path("evidence").path("c1").path("obj_id");
        assertThat(objId.isArray()).isTrue();
        assertThat(objId.get(0).asText()).isEqualTo("o1");
    }

    @Test
    @DisplayName("동결_event_annotation이_없으면_event_annotation키는_null이다")
    void eventAnnotationNullWhenAbsent() {
        // given / when — eventAnnotation 미주입(3-arg 오버로드 → null)
        JsonNode json = objectMapper.valueToTree(buildDoc(ExportKind.ORIGINAL));

        // then — 키는 항상 present(자기완결), 값만 null (클래스 ALWAYS 정책)
        assertThat(json.has("event_annotation")).isTrue();
        assertThat(json.get("event_annotation").isNull()).isTrue();
    }

    @Test
    @DisplayName("프레임설명_FRM_EXPLN이_image_description에_반영된다")
    void frameDescriptionMappedToImage() {
        // given / when
        NiaAnnotationDoc doc = buildDoc(ExportKind.ORIGINAL);

        // then
        assertThat(doc.image().description()).isEqualTo("사람이 횡단보도를 건넌다");
    }

    @Test
    @DisplayName("Jackson_직렬화_키가_snake_case다")
    void serializedKeysAreSnakeCase() {
        // given / when
        JsonNode json = objectMapper.valueToTree(buildDoc(ExportKind.ORIGINAL));

        // then — image/annotation/video/info snake_case 키 존재, camelCase 부재
        JsonNode image = json.get("image");
        assertThat(image.has("file_name")).isTrue();
        assertThat(image.has("fileName")).isFalse();
        assertThat(image.has("video_id")).isTrue();

        JsonNode ann = json.get("annotations").get(0);
        assertThat(ann.has("category_id")).isTrue();
        assertThat(ann.has("image_id")).isTrue();

        assertThat(json.get("info").has("date_created")).isTrue();
        assertThat(json.get("video").has("orign_filename")).isTrue();
    }

    @Test
    @DisplayName("video_id와_dataset_identifier가_rawSn이다")
    void videoIdAndDatasetIdentifierAreRawSn() {
        // given / when
        NiaAnnotationDoc doc = buildDoc(ExportKind.ORIGINAL);

        // then — rawSn=42
        assertThat(doc.image().videoId()).isEqualTo("42");
        assertThat(doc.dataset().identifier()).isEqualTo("42");
        assertThat(doc.video().id()).isEqualTo("42");
    }

    @Test
    @DisplayName("malformed라벨은_문서조립시_skip되고_정상라벨만_남는다")
    void malformedLabelSkippedInBuild() {
        // given — 정상 bbox 1건 + malformed 1건
        LsDataSrc src = frame();
        LsDataLbl ok = bbox("[[0,0],[10,10]]", 7L, src.getSrcSn());
        LsDataLbl bad = bbox("[1,2,3]", 8L, src.getSrcSn());
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), raw(), List.of(label(7L, "person", "BBOX")));

        // when
        NiaAnnotationDoc doc = builder.build(ctx,
                new NiaJsonBuilder.FrameContext(src, List.of(ok, bad)), ExportKind.ORIGINAL);

        // then — 정상 1건만 조립
        assertThat(doc.annotations()).hasSize(1);
        assertThat(doc.annotations().get(0).bbox()).containsExactly(0.0, 0.0, 10.0, 10.0);
    }

    @Test
    @DisplayName("orign_file_name이_kind별로_다른_소스경로를_반영한다")
    void orignFileNameReflectsKindSpecificSourcePath() {
        // given — raw/deid 프레임 파일명을 서로 다르게 세팅
        LsDataSrc src = LsDataSrc.create(42L, 5L, "/nas/frames/raw/42/orig-5.jpg",
                LocalDateTime.of(2026, 3, 3, 10, 0, 5));
        src.attachDeidPath("/nas/frames/deid/42/deid-5.jpg");
        ReflectionTestUtils.setField(src, "srcSn", 500L);
        LsDataLbl lbl = bbox("[[10,20],[40,60]]", 7L, src.getSrcSn());
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), raw(), List.of(label(7L, "person", "BBOX")));

        // when — 동일 프레임을 kind 별로 조립
        NiaAnnotationDoc original = builder.build(ctx,
                new NiaJsonBuilder.FrameContext(src, List.of(lbl)), ExportKind.ORIGINAL);
        NiaAnnotationDoc deid = builder.build(ctx,
                new NiaJsonBuilder.FrameContext(src, List.of(lbl)), ExportKind.DEIDENTIFIED);

        // then — ORIGINAL=원본경로 basename, DEIDENTIFIED=비식별경로 basename (원본경로 미노출)
        assertThat(original.image().orignFileName()).isEqualTo("orig-5.jpg");
        assertThat(deid.image().orignFileName()).isEqualTo("deid-5.jpg");
        assertThat(deid.image().orignFileName()).isNotEqualTo(original.image().orignFileName());
    }

    @Test
    @DisplayName("meta가_null이면_INVALID_INPUT")
    void prepareContextRejectsNullMeta() {
        // when / then — prepareContext 입력 검증
        assertThatThrownBy(() -> builder.prepareContext(null, raw(), List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("ctx또는frame또는kind가_null이면_INVALID_INPUT")
    void buildRejectsNullArgs() {
        // given
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), raw(), List.of(label(7L, "person", "BBOX")));
        NiaJsonBuilder.FrameContext frame = new NiaJsonBuilder.FrameContext(frame(), List.of());

        // when / then — 세 인자 각각 null 이면 거부
        assertThatThrownBy(() -> builder.build(null, frame, ExportKind.ORIGINAL))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> builder.build(ctx, null, ExportKind.ORIGINAL))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> builder.build(ctx, frame, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("라벨이_없는_프레임은_빈_annotations를_반환한다")
    void emptyLabelsYieldEmptyAnnotations() {
        // given — 빈 라벨 리스트
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), raw(), List.of());

        // when
        NiaAnnotationDoc doc = builder.build(ctx,
                new NiaJsonBuilder.FrameContext(frame(), List.of()), ExportKind.ORIGINAL);

        // then
        assertThat(doc.annotations()).isEmpty();
    }

    @Test
    @DisplayName("labels가_null이면_빈_annotations")
    void nullLabelsYieldEmptyAnnotations() {
        // given — labels null
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), raw(), List.of());

        // when
        NiaAnnotationDoc doc = builder.build(ctx,
                new NiaJsonBuilder.FrameContext(frame(), null), ExportKind.ORIGINAL);

        // then
        assertThat(doc.annotations()).isEmpty();
    }

    // ---------- fixtures ----------

    private NiaAnnotationDoc buildDoc(ExportKind kind) {
        LsDataSrc src = frame();
        LsDataLbl lbl = bbox("[[10,20],[40,60]]", 7L, src.getSrcSn());
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), raw(), List.of(label(7L, "person", "BBOX")));
        return builder.build(ctx, new NiaJsonBuilder.FrameContext(src, List.of(lbl)), kind);
    }

    private LsDatasetVideoMeta meta() {
        LsDatasetVideoMeta m = LsDatasetVideoMeta.builder()
                .rawSn(42L)
                .rawFilePathNm("/nas/raw/42/original.mp4")
                .shtDt(LocalDateTime.of(2026, 3, 3, 10, 0))
                .vdoLenSec(35)
                .prvcTypeCd(LsDataRaw.PRVC_TYPE_PRVC)
                .prvcYn("Y")
                .deIdentYn("Y")
                .cctvNm("강남대로 CCTV")
                .wgs84Lat(new BigDecimal("37.5"))
                .wgs84Lot(new BigDecimal("127.0"))
                .sidoNm("서울특별시")
                .sggNm("강남구")
                .fileFmt("mp4")
                .evntNm("보행자 통행")
                .evntTypeCd("PEDESTRIAN")
                .fps(new BigDecimal("30.0"))
                .bitRt(4_000_000L)
                .asprtRt(new BigDecimal("1.7778"))
                .resl("1920x1080")
                .vdoWdth(1920)
                .vdoHgt(1080)
                .fileSz(10_485_760L)
                .dayNgtCd("DAY")
                .sesnCd("SPRING")
                .build();
        return m;
    }

    private LsDataRaw raw() {
        return LsDataRaw.builder()
                .vmsClipId("clip-42").vmsCctvId("cctv-1")
                .prvcTypeCd(LsDataRaw.PRVC_TYPE_PRVC)
                .rawFilePathNm("/nas/raw/42/original.mp4")
                .shtDt(LocalDateTime.of(2026, 3, 3, 10, 0))
                .durationSec(35)
                .build();
    }

    private LsDataSrc frame() {
        LsDataSrc src = LsDataSrc.create(42L, 5L, "/nas/frames/raw/42/frame-5.jpg",
                LocalDateTime.of(2026, 3, 3, 10, 0, 5));
        src.attachDeidPath("/nas/frames/deid/42/frame-5.jpg");
        src.updateDescription("사람이 횡단보도를 건넌다");
        ReflectionTestUtils.setField(src, "srcSn", 500L);
        return src;
    }

    private LsDataLbl bbox(String pointsJson, Long labelId, Long srcSn) {
        return LsDataLbl.builder()
                .srcSn(srcSn == null ? 1L : srcSn).lblTypeCd(LsDataLbl.TYPE_BBOX)
                .labelId(labelId).label("person").pointsJson(pointsJson).build();
    }

    private LsDataLbl polygon(String pointsJson, Long labelId) {
        return LsDataLbl.builder()
                .srcSn(1L).lblTypeCd(LsDataLbl.TYPE_POLYGON)
                .labelId(labelId).label("road").pointsJson(pointsJson).build();
    }

    private LsDataLbl skeleton(String pointsJson, Long labelId) {
        return LsDataLbl.builder()
                .srcSn(1L).lblTypeCd(LsDataLbl.TYPE_SKELETON)
                .labelId(labelId).label("person").pointsJson(pointsJson).build();
    }

    private LsLabel label(Long labelId, String name, String type) {
        LsLabel l = LsLabel.create(name, "#FF0000", type, 0, "tester");
        ReflectionTestUtils.setField(l, "labelId", labelId);
        return l;
    }

    private String keypointJson() {
        List<String> triplets = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            triplets.add("[" + (i + 1) + "," + (i + 2) + ",2]");
        }
        return "[" + String.join(",", triplets) + "]";
    }
}

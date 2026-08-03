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
    @DisplayName("미보유_필수video필드(pixel·cctv_azimuth·weather·vd_description)는_null키로_유지된다")
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
        assertThat(video.has("vd_description")).isTrue();
        assertThat(video.get("vd_description").isNull()).isTrue();
    }

    @Test
    @DisplayName("정본샘플_정합_video블록에_잉여키(orign_filename·cto·vqa)부재_vd_description존재")
    void videoBlockMatchesSampleSchema() {
        // given / when
        JsonNode video = objectMapper.valueToTree(buildDoc(ExportKind.ORIGINAL)).get("video");

        // then — 제거된 잉여키 부재 + 신규 vd_description 존재(정본 샘플 정합)
        assertThat(video.has("orign_filename")).isFalse();
        assertThat(video.has("cto")).isFalse();
        assertThat(video.has("vqa")).isFalse();
        assertThat(video.has("vd_description")).isTrue();
        // 유지되어야 하는 핵심 키
        assertThat(video.has("filename")).isTrue();
        assertThat(video.has("event_log")).isTrue();
    }

    @Test
    @DisplayName("정본샘플_정합_image블록에_잉여키(orign_file_name)부재")
    void imageBlockMatchesSampleSchema() {
        // given / when
        JsonNode image = objectMapper.valueToTree(buildDoc(ExportKind.ORIGINAL)).get("image");

        // then — orign_file_name 제거(정본 샘플에 없음), file_name 은 유지
        assertThat(image.has("orign_file_name")).isFalse();
        assertThat(image.has("file_name")).isTrue();
    }

    @Test
    @DisplayName("최상위_VLM블록키는_event이고_event_annotation키는_부재_위치는_video다음")
    void topLevelVlmKeyIsEventAfterVideo() {
        // given / when
        JsonNode json = objectMapper.valueToTree(buildDoc(ExportKind.ORIGINAL));

        // then — 키 이름은 event, 구 event_annotation 부재
        assertThat(json.has("event")).isTrue();
        assertThat(json.has("event_annotation")).isFalse();

        // and — 위치는 video 다음(현행 순서 유지). 최상위 필드 순서로 확인
        List<String> keys = new ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys.indexOf("event")).isEqualTo(keys.indexOf("video") + 1);
    }

    @Test
    @DisplayName("원천산출물은_개인정보3필드가_모두_null이고_비식별만_값을_갖는다")
    void privacyFieldsOnlyInDeidExport() {
        // given / when
        NiaAnnotationDoc original = buildDoc(ExportKind.ORIGINAL);
        NiaAnnotationDoc deid = buildDoc(ExportKind.DEIDENTIFIED);

        // then — 원천영상은 비식별 처리 전이라 판정하지 않는다(2026-08-03 확정). 값을 지어내지 않고 null.
        assertThat(original.video().anonymity()).isNull();
        assertThat(original.video().pseudonymity()).isNull();
        assertThat(original.video().privacyIncluded()).isNull();
        assertThat(original.image().anonymity()).isNull();
        assertThat(original.image().pseudonymity()).isNull();
        assertThat(original.image().privacyIncluded()).isNull();

        // and — 비식별본은 수동값 미입력이므로 기본상수(Y/N/N)
        assertThat(deid.video().anonymity()).isEqualTo("Y");
        assertThat(deid.video().pseudonymity()).isEqualTo("N");
        assertThat(deid.video().privacyIncluded()).isEqualTo("N");
        assertThat(deid.image().anonymity()).isEqualTo("Y");
        assertThat(deid.image().pseudonymity()).isEqualTo("N");
        assertThat(deid.image().privacyIncluded()).isEqualTo("N");

        // and — 라벨은 두 벌이 동일해야 한다(회귀 가드 — 좌표는 kind 무관).
        assertThat(original.annotations()).isEqualTo(deid.annotations());
    }

    @Test
    @DisplayName("원천산출물은_수동값이_저장돼_있어도_개인정보3필드가_null이다")
    void manualValuesNeverLeakIntoOriginalExport() {
        // given — 영상 단위·프레임 단위 수동값이 모두 저장된 상태
        LsDataSrc src = frame();
        src.updatePrivacyMeta("N", "Y", "Y");
        LsDataRaw raw = raw();
        raw.changePrivacyMeta("N", "Y", "Y");
        LsDataLbl lbl = bbox("[[10,20],[40,60]]", 7L, src.getSrcSn());
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), raw, List.of(label(7L, "person", "BBOX")));

        // when
        NiaAnnotationDoc original = builder.build(ctx,
                new NiaJsonBuilder.FrameContext(src, List.of(lbl)), ExportKind.ORIGINAL);

        // then — 수동 판정은 <비식별 산출물에 대한 판단>이므로 원천에 싣지 않는다.
        assertThat(original.video().anonymity()).isNull();
        assertThat(original.video().pseudonymity()).isNull();
        assertThat(original.video().privacyIncluded()).isNull();
        assertThat(original.image().anonymity()).isNull();
        assertThat(original.image().pseudonymity()).isNull();
        assertThat(original.image().privacyIncluded()).isNull();
    }

    @Test
    @DisplayName("비식별산출물은_video는_영상단위_image는_프레임단위_수동값을_읽는다")
    void deidReadsPerAxisManualValues() {
        // given — 영상 단위(V163)와 프레임 단위(V130)에 <서로 다른> 판정이 저장된 상태.
        //   ★ 이것이 이번 정책의 핵심이다: 두 블록은 같은 판정기(ExportPrivacyPolicy)를 쓰되
        //     각자 자기 입도의 원천을 읽는다. 값이 달라도 모순이 아니라
        //     "영상 어딘가엔 개인정보가 있지만 이 프레임엔 없다"는 서로 다른 입도의 사실이다.
        LsDataSrc src = frame();
        src.updatePrivacyMeta("Y", "N", "N");   // 이 프레임엔 개인정보 없음
        LsDataRaw raw = raw();
        raw.changePrivacyMeta("N", "Y", "Y");   // 영상 전체로는 개인정보 있음
        LsDataLbl lbl = bbox("[[10,20],[40,60]]", 7L, src.getSrcSn());
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), raw, List.of(label(7L, "person", "BBOX")));

        // when
        NiaAnnotationDoc deid = builder.build(ctx,
                new NiaJsonBuilder.FrameContext(src, List.of(lbl)), ExportKind.DEIDENTIFIED);

        // then — video 는 영상 단위 값, image 는 프레임 단위 값
        assertThat(deid.video().anonymity()).isEqualTo("N");
        assertThat(deid.video().pseudonymity()).isEqualTo("Y");
        assertThat(deid.video().privacyIncluded()).isEqualTo("Y");
        assertThat(deid.image().anonymity()).isEqualTo("Y");
        assertThat(deid.image().pseudonymity()).isEqualTo("N");
        assertThat(deid.image().privacyIncluded()).isEqualTo("N");
    }

    /**
     * ★ 파생영상 계승 회귀 가드 (DEV_FIX 2026-08-03, HIGH).
     *
     * <p>부모가 "개인정보 잔존(Y)"으로 판정된 영상의 <b>파생본</b>은 프레임 축 값을 이미 복사받으므로
     * ({@code AugmentExtractPersist}/{@code ResolutionPersistService}), 영상 축을 계승하지 않으면
     * 같은 문서에서 {@code image="Y"} / {@code video="N"} 이 난다. 이는 정책이 정당화한 방향
     * ("영상엔 있지만 이 프레임엔 없다")의 <b>역방향</b>이라 성립 불가능한 조합이고, 실제로는 개인정보
     * 잔존이 영상 단위로 <b>과소 신고</b>되는 것이다. {@code LsDataRaw.copyPrivacyMetaFrom} 을 지우면
     * 이 테스트가 실패한다.
     */
    @Test
    @DisplayName("파생영상_export는_부모의_영상단위_판정을_계승해_image와_video가_갈리지_않는다")
    void derivativeInheritsVideoAxisPrivacy() {
        // given — 부모: 영상 축·프레임 축 모두 "개인정보 잔존(Y)"
        LsDataRaw parent = raw();
        parent.changePrivacyMeta("N", "N", "Y");
        // 파생 영상행 = 실제 생성 경로(팩토리)로 만든다 — 계승 배선을 우회하지 않기 위함
        LsDataRaw derivative = LsDataRaw.createFromAugment(parent, "/nas/deid/aug/winter.mp4", "WINTER", 9001L);
        // 파생 프레임 = 부모 프레임의 개인정보 수동값을 복사받은 상태(운영 경로와 동일)
        LsDataSrc derivedFrame = frame();
        derivedFrame.updatePrivacyMeta("N", "N", "Y");
        LsDataLbl lbl = bbox("[[10,20],[40,60]]", 7L, derivedFrame.getSrcSn());
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), derivative, List.of(label(7L, "person", "BBOX")));

        // when
        NiaAnnotationDoc deid = builder.build(ctx,
                new NiaJsonBuilder.FrameContext(derivedFrame, List.of(lbl)), ExportKind.DEIDENTIFIED);

        // then — 영상 축이 부모 판정을 그대로 신고한다
        assertThat(deid.video().privacyIncluded()).isEqualTo("Y");
        assertThat(deid.video().anonymity()).isEqualTo("N");
        assertThat(deid.video().pseudonymity()).isEqualTo("N");
        // and — 성립 불가능한 조합(image=Y / video=N)이 나오지 않는다
        assertThat(deid.image().privacyIncluded()).isEqualTo(deid.video().privacyIncluded());
        assertThat(deid.image().anonymity()).isEqualTo(deid.video().anonymity());
    }

    @Test
    @DisplayName("두_블록은_같은_판정기를_써서_같은_입력이면_항상_같은_값을_낸다")
    void bothBlocksShareSingleDecisionMaker() {
        // given — 라벨링 화면에서 나올 수 있는 수동값 조합.
        //   ★ 회귀 가드(취지 유지): 판정 로직을 한쪽 블록에만 배선하거나 복제하면
        //     <같은 입력인데도> 두 블록의 값이 갈린다. 원천(입도)이 다른 것과 판정기가 다른 것은 별개다.
        String[][] combos = {
                {null, null, null},   // 미입력 → 기본상수
                {null, null, "Y"},
                {"N", null, null},
                {null, "Y", null},
                {"N", "Y", "Y"},
                {"Y", "N", "N"},
        };

        for (String[] c : combos) {
            LsDataSrc src = frame();
            src.updatePrivacyMeta(c[0], c[1], c[2]);
            LsDataRaw raw = raw();
            raw.changePrivacyMeta(c[0], c[1], c[2]);   // 두 축에 <동일> 입력
            LsDataLbl lbl = bbox("[[10,20],[40,60]]", 7L, src.getSrcSn());
            NiaJsonBuilder.VideoExportContext ctx =
                    builder.prepareContext(meta(), raw, List.of(label(7L, "person", "BBOX")));

            // when — 두 벌 산출
            NiaAnnotationDoc deid = builder.build(ctx,
                    new NiaJsonBuilder.FrameContext(src, List.of(lbl)), ExportKind.DEIDENTIFIED);
            NiaAnnotationDoc original = builder.build(ctx,
                    new NiaJsonBuilder.FrameContext(src, List.of(lbl)), ExportKind.ORIGINAL);

            // then — 같은 입력이면 video/image 가 3필드 모두 동일해야 한다.
            String combo = java.util.Arrays.toString(c);
            assertThat(deid.image().anonymity()).as("anonymity %s", combo)
                    .isEqualTo(deid.video().anonymity());
            assertThat(deid.image().pseudonymity()).as("pseudonymity %s", combo)
                    .isEqualTo(deid.video().pseudonymity());
            assertThat(deid.image().privacyIncluded()).as("privacy_included %s", combo)
                    .isEqualTo(deid.video().privacyIncluded());
            // and — ORIGINAL 은 어떤 조합에서도 3필드가 null(판정 안 함)
            assertThat(original.image().anonymity()).as("original anonymity %s", combo).isNull();
            assertThat(original.video().privacyIncluded()).as("original privacy %s", combo).isNull();
        }
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
    @DisplayName("event가_각_프레임_최상위에_c1cn_형태로_pass_through된다")
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

        // then — 최상위 event 키(정본 샘플) + 원문 형태(c1/caption_text/cot/evidence) 보존
        assertThat(json.has("event")).isTrue();
        JsonNode ea = json.get("event");
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
    @DisplayName("동결_event가_없으면_event키는_null이다")
    void eventAnnotationNullWhenAbsent() {
        // given / when — eventAnnotation 미주입(3-arg 오버로드 → null)
        JsonNode json = objectMapper.valueToTree(buildDoc(ExportKind.ORIGINAL));

        // then — 키는 항상 present(자기완결), 값만 null (클래스 ALWAYS 정책)
        assertThat(json.has("event")).isTrue();
        assertThat(json.get("event").isNull()).isTrue();
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
        assertThat(json.get("video").has("time_of_day")).isTrue();
        assertThat(json.get("video").has("event_log")).isTrue();
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
    @DisplayName("image블록은_kind무관_file_name만_보유하고_소스경로를_노출하지_않는다")
    void imageBlockCarriesNoSourcePath() throws Exception {
        // given — raw/deid 프레임 파일명을 서로 다르게 세팅(경로 누수 여부 검증)
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

        // then — image.file_name 은 kind 무관이며 export writer·관제 통지와 동일한 규칙(ExportFileNaming,
        //        {FRM_NO 4자리}.jpg)을 따라야 한다. 구 규칙("frame-5.jpg")은 같은 폴더에 실재하지 않는
        //        파일을 가리켜 JSON↔디스크가 어긋났다(A-1). 소스 경로도 새지 않는다.
        assertThat(original.image().fileName()).isEqualTo("0005.jpg");
        assertThat(original.image().fileName())
                .isEqualTo(kr.co.cudo.authoring.dataset.export.ExportFileNaming.imageFileName(5));
        assertThat(deid.image().fileName()).isEqualTo(original.image().fileName());
        String origJson = objectMapper.valueToTree(original).get("image").toString();
        String deidJson = objectMapper.valueToTree(deid).get("image").toString();
        assertThat(origJson).doesNotContain("/nas/frames/");
        assertThat(deidJson).doesNotContain("/nas/frames/");
    }

    @Test
    @DisplayName("DEID_kind_JSON의_dataset경로는_비식별경로_원본아님")
    void deidDatasetPathUsesDeidVideoPathNotOriginal() {
        // given — 비식별 영상 경로가 있는 컨텍스트(proc log DE_IDNTF_FILE_PATH_NM 상당)
        String deidVideoPath = "/nas/deid/42/deidentified.mp4";
        NiaAnnotationDoc deid = buildDoc(ExportKind.DEIDENTIFIED, deidVideoPath);

        // then — DEID dataset.src_path 는 비식별 경로여야 하고, 원본 raw 경로가 아니어야 한다.
        assertThat(deid.dataset().srcPath()).isEqualTo(deidVideoPath);
        assertThat(deid.dataset().srcPath()).isNotEqualTo("/nas/raw/42/original.mp4");
        assertThat(deid.dataset().srcPath()).doesNotContain("/nas/raw/");
        assertThat(deid.dataset().name()).isEqualTo("deidentified");
        // identifier(=videoId)는 kind 무관 유지
        assertThat(deid.dataset().identifier()).isEqualTo("42");
    }

    @Test
    @DisplayName("ORIGINAL_kind_JSON의_dataset경로는_원본유지")
    void originalDatasetPathRetainsRawPath() {
        // given / when — 회귀 가드: 원본 산출은 기존대로 원본 raw 경로
        NiaAnnotationDoc original = buildDoc(ExportKind.ORIGINAL, "/nas/deid/42/deidentified.mp4");

        // then
        assertThat(original.dataset().srcPath()).isEqualTo("/nas/raw/42/original.mp4");
        assertThat(original.dataset().name()).isEqualTo("original");
    }

    @Test
    @DisplayName("DEID_kind에서_video_filename도_원본경로_아님")
    void deidVideoFilenameUsesDeidVideoPathNotOriginal() {
        // given — 비식별 영상 경로 세팅
        String deidVideoPath = "/nas/deid/42/deidentified.mp4";
        NiaAnnotationDoc deid = buildDoc(ExportKind.DEIDENTIFIED, deidVideoPath);

        // then — video.filename 은 비식별 파일명, 원본 파일명("original.mp4") 미노출
        assertThat(deid.video().filename()).isEqualTo("deidentified.mp4");
        assertThat(deid.video().filename()).isNotEqualTo("original.mp4");
    }

    @Test
    @DisplayName("deid_영상경로_없으면_원본절대경로_미노출")
    void deidWithoutDeidPathDoesNotLeakOriginalPath() throws Exception {
        // given — 비식별 영상 경로 미상(null) — fail-secure 경로
        NiaAnnotationDoc deid = buildDoc(ExportKind.DEIDENTIFIED, null);

        // then — dataset/video 경로 필드에 원본 절대경로가 새지 않는다(null 또는 원본 미포함).
        assertThat(deid.dataset().srcPath()).isNull();
        assertThat(deid.dataset().name()).isNull();
        assertThat(deid.video().filename()).isNull();
        // 문서 전체 직렬화에도 원본 raw 경로 문자열이 등장하지 않는다(경로 누수 종합 가드).
        String json = objectMapper.writeValueAsString(deid);
        assertThat(json).doesNotContain("/nas/raw/42/original.mp4");
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

    private NiaAnnotationDoc buildDoc(ExportKind kind, String deidVideoPath) {
        LsDataSrc src = frame();
        LsDataLbl lbl = bbox("[[10,20],[40,60]]", 7L, src.getSrcSn());
        NiaJsonBuilder.VideoExportContext ctx =
                builder.prepareContext(meta(), raw(), List.of(label(7L, "person", "BBOX")), null, deidVideoPath);
        return builder.build(ctx, new NiaJsonBuilder.FrameContext(src, List.of(lbl)), kind);
    }

    private LsDatasetVideoMeta meta() {
        return metaWithPrvcType(LsDataRaw.PRVC_TYPE_PRVC);
    }

    private LsDatasetVideoMeta metaWithPrvcType(String prvcTypeCd) {
        LsDatasetVideoMeta m = LsDatasetVideoMeta.builder()
                .rawSn(42L)
                .rawFilePathNm("/nas/raw/42/original.mp4")
                .shtDt(LocalDateTime.of(2026, 3, 3, 10, 0))
                .vdoLenSec(35)
                .prvcTypeCd(prvcTypeCd)
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

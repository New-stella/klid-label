package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LabelToAnnotationMapper} 단위 테스트 — LBL_TYPE_CD 분기·좌표 검증·null 전파 커버리지.
 *
 * <p>DEV_FIX: 기존 NiaJsonBuilderTest 에 몰려있던 매퍼 검증을 클래스 전용 파일로 분리하고
 * 미커버 분기(SEGMENT 타입 문자열·else 거부·빈 좌표·다점 바운딩·null id/categoryId)를 고정한다.
 */
class LabelToAnnotationMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LabelToAnnotationMapper mapper = new LabelToAnnotationMapper(objectMapper);

    @Test
    @DisplayName("SEGMENT라벨이_polygon으로_변환된다")
    void segmentToPolygon() {
        // given — SEGMENT 는 POLYGON 과 동일 경로지만 타입 문자열이 다름
        LsDataLbl lbl = lbl(LsDataLbl.TYPE_SEGMENT, "[[10,20],[30,40],[50,60]]", 5L, null);

        // when
        NiaAnnotation ann = mapper.toAnnotation(lbl, 1);

        // then — 단일 ring flat 좌표, bbox/keypoints 는 null
        assertThat(ann.polygon()).hasSize(1);
        assertThat(ann.polygon().get(0)).containsExactly(10.0, 20.0, 30.0, 40.0, 50.0, 60.0);
        assertThat(ann.bbox()).isNull();
        assertThat(ann.keypoints()).isNull();
    }

    @Test
    @DisplayName("미지원_라벨타입은_INVALID_INPUT으로_거부된다")
    void unsupportedTypeRejected() {
        // given — else 분기 (POINT 는 매퍼 미지원)
        LsDataLbl point = lbl("POINT", "[[1,2]]", 3L, null);

        // when / then — else 에서 던진 CustomException 이 catch(CustomException) 를 통해 원형 그대로 전파
        assertThatThrownBy(() -> mapper.toAnnotation(point, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("LBL_TYPE_CD가_null이면_INVALID_INPUT으로_거부된다")
    void nullTypeRejected() {
        // given — lblTypeCd null → else 분기
        LsDataLbl nullType = lbl(null, "[[1,2],[3,4]]", 3L, null);

        // when / then
        assertThatThrownBy(() -> mapper.toAnnotation(nullType, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("BBOX_좌표가_비어있으면_INVALID_INPUT")
    void emptyBboxRejected() {
        assertInvalid(lbl(LsDataLbl.TYPE_BBOX, "[]", 1L, null));
    }

    @Test
    @DisplayName("POLYGON_좌표가_비어있으면_INVALID_INPUT")
    void emptyPolygonRejected() {
        assertInvalid(lbl(LsDataLbl.TYPE_POLYGON, "[]", 1L, null));
    }

    @Test
    @DisplayName("SKELETON_좌표가_비어있으면_INVALID_INPUT")
    void emptySkeletonRejected() {
        assertInvalid(lbl(LsDataLbl.TYPE_SKELETON, "[]", 1L, null));
    }

    @Test
    @DisplayName("다점BBOX는_min_max로_바운딩된다")
    void multiPointBboxBounded() {
        // given — 3점 이상 좌표 (사각/폴리곤형) → min/max 바운딩
        LsDataLbl lbl = lbl(LsDataLbl.TYPE_BBOX, "[[50,80],[10,120],[90,40]]", 2L, null);

        // when
        NiaAnnotation ann = mapper.toAnnotation(lbl, 1);

        // then — minX=10, minY=40, w=90-10=80, h=120-40=80
        assertThat(ann.bbox()).containsExactly(10.0, 40.0, 80.0, 80.0);
    }

    @Test
    @DisplayName("trackId가_null인_BBOX는_annotation_trackId도_null")
    void nullTrackIdPropagated() {
        // given — trackId 미지정 BBOX
        LsDataLbl lbl = lbl(LsDataLbl.TYPE_BBOX, "[[0,0],[10,10]]", 4L, null);

        // when
        NiaAnnotation ann = mapper.toAnnotation(lbl, 1);

        // then
        assertThat(ann.trackId()).isNull();
        assertThat(ann.bbox()).containsExactly(0.0, 0.0, 10.0, 10.0);
    }

    @Test
    @DisplayName("lblSn과_labelId가_null이면_id와_categoryId도_null")
    void nullIdsPropagated() {
        // given — labelId null, lblSn 미할당(신규 엔티티라 null)
        LsDataLbl lbl = lbl(LsDataLbl.TYPE_BBOX, "[[0,0],[5,5]]", null, null);

        // when
        NiaAnnotation ann = mapper.toAnnotation(lbl, 1);

        // then
        assertThat(ann.id()).isNull();
        assertThat(ann.categoryId()).isNull();
    }

    // ---------- helpers ----------

    private void assertInvalid(LsDataLbl lbl) {
        assertThatThrownBy(() -> mapper.toAnnotation(lbl, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    private LsDataLbl lbl(String type, String pointsJson, Long labelId, String trackId) {
        return LsDataLbl.builder()
                .srcSn(1L)
                .lblTypeCd(type)
                .labelId(labelId)
                .label("obj")
                .pointsJson(pointsJson)
                .trackId(trackId)
                .build();
    }
}

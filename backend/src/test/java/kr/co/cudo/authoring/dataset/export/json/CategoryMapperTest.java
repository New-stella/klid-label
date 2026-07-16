package kr.co.cudo.authoring.dataset.export.json;

import kr.co.cudo.authoring.label.entity.LsLabel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CategoryMapper} 단위 테스트 — LBL_TYPE_CD→COCO type 소문자 매핑 전 분기와
 * null/빈 컬렉션·null 원소 방어 커버리지.
 *
 * <p>DEV_FIX: TRACK/SEGMENT/POINT 및 미상 타입(default) 분기, null 타입, 컬렉션 방어를 고정한다.
 */
class CategoryMapperTest {

    private final CategoryMapper mapper = new CategoryMapper();

    @Test
    @DisplayName("TRACK_SEGMENT_POINT_라벨타입이_올바르게_매핑된다")
    void trackSegmentPointMapped() {
        // given
        List<LsLabel> labels = List.of(
                label(1L, "car", "TRACK"),
                label(2L, "road", "SEGMENT"),
                label(3L, "person", "POINT"));

        // when
        List<NiaCategory> categories = mapper.toCategories(labels);

        // then — TRACK→bbox, SEGMENT→polygon, POINT→keypoints
        assertThat(categories).extracting(NiaCategory::type)
                .containsExactly("bbox", "polygon", "keypoints");
        // POINT 는 keypoints 타입이므로 COCO-17 관절/스켈레톤 주입
        assertThat(categories.get(2).keypoints()).isNotNull();
        assertThat(categories.get(2).skeleton()).isNotNull();
        // bbox/polygon 은 미주입
        assertThat(categories.get(0).keypoints()).isNull();
        assertThat(categories.get(1).skeleton()).isNull();
    }

    @Test
    @DisplayName("미상_라벨타입은_소문자로_그대로_매핑된다")
    void unknownTypeLowercased() {
        // given — 매핑 테이블 미등록 타입
        List<NiaCategory> categories = mapper.toCategories(List.of(label(9L, "box", "CUBOID")));

        // then — default 분기: 원본 소문자
        assertThat(categories.get(0).type()).isEqualTo("cuboid");
        assertThat(categories.get(0).keypoints()).isNull();
    }

    @Test
    @DisplayName("라벨타입이_null이면_카테고리타입도_null")
    void nullTypeMapsToNull() {
        // given — labelTypeCd null
        List<NiaCategory> categories = mapper.toCategories(List.of(label(4L, "unknown", null)));

        // then
        assertThat(categories.get(0).type()).isNull();
        assertThat(categories.get(0).keypoints()).isNull();
        assertThat(categories.get(0).skeleton()).isNull();
    }

    @Test
    @DisplayName("null컬렉션은_빈_리스트")
    void nullCollectionEmpty() {
        assertThat(mapper.toCategories(null)).isEmpty();
    }

    @Test
    @DisplayName("빈_라벨셋은_빈_카테고리목록")
    void emptyCollectionEmpty() {
        assertThat(mapper.toCategories(List.of())).isEmpty();
    }

    @Test
    @DisplayName("null라벨원소는_건너뛴다")
    void nullElementSkipped() {
        // given — null 원소 포함
        List<LsLabel> labels = Arrays.asList(label(1L, "car", "BBOX"), null, label(2L, "road", "SEGMENT"));

        // when
        List<NiaCategory> categories = mapper.toCategories(labels);

        // then — null 은 건너뛰고 2건만
        assertThat(categories).hasSize(2);
        assertThat(categories).extracting(NiaCategory::type).containsExactly("bbox", "polygon");
    }

    // ---------- helpers ----------

    private LsLabel label(Long labelId, String name, String type) {
        LsLabel l = LsLabel.create(name, "#FF0000", type, 0, "tester");
        ReflectionTestUtils.setField(l, "labelId", labelId);
        return l;
    }
}

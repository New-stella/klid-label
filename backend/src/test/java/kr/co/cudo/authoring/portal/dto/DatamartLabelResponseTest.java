package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DatamartLabelResponse 단위 테스트.
 *
 * <p>요구 R3 회귀 가드: write-time 정규화 + V66 백필 이후 {@code LS_DATA_LBL.POINT_CN} 은 항상
 * nested {@code [[x,y],...]} 포맷이며, 데이터마트 Load 응답은 이를 파싱 없이 raw 그대로
 * {@code pointsJson} 으로 포워딩한다. nested 입력 → nested 출력 일관성을 단언한다.
 */
class DatamartLabelResponseTest {

    @Test
    @DisplayName("from_nested_POINT_CN_그대로_pointsJson_노출")
    void from_nestedPointCn_forwardedAsIs() {
        // given -- DB 가 보장하는 nested 좌표
        String nestedPoints = "[[10,10],[50,50]]";
        LsDataLbl entity = LsDataLbl.createManual(
                10L, "BBOX", null, "person", nestedPoints, 1L);
        setField(entity, "lblSn", 1L);

        // when
        DatamartLabelResponse response = DatamartLabelResponse.from(entity);

        // then -- nested 입력이 nested 그대로 노출 (flat 변환·파싱 없음)
        assertThat(response.pointsJson()).isEqualTo(nestedPoints);
        assertThat(response.lblSn()).isEqualTo(1L);
        assertThat(response.srcSn()).isEqualTo(10L);
        assertThat(response.lblTypeCd()).isEqualTo("BBOX");
        assertThat(response.label()).isEqualTo("person");
    }

    @Test
    @DisplayName("from_polygon_nested_POINT_CN_그대로_노출")
    void from_polygonNestedPointCn_forwardedAsIs() {
        // given -- 다각형도 nested 좌표 그대로 노출되어야 한다
        String nestedPolygon = "[[1,2],[3,4],[5,6]]";
        LsDataLbl entity = LsDataLbl.createManual(
                11L, "POLYGON", null, "tree", nestedPolygon, 1L);

        // when
        DatamartLabelResponse response = DatamartLabelResponse.from(entity);

        // then
        assertThat(response.pointsJson()).isEqualTo(nestedPolygon);
        assertThat(response.lblTypeCd()).isEqualTo("POLYGON");
    }

    /**
     * 테스트용 리플렉션 필드 설정 — @Id @GeneratedValue 필드에 값 주입.
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}

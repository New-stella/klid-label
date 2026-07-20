package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import kr.co.cudo.authoring.label.dto.AutolabelRequest;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * shape 는 자유 문자열이 아닌 {@link AutolabelShape} enum 으로 받는다(CWE-20 화이트리스트).
 *
 * <p>잘못된 값은 Jackson 역직렬화 단계에서 {@link InvalidFormatException} 을 던지고, 컨트롤러 진입 전
 * {@code HttpMessageNotReadableException} 으로 래핑되어 {@code GlobalExceptionHandler} 가 400 으로 매핑한다.
 * (본 단위 테스트는 역직렬화 거부 자체를 검증한다 — 스프링 컨텍스트 불필요.)
 */
class AutolabelRequestShapeValidationTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("shape에_잘못된값이면_역직렬화에서_거부된다_400매핑")
    void invalidShapeRejected() {
        String json = "{\"shape\":\"CIRCLE\"}";
        assertThatThrownBy(() -> om.readValue(json, AutolabelRequest.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("shape_BBOX_POLYGON은_정상_역직렬화된다")
    void validShapeParsed() throws Exception {
        assertThat(om.readValue("{\"shape\":\"POLYGON\"}", AutolabelRequest.class).shape())
                .isEqualTo(AutolabelShape.POLYGON);
        assertThat(om.readValue("{\"shape\":\"BBOX\"}", AutolabelRequest.class).shape())
                .isEqualTo(AutolabelShape.BBOX);
    }

    @Test
    @DisplayName("shape_미지정이면_null이며_기본값_BBOX로_정규화된다")
    void missingShapeDefaultsBbox() throws Exception {
        AutolabelRequest req = om.readValue("{}", AutolabelRequest.class);
        assertThat(req.shape()).isNull();
        assertThat(req.shapeOrDefault()).isEqualTo(AutolabelShape.BBOX);
    }
}

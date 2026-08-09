package kr.co.cudo.authoring.preset.controller;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PresetRequest.eventTypeCd} 의 <b>표준도메인 정합 가드</b> — 상한은 컬럼과 같은 20 이다.
 *
 * <p><b>배경(드리프트)</b>: 최초 정의(V15)는 {@code VARCHAR(32)} 였으나 V107 이 코드값 표준도메인으로
 * 정합해 {@code LS_LABEL_PRESET.EVNT_TYPE_CD} 를 {@code VARCHAR(20)} 으로 좁혔고 엔티티
 * {@link LsLabelPreset} 도 {@code length=20} 이 됐다. <b>요청 DTO 만 32 로 남아</b> 21~32자 입력이
 * 입력 검증을 통과한 뒤 INSERT 시점에 DB 오류로 새는 상태였다.
 *
 * <p><b>왜 빈 검증(Validator) 단위 테스트인가</b>: 엔드투엔드(MockMvc)로는 이 드리프트를 잡을 수
 * 없다 — 21자 값은 어차피 서비스의 유효 이벤트유형 검증({@code PresetService.validateEventType})에
 * 걸려 <b>같은 400</b> 이 나므로, 상한을 32 로 되돌려도 응답이 달라지지 않아 가드가 무력해진다.
 * 검사 대상이 어노테이션 값 자체이므로 제약을 직접 평가한다.
 *
 * <p>DB 상한을 <b>하드코딩하지 않고</b> 엔티티 {@code @Column(length)} 에서 읽어 비교한다 — 컬럼이
 * 다시 넓어지거나 좁아지면 이 가드가 함께 따라가야 하고, 두 값을 각각 적으면 그때 갈라진다.
 */
class PresetRequestEventTypeCdSizeTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    /** 컬럼 상한(진실원) — 엔티티 매핑에서 읽는다. */
    private static int columnLength() throws NoSuchFieldException {
        Field field = LsLabelPreset.class.getDeclaredField("eventTypeCd");
        return field.getAnnotation(Column.class).length();
    }

    private static PresetController.PresetRequest requestWithEventTypeCd(String eventTypeCd) {
        return new PresetController.PresetRequest("프리셋", "설명", List.of(1L), eventTypeCd);
    }

    @Test
    @DisplayName("이벤트타입코드_상한은_컬럼과_동일한_20자다")
    void 상한은_컬럼과_동일() throws Exception {
        assertThat(columnLength())
                .as("LS_LABEL_PRESET.EVNT_TYPE_CD 는 코드값 표준도메인 VARCHAR(20)")
                .isEqualTo(20);

        // 경계 — 딱 20자는 통과해야 한다(상한을 과도하게 좁히는 반대 방향 회귀도 함께 막는다).
        String exactlyMax = "A".repeat(columnLength());
        assertThat(validator.validate(requestWithEventTypeCd(exactlyMax)))
                .as("컬럼 상한과 같은 길이는 입력 검증을 통과해야 한다")
                .noneMatch(v -> "eventTypeCd".equals(v.getPropertyPath().toString()));
    }

    @Test
    @DisplayName("컬럼폭을_넘는_21자_이벤트타입코드는_입력검증에서_거부된다_DB오류로_새지_않는다")
    void 컬럼폭_초과는_입력검증에서_거부() throws Exception {
        // given — 컬럼 상한 +1. 구 DTO 상한(32)에서는 통과해 INSERT 시점 DB 오류(500)로 샜다.
        String overflow = "A".repeat(columnLength() + 1);

        // when / then
        assertThat(validator.validate(requestWithEventTypeCd(overflow)))
                .as("21자는 입구에서 400 으로 거부돼야 한다 (상한을 32 로 되돌리면 이 단언이 깨진다)")
                .anyMatch(v -> "eventTypeCd".equals(v.getPropertyPath().toString()));
    }

    @Test
    @DisplayName("이벤트타입코드_미지정은_길이제약_대상이_아니다")
    void 미지정은_길이제약_대상아님() {
        // null/빈 문자열은 "미매핑" 의미라 @Size 가 걸리면 안 된다(기존 계약 보존).
        assertThat(validator.validate(requestWithEventTypeCd(null)))
                .noneMatch(v -> "eventTypeCd".equals(v.getPropertyPath().toString()));
        assertThat(validator.validate(requestWithEventTypeCd("")))
                .noneMatch(v -> "eventTypeCd".equals(v.getPropertyPath().toString()));
    }
}

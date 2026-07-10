package kr.co.cudo.authoring.common.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 공공 표준 여부C1 컨버터 — {@link Boolean} 도메인 필드 ↔ CHAR(1) 'Y'/'N' 컬럼.
 *
 * <p>Phase 4 (여부 도메인 CHAR(1) 전환): 저작도구 LS_* 의 BOOLEAN 컬럼
 * (LS_LABEL_PRESET_CODE.BBOX_ENABLED / POLYGON_ENABLED)을 공공 표준 여부C1(CHAR(1))로
 * 통일하면서, 엔티티 필드 타입(boolean)·게터/세터·기존 비즈니스 로직은 불변으로 유지하기 위한
 * 어댑터다. 적용 필드에는 {@code @Convert(converter = YesNoConverter.class)} 와 함께
 * {@code @JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)} 를 지정해 관계형 타입을 CHAR 로 고정한다.
 *
 * <p>매핑 규칙: {@code Boolean.TRUE ↔ 'Y'}, {@code Boolean.FALSE ↔ 'N'}.
 * null 은 양방향 모두 null 로 통과시킨다(NOT NULL 컬럼이라 정상 경로에서는 발생하지 않으며,
 * 매핑 규칙에 없는 미상값을 임의로 'N' 으로 왜곡하지 않기 위함).
 *
 * <p>{@code autoApply = false}(기본) — YN 의미가 아닌 다른 Boolean 필드에 무분별 적용되지 않도록
 * 필드별 명시적 {@code @Convert} 로만 사용한다.
 */
@Converter
public class YesNoConverter implements AttributeConverter<Boolean, String> {

    private static final String YES = "Y";
    private static final String NO = "N";

    @Override
    public String convertToDatabaseColumn(Boolean attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute ? YES : NO;
    }

    @Override
    public Boolean convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return YES.equals(dbData);
    }
}

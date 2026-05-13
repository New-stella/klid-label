package kr.co.cudo.authoring.common.eventtype;

import java.util.Arrays;
import java.util.Optional;

/**
 * 영상 이벤트 타입 — 시스템 단일 진실(SoT).
 *
 * <p>DB 컬럼 {@code LS_DATA_RAW.EVNT_TYPE_CD} / {@code LS_LABEL_PRESET.EVNT_TYPE_CD} 값과
 * FE 표시 라벨을 본 enum 하나로 통일한다.
 *
 * <p>6 종 운영: 쓰러짐 / 폭력 / 교통사고 / 이상행동(유괴) / 침수 / 산불.
 */
public enum EvntType {
    EVT_FALL("쓰러짐"),
    EVT_VIOLENCE("폭력"),
    EVT_ACCIDENT("교통사고"),
    EVT_ABNORMAL("이상행동(유괴)"),
    EVT_FLOOD("침수"),
    EVT_FIRE("산불");

    private final String label;

    EvntType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public String getCode() {
        return name();
    }

    /**
     * 코드 문자열로 enum 을 조회한다. 매칭 없을 시 {@code Optional.empty()}.
     *
     * @param code DB 코드 (예: {@code EVT_FALL}). null 안전.
     */
    public static Optional<EvntType> ofCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(e -> e.name().equals(code))
                .findFirst();
    }
}

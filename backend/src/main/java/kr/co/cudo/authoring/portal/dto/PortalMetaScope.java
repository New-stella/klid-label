package kr.co.cudo.authoring.portal.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 메타 원소가 <b>영상 축인지 프레임 축인지</b>.
 *
 * <p>한 응답에 두 축이 함께 담기므로 원소마다 표시한다. ★<b>저장할 때 그 축으로 되돌려 보내야
 * 영상 축 값이 프레임에 매달리지 않는다</b> — 영상 축 원소를 프레임 참조와 함께 적재하면 없는
 * 프레임을 가리키는 행이 된다.
 *
 * @design API-234
 * @design API-235
 */
public enum PortalMetaScope {

    /** 영상 축 — 적재 시 프레임 참조를 <b>비운다</b>. */
    VIDEO,
    /** 프레임 축 — 적재 시 대상 프레임을 채운다. */
    FRAME;

    /** 계약 표기는 소문자다. */
    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 계약 표기 → enum. 모르는 값은 {@code null} 이 아니라 예외로 떨어져 400 이 된다
     * (fail-closed — 모르는 축을 임의로 한쪽에 배정하면 값이 엉뚱한 자리에 적재된다).
     */
    @JsonCreator
    public static PortalMetaScope from(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("scope 는 필수입니다.");
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "video" -> VIDEO;
            case "frame" -> FRAME;
            default -> throw new IllegalArgumentException("허용되지 않은 scope 입니다.");
        };
    }
}

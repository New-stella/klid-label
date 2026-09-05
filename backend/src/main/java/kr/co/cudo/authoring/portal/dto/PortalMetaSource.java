package kr.co.cudo.authoring.portal.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaResponse;

/**
 * 메타 원소의 <b>원본 쪽 값이 무엇이었는지</b>.
 *
 * <h3>★ {@code overridden} 과 다른 축이다 — 하나로 합치지 말 것</h3>
 * <p>{@code overridden} 은 <b>내 값이 덮었는가</b>이고 이 값은 <b>덮인 쪽이 무엇이었는가</b>다.
 * 둘을 합치면 「자동으로 채워져 있던 값을 사람이 바꿨다」를 화면이 표현하지 못한다.
 *
 * <h3>왜 이 축이 필요한가</h3>
 * <p>이 창구가 담는 축 가운데 촬영환경·프레임 설명·개인정보 판정 셋은 원장의 <b>컬럼</b>에서 오며,
 * 그 축의 유효값은 <b>수동 저장값이 있으면 그 값, 없으면 자동으로 계산한 값</b>이다. 값만 내려주면
 * 화면은 그 둘을 구분하지 못하고, 자동 계산값을 그대로 되돌려 보내 <b>사람의 판정으로 승격</b>시킨다.
 *
 * <h3>어휘를 복제하지 않는다</h3>
 * <p>{@link #MANUAL}·{@link #DERIVED} 의 표기는 원장 쪽 창구
 * ({@link VideoPrivacyMetaResponse#SOURCE_MANUAL}·{@link VideoPrivacyMetaResponse#SOURCE_DERIVED})가
 * 소유한다 — 리터럴을 여기서 다시 선언하면 한쪽만 바뀌어 어긋난다. {@link #STORED}·{@link #NONE} 은
 * 그 창구에 없는 축이라 이 계약이 소유한다.
 *
 * @design API-234
 */
public enum PortalMetaSource {

    /** 원본에서 <b>사람이 직접 고른</b> 값. */
    MANUAL(VideoPrivacyMetaResponse.SOURCE_MANUAL),
    /** 원본에 저장값이 없어 <b>자동으로 계산한</b> 값. */
    DERIVED(VideoPrivacyMetaResponse.SOURCE_DERIVED),
    /** 출처 구분이 없는 축의 저장값(시계열 메타 등). */
    STORED("STORED"),
    /** 원본에 <b>값 자체가 없다</b> — 사용자가 새로 더한 항목이거나 아직 아무도 채우지 않은 칸. */
    NONE("NONE");

    private final String wireValue;

    PortalMetaSource(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}

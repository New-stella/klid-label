package kr.co.cudo.authoring.eventtype.dto;

import kr.co.cudo.authoring.batch.policy.PresetResolution;

/**
 * 이벤트유형 관리 목록의 <b>연결 상태 거르기</b> 값. [@design API-185] [@design AC-116]
 *
 * <h3>응답 enum 과 값 집합이 다르다 — 합치지 말 것 (Critical)</h3>
 * <p>여기에는 {@link #WITHHELD} 가 하나 더 있고, 그 값은 <b>거르기 전용</b>이라
 * {@link PresetLinkStatus} 필드로는 절대 나가지 않는다. 두 enum 을 하나로 합치면 응답에
 * {@code WITHHELD} 가 실릴 수 있는 형이 되고, 화면은 존재하지 않는 상태를 분기해야 한다.
 *
 * <h3>왜 WITHHELD 라는 값이 필요한가</h3>
 * <p>「오토라벨이 보류되는 상태가 무엇인가」의 정의를 <b>서버가 소유</b>하기 위해서다. 이 값이 없으면
 * 화면이 {@code LINKED_INEFFECTIVE} 와 {@code UNLINKED} 를 스스로 합쳐 보내야 하는데, 그러면 보류
 * 조건이 바뀔 때(예: 제외 선언이 새로 갈릴 때) 배치와 화면이 조용히 어긋난다. 실제로
 * {@link PresetLinkStatus#LINKED_EXCLUDED} 는 상태 이름만 보면 "연결됨"이라 화면이 조합하면 놓치기 쉽다.
 *
 * <h3>판정은 배치 정책이 한다</h3>
 * <p>{@link #WITHHELD} 는 상태 값들을 나열해 비교하지 않고 {@link PresetResolution#isWithheld()} 를
 * 그대로 묻는다 — 보류 여부의 진실원은 그 술어 하나다.
 */
public enum PresetLinkStatusFilter {

    /** 프리셋이 있고 실효하는 유형만. */
    LINKED(PresetLinkStatus.LINKED),

    /** 라벨을 담지 않은 프리셋(오토라벨 제외 선언) 유형만. */
    LINKED_EXCLUDED(PresetLinkStatus.LINKED_EXCLUDED),

    /** 프리셋은 있으나 실효하지 않는 유형만. */
    LINKED_INEFFECTIVE(PresetLinkStatus.LINKED_INEFFECTIVE),

    /** 프리셋이 없는 유형만. */
    UNLINKED(PresetLinkStatus.UNLINKED),

    /**
     * <b>오토라벨이 보류되는 유형 전체</b> — 조치가 필요한 것만 모아 보는 값이다.
     * 제외 선언({@link PresetLinkStatus#LINKED_EXCLUDED})은 보류를 유발하지 않으므로 포함하지 않는다.
     */
    WITHHELD(null);

    /** 이 값이 겨냥하는 화면 상태. {@link #WITHHELD} 는 단일 상태가 아니므로 null 이다. */
    private final PresetLinkStatus target;

    PresetLinkStatusFilter(PresetLinkStatus target) {
        this.target = target;
    }

    /**
     * 이 거르기 값이 주어진 해석 결과를 통과시키는가.
     *
     * @param resolution 프리셋 해석 결과 — 판정의 단일 진실원
     */
    public boolean matches(PresetResolution resolution) {
        if (this == WITHHELD) {
            return resolution.isWithheld();
        }
        return target == PresetLinkStatus.from(resolution.status());
    }
}

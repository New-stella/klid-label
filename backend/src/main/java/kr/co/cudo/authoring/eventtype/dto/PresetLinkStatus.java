package kr.co.cudo.authoring.eventtype.dto;

import kr.co.cudo.authoring.batch.policy.PresetResolutionStatus;

/**
 * 이벤트유형 <b>관리 화면</b>이 보여주는 프리셋 <b>연결 상태</b>. [@design API-185] [@design ADR-054]
 *
 * <h3>저장값이 아니다</h3>
 * <p>응답 시점에 프리셋 해석 결과({@link PresetResolutionStatus})에서 <b>파생</b>한다. 컬럼도
 * 캐시 항목도 아니다 — 프리셋을 등록하면 다음 조회부터 곧바로 바뀌어야 하기 때문이다.
 *
 * <h3>★판정을 여기서 다시 유도하지 않는다 (Critical)</h3>
 * <p>이 enum 은 <b>해석 사유 → 화면 상태</b> 번역만 한다. 프리셋을 다시 조회하거나 라벨 매핑을 다시
 * 검사하면 배치와 화면이 각각 판정을 갖게 되어 규칙이 바뀔 때 한쪽만 낡는다(이 저장소의 반복 결함).
 * 판정의 단일 진실원은 {@code PresetLabelLookupService.resolve} 다.
 *
 * <h3>사유가 여섯인데 상태가 넷인 이유</h3>
 * <p>화면이 구분해야 하는 것은 "무엇을 고쳐야 하는가" 넷뿐이다 — 고칠 것 없음(LINKED) / 사람이 일부러
 * 뺐음(LINKED_EXCLUDED) / 등록해 뒀는데 안 먹음(LINKED_INEFFECTIVE) / 등록 안 함(UNLINKED).
 * 미연결과 미매핑은 조치가 같아(프리셋의 라벨을 검출 클래스에 매핑) 한 상태로 접는다.
 */
public enum PresetLinkStatus {

    /** 프리셋이 있고 <b>실효</b>한다 — 오토라벨이 그 기준으로 수행된다. */
    LINKED,

    /**
     * 프리셋은 있으나 <b>라벨을 하나도 담지 않았다</b> — 그 유형을 오토라벨 대상에서 뺀
     * <b>사람의 선언</b>이다. 보류를 유발하지 않으므로 {@link PresetLinkStatusFilter#WITHHELD}
     * 거르기에 들어가지 않는다. [@design AC-119]
     */
    LINKED_EXCLUDED,

    /**
     * 프리셋은 있으나 <b>실효하지 않는다</b> — 담긴 라벨이 마스터에 연결되지 않았거나, 연결은 됐으나
     * 전부 AI 검출 클래스에 매핑돼 있지 않다. 운영자는 필터를 걸었다고 믿는데 실제로는 걸리지 않는
     * 상태라 <b>사고</b>이며 오토라벨이 보류된다. 위 제외 선언과 반드시 구분한다. [@design AC-114]
     */
    LINKED_INEFFECTIVE,

    /** 그 유형(정확히는 그 유형이 속한 표시명 그룹의 대표코드)에 걸린 프리셋이 없다. */
    UNLINKED;

    /**
     * 프리셋 해석 사유 → 화면 상태.
     *
     * <p>{@code default} 절을 두지 않는다 — 사유가 늘어나면 <b>컴파일이 깨져</b> 이 대응을 다시 보게
     * 하려는 것이다. 조용한 폴백을 두면 새 사유가 임의의 상태로 뭉개진다.
     *
     * <p>{@link PresetResolutionStatus#EVENT_TYPE_UNREGISTERED} 는 <b>이 목록에서 정상적으로는 나오지
     * 않는다</b>(등록된 유형만 보여주므로). 유형 그룹 캐시와 마스터 조회 사이의 좁은 경합 창에서만
     * 도달 가능하며, 그때도 "프리셋을 특정할 수 없다"는 사실은 같으므로 {@link #UNLINKED} 로 낮춘다 —
     * 예외를 던지면 관리 화면 전체가 500 이 되고, 보류 여부 판정({@code PresetResolution#isWithheld})
     * 결과도 UNLINKED 와 같아 거르기가 어긋나지 않는다.
     */
    public static PresetLinkStatus from(PresetResolutionStatus status) {
        return switch (status) {
            case RESOLVED -> LINKED;
            case PRESET_EMPTY -> LINKED_EXCLUDED;
            case PRESET_UNLINKED, PRESET_UNMAPPED -> LINKED_INEFFECTIVE;
            case PRESET_ABSENT, EVENT_TYPE_UNREGISTERED -> UNLINKED;
        };
    }
}

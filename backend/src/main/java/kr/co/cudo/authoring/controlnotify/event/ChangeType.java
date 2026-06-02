package kr.co.cudo.authoring.controlnotify.event;

import java.util.Set;

/**
 * 관제서버 outbound TASK_MODIFIED 통지의 변경 종류(changeType) 표준 값 집합.
 *
 * <p>계약값은 {@code LABEL_ADDED | LABEL_UPDATED | LABEL_DELETED | META_UPDATED} 이며,
 * {@link TaskModifiedEvent#changeType()} → {@link kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload#changeTypes()}
 * 로 그대로 관제서버에 실린다. 매직스트링 발행으로 인한 계약 위반을 막기 위해 발행 측(LabelService,
 * MetaService)·테스트가 본 상수를 공유한다.
 */
public final class ChangeType {

    /** 프레임에 라벨이 신규 추가됨. */
    public static final String LABEL_ADDED = "LABEL_ADDED";
    /** 프레임의 기존 라벨이 수정됨. */
    public static final String LABEL_UPDATED = "LABEL_UPDATED";
    /** 프레임의 라벨이 삭제됨. */
    public static final String LABEL_DELETED = "LABEL_DELETED";
    /** 프레임의 시계열 메타가 수정됨. */
    public static final String META_UPDATED = "META_UPDATED";

    /** 계약상 허용되는 표준 changeType 전체 집합 (검증용). */
    public static final Set<String> ALL =
            Set.of(LABEL_ADDED, LABEL_UPDATED, LABEL_DELETED, META_UPDATED);

    private ChangeType() {
    }
}

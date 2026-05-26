package kr.co.cudo.authoring.controlnotify.event;

/**
 * Phase 2 — 라벨/메타 수정 도메인 이벤트.
 *
 * <p>검수 완료 후 라벨/메타가 수정될 때 발행되어 outbound TASK_MODIFIED 통지를 트리거한다.
 *
 * @param rawSn      영상 단위 식별자 (LS_DATA_RAW.RAW_SN)
 * @param srcSn      변경 프레임 ID (LS_DATA_SRC.SRC_SN)
 * @param changeType 변경 종류 (LABEL_ADDED, LABEL_UPDATED, LABEL_DELETED, META_UPDATED)
 * @param modifierNo 수정자 번호
 */
public record TaskModifiedEvent(
        Long rawSn,
        Long srcSn,
        String changeType,
        Long modifierNo
) {}

package kr.co.cudo.authoring.augment.dto;

/**
 * 증강 잡(영상 단위 그룹) 집계 상태 — FE {@code AugmentJobStatus} 계약(4값)과 1:1 정합.
 *
 * <p>LS_DATA_AUG 는 (영상 대표프레임 SRC_SN × AUG_TYPE_CD) per-row 구조이며 잡/배치 식별자가 없어
 * 영상(SRC_SN 그룹) 단위로 per-row 상태를 아래 규칙으로 집계한다(매직스트링 금지):
 *
 * <ul>
 *   <li>{@link #FAILED} — 그룹 내 하나라도 dead-letter(영구 실패, DEAD_LETTER_AT) 인 경우</li>
 *   <li>{@link #COMPLETED} — 그룹 전부가 종료 상태(AUG_PROC_STTS_CD=ACCEPTED/REJECTED) 인 경우</li>
 *   <li>{@link #IN_PROGRESS} — 일부만 종료(종료 1건 이상 + 미종료 1건 이상) 인 경우</li>
 *   <li>{@link #REQUESTED} — 전부 최초 PENDING 인 경우</li>
 * </ul>
 */
public enum AugmentJobStatus {
    REQUESTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

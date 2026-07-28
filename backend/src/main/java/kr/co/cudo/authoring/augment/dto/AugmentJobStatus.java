package kr.co.cudo.authoring.augment.dto;

/**
 * 증강 잡(영상 단위 그룹) 집계 상태 — FE {@code AugmentJobStatus} 계약(4값)과 1:1 정합.
 *
 * <p>LS_DATA_AUG 는 (영상 대표프레임 SRC_SN × AUG_TYPE_CD) per-row 구조이며 잡/배치 식별자가 없어
 * 영상(SRC_SN 그룹) 단위로 per-row 상태를 아래 규칙으로 집계한다(매직스트링 금지):
 *
 * <ul>
 *   <li>{@link #FAILED} — 그룹 내 하나라도 <b>처리 실패</b>(dead-letter, DEAD_LETTER_AT) 인 경우</li>
 *   <li>{@link #COMPLETED} — 그룹 전부가 종료 상태(AUG_PROC_STTS_CD=ACCEPTED/REJECTED) 인 경우</li>
 *   <li>{@link #IN_PROGRESS} — 일부만 종료(종료 1건 이상 + 미종료 1건 이상) 인 경우</li>
 *   <li>{@link #REQUESTED} — 전부 최초 PENDING 인 경우</li>
 * </ul>
 *
 * <p><b>{@code REJECTED} 는 실패 판정 축이 아니다</b>(Phase 8-B): 검수 결과 축(AUG_PROC_STTS_CD)의
 * REJECTED 에는 REVIEWER 의 정상 반려와 외부 처리 실패 롤업이 함께 들어 있어 둘을 구분할 수 없다.
 * 실패는 처리 실패 전용 마커(DEAD_LETTER_AT)로만 판정한다. 외부 처리 축(LS_DATA_AUG_JOB.JOB_STTS_CD)
 * 은 또 다른 코드 공간이며 이 집계에 직접 섞이지 않는다.
 */
public enum AugmentJobStatus {
    REQUESTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

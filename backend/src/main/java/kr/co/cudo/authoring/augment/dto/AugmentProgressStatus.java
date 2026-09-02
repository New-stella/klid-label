package kr.co.cudo.authoring.augment.dto;

/**
 * FE 진행상태 화면({@code GET /v1/augments/{id}/progress})이 표시하는 <b>외부 처리 축</b> 상태.
 *
 * <p>「생성형 AI API 연동명세서 v1.3」 §3.2 상태머신({@code LS_DATA_AUG_JOB.JOB_STTS_CD})과 같은
 * 코드 공간이며, 증강 1건이 여러 청크 job 으로 나뉘므로 <b>청크 집계 결과</b>를 담는다.
 *
 * <h3>★ {@link AugmentJobStatus} 를 확장하지 않는 이유 (S7 — E-06 재발 방지)</h3>
 * <p>{@link AugmentJobStatus}(REQUESTED/IN_PROGRESS/COMPLETED/FAILED)는 <b>영상 그룹 집계</b> 전용이고
 * 판정 축이 {@code AUG_PROC_STTS_CD}(검수 결과) + {@code DEAD_LETTER_AT}(처리 실패 마커)다. 여기에
 * 외부 처리 상태를 섞으면 "롤업 REJECTED 가 COMPLETED 로 오집계" 됐던 결함(E-06)이 재현된다.
 * 두 enum 은 <b>다른 축</b>이며 서로 값을 빌려오지 않는다.
 *
 * <h3>집계 규칙 (fail-closed)</h3>
 * <ol>
 *   <li>증강 행이 {@code CANCELED} 면 무조건 {@link #CANCELED} — 사용자 취소가 최우선 사실이다.</li>
 *   <li>전 job 종결: 하나라도 FAILED → {@link #FAILED}, 아니면 하나라도 CANCELED → {@link #CANCELED},
 *       그 외 {@link #SUCCEEDED}. ("부분 실패 = 전체 실패" — {@code AugmentJobRollup} 과 같은 태도)</li>
 *   <li>비종결 job 잔존: 하나라도 RUNNING → {@link #RUNNING}, 아니면 {@link #RECEIVED}.</li>
 * </ol>
 */
public enum AugmentProgressStatus {

    /** 위탁 접수(외부가 아직 처리를 시작하지 않음). */
    RECEIVED,
    /** 외부 처리중. */
    RUNNING,
    /** 전 청크 성공 종결. */
    SUCCEEDED,
    /** 하나 이상 실패 종결(부분 실패 = 전체 실패). */
    FAILED,
    /** 취소 종결. */
    CANCELED
}

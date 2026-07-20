package kr.co.cudo.authoring.version.entity;

/**
 * LS_DATA_LBL_HSTRY.CHG_KIND_CD 변경종류 코드 (매직스트링 금지 — enum 관리).
 *
 * <ul>
 *   <li>{@link #ADDED}   : 라벨 신규 추가 (bulkUpsert id == null).</li>
 *   <li>{@link #UPDATED} : 기존 라벨 수정 (bulkUpsert id != null).</li>
 *   <li>{@link #DELETED} : 라벨 삭제 (비식별 신고 시 영상 전체 라벨 삭제 등).</li>
 * </ul>
 *
 * DB 에는 {@link #name()} (UPPER_SNAKE) 문자열로 적재된다.
 */
public enum LabelChangeKind {
    ADDED,
    UPDATED,
    DELETED
}

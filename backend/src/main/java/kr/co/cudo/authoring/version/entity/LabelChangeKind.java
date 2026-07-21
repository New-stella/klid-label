package kr.co.cudo.authoring.version.entity;

/**
 * 라벨 변경 종류 코드 (매직스트링 금지 — enum 관리).
 *
 * <p>V114 저장이벤트 재구조화 이후 {@code LS_DATA_LBL_HSTRY.CHG_KIND_CD} 컬럼은 제거됐다
 * ('라벨 1건=1행' → '저장 이벤트=1행 + diff 페이로드'). 이 enum 은 이제 저장 이벤트 diff
 * 페이로드({@code CHG_DTL_CN}) 안의 항목별 {@link LabelChange#kind()} 로 쓰이며,
 * 건수 집계({@code ADD_CNT}/{@code MDFCN_CNT}/{@code DEL_CNT}) 산정 기준이 된다.
 *
 * <ul>
 *   <li>{@link #ADDED}   : 라벨 신규 추가 (bulkUpsert id == null · 첫 저장은 전부 ADDED).</li>
 *   <li>{@link #UPDATED} : 기존 라벨 수정 (bulkUpsert id != null).</li>
 *   <li>{@link #DELETED} : 라벨 삭제 (프레임 전체 교체 시 요청에서 빠진 라벨 · 비식별 신고 삭제 등).</li>
 * </ul>
 *
 * <p>diff 페이로드 JSON 에는 {@link #name()} (UPPER_SNAKE) 문자열로 직렬화된다.
 */
public enum LabelChangeKind {
    ADDED,
    UPDATED,
    DELETED
}

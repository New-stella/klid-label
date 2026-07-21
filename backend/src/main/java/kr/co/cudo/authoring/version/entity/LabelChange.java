package kr.co.cudo.authoring.version.entity;

/**
 * 저장 이벤트 안의 라벨 변경 1건 — 종류(ADDED/UPDATED/DELETED) + before/after 스냅샷 diff.
 *
 * <p>여러 {@link LabelChange} 가 하나의 저장 이벤트({@link LsDataLblHstry}) 로 묶여
 * {@code CHG_DTL_CN}(JSON) 에 직렬화된다. "라벨 1건=1행" 이 아니라 "저장 이벤트=1행 + diff 페이로드".
 *
 * <ul>
 *   <li>{@code lblSn}     : 대상 라벨 LS_DATA_LBL.LBL_SN (삭제 후에도 식별 보존).</li>
 *   <li>{@code kind}      : 변경 종류.</li>
 *   <li>{@code labelName} : 라벨명(빠른 요약 노출용).</li>
 *   <li>{@code before}    : 변경 전 스냅샷 (ADDED 는 null).</li>
 *   <li>{@code after}     : 변경 후 스냅샷 (DELETED 는 null).</li>
 * </ul>
 *
 * <p>불변(record) 값객체. Jackson 역직렬화는 canonical constructor 로만 수행(보안 CWE-502).
 */
public record LabelChange(
        Long lblSn,
        LabelChangeKind kind,
        String labelName,
        LabelSnapshot before,
        LabelSnapshot after
) {

    /** 신규 추가 변경 — before=null. */
    public static LabelChange added(Long lblSn, String labelName, LabelSnapshot after) {
        return new LabelChange(lblSn, LabelChangeKind.ADDED, labelName, null, after);
    }

    /** 수정 변경 — before/after 모두 보유(Phase 1 에선 before 가 null 일 수 있음). */
    public static LabelChange updated(Long lblSn, String labelName, LabelSnapshot before, LabelSnapshot after) {
        return new LabelChange(lblSn, LabelChangeKind.UPDATED, labelName, before, after);
    }

    /** 삭제 변경 — after=null. */
    public static LabelChange deleted(Long lblSn, String labelName, LabelSnapshot before) {
        return new LabelChange(lblSn, LabelChangeKind.DELETED, labelName, before, null);
    }
}

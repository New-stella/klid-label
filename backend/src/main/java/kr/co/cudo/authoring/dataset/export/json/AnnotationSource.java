package kr.co.cudo.authoring.dataset.export.json;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;

/**
 * {@link NiaAnnotation} 을 만드는 데 필요한 <b>최소 입력</b> — 라벨 저장소(엔티티)에서 분리된 좁은 형태.
 *
 * <p>같은 어노테이션 문서를 만드는 저장소가 둘이다: 내부 파이프라인 라벨({@link LsDataLbl})과 포털
 * 사용자 작업 라벨(포털 전용 저장소). 두 엔티티는 타입이 다르지만 어노테이션 항목을 만드는 데 필요한
 * 것은 <b>다섯 값</b>뿐이라, 그 다섯을 이 형태로 받으면 {@link LabelToAnnotationMapper} 의 판정
 * (도형 유형 분기 · 좌표 파싱 · bbox 바운딩)을 <b>한 벌만</b> 유지할 수 있다.
 *
 * <p>★ 판정을 복제하지 말 것 — 복제하면 두 산출물의 좌표 해석이 갈리고, 한쪽만 고쳐질 때 조용히
 * 어긋난다. 그것이 이 형태를 도입해 없애려는 결함이다.
 *
 * <p>포털 저장소가 이 형태로 넘어오는 값 중 {@code labelId}·{@code trackId} 는 뒤늦게 추가된 컬럼이라
 * 그 이전에 저장된 행에서는 {@code null} 이다. <b>지어내 채우지 않는다</b> — 라벨명 소급 매칭은
 * 동명이인·비활성 마스터 오매칭 위험이 있어 두지 않기로 확정돼 있다.
 *
 * @param id         어노테이션 식별자 원천(내부=LS_DATA_LBL.LBL_SN / 포털=LS_PORTAL_USER_LABEL.USER_LBL_SN)
 * @param lblTypeCd  도형 유형 코드 (BBOX/TRACK · POLYGON/SEGMENT · SKELETON)
 * @param pointCn    좌표 직렬화 문자열
 * @param labelId    라벨 마스터 식별자 — {@code category_id} 조달처. 미연결이면 null
 * @param trackId    트랙 식별자 — {@code track_id} 조달처. 트랙 미소속이면 null
 *
 * @design API-203
 * @design ERD-018
 */
public record AnnotationSource(
        Long id,
        String lblTypeCd,
        String pointCn,
        Long labelId,
        String trackId
) {

    /** 내부 파이프라인 라벨 → 좁은 입력. {@code null} 입력은 {@code null} 로 그대로 통과시킨다. */
    public static AnnotationSource of(LsDataLbl lbl) {
        if (lbl == null) {
            return null;
        }
        return new AnnotationSource(
                lbl.getLblSn(), lbl.getLblTypeCd(), lbl.getPointCn(), lbl.getLabelId(), lbl.getTrackId());
    }
}

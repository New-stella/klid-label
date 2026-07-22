package kr.co.cudo.authoring.evntanno.dto;

import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;

/**
 * event_annotation 조회/저장 응답 DTO — Entity 직접 노출 금지(Mass Assignment/과다노출 방지).
 *
 * <p>{@code annoCn}(jsonb 원문)은 {@link EventAnnotationPayload} 로 파싱해 구조화된 형태로 반환하고,
 * 검토 상태({@code reviewStatus})는 {@code LS_EVNT_ANNO_REVIEW} 의 현재 상태를 함께 실어준다.
 */
public record EventAnnotationInfo(
        Long rawSn,
        Long evntAnnoSn,
        EventAnnotationPayload payload,
        String reviewStatus,
        String regId,
        String mdfcnId
) {

    /**
     * 엔티티 + 검토 상태로 응답 DTO 생성.
     *
     * @param anno         event_annotation 엔티티
     * @param reviewStatus 현재 검토 상태(없으면 null)
     */
    public static EventAnnotationInfo from(LsEvntAnno anno, String reviewStatus) {
        return new EventAnnotationInfo(
                anno.getRawSn(),
                anno.getEvntAnnoSn(),
                EventAnnotationPayload.fromJson(anno.getAnnoCn()),
                reviewStatus,
                anno.getRegId(),
                anno.getMdfcnId()
        );
    }
}

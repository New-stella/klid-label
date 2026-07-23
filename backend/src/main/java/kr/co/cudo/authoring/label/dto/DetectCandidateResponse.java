package kr.co.cudo.authoring.label.dto;

import kr.co.cudo.authoring.label.entity.LsLabel;

/**
 * AI 탐지 후보 응답 DTO — 라벨링 화면 'AI 탐지' 팝업이 소비한다.
 *
 * <p>활성 라벨 마스터를 그대로 노출하되, 각 라벨의 <b>COCO 검출 매핑 여부</b>({@code mapped})를 함께
 * 준다. FE 는 매핑된 라벨만 선택 가능(체크박스 활성)하게 하고 미매핑은 표시하되 비활성화한다.
 * 실제 검출 대상 재구성·재검증은 BE(신뢰 경계, HIGH#1)가 수행하므로 이 응답은 표시·선택 후보일 뿐이다.
 *
 * @param labelId    라벨 마스터 PK
 * @param name       라벨명(한글 등 마스터 명칭)
 * @param color      색상값(#RRGGBB)
 * @param type       형태(BBOX/POLYGON/POINT/SKELETON)
 * @param dtctTypeCd 매핑된 COCO 클래스명(예: person). 미매핑이면 null.
 * @param mapped     COCO 검출 매핑 여부(= dtctTypeCd != null). 선택 가능/불가 판단용.
 */
public record DetectCandidateResponse(
        Long labelId,
        String name,
        String color,
        String type,
        String dtctTypeCd,
        boolean mapped
) {

    /** Entity → 후보 DTO 변환. mapped 는 검출유형 매핑 존재 여부로 파생. */
    public static DetectCandidateResponse from(LsLabel e) {
        String dtct = e.getDtctTypeCd();
        return new DetectCandidateResponse(
                e.getLabelId(),
                e.getLabelNm(),
                e.getColrVl(),
                e.getLabelTypeCd(),
                dtct,
                dtct != null && !dtct.isBlank()
        );
    }
}

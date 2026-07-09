package kr.co.cudo.authoring.eventtype.dto;

import java.util.List;

/**
 * 이벤트 타입 필터 옵션 응답 (Phase 2).
 *
 * <p>관제 이벤트 코드(EV-코드)를 카테고리 단위로 dedup 해 필터 드롭다운 1행으로 제공한다.
 * 같은 카테고리(EVNT_CLS_CD, EVNT_CTGRY_CD)에 속한 상세 코드 여러 개(예: 침수
 * EV01000101/102/103)는 1개 옵션으로 묶이고 {@code memberCodes} 에 원본 코드들이 담긴다.
 *
 * @param categoryKey 카테고리 키 = EVNT_CLS_CD + EVNT_CTGRY_CD (예 "020002")
 * @param label       카테고리 한글명 (MAP CD_TYPE='02' EVNT_NM, 없으면 categoryKey 폴백)
 * @param memberCodes 이 카테고리에 속한 수집대상 EV-코드 목록 (오름차순)
 */
public record EventTypeResponse(String categoryKey, String label, List<String> memberCodes) {

    /** 카테고리 키·라벨·소속 코드로 응답을 생성한다. memberCodes 는 방어적 복사한다. */
    public static EventTypeResponse from(String categoryKey, String label, List<String> memberCodes) {
        return new EventTypeResponse(categoryKey, label, List.copyOf(memberCodes));
    }
}

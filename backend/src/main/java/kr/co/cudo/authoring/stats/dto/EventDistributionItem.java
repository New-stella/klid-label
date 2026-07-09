package kr.co.cudo.authoring.stats.dto;

/**
 * 이벤트 카테고리별 누적 데이터 카운트 (대시보드 분포 그리드).
 *
 * <p>Phase 3 — 관제 코드 체계 기준. {@code eventTypeCd} 는 관제 카테고리 키(EVNT_CLS_CD+
 * EVNT_CTGRY_CD, 예 {@code "020002"})이며 {@code label} 은 카테고리 한글명(예 쓰러짐).
 * 카테고리 구성·순서는 {@code EventTypeService.filterOptions()} 를 따른다.
 */
public record EventDistributionItem(String eventTypeCd, String label, long count) {
}

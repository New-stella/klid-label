package kr.co.cudo.authoring.common.client.dto;

/**
 * KPST {@code GET /retrieve_progress} 요청 바디 DTO.
 *
 * <p>실서버는 GET 요청에도 JSON 바디로 필터({@code reqUserId}/{@code prjId})를 강제하며, 쿼리
 * 파라미터 전용 호출은 {@code HTTP 400 (Invalid JSON body)} 로 거부됨이 실서버에서 확인되었다.
 * 따라서 진행조회 필터는 본 record 로 JSON 직렬화하여 바디로 전송한다. 서버는 camelCase 키
 * ({@code reqUserId}/{@code prjId}) 를 수용한다.
 *
 * @param reqUserId 요청자 ID (필수)
 * @param prjId     프로젝트 ID 필터 (필수 — 폴링 시 단일 프로젝트 대상)
 */
public record KpstProgressRequest(
        String reqUserId,
        Long prjId
) {
}

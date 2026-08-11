package kr.co.cudo.authoring.common.client.dto;

/**
 * KPST {@code GET /retrieve_report} 요청 바디 DTO. [req: R14]
 *
 * <p>규격 §22.3.7 은 필터를 진행조회와 같은 패턴({@code reqUserId} 필수 + {@code userId}/{@code prjId}/
 * 기간)으로 정의하며, 진행조회와 마찬가지로 <b>GET 요청에도 JSON 바디</b>로 전송한다.
 * 저작도구는 영상 1건 = 프로젝트 1개로 운영하므로 {@code prjId} 한 건으로 좁혀 조회한다.
 *
 * <p>{@link KpstProgressRequest} 와 필드 구성이 같지만 <b>별도 엔드포인트의 요청 계약</b>이므로
 * 재사용하지 않는다 — 한쪽 규격이 바뀔 때 다른 쪽이 조용히 끌려가지 않게 한다.
 *
 * @param reqUserId 요청자 ID (필수)
 * @param prjId     프로젝트 ID 필터
 */
public record KpstReportRequest(
        String reqUserId,
        Long prjId
) {
}

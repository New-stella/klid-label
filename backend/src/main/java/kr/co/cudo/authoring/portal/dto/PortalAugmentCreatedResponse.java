package kr.co.cudo.authoring.portal.dto;

import java.time.LocalDateTime;

/**
 * 포털 증강 요청 접수 결과 — {@code 201}.
 *
 * <p>이 응답이 확정적으로 말하는 것은 <b>요청을 접수했다는 사실과 그 요청을 가리키는 식별자</b>
 * 뿐이다. 결과가 준비됐다는 뜻이 아니며, 도착 여부는 요청 현황 목록·단건 조회에서 확인한다.
 *
 * @param augSn       접수된 증강 요청 식별자
 * @param uldSn       요청 대상이 된 업로드 영상 자산 식별자
 * @param requestedAt 요청을 접수한 일시
 * @design API-231
 */
public record PortalAugmentCreatedResponse(Long augSn, Long uldSn, LocalDateTime requestedAt) {
}

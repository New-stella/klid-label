package kr.co.cudo.authoring.portal.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 포털 증강 요청 단건 상세.
 *
 * <h3>핵심은 결과물을 가리키는 자산 식별자다</h3>
 * <p>{@link #resultUldSn} 은 <b>후속 작업 진입(포털 라벨링)과 내려받기 창구가 받는 자산 식별자와
 * 같은 축</b>이다. 결과를 기다리는 중이거나 실패한 요청은 비어 있다.
 *
 * <p>대기·실패도 조회 자체는 성공이며 오류로 다루지 않는다. 채택·반려 결정 단계는 이 경로에 없다 —
 * 확인한 결과물은 그대로 후속 작업과 내려받기로 이어진다.
 *
 * @param failRsnCn   실패 사유. 실패한 요청에서만 채워진다
 * @param resultUldSn 증강 결과물을 가리키는 포털 업로드 자산 식별자
 * @design API-233
 */
public record PortalAugmentDetailResponse(
        Long augSn,
        Long uldSn,
        String orgnlFileNm,
        LocalDateTime requestedAt,
        Map<String, Object> generationCondition,
        String augSttsCd,
        String failRsnCn,
        boolean resultReady,
        Long resultUldSn,
        LocalDateTime resultArrivedAt
) {
}

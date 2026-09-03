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
 * <h3>실패 사유는 <b>사용자에게 보여 줄 수 있는 문장</b>이다</h3>
 * <p>이 응답은 외부 채널로 그대로 나간다. {@link #failRsnCn} 에 내부 오류 코드·파일 경로·데이터 제약
 * 이름·예외 클래스명·연동 상대가 돌려준 원문을 실지 않는다. 판정과 문장은
 * {@code PortalAugmentFailureReason} 한 곳이 정하며, 요청 현황 목록({@code API-232})의 같은 축과
 * <b>이름·형태·제약을 맞춘다</b>.
 *
 * @param failRsnCn   실패 사유. 실패한 요청에서만 채워진다
 * @param resultUldSn 증강 결과물을 가리키는 포털 업로드 자산 식별자
 * @design API-233
 * @design ADR-061
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

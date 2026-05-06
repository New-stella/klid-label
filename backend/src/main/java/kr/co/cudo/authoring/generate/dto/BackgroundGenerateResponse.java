package kr.co.cudo.authoring.generate.dto;

import java.time.LocalDateTime;

/**
 * 외부 시스템의 배경영상 생성 요청 ack 응답.
 *
 * <p>실제 영상 산출은 외부 SFR-06/11 시스템에서 비동기로 진행되며, 본 응답은 요청 접수 ack 만 보장한다.
 */
public record BackgroundGenerateResponse(
        String requestId,
        String genType,
        String status,         // ACCEPTED / REJECTED (외부 ack)
        LocalDateTime requestedAt
) {
}

package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 외부 VLM 서비스 위탁 응답 DTO — Phase 1 신설.
 *
 * <p>비동기 위탁 패턴이므로 본 응답은 **수락(Acknowledge)** 만 의미한다.
 * 실제 시계열 메타 결과는 Phase 2 의 {@code POST /v1/vlm/result} webhook 으로 전달된다.
 *
 * <p>DEV_FIX-1: 외부 응답 무결성 보호용으로 {@link JsonIgnoreProperties}{@code (ignoreUnknown=true)} 적용.
 * 외부 시스템이 추가 필드를 보내도 역직렬화는 실패하지 않으며, 알려진 필드만 안전하게 매핑된다.
 * 값 자체에 대한 화이트리스트/길이/패턴 검증은 {@code VlmClient.validateResponse} 에서 수행한다.
 *
 * @param externalJobId  외부 시스템이 발급한 작업 ID — {@code LS_BATCH_PROC_LOG} 에 저장하여 결과 매핑에 사용.
 * @param idempotencyKey 본 도구가 발급한 멱등 키 — 요청과 1:1 대응 검증용.
 * @param status         외부 시스템의 작업 상태 (예: "ACCEPTED", "QUEUED", "REJECTED", "SKIPPED").
 *                       enabled=false 또는 stub 모드에서는 "SKIPPED" 반환.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VlmTimeseriesResponse(
        String externalJobId,
        String idempotencyKey,
        String status
) {
    /** 외부 위탁 비활성 / stub 모드에서 반환하는 sentinel. */
    public static VlmTimeseriesResponse skipped(String idempotencyKey) {
        return new VlmTimeseriesResponse(null, idempotencyKey, "SKIPPED");
    }
}

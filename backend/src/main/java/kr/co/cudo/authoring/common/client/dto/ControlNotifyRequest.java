package kr.co.cudo.authoring.common.client.dto;

/**
 * Phase 2 — 관제서버 통지 요청 래퍼 DTO.
 *
 * <p>관제서버 inbound SPI 의 공통 요청 형식에 맞춰 페이로드를 래핑한다.
 *
 * @param eventType 이벤트 타입 (TASK_COMPLETED / TASK_MODIFIED)
 * @param payload   JSON 직렬화된 페이로드
 */
public record ControlNotifyRequest(
        String eventType,
        Object payload
) {}

package kr.co.cudo.authoring.controlnotify.dto;

import java.time.Instant;

/**
 * Phase 2 — 관제서버 outbound TASK_COMPLETED 통지 페이로드.
 *
 * <p>CWE-359: PII, 토큰, 원본 이미지 경로 포함 금지.
 * 라벨/메타 본문 자체는 포함하지 않으며, 관제가 필요 시 본 도구 조회 API 로 보강.
 *
 * @param eventType     이벤트 타입 — "TASK_COMPLETED"
 * @param rawSn         작업 ID (= LS_DATA_RAW.RAW_SN)
 * @param reviewerName  검수자 이름
 * @param approvedAt    검수 완료 일시
 * @param totalFrames   총 프레임 수
 * @param labeledFrames 라벨링 완료 프레임 수
 * @param requestId     요청 ID (UUID, idempotency)
 */
public record TaskCompletedPayload(
        String eventType,
        Long rawSn,
        String reviewerName,
        Instant approvedAt,
        int totalFrames,
        int labeledFrames,
        String requestId
) {}

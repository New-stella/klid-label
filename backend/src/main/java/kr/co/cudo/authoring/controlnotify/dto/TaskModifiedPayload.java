package kr.co.cudo.authoring.controlnotify.dto;

import java.time.Instant;
import java.util.List;

/**
 * Phase 2 — 관제서버 outbound TASK_MODIFIED 통지 페이로드.
 *
 * <p>CWE-359: PII, 토큰, 원본 비-비식별 이미지 경로 포함 금지.
 * 변경 프레임 단위 데이터를 포함하여 관제가 별도 조회 없이 차분 적용 가능.
 *
 * @param eventType      이벤트 타입 — "TASK_MODIFIED"
 * @param rawSn          작업 ID (= LS_DATA_RAW.RAW_SN)
 * @param lastModifiedAt 마지막 수정 일시
 * @param frameIds       변경 프레임 ID 목록 (SRC_SN)
 * @param changeTypes    변경 종류 목록 (LABEL_ADDED, LABEL_UPDATED, LABEL_DELETED, META_UPDATED)
 * @param requestId      요청 ID (UUID, idempotency)
 */
public record TaskModifiedPayload(
        String eventType,
        Long rawSn,
        Instant lastModifiedAt,
        List<Long> frameIds,
        List<String> changeTypes,
        String requestId
) {}

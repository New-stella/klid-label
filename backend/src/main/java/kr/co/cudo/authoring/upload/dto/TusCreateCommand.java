package kr.co.cudo.authoring.upload.dto;

import java.time.Instant;

/**
 * TUS POST(세션 생성) 입력 — Upload-Length + Upload-Metadata 파싱 결과.
 *
 * <p>메타데이터는 {@code LS_DATA_RAW} 합류용으로 {@link kr.co.cudo.authoring.dev.dto.AutolabelTestRequest}
 * 와 동일 필드를 받는다 (완료 시 검증 로직 공용화).
 */
public record TusCreateCommand(
        long uploadLength,
        String fileName,
        String vmsClipId,
        String cctvId,
        String eventTypeCd,
        String localGovCd,
        String prvcTypeCd,
        Instant capturedAt
) {
}

package kr.co.cudo.authoring.export.dto;

import kr.co.cudo.authoring.export.entity.LsDataSet;

import java.time.LocalDateTime;

/**
 * Phase 10 — 학습데이터셋 내보내기 상태 응답.
 */
public record ExportStatusResponse(
        Long exportSn,
        Long pjtId,
        String format,
        String status,
        String nasPath,
        String errorMessage,
        LocalDateTime exportedAt,
        LocalDateTime registeredAt
) {

    public static ExportStatusResponse from(LsDataSet entity) {
        return new ExportStatusResponse(
                entity.getExportSn(),
                entity.getPjtId(),
                entity.getExportFormat(),
                entity.getExportSttsCd(),
                entity.getNasPath(),
                entity.getErrorMessage(),
                entity.getExportedAt(),
                entity.getRegisteredAt()
        );
    }
}

package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.entity.LsPortalUserVideo;

import java.time.LocalDateTime;

/**
 * Phase 11 — 포털 업로드 영상 응답 (목록/상세 공용).
 */
public record PortalUploadResponse(
        Long portalVideoSn,
        String fileName,
        Long fileSize,
        String mimeType,
        String thumbnailPath,
        LocalDateTime registeredAt
) {
    public static PortalUploadResponse from(LsPortalUserVideo e) {
        return new PortalUploadResponse(
                e.getPortalVideoSn(),
                e.getFileNm(),
                e.getFileSz(),
                e.getMimeTypeCd(),
                e.getThmbFilePathNm(),
                e.getRegDt()
        );
    }
}

package kr.co.cudo.authoring.portal.tus;

import java.nio.file.Path;

/**
 * Phase 11 — TUS 업로드 세션 핸들 (read-only view).
 *
 * @param fileId      {userId}~{uuid} 형식
 * @param ownerUserId 토큰 sub (소유자)
 * @param size        Upload-Length (총 파일 바이트)
 * @param offset      현재 누적 수신 오프셋
 * @param fileName    Upload-Metadata.filename
 * @param mimeType    Upload-Metadata.filetype
 * @param dataPath    실제 데이터 파일 경로 (bytes)
 * @param metaPath    .meta 사이드카 파일 경로 (JSON)
 */
public record TusFile(
        String fileId,
        String ownerUserId,
        long size,
        long offset,
        String fileName,
        String mimeType,
        Path dataPath,
        Path metaPath
) {
    public boolean isComplete() {
        return offset == size;
    }
}

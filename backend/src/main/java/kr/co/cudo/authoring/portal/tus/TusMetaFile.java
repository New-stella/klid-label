package kr.co.cudo.authoring.portal.tus;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Phase 11 — TUS 사이드카 .meta 파일 직렬화 모델.
 *
 * CVAT 패턴: {@code {file_id}.meta} 에 JSON 으로 저장 → DB 없이 업로드 상태 추적.
 *
 * <pre>
 * {
 *   "fileSize": 5368709120,
 *   "offset": 1048576,
 *   "filename": "video.mp4",
 *   "filetype": "video/mp4",
 *   "ownerUserId": "5"
 * }
 * </pre>
 *
 * 보안 (CWE-639 IDOR 2차 방어):
 *  - ownerUserId 를 .meta 에 함께 저장 → fileId 형식만으로 신뢰하지 않고 verify 가능.
 *
 * 컨벤션: record 사용 (CLAUDE.md "@Setter 금지" 준수).
 *  - Jackson 2.12+ 는 record 자동 매핑 지원 (constructor + accessor).
 *  - isComplete 는 비파생 필드가 아닌 derived value → 별도 메서드 유지.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TusMetaFile(
        long fileSize,
        long offset,
        String filename,
        String filetype,
        String ownerUserId
) {
    public boolean isComplete() {
        return offset == fileSize;
    }
}

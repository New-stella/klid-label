package kr.co.cudo.authoring.common.client.dto;

import java.time.Instant;

/**
 * Gitea 커밋 메타데이터 (PUT /api/v1/repos/{owner}/{repo}/contents/{path} 등의 응답에서 추출).
 *
 *  - sha     : Git SHA-1 (40자 hex)
 *  - message : 커밋 메시지
 *  - author  : 커밋 작성자 (USER_NO 등)
 *  - date    : 커밋 시각 (UTC)
 */
public record CommitResponse(String sha, String message, String author, Instant date) {
}

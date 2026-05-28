package kr.co.cudo.authoring.version.dto;

import java.time.Instant;

/**
 * FE 정합 — 라벨 커밋 결과 응답.
 * FE {@code CommitResponse} 인터페이스와 동일한 와이어 포맷:
 * {@code { commitSha: string, committedAt: string }}
 */
public record CommitResponseDto(String commitSha, Instant committedAt) {

    public static CommitResponseDto of(String sha) {
        return new CommitResponseDto(sha, Instant.now());
    }
}

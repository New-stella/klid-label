package kr.co.cudo.authoring.version.dto;

import jakarta.annotation.Nullable;

/**
 * FE -> BE 라벨 커밋 요청.
 * message 는 선택 — null 이면 VersionService 가 자동 생성.
 */
public record CommitRequest(@Nullable String message) {
}

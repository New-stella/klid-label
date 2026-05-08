package kr.co.cudo.authoring.stats.dto;

import java.time.Instant;

/**
 * 대시보드 공지사항 1건 — FE 측 {@code Notice} 타입과 매칭.
 * V1.x 에서는 공지 도메인이 별도 운영되지 않아 항상 빈 배열로 응답한다 (스텁).
 */
public record NoticeItem(long id, String title, boolean pinned, Instant createdAt) {
}

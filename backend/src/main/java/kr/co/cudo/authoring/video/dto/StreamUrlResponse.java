package kr.co.cudo.authoring.video.dto;

/**
 * 영상 스트림 단기 서명 URL 응답.
 *
 * @param url       서명이 포함된 상대 URL ({@code /api/v1/videos/{rawSn}/stream?exp=...&sig=...})
 * @param expiresAt 만료 epoch-second
 * @param ttlSeconds 발급 TTL(초) — FE 가 만료 직전 재발급 판단에 사용
 */
public record StreamUrlResponse(String url, long expiresAt, long ttlSeconds) {
}

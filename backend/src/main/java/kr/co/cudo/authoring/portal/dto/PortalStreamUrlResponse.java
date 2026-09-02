package kr.co.cudo.authoring.portal.dto;

/**
 * 포털 업로드 영상 재생용 단기 서명 주소. [design: API-239]
 *
 * @param url        서명이 붙은 재생 주소 — 재생 요소에 그대로 물린다
 * @param expiresAt  만료 시각(에포크 초) — 화면이 만료 직전에 미리 다시 받을지 판단하는 데 쓴다
 * @param ttlSeconds 발급 시점부터의 유효 시간(초)
 */
public record PortalStreamUrlResponse(String url, long expiresAt, long ttlSeconds) {
}

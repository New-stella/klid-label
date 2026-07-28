package kr.co.cudo.authoring.common.security.webhook;

/**
 * 웹훅 가드 통과 증거 마커 — <b>positive marker 이중 게이트</b> (S-18 / E-ISSUE-01 수정 방향).
 *
 * <p>경로 정규화만으로는 부족하다. 정규화 규칙이 필터·Security·MVC <b>3벌</b> 존재하는 한 불일치는
 * 형태만 바꿔 재발할 수 있다. 그래서 "어떤 경로 표현으로 라우팅됐든 <b>필터를 통과했다는 증거</b>가
 * 없으면 컨트롤러 진입을 거부" 하는 두 번째 게이트를 둔다.
 *
 * <p>증거는 {@code request.setAttribute} 가 아니라 <b>요청 래퍼 타입</b>이다. attribute 는
 * ERROR/ASYNC dispatch 에서 유실될 수 있으나, 필터가 감싼 래퍼는 요청 객체 자체를 타고 다닌다.
 * 판정은 {@code WebUtils.getNativeRequest(request, WebhookGuardedRequest.class)} 로 수행한다.
 *
 * @see WebhookGateInterceptor
 */
public interface WebhookGuardedRequest {

    /**
     * HMAC 서명 검증까지 통과했는지 여부.
     *
     * <p>{@code false} 이면 크기 상한 · rate limit 등 가드만 통과한 상태다(벤더 무서명 규격 경로).
     */
    boolean isSignatureVerified();
}

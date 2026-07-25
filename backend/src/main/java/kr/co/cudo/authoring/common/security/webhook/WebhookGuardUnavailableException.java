package kr.co.cudo.authoring.common.security.webhook;

/**
 * 웹훅 가드 공유 저장소(nonce) 사용 불가 — <b>fail-closed</b> 시그널.
 *
 * <p>nonce(1회성 서명 소비)는 공유 저장소가 죽으면 replay 를 판정할 수 없다. 이때 요청을 통과시키면
 * replay 방어가 사라지므로 <b>거부</b>한다(S-05 비대칭 정책: nonce=fail-closed / rate limit=fail-open).
 *
 * <p>단, 이는 <b>인증 실패가 아니라 일시적 저장소 장애</b>이므로 401 이 아니라 503 + {@code Retry-After}
 * 로 응답한다. 401 로 응답하면 벤더가 "인증 실패=영구 오류"로 간주해 재시도를 포기하고 결과가
 * 영구 유실될 수 있다.
 */
public class WebhookGuardUnavailableException extends RuntimeException {

    public WebhookGuardUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

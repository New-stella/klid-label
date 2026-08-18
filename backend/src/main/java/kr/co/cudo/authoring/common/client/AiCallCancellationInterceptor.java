package kr.co.cudo.authoring.common.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 온디맨드 AI 추론 요청에 <b>취소 스코프</b>를 매어 준다.
 *
 * <h3>왜 컨트롤러가 아니라 인터셉터인가</h3>
 * <p>네 경로(오토라벨·분할·SAM2 추적·AI 자동 추적)의 컨트롤러 시그니처와 응답 방식을 <b>전혀 건드리지
 * 않기</b> 위해서다. 응답 방식을 바꾸면(동기 → 비동기) 기존 호출·테스트·비동기 제한시간·실행기 예산까지
 * 함께 흔들리는데, 취소를 붙이자고 그 위험을 살 이유가 없다.
 *
 * <p>스코프는 «요청 처리 스레드» 에 매인다. 이 경로들은 요청 스레드에서 그대로 추론을 기다리므로
 * 그것으로 충분하다.
 *
 * <h3>배치 경로에는 매이지 않는다</h3>
 * <p>등록 경로를 온디맨드 4종으로 한정하므로({@code AiCallCancellationWebConfig}) 배치 파이프라인
 * 스레드에는 스코프가 없고, 그러면 {@link CancellableAiCall} 이 기존 블로킹 동작을 그대로 한다.
 *
 * <h3>실패해도 추론을 막지 않는다</h3>
 * <p>취소 식별자가 없거나 형식이 틀리거나 추적 상한에 걸리면 <b>추적하지 않는 스코프</b>가 되어 요청은
 * 평소대로 처리된다. 취소는 편의 기능이고, 그것 때문에 추론이 실패하면 손해가 더 크다.
 */
@Component
@RequiredArgsConstructor
public class AiCallCancellationInterceptor implements HandlerInterceptor {

    /**
     * 취소 식별자 헤더 — 클라이언트가 요청마다 새로 만들어 싣고, 취소 버튼이 같은 값으로 취소 API 를 부른다.
     *
     * <p>본문 필드가 아니라 헤더인 이유: 네 경로의 요청 본문 계약이 서로 다르고(하나는 본문이 아예
     * 선택이다) 본문에 넣으면 네 DTO 를 모두 고쳐야 한다.
     */
    public static final String REQUEST_ID_HEADER = "X-AI-Request-Id";

    private final AiCallCancellationRegistry registry;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        registry.open(request.getHeader(REQUEST_ID_HEADER), currentSubject());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AiCallScope scope = AiCallCancellationRegistry.current();
        if (scope != null) {
            // 등록소에서 지우고 스레드 결속을 푼다. 스레드는 재사용되므로 이 정리를 빠뜨리면
            // 다음 요청이 남의 스코프를 물려받는다.
            scope.close();
        }
        registry.unbind();
    }

    /** 토큰 subject — 인증 필터 이후라 사용할 수 있다. 없으면 «추적하지 않음». */
    private String currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof TokenClaims claims)) {
            return null;
        }
        return claims.sub();
    }
}

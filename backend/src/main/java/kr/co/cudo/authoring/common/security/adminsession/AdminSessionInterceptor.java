package kr.co.cudo.authoring.common.security.adminsession;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@link RequiresAdminSession} 이 붙은 창구에 관리자 단기 유효창을 강제한다. [@design ADR-046]
 *
 * <p>인터셉터는 {@code @RequestBody} 역직렬화 <b>이전</b>에 돌므로, 유효창 없는 요청이 바디 파싱
 * 비용조차 유발하지 않는다(웹훅 2단 게이트와 같은 골격).
 *
 * <p>거부는 예외를 그대로 던져 {@code GlobalExceptionHandler} 로 흘린다 — 여기서 응답을 직접
 * 조립하면 같은 사유의 403 이 <b>두 가지 형태</b>로 나가고, 그 차이가 곧 프로그램적 경로와의
 * 드리프트가 된다.
 */
@Component
@RequiredArgsConstructor
public class AdminSessionInterceptor implements HandlerInterceptor {

    private final AdminSessionGate gate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ASYNC 재디스패치는 최초 디스패치에서 이미 이 게이트를 통과한 요청이다. 컨트롤러가
        // startAsync() 를 쓰면 여기가 한 번 더 도는데, 그때 다시 검증하면 <처리 도중 유효창이 만료된>
        // 정상 요청이 403 으로 끝난다. 디스패치 타입은 컨테이너가 정하며 클라이언트가 위조할 수 없다.
        if (DispatcherType.ASYNC.equals(request.getDispatcherType())) {
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod) || !requiresAdminSession(handlerMethod)) {
            return true;
        }
        gate.requireForRequest(request);
        return true;
    }

    /**
     * 메서드 · 그 메서드를 가진 <b>컨트롤러 클래스</b> 양쪽을 본다.
     *
     * <p>클래스 축을 함께 보는 이유는, 창구 전체를 보호할 때 메서드마다 붙이게 하면 <b>새로 추가된
     * 메서드만 조용히 빠지는</b> 구멍이 열리기 때문이다. 클래스에 한 번 붙이면 하위 창구의 기본이
     * 「보호됨」이 된다.
     */
    private static boolean requiresAdminSession(HandlerMethod handlerMethod) {
        return handlerMethod.hasMethodAnnotation(RequiresAdminSession.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), RequiresAdminSession.class);
    }
}

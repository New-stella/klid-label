package kr.co.cudo.authoring.common.security.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;

import java.io.IOException;

/**
 * 웹훅 <b>이중 게이트</b> 2단 — 컨트롤러 진입 직전 fail-closed 검사 (S-18 / E-ISSUE-01).
 *
 * <p>1단(경로 allowlist 기반 필터)만으로는 부족하다. 경로 정규화 규칙이 필터·Security·MVC 3벌
 * 존재하는 한 불일치는 형태만 바꿔 재발할 수 있다. 본 인터셉터는 <b>경로 문자열 논쟁에서 벗어나</b>
 * "필터를 통과했다는 증거({@link WebhookGuardedRequest} 래퍼)" 유무만 본다.
 *
 * <p>매칭 경로는 {@code WebMvcConfigurer} 에 등록되며 <b>MVC 자신의 경로 매칭</b>을 쓰므로, 라우팅이
 * 성립하는 어떤 표현이든 본 게이트를 반드시 거친다.
 *
 * <ul>
 *   <li>증거 래퍼 부재 → 401 (필터를 우회해 라우팅된 요청)</li>
 *   <li>증거는 있으나 서명 미검증인데 서명 필수 경로 → 401</li>
 * </ul>
 *
 * <p>인터셉터는 {@code @RequestBody} 역직렬화 <b>이전</b>에 실행되므로, 우회 요청이 바디 파싱 비용조차
 * 유발하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookGateInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // ASYNC 재디스패치는 이미 최초 디스패치에서 게이트를 통과한 요청이다. 필터는
        // shouldNotFilterAsyncDispatch()=true 라 재실행되지 않고, 컨트롤러가 startAsync() 를 쓰면
        // 증거 래퍼가 소실될 수 있어 "정상 콜백이 401" 이 된다(DEV_FIX L-2). 디스패치 타입은 컨테이너가
        // 정하며 클라이언트가 위조할 수 없으므로 여기서 건너뛰어도 우회 표면이 아니다.
        if (DispatcherType.ASYNC.equals(request.getDispatcherType())) {
            return true;
        }
        WebhookGuardedRequest guarded = WebUtils.getNativeRequest(request, WebhookGuardedRequest.class);
        if (guarded == null) {
            log.warn("[Webhook] gate blocked — filter marker absent path={}",
                    WebhookProtectedPaths.describePath(request));
            writeUnauthorized(response, "Webhook 인증 필터를 거치지 않은 요청입니다.");
            return false;
        }
        if (WebhookProtectedPaths.requiresSignature(request) && !guarded.isSignatureVerified()) {
            log.warn("[Webhook] gate blocked — signature not verified path={}",
                    WebhookProtectedPaths.describePath(request));
            writeUnauthorized(response, "Webhook 시그니처 검증이 필요합니다.");
            return false;
        }
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "HMAC");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiResponse.error(ErrorCode.UNAUTHORIZED, message)));
    }
}

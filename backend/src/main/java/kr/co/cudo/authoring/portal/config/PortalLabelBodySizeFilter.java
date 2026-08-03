package kr.co.cudo.authoring.portal.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.List;

/**
 * Phase 4 (DEV_FIX M-1) — 포털 라벨 본문 크기 상한 필터.
 *
 * <p>아래 <b>라벨 좌표를 본문으로 받는 경로</b>에 한해 Jackson 역직렬화(본문 버퍼링)
 * <b>이전</b>에 요청 본문 바이트를 조기 검사한다.
 * <ul>
 *   <li>{@code PUT  /v1/portal/uploads/frames/{uldFrmeSn}/labels} — 포털 업로드 자산 라벨 전체교체</li>
 *   <li>{@code POST /v1/portal/user-labels} — 데이터마트 사용자 라벨 저장(3차 QA 보정).
 *       {@code points} 를 좌표 JSON <b>문자열</b>로 받는데 필터 대상이 아니어서 원 결함과 동일
 *       클래스(pre-parse DoS)가 그대로 남아 있었다. 파싱 후 필드 상한
 *       ({@code PortalUserLabelRequest.MAX_POINTS_LENGTH})과 2층으로 방어한다.</li>
 * </ul>
 * 라벨 배열은 서비스에서 개수/좌표 상한을 강제하지만,
 * 그 검증은 본문을 전량 역직렬화한 뒤에야 수행되므로 대용량 페이로드로 파서 메모리를 소진시키는
 * pre-parse DoS(CWE-770 / OWASP API4)를 막지 못한다. 본 필터가 파싱 전에 상한을 적용한다.
 *
 * <ul>
 *   <li>Content-Length &gt; {@code portal.upload.max-label-body-bytes}(기본 2MB, {@link PortalUploadProperties}) → 413.</li>
 *   <li>Content-Length 부재(chunked, -1) → 411 거부. 정상 클라이언트는 JSON 본문에 Content-Length 를
 *       송신하므로 chunked 조기 거부는 안전하며, size-cap 을 우회하는 대용량 chunked 스트림을 차단한다.</li>
 * </ul>
 *
 * <p>Jackson 전역 {@code StreamReadConstraints} 대신 경로 한정 필터를 사용해 타 엔드포인트에 영향을 주지
 * 않는다. {@link OncePerRequestFilter} 는 이중 등록되어도 요청당 1회만 실행된다.
 *
 * <h3>왜 정규식 문자열 매칭이 아니라 PathPattern 인가 (CWE-436 → CWE-770)</h3>
 * <p>구현체는 과거 {@code request.getRequestURI()}(퍼센트 디코딩 <b>전</b> 원문)에 정규식을 매칭해 적용
 * 여부를 판정했다. 반면 Spring MVC 는 <b>디코딩·정규화된 경로</b>로 핸들러를 라우팅하므로
 * {@code /labels} 의 한 글자만 인코딩({@code %6Cabels})하면 <b>필터는 스킵되고 컨트롤러는 정상 실행</b>
 * 되어 본문 상한과 chunked 차단이 통째로 무력화됐다(2차 QA 실증: 12MB 페이로드 · chunked 라벨 교체 성공).
 *
 * <p>따라서 <b>자체 디코딩을 구현하지 않고</b> MVC 라우팅과 동일한 {@link RequestPath} +
 * {@link PathPattern} 으로 판정한다. {@code WebhookProtectedPaths} 가 같은 클래스의 결함(인증 우회)에
 * 대해 채택한 것과 동일한 규약이며, "필터가 보는 경로 = 컨트롤러가 매핑하는 경로"가 구조적으로
 * 같아져야 재발하지 않는다. 자체 디코딩 루프를 넣으면 오히려 이중 인코딩에서 MVC 와 어긋난다.
 *
 * <p><b>trailing slash 변형은 패턴을 하나 더 등록해 덮는다</b>: 후행 슬래시 매칭 여부는 handler mapping
 * 설정에 좌우되는데(실측: {@code /labels/} 가 컨트롤러로 라우팅됨) 필터가 "덜 매칭"하는 순간 그대로
 * 우회가 된다. 상한 필터는 <b>덜 매칭하면 취약, 더 매칭하면 무해</b>(핸들러가 없으면 어차피 404 이던
 * 것이 413 이 될 뿐)하므로 어느 설정에서도 MVC 매칭을 포함하도록 잡는다.
 *
 * <p><b>경로를 다시 파싱하지 않는다</b>: {@code valueToMatch()} 로 이미 디코딩된 값을
 * {@code PathContainer.parsePath} 에 재투입하면 <b>두 번째 디코딩</b>이 일어나 이중 인코딩
 * ({@code %256Cabels})이 {@code labels} 로 접히고, MVC 는 접지 않으므로 판정이 다시 어긋난다.
 */
@Slf4j
@Component
public class PortalLabelBodySizeFilter extends OncePerRequestFilter {

    /**
     * 라벨 PUT 경로 패턴 — {@code PortalUploadLabelController} 의 매핑
     * ({@code @RequestMapping("/v1/portal/uploads")} + {@code @PutMapping("/frames/{uldFrmeSn}/labels")})
     * 과 <b>같은 문자열·같은 파서</b>를 쓴다. 두 번째 항목은 후행 슬래시 변형(클래스 주석 참조).
     */
    private static final List<PathPattern> LABEL_PUT_PATTERNS = List.of(
            parse("/v1/portal/uploads/frames/{uldFrmeSn}/labels"),
            parse("/v1/portal/uploads/frames/{uldFrmeSn}/labels/"));

    /**
     * 데이터마트 사용자 라벨 저장 경로 패턴 — {@code PortalLabelController} 의 매핑
     * ({@code @RequestMapping("/v1/portal")} + {@code @PostMapping("/user-labels")}).
     * 후행 슬래시 변형은 위와 같은 이유로 함께 잡는다(덜 매칭하면 취약, 더 매칭하면 무해).
     */
    private static final List<PathPattern> USER_LABEL_SAVE_PATTERNS = List.of(
            parse("/v1/portal/user-labels"),
            parse("/v1/portal/user-labels/"));

    private static PathPattern parse(String pattern) {
        return PathPatternParser.defaultInstance.parse(pattern);
    }

    private final ObjectMapper objectMapper;
    private final long maxBodyBytes;

    public PortalLabelBodySizeFilter(ObjectMapper objectMapper, PortalUploadProperties properties) {
        this.objectMapper = objectMapper;
        this.maxBodyBytes = properties.maxLabelBodyBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean isPut = "PUT".equalsIgnoreCase(method);
        boolean isPost = "POST".equalsIgnoreCase(method);
        // 본문을 갖는 저장/교체 메서드만 대상. 조회(GET)·삭제는 대상 아니다.
        if (!isPut && !isPost) {
            return true;
        }
        PathContainer path = pathWithinApp(request);
        if (path == null) {
            // 경로 판정 불가 = 가장 엄격하게(fail-closed). 파싱조차 안 되는 URI 는 어떤 핸들러에도
            // 라우팅되지 않으므로 상한을 적용해도 정상 요청에 영향이 없다.
            return false;
        }
        // 라벨 전체교체는 PUT 전용 매핑이라 메서드까지 좁힌다(POST 는 핸들러가 없다).
        if (isPut && matchesAny(LABEL_PUT_PATTERNS, path)) {
            return false;
        }
        // 사용자 라벨 저장은 POST 매핑이지만, 메서드 오지정이 상한 우회가 되지 않도록 PUT 도 함께 잡는다
        // (핸들러가 없으면 어차피 405/404 이던 것이 413 이 될 뿐 — 더 매칭은 무해).
        return !matchesAny(USER_LABEL_SAVE_PATTERNS, path);
    }

    private static boolean matchesAny(List<PathPattern> patterns, PathContainer path) {
        for (PathPattern pattern : patterns) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        // chunked/무 Content-Length(-1) 는 size-cap 을 우회하므로 파싱 전 조기 거부(411).
        if (contentLength < 0) {
            log.warn("[PortalLabel] missing/chunked Content-Length on label PUT");
            writeError(response, HttpServletResponse.SC_LENGTH_REQUIRED, ErrorCode.LENGTH_REQUIRED,
                    "Content-Length 헤더가 필요합니다. (chunked 전송은 허용되지 않습니다)");
            return;
        }
        if (contentLength > maxBodyBytes) {
            log.warn("[PortalLabel] label body too large declared={} limit={}", contentLength, maxBodyBytes);
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE,
                    "라벨 본문은 " + (maxBodyBytes / 1024) + "KB 를 초과할 수 없습니다.");
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(errorCode, message)));
    }

    /**
     * MVC 라우팅과 <b>동일한</b> 애플리케이션 기준 경로(컨텍스트 경로 + 서블릿 경로 접두 제외,
     * 퍼센트 디코딩·정규화 완료).
     *
     * <p>DispatcherServlet 이 이미 파싱·캐시한 {@link RequestPath} 가 있으면 그대로 쓰고(필터는
     * DispatcherServlet 보다 앞이라 대개 없다), 없으면 <b>DispatcherServlet 이 쓰는 것과 같은</b>
     * {@link ServletRequestPathUtils#parseAndCache} 로 파싱한다. 캐시는 즉시 지워 이후
     * DispatcherServlet 의 파싱 동작에 영향을 주지 않는다(등록 순서 무관).
     *
     * <p><b>왜 {@code RequestPath.parse(uri, contextPath)} 를 쓰지 않는가 (CWE-436 잔여, fail-open)</b>:
     * 그 오버로드는 <b>컨텍스트 경로만</b> 반영하고 서블릿 경로 접두({@code spring.mvc.servlet.path})는
     * 반영하지 않는다. Spring MVC 는 {@code ServletRequestPath} 로 <b>contextPath + servletPathPrefix</b>
     * 를 제외한 경로를 라우팅에 쓰므로, 그 설정이 들어오는 순간 "MVC 는 라우팅, 필터는 스킵"이라는
     * 원 결함과 <b>동일 클래스의 우회</b>가 조용히 재발한다. 판정 규약을 두 벌로 두지 않기 위해
     * 자체 파싱을 제거하고 공개 API 하나만 사용한다({@code WebhookProtectedPaths} 와 동일 규약).
     *
     * @return 애플리케이션 기준 경로. 판정 불가 시 {@code null}(호출자가 fail-closed 처리)
     */
    private static PathContainer pathWithinApp(HttpServletRequest request) {
        try {
            if (ServletRequestPathUtils.hasParsedRequestPath(request)) {
                return ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication();
            }
            String uri = request.getRequestURI();
            if (uri == null || uri.isEmpty()) {
                return null;
            }
            try {
                return ServletRequestPathUtils.parseAndCache(request).pathWithinApplication();
            } finally {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        } catch (RuntimeException e) {
            // 원시 URI 를 로그에 남기지 않는다(CWE-117/209) — 예외 클래스명만.
            log.warn("[PortalLabel] path resolution failed reason={}", e.getClass().getSimpleName());
            return null;
        }
    }
}

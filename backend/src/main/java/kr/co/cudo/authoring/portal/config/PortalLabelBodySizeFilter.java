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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Phase 4 (DEV_FIX M-1) — 포털 라벨 전체교체(PUT) 본문 크기 상한 필터.
 *
 * <p>{@code PUT /v1/portal/uploads/frames/{uldFrmeSn}/labels} 경로에 한해 Jackson 역직렬화(본문 버퍼링)
 * <b>이전</b>에 요청 본문 바이트를 조기 검사한다. 라벨 배열은 서비스에서 개수/좌표 상한을 강제하지만,
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
 */
@Slf4j
@Component
public class PortalLabelBodySizeFilter extends OncePerRequestFilter {

    /** 라벨 PUT 경로(컨텍스트 경로 제외 servletPath) — {uldFrmeSn} 세그먼트 1개. */
    private static final Pattern LABEL_PUT_PATH =
            Pattern.compile("^/v1/portal/uploads/frames/[^/]+/labels$");

    private final ObjectMapper objectMapper;
    private final long maxBodyBytes;

    public PortalLabelBodySizeFilter(ObjectMapper objectMapper, PortalUploadProperties properties) {
        this.objectMapper = objectMapper;
        this.maxBodyBytes = properties.maxLabelBodyBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"PUT".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !LABEL_PUT_PATH.matcher(pathWithinApp(request)).matches();
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
     * 컨텍스트 경로(/api) 를 제외한 애플리케이션 내 경로. {@code getServletPath()} 는 일부 환경
     * (MockMvc 등)에서 비어 있을 수 있어, {@code getRequestURI()} 에서 컨텍스트 경로를 직접 제거한다.
     */
    private static String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }
}

package kr.co.cudo.authoring.common.security.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 웹훅 <b>보호 대상 경로 allowlist</b> — 단일 진실원 (E-ISSUE-01 / A-ISSUE-13).
 *
 * <h3>왜 문자열 정확일치가 아니라 PathPattern 인가</h3>
 * <p>구현체는 과거 {@code request.getRequestURI()}(퍼센트 디코딩 <b>전</b> 원문)의 문자열 정확일치로
 * 필터 적용 여부를 판정했다. 반면 Spring MVC 라우팅과 Spring Security 매처는 <b>디코딩된 경로</b>를
 * 사용하므로, {@code /v1/%61ug/callback}(a → %61) 같은 변형은 "필터 스킵 + 컨트롤러 도달" 이 되어
 * 인증이 통째로 우회됐다(CWE-436 Interpretation Conflict → CWE-288 인증 우회). 1차 검증에서
 * 무인증 증강행 전이·신규 영상 생성까지 실증됐다.
 *
 * <p>따라서 본 클래스는 <b>자체 디코딩·정규화를 구현하지 않고</b> Spring MVC 가 라우팅에 쓰는 것과
 * 동일한 {@link RequestPath} + {@link PathPattern} 을 사용한다(필터/Security/MVC 3벌 규칙이
 * 어긋나 재발하는 것을 구조적으로 차단, S-17).
 *
 * <h3>allowlist(보호 대상) 방식</h3>
 * <p>"이 경로들만 보호" 를 열거한다. 접두 패턴({@code /v1/aug/**})이라 하위에 신규 웹훅 엔드포인트가
 * 추가돼도 기본이 <b>보호됨</b>이다(추가 시 열어주는 방향이 아니라 닫혀 있는 방향).
 *
 * <h3>fail-closed</h3>
 * <p>경로 판정 중 예외가 발생하거나 판정이 불가능하면 <b>보호 대상(가장 엄격한 서명 요구)</b> 으로
 * 간주한다(S-13). 예외를 유발하는 URI 하나로 우회가 재현되는 것을 막는다.
 */
@Slf4j
public final class WebhookProtectedPaths {

    /**
     * 생성형 AI(증강) 결과 웹훅 경로 — <b>단일 진실원</b> (Phase 7-A1/A2).
     *
     * <p>「생성형 AI API 연동명세서 v1.1」의 웹훅은 <b>무서명</b> 규격이라 HMAC 대상이 아니다.
     * 요청측(Phase 7-A1)이 {@code callback_url} 조립에 이 상수를 참조하고, 수신 컨트롤러(A2)와
     * 가드 패턴 등록이 같은 상수를 참조한다(경로 드리프트 차단).
     *
     * <p>구 계약 경로({@code /v1/aug/callback}, HMAC 서명 필수)는 Phase 7-A2 에서 컨트롤러·DTO·
     * dev 시뮬레이터와 함께 제거됐다. 서명 필수 목록에도 남기지 않는다 — 수신처 없는 경로를
     * 보호 목록에 두면 "죽은 보안 설정"이 되어 실제 보호 범위를 오독하게 만든다.
     */
    public static final String PATH_GENAI_CALLBACK = "/v1/genai/callback";

    /**
     * VLM describe 콜백 경로 — 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1)상 <b>무서명</b> 규격이라
     * HMAC 대상이 아니다. 다만 무인증 상태의 pre-auth 자원 소모를 막기 위해 본문 크기 상한 ·
     * rate limit · (설정 시) IP allowlist 는 적용한다.
     */
    public static final String PATH_VLM = "/v1/vlm/callback";

    /**
     * 무서명 가드 전용 경로 패턴 문자열 — 인터셉터 등록({@code WebhookGateConfig})이 같은 값을
     * 참조하도록 노출한다. 패턴을 두 벌로 적으면 신규 웹훅 추가 시 한쪽만 갱신돼 2단 게이트가 조용히
     * 미적용되는 드리프트가 생긴다(DEV_FIX L-1).
     */
    public static final String PATTERN_VLM = "/v1/vlm/**";

    /** 생성형 AI(증강) 웹훅 경로 패턴 — 무서명 가드 전용({@link #PATTERN_VLM} 과 동일 취지). */
    public static final String PATTERN_GENAI = "/v1/genai/**";

    /** 2단 게이트(인터셉터)가 덮어야 하는 경로 패턴 전체 — 단일 진실원. */
    public static final List<String> GATE_PATTERNS = List.of(PATTERN_VLM, PATTERN_GENAI);

    /** 메트릭 태그값 — 저카디널리티 고정 라벨(CWE-770 태그 폭주 차단, DEV_FIX M-2). */
    public static final String TAG_VLM = "vlm";
    public static final String TAG_GENAI = "genai";
    public static final String TAG_OTHER = "other";

    /** 경로 판정 불가 시 사용하는 고정 키 — 원시 URI 를 키로 쓰지 않는다(DEV_FIX H-2). */
    public static final String UNRESOLVED_PATH = "unresolved";

    /**
     * HMAC 서명 필수 경로 패턴 — <b>현재 비어 있다</b>(Phase 7-A2).
     *
     * <p>유일한 서명 필수 웹훅이던 구 증강 콜백({@code /v1/aug/**})이 제거되면서 등록 경로가 없다.
     * 목록을 비워도 서명 검증 분기는 살아 있다 — {@link #requiresSignature} 는 <b>경로 판정 불가</b>
     * 시 {@code true} 를 돌려주므로(S-13 fail-closed), URI 파싱이 깨지는 요청은 여전히 서명 요구
     * 경로로 처리되어 401 로 거부된다.
     */
    private static final List<PathPattern> SIGNATURE_REQUIRED = List.of();

    /** VLM 무서명 경로 패턴. */
    private static final List<PathPattern> GUARD_VLM = List.of(parse(PATTERN_VLM));

    /** 생성형 AI(증강) 무서명 경로 패턴. */
    private static final List<PathPattern> GUARD_GENAI = List.of(parse(PATTERN_GENAI));

    /** 서명은 없지만 크기·빈도 가드가 필요한 경로 패턴(벤더/외부 무서명 규격). */
    private static final List<PathPattern> GUARD_ONLY = List.of(parse(PATTERN_VLM), parse(PATTERN_GENAI));

    /** 로그 인젝션(CWE-117) 차단용 — 개행/탭 제거. */
    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    /** 로그에 남기는 경로 최대 길이. */
    private static final int LOG_PATH_MAX = 200;

    private WebhookProtectedPaths() {
    }

    private static PathPattern parse(String pattern) {
        return PathPatternParser.defaultInstance.parse(pattern);
    }

    /**
     * 서명 필수 경로가 <b>하나라도 등록돼 있는가</b>.
     *
     * <p>목록이 비면 HMAC 시크릿은 정상 요청에 한 번도 쓰이지 않는다. 그런데도 시크릿을 기동 필수로
     * 강제하면 운영은 <b>아무 값이나 넣어야만 뜨고, 제거하면 기동이 죽는</b> 상태가 된다 — 보안 통제가
     * 아니라 배포 함정이다(DEV_FIX MEDIUM-3). 그래서 {@code HmacWebhookFilter} 는 이 값이 {@code true}
     * 일 때만 시크릿을 강제한다.
     *
     * <p>강제를 끄더라도 <b>fail-closed 는 그대로다</b>: {@link #requiresSignature} 는 경로 판정 불가 시
     * {@code true} 를 돌려주고, 그 경로는 시크릿이 없으면 필터에서 401 로 거부된다.
     */
    public static boolean hasSignatureRequiredPaths() {
        return !SIGNATURE_REQUIRED.isEmpty();
    }

    /** 서명 검증이 필수인 경로인가. 판정 불가 시 {@code true}(fail-closed). */
    public static boolean requiresSignature(HttpServletRequest request) {
        PathContainer path = pathWithinApplication(request);
        if (path == null) {
            return true; // 판정 불가 = 가장 엄격하게 (fail-closed)
        }
        return matchesAny(SIGNATURE_REQUIRED, path);
    }

    /** 서명 없이 크기·빈도 가드만 적용하는 경로인가. */
    public static boolean requiresGuardOnly(HttpServletRequest request) {
        PathContainer path = pathWithinApplication(request);
        if (path == null) {
            return false; // 판정 불가 시 requiresSignature 가 true 를 반환하므로 여기서는 false
        }
        return matchesAny(GUARD_ONLY, path);
    }

    /**
     * 생성형 AI(증강) 웹훅 경로인가 — 무서명 가드가 <b>VLM 과 다른 정책</b>(전용 IP allowlist·본문
     * 상한)을 골라야 하므로 노출한다. 판정 불가 시 {@code false}(호출자가 fail-closed 처리).
     */
    public static boolean isGenAi(HttpServletRequest request) {
        PathContainer path = pathWithinApplication(request);
        return path != null && matchesAny(GUARD_GENAI, path);
    }

    /** 본 필터가 개입해야 하는 경로인가(서명 필수 + 가드 전용). */
    public static boolean isProtected(HttpServletRequest request) {
        PathContainer path = pathWithinApplication(request);
        if (path == null) {
            return true; // fail-closed
        }
        return matchesAny(SIGNATURE_REQUIRED, path) || matchesAny(GUARD_ONLY, path);
    }

    /**
     * <b>보안 판정용 정규화 경로</b> — MVC 라우팅과 동일한 디코딩·정규화를 거친 표현.
     *
     * <p>nonce 키처럼 <b>보안 결정에 쓰이는 경로</b>는 반드시 이 값을 써야 한다. 원시 URI
     * ({@link #describePath})를 키로 쓰면 {@code /v1/%61ug/callback} · {@code /v1/au%67/callback} 처럼
     * 같은 엔드포인트를 가리키는 인코딩 변형마다 키가 달라져, <b>같은 서명을 변형 경로로 재전송하면
     * replay 가 전부 신규로 판정된다</b>(DEV_FIX H-2, CWE-294 — E-ISSUE-01 과 동일 뿌리의 재발).
     *
     * <p>{@code PathSegment.valueToMatch()} 는 퍼센트 디코딩 + path parameter({@code ;a=b}) 제거를
     * 마친 값이라, 8천여 가지 인코딩 변형이 모두 하나의 키로 수렴한다.
     *
     * @return 정규화 경로. 판정 불가 시 {@link #UNRESOLVED_PATH} (원시 URI 를 노출하지 않는다)
     */
    public static String canonicalPath(HttpServletRequest request) {
        PathContainer path = pathWithinApplication(request);
        if (path == null) {
            return UNRESOLVED_PATH;
        }
        StringBuilder sb = new StringBuilder();
        for (PathContainer.Element element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                sb.append(segment.valueToMatch());
            } else {
                sb.append(element.value());
            }
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    /**
     * 메트릭 태그용 <b>저카디널리티</b> 라벨.
     *
     * <p>경로 문자열을 그대로 태그로 쓰면 공격자가 {@code /v1/aug/AAAA0001..9999} 로 영구 보존되는
     * Meter 를 무한 생성할 수 있다(MeterRegistry 는 evict 하지 않음 — CWE-770, DEV_FIX M-2).
     */
    public static String metricTag(HttpServletRequest request) {
        PathContainer path = pathWithinApplication(request);
        if (path == null) {
            return TAG_OTHER;
        }
        if (matchesAny(GUARD_VLM, path)) {
            return TAG_VLM;
        }
        return matchesAny(GUARD_GENAI, path) ? TAG_GENAI : TAG_OTHER;
    }

    /**
     * <b>로그 전용</b> 경로 문자열 — sanitize + 길이 제한 (CWE-117).
     *
     * <p>퍼센트 디코딩 <b>전</b> 원문이라 "요청이 실제로 어떤 문자열로 들어왔는가" 를 남기는 용도다.
     * <b>보안 판정(nonce 키·매칭·태그)에 사용 금지</b> — 그 용도는 {@link #canonicalPath} /
     * {@link #metricTag} 를 쓴다(DEV_FIX H-2).
     */
    public static String describePath(HttpServletRequest request) {
        String raw;
        try {
            raw = request.getRequestURI();
        } catch (RuntimeException e) {
            return "unparsable";
        }
        return sanitize(raw);
    }

    /** 로그 안전 문자열 변환 — 개행 제거 + 길이 절단. */
    public static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        String cleaned = LOG_UNSAFE.matcher(value).replaceAll("_");
        return cleaned.length() <= LOG_PATH_MAX ? cleaned : cleaned.substring(0, LOG_PATH_MAX) + "...";
    }

    private static boolean matchesAny(List<PathPattern> patterns, PathContainer path) {
        for (PathPattern pattern : patterns) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * MVC 라우팅과 <b>동일한</b> 경로 표현을 얻는다.
     *
     * <p>DispatcherServlet 이 이미 파싱·캐시한 {@link RequestPath} 가 있으면 그것을 그대로 쓰고,
     * (필터는 DispatcherServlet 보다 앞이라 대개 없다) 없으면 같은 API 로 파싱한다. 캐시를 남기지
     * 않으므로 이후 DispatcherServlet 의 파싱 동작에 영향을 주지 않는다.
     *
     * @return 애플리케이션 기준 경로. 판정 불가 시 {@code null}(호출자가 fail-closed 처리)
     */
    private static PathContainer pathWithinApplication(HttpServletRequest request) {
        try {
            if (ServletRequestPathUtils.hasParsedRequestPath(request)) {
                return ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication();
            }
            String uri = request.getRequestURI();
            if (uri == null || uri.isEmpty()) {
                return null;
            }
            String contextPath = request.getContextPath();
            return RequestPath.parse(uri, contextPath == null ? "" : contextPath).pathWithinApplication();
        } catch (RuntimeException e) {
            // 판정 불가 — 호출자가 fail-closed 로 처리한다 (S-13).
            log.warn("[Webhook] path resolution failed reason={}", e.getClass().getSimpleName());
            return null;
        }
    }
}

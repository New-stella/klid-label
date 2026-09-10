package kr.co.cudo.authoring.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 보안 회귀 방지 아키텍처 테스트 — 모든 <b>상태변경(POST/PUT/PATCH/DELETE)</b> 핸들러가
 * 역할 가드(@PreAuthorize hasRole/hasAnyRole) 또는 URL 매처 역할 게이트로 보호됨을 강제한다.
 *
 * <p><b>배경</b>: 역할 분리 Phase 3 에서 'LS 역할 미배정(role=null)' INTERNAL 인증 사용자 상태가 도입됐다.
 * {@code SecurityConfig} 의 {@code /v1/**} 매처는 {@code CHANNEL_INTERNAL} authority 만 요구하고
 * baseline 역할(ROLE_*)을 요구하지 않으므로, 역할이 없는 prefix(/v1/labels, /v1/frames, /v1/videos,
 * /v1/reviews, /v1/issues, /v1/uploads, /v1/augments, /v1/assignments, /v1/users,
 * /v1/versions, /v1/tasks 등)의 쓰기 보호가 <b>메서드 @PreAuthorize 단일 계층</b>에 의존한다.
 * 현재 누락 0건이나, 향후 @PreAuthorize 를 빠뜨리면 role=null 사용자에게 fail-open 노출된다.
 * 본 테스트가 그 회귀를 <b>빌드 실패</b>로 차단한다.
 *
 * <p><b>방식 A</b>: {@code @SpringBootTest} 컨텍스트의 {@link RequestMappingHandlerMapping} 으로
 * 전체 핸들러 메서드를 실제 매핑 기반으로 열거 → 상태변경 동사 핸들러만 골라 가드 보유를 검증한다.
 *
 * <p><b>보호 인정 기준(가드 OK)</b>:
 * <ol>
 *   <li>메서드 또는 클래스 {@code @PreAuthorize} 가 {@code hasRole(...)} / {@code hasAnyRole(...)} 를 포함, 또는</li>
 *   <li>URL 패턴이 SecurityConfig 에서 역할 게이트되는 prefix 에 속함(/v1/manage/**, /v1/system/**,
 *       /v1/notices/**, /v1/portal/**, /v1/dev/**) 또는 전면 거부 prefix(/v1/integration/**,
 *       /v1/export-api/**).</li>
 * </ol>
 * 둘 중 하나면 role=null 사용자 차단이 보장된다. {@code isAuthenticated()} 단독은 role=null 을
 * 막지 못하므로 가드로 인정하지 않는다(=실제 위협 모델과 일치).
 */
@SpringBootTest
@ActiveProfiles("local")
class WriteEndpointAuthorizationGuardTest {

    /** 상태변경(쓰기) HTTP 메서드. */
    private static final Set<RequestMethod> STATE_CHANGING =
            EnumSet.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /**
     * SecurityConfig 에서 URL 매처로 역할이 게이트되는(또는 전면 거부되는) prefix.
     * 이 prefix 하위 상태변경 핸들러는 매처 단계에서 role=null 이 차단되므로 메서드 @PreAuthorize 가
     * 없어도 보호된다.
     */
    private static final List<String> ROLE_GATED_PREFIXES = List.of(
            "/v1/manage",      // hasRole(REVIEWER) — GET /v1/manage/labels/** 만 REVIEWER·WORKER·PORTAL_USER
                               //   (라벨 마스터 <읽기> 공용 계약), 쓰기는 REVIEWER
            "/v1/system",      // hasRole(REVIEWER)
            "/v1/notices",     // hasAnyRole(REVIEWER, WORKER)
            "/v1/portal",      // ROLE_PORTAL_USER + CHANNEL_PORTAL
            "/v1/dev",         // hasRole(REVIEWER) (tokens 제외 — 아래 allowlist)
            "/v1/integration", // denyAll
            "/v1/export-api"   // denyAll
    );

    /**
     * 역할 가드 면제가 정당한 상태변경 핸들러 allowlist — "SimpleClassName#methodName".
     * 각 항목은 메서드 시큐리티 전수점검에서 확인된 의도적 비역할 핸들러이며 별도 보호 계층을 가진다.
     * allowlist 에 없으면서 가드 없는 상태변경 핸들러가 있으면 테스트 실패.
     */
    private static final Set<String> ALLOWLIST = Set.of(
            // 벤더 무서명 규격 콜백 — 역할 무관. IP allowlist + rate limit + size cap(HmacWebhookFilter)
            // + request_id 발급 게이트(VlmResultService)로 보호한다.
            "VlmResultController#receive",
            // 생성형 AI(증강) 무서명 웹훅 — 역할 무관. IP allowlist(fail-closed) + rate limit + size cap
            // + request_id 발급 게이트(GenAiCallbackService)로 보호한다.
            "GenAiCallbackController#receive",
            // 설계상 role=null 부트스트랩 진입점 — 관리자 PW + rate limit 로 보호(@PreAuthorize isAuthenticated()).
            // role=null 사용자가 최초 역할을 획득하는 유일 경로이므로 역할 가드를 둘 수 없다.
            "RoleClaimController#claim",
            // dev 전용 토큰 발급 — permitAll(authoring.dev.login.enabled=true 시) + @ConditionalOnProperty
            // (운영 prd 빈 미등록). dev/stg/local 한정 부트스트랩 토큰 진입점.
            "DevTokenController#issue",
            // 포털 서버간 창구 — 역할 축이 아니라 <사전 공유 키 축>으로 보호한다
            // (@design INT-014 · API-244 · AC-1103).
            //
            // ★ 역할 가드를 둘 수 없는 것이 이 창구의 <설계>다. 부르는 쪽은 사람이 아니라
            //   시스템이고 토큰을 싣지 않으므로 저작도구 역할을 <가질 수가 없다>. 역할을
            //   요구하면 정당한 호출이 전건 거부된다.
            //
            // 별도 보호 계층 — SecurityConfig 의 /v1/portal-system/** 매처가 전용 권한
            //   PORTAL_SYSTEM_API 를 요구하고, 그 권한을 부여하는 자리는 PortalSystemApiKeyFilter
            //   한 곳뿐이다(정확 경로 + 키 상수시간 일치). 그러므로 이 가드가 걱정하는
            //   「role=null INTERNAL 사용자」도, 포털 사용자 토큰도 그 권한을 갖지 못해 걸리며
            //   사용자가 스스로 획득할 수 없다.
            "PortalDatasetCleanupTriggerController#receiveDatasetCleanupTrigger",
            // 관제 세션 중계 창구 — 갱신·로그아웃 (@design API-247 · API-246 · INT-015).
            //
            // ★ 로그인 검사를 두지 않는 것이 <사양>이다. 갱신은 저작도구 access 토큰이 이미 만료돼
            //   401 이 돌아온 뒤의 재시도 경로에서도 돼야 하고, 로그아웃은 만료 직전·직후 토큰으로도
            //   진행돼야 한다 — 역할을 요구하면 정확히 그 순간 막힌다.
            //
            // 별도 보호 계층 — ①자격증명은 요청의 관제 토큰(갱신=본문 refresh, 로그아웃=Bearer access)
            //   이고 유효성 판정은 관제가 한다. 저작도구 쪽 상태를 바꾸지 않는다(DB 쓰기 0 · 토큰 미저장)
            //   ②호출 대상 주소는 배포 설정값 고정이라 요청이 대상을 바꿀 수 없다 ③포털 향 배포본에서는
            //   두 창구 모두 404 ④refresh 토큰은 인증 필터가 API 자격증명으로 받지 않는다(ADR-063 ⑦).
            "ControlSessionController#refresh",
            "ControlSessionController#logout"
    );

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("모든_상태변경_핸들러는_역할가드_또는_허용목록에_있다")
    void 모든_상태변경_핸들러는_역할가드_또는_허용목록에_있다() {
        // given: 전체 핸들러 매핑 + 상태변경 핸들러만 추출
        List<WriteHandler> writeHandlers = collectStateChangingHandlers();
        assertThat(writeHandlers)
                .as("상태변경(POST/PUT/PATCH/DELETE) 핸들러가 하나도 없으면 컨텍스트 로딩/열거가 잘못된 것")
                .isNotEmpty();

        // when: 역할 가드도 없고 URL 게이트 prefix 도 아니며 allowlist 에도 없는 핸들러 = 위반
        List<String> violations = new ArrayList<>();
        for (WriteHandler h : writeHandlers) {
            if (hasRoleGuard(h.handler) || isUnderRoleGatedPrefix(h.patterns) || isAllowlisted(h.signature)) {
                continue;
            }
            violations.add(h.describe());
        }

        // then: role=null 사용자에게 fail-open 노출되는 무가드 쓰기 핸들러는 0건이어야 한다
        assertThat(violations)
                .as("역할 가드(@PreAuthorize hasRole/hasAnyRole) 도 URL 역할 게이트도 allowlist 도 없는 "
                        + "상태변경 핸들러가 발견됨 — role=null INTERNAL 사용자에게 fail-open 노출 위험. "
                        + "검사한 상태변경 핸들러 수=%d. 위반 핸들러:%n%s",
                        writeHandlers.size(), String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("allowlist_핸들러는_여전히_존재한다")
    void allowlist_핸들러는_여전히_존재한다() {
        // given: 실제 등록된 전체 핸들러 시그니처
        Set<String> registered = new LinkedHashSet<>();
        for (HandlerMethod hm : handlerMapping.getHandlerMethods().values()) {
            registered.add(signature(hm));
        }

        // when/then: allowlist 항목이 stale(삭제/리네임)되지 않았는지 — 모두 실재해야 한다.
        // (DevTokenController 는 authoring.dev.login.enabled=true 인 local 프로파일에서 등록됨)
        List<String> missing = ALLOWLIST.stream()
                .filter(sig -> !registered.contains(sig))
                .sorted()
                .toList();
        assertThat(missing)
                .as("allowlist 가 stale 됨 — 다음 면제 핸들러가 더 이상 존재하지 않는다. "
                        + "allowlist 에서 제거하라: %s", missing)
                .isEmpty();
    }

    // --- helpers ---

    private List<WriteHandler> collectStateChangingHandlers() {
        List<WriteHandler> result = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            boolean stateChanging = methods.stream().anyMatch(STATE_CHANGING::contains);
            if (!stateChanging) {
                continue;
            }
            result.add(new WriteHandler(e.getValue(), patternsOf(info), signature(e.getValue())));
        }
        return result;
    }

    /** Boot 3.x 는 기본 PathPattern, 일부 설정은 AntPath — 둘 다 대응. */
    private Set<String> patternsOf(RequestMappingInfo info) {
        PathPatternsRequestCondition pp = info.getPathPatternsCondition();
        if (pp != null) {
            return pp.getPatternValues();
        }
        PatternsRequestCondition ap = info.getPatternsCondition();
        return ap != null ? ap.getPatterns() : Collections.emptySet();
    }

    private boolean hasRoleGuard(HandlerMethod handler) {
        return containsRoleExpression(AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class))
                || containsRoleExpression(AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class));
    }

    private boolean containsRoleExpression(PreAuthorize preAuthorize) {
        if (preAuthorize == null) {
            return false;
        }
        String expr = preAuthorize.value();
        // isAuthenticated() 단독은 role=null 을 막지 못하므로 가드로 인정하지 않는다.
        return expr.contains("hasRole(") || expr.contains("hasAnyRole(");
    }

    private boolean isUnderRoleGatedPrefix(Set<String> patterns) {
        for (String pattern : patterns) {
            for (String prefix : ROLE_GATED_PREFIXES) {
                if (pattern.equals(prefix) || pattern.startsWith(prefix + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAllowlisted(String signature) {
        return ALLOWLIST.contains(signature);
    }

    private static String signature(HandlerMethod hm) {
        return hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
    }

    private record WriteHandler(HandlerMethod handler, Set<String> patterns, String signature) {
        String describe() {
            return signature + "  patterns=" + patterns;
        }
    }
}

package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.client.AiCallCancellationInterceptor;
import kr.co.cudo.authoring.common.config.AiCallCancellationWebConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 온디맨드 AI 추론 취소 API {@code POST /v1/ai-requests/{requestId}/cancel} 와 그 배선.
 *
 * <h3>배선을 함께 고정하는 이유</h3>
 * <p>취소는 «인터셉터가 그 경로에 매여 있어야» 성립한다. 경로 패턴에 오타가 있거나 새 추론 경로를
 * 등록 목록에 넣지 않으면, 취소 API 는 200 을 돌려주는데 <b>아무것도 끊지 않는다</b>(조용한 무동작).
 * 그래서 등록 경로가 실제 매핑과 맞는지를 기계로 대조한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AiCancelApiTest {

    private static final String PATH = "/v1/ai-requests/{id}/cancel";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    // 액추에이터도 같은 타입의 빈을 하나 더 등록하므로 이름으로 고른다.
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String workerToken;
    private String reviewerToken;
    private String portalToken;

    @BeforeEach
    void setUp() {
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",      "INTERNAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER",    "INTERNAL", issuer, 60);
        portalToken   = JwtTestSupport.token(secret, "500", "PORTAL_USER", "PORTAL",   issuer, 60);
    }

    @Test
    @DisplayName("진행_중이_아닌_식별자를_취소하면_200과_cancelled_false_다")
    void unknownRequestIsNotAnError() throws Exception {
        // 이미 끝난 요청을 취소하는 것은 정상 동선이다 — 오류로 만들면 화면이 붉은 안내를 띄운다.
        String body = mockMvc.perform(post(PATH, "not-running")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        assertThat(data.get("cancelled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("검수자도_같은_경로를_쓸_수_있다")
    void reviewerMayCancel() throws Exception {
        mockMvc.perform(post(PATH, "not-running")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("포털_채널은_취소_경로에_들어오지_못한다")
    void portalChannelForbidden() throws Exception {
        mockMvc.perform(post(PATH, "not-running")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증_요청은_401이다")
    void anonymousUnauthorized() throws Exception {
        mockMvc.perform(post(PATH, "not-running"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("취소_인터셉터가_매인_경로가_전부_실제_매핑과_맞는다")
    void everyCancellablePathHasARealMapping() {
        // Spring 6 기본은 PathPattern 파서라 구 AntPath 조건(getPatternsCondition)이 null 이다.
        Set<String> mapped = handlerMapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(java.util.Objects::nonNull)
                .flatMap(condition -> condition.getPatterns().stream())
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(mapped).as("매핑을 하나도 읽지 못했다 — 이 검사가 통째로 무의미해진다").isNotEmpty();

        // 패턴은 «*» 로 쓰지만 실제 매핑은 «{srcSn}» 이다 — 그 자리만 맞춰 비교한다.
        for (String pattern : AiCallCancellationWebConfig.CANCELLABLE_PATHS) {
            String suffix = pattern.substring(pattern.lastIndexOf('/'));
            String prefix = pattern.substring(0, pattern.indexOf("/*/"));
            assertThat(mapped)
                    .as("등록 경로 %s 에 대응하는 실제 매핑이 없다 — 오타면 취소가 조용히 무동작이 된다",
                            pattern)
                    .anyMatch(m -> m.startsWith(prefix) && m.endsWith(suffix));
        }
    }

    @Test
    @DisplayName("온디맨드_추론_경로가_모두_취소_등록_목록에_들어_있다")
    void everyOnDemandInferencePathIsRegistered() {
        // 새 추론 경로가 생겼는데 등록을 빠뜨리면 그 경로만 취소가 안 되는 «반쪽 기능» 이 된다.
        List<String> onDemandSuffixes = List.of("/autolabel", "/sam2-segment", "/sam2-track", "/yolo-track");
        Set<String> registeredSuffixes = AiCallCancellationWebConfig.CANCELLABLE_PATHS.stream()
                .map(p -> p.substring(p.lastIndexOf('/')))
                .collect(Collectors.toSet());

        assertThat(registeredSuffixes).containsAll(onDemandSuffixes);
    }

    @Test
    @DisplayName("추론_경로에는_취소_인터셉터가_실제로_붙고_다른_경로에는_붙지_않는다")
    void interceptorIsActuallyApplied() throws Exception {
        // 경로 목록이 맞아도 «등록이 실제로 먹었는가» 는 별개다 — 그 배선을 직접 확인한다.
        for (String pattern : AiCallCancellationWebConfig.CANCELLABLE_PATHS) {
            String concrete = pattern.replace("/*/", "/1/");
            assertThat(interceptorNamesFor("POST", concrete))
                    .as("%s 에 취소 인터셉터가 붙지 않았다 — 취소 API 가 아무것도 끊지 못한다", concrete)
                    .contains(AiCallCancellationInterceptor.class.getSimpleName());
        }

        // 범위를 좁힌 것이 요점이므로 «붙지 않는 쪽» 도 함께 고정한다.
        assertThat(interceptorNamesFor("GET", "/v1/ai-defaults"))
                .doesNotContain(AiCallCancellationInterceptor.class.getSimpleName());
    }

    private List<String> interceptorNamesFor(String method, String path) throws Exception {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest(method, path);
        org.springframework.web.servlet.HandlerExecutionChain chain = handlerMapping.getHandler(request);
        assertThat(chain).as("%s %s 에 대응하는 핸들러가 없다", method, path).isNotNull();
        return java.util.Arrays.stream(chain.getInterceptors() == null
                        ? new org.springframework.web.servlet.HandlerInterceptor[0]
                        : chain.getInterceptors())
                .map(i -> i.getClass().getSimpleName())
                .toList();
    }

    @Test
    @DisplayName("취소_식별자_헤더_이름이_바뀌면_화면과_어긋난다")
    void headerNameIsPartOfTheContract() {
        // 화면이 이 이름으로 실어 보낸다 — 계약이므로 값 자체를 고정한다.
        assertThat(AiCallCancellationInterceptor.REQUEST_ID_HEADER).isEqualTo("X-AI-Request-Id");
    }
}

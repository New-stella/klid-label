package kr.co.cudo.authoring.sysconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.StreamSignatureFilter;
import kr.co.cudo.authoring.sysconfig.repository.LsSystemConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-193 — AI 정밀도 기본값 읽기 전용 조회 {@code GET /v1/ai-defaults}.
 *
 * <p>기존 {@code GET /v1/manage/configs} 의 읽기 권한을 넓히지 않는 이유를 고정하는 가드다.
 * 그 응답은 설정 전량 + 마지막 수정자 계정 식별자를 담고 있어, 작업자에게 운영 파라미터와
 * 검수자 계정 정보가 함께 나간다. 화면이 실제로 쓰는 값은 두 개뿐이므로 그 둘만 노출한다.
 *
 * <p>핵심 가드는 <b>응답 키 집합 정확 단언</b>이다 — 설정 키가 늘어도 이 경로로 조용히 새지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AiDefaultsControllerTest {

    private static final String PATH = "/v1/ai-defaults";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsSystemConfigRepository repository;
    @Autowired private CacheManager cacheManager;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;
    private String portalToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER",    "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",      "INTERNAL", issuer, 60);
        portalToken   = JwtTestSupport.token(secret, "500", "PORTAL_USER", "PORTAL",   issuer, 60);
        // 다른 설정 테스트가 남긴 캐시 값이 새지 않도록 격리.
        if (cacheManager.getCache("sysconfig") != null) {
            cacheManager.getCache("sysconfig").clear();
        }
    }

    @Test
    @DisplayName("작업자_토큰으로_조회하면_200과_두_기본값을_반환한다")
    void workerReadsAiDefaults() throws Exception {
        // given: DB 시드 값 (다른 테스트가 PUT 으로 바꿀 수 있어 하드코딩하지 않고 원본에서 읽는다)
        String expectedConf = configValue(ConfigKeys.YOLO_CONF_THRESHOLD);
        String expectedTolerance = configValue(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE);

        // when
        JsonNode data = dataOf(workerToken);

        // then
        assertThat(data.get("confThreshold").asInt()).isEqualTo(Integer.parseInt(expectedConf));
        assertThat(data.get("simplifyTolerance").asDouble()).isEqualTo(Double.parseDouble(expectedTolerance));
    }

    @Test
    @DisplayName("검수자_토큰으로도_같은_응답을_받는다")
    void reviewerReadsAiDefaults() throws Exception {
        // given / when
        JsonNode data = dataOf(reviewerToken);

        // then
        assertThat(data.has("confThreshold")).isTrue();
        assertThat(data.has("simplifyTolerance")).isTrue();
    }

    @Test
    @DisplayName("응답_data_의_키는_정확히_두_개다_설정키가_늘어도_새지_않는다")
    void exposesExactlyTwoKeys() throws Exception {
        // given / when
        JsonNode data = dataOf(workerToken);

        // then: 키 집합 정확 단언 — 이 가드가 이 작업의 핵심이다.
        List<String> keys = new ArrayList<>();
        data.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("confThreshold", "simplifyTolerance");
    }

    @Test
    @DisplayName("응답에_마지막_수정자와_수정일시_같은_운영_메타가_없다")
    void doesNotExposeOperationalMeta() throws Exception {
        // given / when
        String body = mockMvc.perform(get(PATH).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // then: 계정 식별자·수정일시·설정키·설명 어느 것도 실리지 않는다.
        assertThat(body)
                .doesNotContain("mdfrId")
                .doesNotContain("mdfcnDt")
                .doesNotContain("configKey")
                .doesNotContain("configVl")
                .doesNotContain("configTypeCd")
                .doesNotContain("expln");
    }

    @Test
    @DisplayName("포털_사용자_토큰은_403으로_차단된다")
    void portalUserForbidden() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증_요청은_401이다")
    void anonymousUnauthorized() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("서명_스트림_권한만_가진_컨텍스트는_403이다")
    void streamSignedContextForbidden() throws Exception {
        // given: SecurityConfig 의 /v1/** 매처는 CHANNEL_INTERNAL + (REVIEWER|WORKER|STREAM_SIGNED) 를
        // 허용하므로 서명 스트림 컨텍스트도 1차 매처는 통과한다. 이 경로는 사람 역할만 허용해야 하며
        // 그 판정은 컨트롤러 @PreAuthorize(2차)가 담당한다 — 그것이 실제로 무는지 고정한다.
        Authentication signed = new UsernamePasswordAuthenticationToken(
                StreamSignatureFilter.STREAM_SIGNED_PRINCIPAL, null,
                List.of(new SimpleGrantedAuthority("CHANNEL_" + Channel.INTERNAL.name()),
                        new SimpleGrantedAuthority(StreamSignatureFilter.AUTHORITY_STREAM_SIGNED)));

        // when / then
        mockMvc.perform(get(PATH).with(authentication(signed)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이_경로에는_쓰기_메서드가_없다")
    void noWriteCounterpart() throws Exception {
        mockMvc.perform(put(PATH).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(PATH).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isMethodNotAllowed());
    }

    /* ========== helpers ========== */

    private JsonNode dataOf(String token) throws Exception {
        String body = mockMvc.perform(get(PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        assertThat(data).isNotNull();
        return data;
    }

    private String configValue(String key) {
        return repository.findByConfigKey(key).orElseThrow().getConfigVl();
    }
}

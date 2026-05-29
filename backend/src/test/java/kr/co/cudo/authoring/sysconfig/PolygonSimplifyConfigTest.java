package kr.co.cudo.authoring.sysconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.sysconfig.dto.ConfigUpdateRequest;
import kr.co.cudo.authoring.sysconfig.repository.LsSystemConfigRepository;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FEAT-007 (SFR-08-03) — 경계 세밀함 설정 키 POLYGON_SIMPLIFY_TOLERANCE.
 * <p>
 * V54 시드로 추가된 DECIMAL 타입 키의 저장/조회/범위검증/권한을 검증한다.
 *  - 기본값 1.0 (getDouble)
 *  - 0.0~50.0 범위, 벗어나면 400 INVALID_INPUT
 *  - REVIEWER 만 수정 가능 (비-REVIEWER 403)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PolygonSimplifyConfigTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SystemConfigService service;
    @Autowired private LsSystemConfigRepository repository;
    @Autowired private CacheManager cacheManager;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        if (cacheManager.getCache("sysconfig") != null) {
            cacheManager.getCache("sysconfig").clear();
        }
    }

    @AfterEach
    void restore() throws Exception {
        // 후속 테스트 격리 — 기본값 1.0 으로 원복 (PUT 은 트랜잭션 커밋되어 영속).
        ConfigUpdateRequest req = new ConfigUpdateRequest("1.0");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE)
                .header("Authorization", "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));
        if (cacheManager.getCache("sysconfig") != null) {
            cacheManager.getCache("sysconfig").clear();
        }
    }

    @Test
    @DisplayName("V54시드_적용후_POLYGON_SIMPLIFY_TOLERANCE_기본값은_1_0")
    void defaultToleranceIsOne() {
        double value = service.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE);
        assertThat(value).isCloseTo(1.0, within(0.0001));
    }

    @Test
    @DisplayName("REVIEWER가_경계세밀함을_2_5로_저장하면_getDouble로_반영")
    void reviewerUpdatesTolerance() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("2.5");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configValue").value("2.5"));

        assertThat(service.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE))
                .isCloseTo(2.5, within(0.0001));
    }

    @Test
    @DisplayName("음수_입력시_INVALID_INPUT_400")
    void negativeRejected() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("-1.0");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("상한초과_입력시_INVALID_INPUT_400")
    void overMaxRejected() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("51");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("숫자가_아닌_값_입력시_INVALID_INPUT_400")
    void nonNumberRejected() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("abc");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("WORKER가_경계세밀함_수정시_403")
    void workerForbidden() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("3.0");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("미인증_요청시_401")
    void unauthenticatedRejected() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("3.0");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
}

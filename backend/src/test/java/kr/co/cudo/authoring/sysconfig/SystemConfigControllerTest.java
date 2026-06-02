package kr.co.cudo.authoring.sysconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.dto.ConfigUpdateRequest;
import kr.co.cudo.authoring.sysconfig.repository.LsSystemConfigRepository;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SystemConfigControllerTest {

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
        // 테스트 간 캐시 격리 — 이전 테스트의 캐시된 Integer 값이 다음 테스트에 새지 않도록.
        if (cacheManager.getCache("sysconfig") != null) {
            cacheManager.getCache("sysconfig").clear();
        }
    }

    @Test
    @DisplayName("SystemConfig_WORKER가_configs_PUT_호출시_403")
    void workerCannotUpdateConfig() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("3");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.BATCH_CONCURRENCY)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("SystemConfig_REVIEWER_BATCH_INTERVAL_SEC_업데이트시_캐시_무효화")
    void reviewerUpdatesAndCacheInvalidates() throws Exception {
        // 사전: getInt 호출로 캐시 워밍업 (현재 값 60).
        Integer beforeValue = service.getInt(ConfigKeys.BATCH_INTERVAL_SEC);
        assertThat(beforeValue).isEqualTo(60);

        // PUT 으로 120 으로 변경 → @CacheEvict 발동.
        ConfigUpdateRequest req = new ConfigUpdateRequest("120");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.BATCH_INTERVAL_SEC)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configVl").value("120"));

        // 캐시 무효화 후 다시 호출 시 새 값 반영.
        Integer afterValue = service.getInt(ConfigKeys.BATCH_INTERVAL_SEC);
        assertThat(afterValue).isEqualTo(120);

        // 후속 테스트 영향 차단 — 원복.
        repository.findByConfigKey(ConfigKeys.BATCH_INTERVAL_SEC)
                .ifPresent(c -> c.updateValue("60", "TEST"));
    }

    @Test
    @DisplayName("SystemConfig_NUMBER에_문자열_입력시_INVALID_INPUT_400")
    void numberTypeRejectsString() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("not-a-number");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.BATCH_CONCURRENCY)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("SystemConfig_BATCH_CONCURRENCY_범위_초과시_INVALID_INPUT_400")
    void batchConcurrencyRangeRejected() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("11");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.BATCH_CONCURRENCY)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("SystemConfig_화이트리스트_외_키_업데이트시_INVALID_INPUT")
    void unknownKeyRejected() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("anything");
        mockMvc.perform(put("/v1/manage/configs/UNKNOWN_KEY")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("SystemConfig_REVIEWER_GET_목록_조회시_화이트리스트_키_전체_반환")
    void reviewerGetsAllConfigs() throws Exception {
        // Batch 2 + YOLO 3 = 5 키
        mockMvc.perform(get("/v1/manage/configs")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(ConfigKeys.ALLOWED.size()));
    }

    @Test
    @DisplayName("Phase1_V19_마이그레이션_적용_후_YOLO_CONF_THRESHOLD_기본값은_25")
    void v19MigrationLowersYoloConfThresholdTo25() {
        // V17 시드값 40 → V19 마이그레이션에서 25 로 하향
        // 작은 객체 검출율 향상이 목적
        Integer value = service.getInt(ConfigKeys.YOLO_CONF_THRESHOLD);
        assertThat(value).isEqualTo(25);
    }
}

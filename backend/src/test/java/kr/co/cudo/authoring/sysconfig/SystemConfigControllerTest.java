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
    @DisplayName("SystemConfig_REVIEWER_GET_목록_조회시_시드된_화이트리스트_키_전체_반환")
    void reviewerGetsAllConfigs() throws Exception {
        // 목록은 <b>DB 에 행이 있는</b> 화이트리스트 키만 담는다.
        //
        // ⚠ R11 연동 주소 4종은 <b>일부러 시드하지 않는다</b> — 행이 없으면 배포 기본값(@Value)을
        //    쓰는 것이 설계다. 그래서 "허용 키 수"와 "목록 길이"는 같지 않으며, 같아야 한다고
        //    단언하면 그 설계를 되돌리라는 압력이 된다. 시드 대상은 선언 타입을 갖지 않는 키다.
        int seededKeyCount = ConfigKeys.ALLOWED.size() - ConfigKeys.DECLARED_TYPE.size();

        mockMvc.perform(get("/v1/manage/configs")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(seededKeyCount))
                // 저장 전 연동 주소 키는 목록에 나타나지 않는다(= 배포 기본값 사용 중).
                .andExpect(jsonPath("$.data[?(@.configKey == '"
                        + ConfigKeys.INTEGRATION_AI_SERVER_BASE_URL + "')]").isEmpty());
    }

    @Test
    @DisplayName("REVIEWER가_제외코드_설정_PUT시_200")
    void reviewerUpdatesEventExcludedClassCodes() throws Exception {
        // given — 시드 기본값 ["08"]
        assertThat(service.getStringSet(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES)).containsExactly("08");
        ConfigUpdateRequest req = new ConfigUpdateRequest("[\"08\",\"09\"]");

        // when / then
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.EVENT_EXCLUDED_CLASS_CODES)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configVl").value("[\"08\",\"09\"]"));

        assertThat(service.getStringSet(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES))
                .containsExactlyInAnyOrder("08", "09");

        // 후속 테스트 영향 차단 — 원복.
        repository.findByConfigKey(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES)
                .ifPresent(c -> c.updateValue("[\"08\"]", "TEST"));
    }

    @Test
    @DisplayName("REVIEWER_아닌_역할이_설정_PUT시_403")
    void workerCannotUpdateEventExcludedClassCodes() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("[\"08\",\"09\"]");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.EVENT_EXCLUDED_CLASS_CODES)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("제외코드_2자리숫자_아닌_값_PUT시_400")
    void malformedExcludedClassCodeRejected() throws Exception {
        ConfigUpdateRequest req = new ConfigUpdateRequest("[\"8\"]");
        mockMvc.perform(put("/v1/manage/configs/" + ConfigKeys.EVENT_EXCLUDED_CLASS_CODES)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
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

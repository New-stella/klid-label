package kr.co.cudo.authoring.preset.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 — PresetController 의 라벨 코드 옵션 (BBOX/POLYGON 토글) 입출력 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class PresetControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsLabelPresetRepository presetRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    /** {@code labelCodeOptions} JSON 빌더. */
    private ObjectNode option(String code, boolean bbox, boolean polygon) {
        ObjectNode opt = objectMapper.createObjectNode();
        opt.put("code", code);
        opt.put("bboxEnabled", bbox);
        opt.put("polygonEnabled", polygon);
        return opt;
    }

    @Test
    @DisplayName("PresetController_POST_labelCodeOptions_정상_요청은_옵션과_함께_201")
    void createWithLabelCodeOptionsSucceeds() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "옵션 프리셋");
        body.put("description", "phase1");
        ArrayNode opts = body.putArray("labelCodeOptions");
        opts.add(option("PERSON", true, false));
        opts.add(option("VEHICLE", true, true));
        body.put("eventTypeCd", "");

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions.length()").value(2))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].code").value("PERSON"))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].bboxEnabled").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].polygonEnabled").value(false))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].code").value("VEHICLE"))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].bboxEnabled").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].polygonEnabled").value(true))
                // 응답에 레거시 labelCodes 도 포함되는지 확인
                .andExpect(jsonPath("$.data.labelCodes[0]").value("PERSON"))
                .andExpect(jsonPath("$.data.labelCodes[1]").value("VEHICLE"));
    }

    @Test
    @DisplayName("PresetController_POST_둘다_false_조합이면_400_반환")
    void createWithBothFalseReturns400() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "둘다 false");
        body.put("description", "");
        ArrayNode opts = body.putArray("labelCodeOptions");
        opts.add(option("PERSON", false, false));  // 둘 다 false
        body.put("eventTypeCd", "");

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_POST_레거시_labelCodes_만_있어도_모두_true_로_저장")
    void createWithLegacyLabelCodesNormalizesToBoth() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "레거시 호환 프리셋");
        body.put("description", "legacy");
        ArrayNode codes = body.putArray("labelCodes");
        codes.add("PERSON");
        codes.add("VEHICLE");
        body.put("eventTypeCd", "");

        MvcResult res = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.labelCodes.length()").value(2))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].code").value("PERSON"))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].bboxEnabled").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].polygonEnabled").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].bboxEnabled").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].polygonEnabled").value(true))
                .andReturn();

        // DB 상태도 확인 (옵션 기본값 true,true)
        long id = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        var saved = presetRepository.findById(id).orElseThrow();
        assertThat(saved.getCodes()).allMatch(c -> c.isBboxEnabled() && c.isPolygonEnabled());
    }

    @Test
    @DisplayName("PresetController_POST_둘다_빈_목록이면_400_INVALID_INPUT")
    void createWithBothEmptyReturns400() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "라벨 없음");
        body.put("description", "");
        body.putArray("labelCodeOptions");
        body.putArray("labelCodes");
        body.put("eventTypeCd", "");

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_PUT_옵션_토글_변경이_정확히_반영")
    void updateAppliesNewToggles() throws Exception {
        // 1) 먼저 BOTH 로 생성
        ObjectNode create = objectMapper.createObjectNode();
        create.put("name", "수정대상 프리셋 " + System.nanoTime());
        create.put("description", "init");
        ArrayNode createOpts = create.putArray("labelCodeOptions");
        createOpts.add(option("PERSON", true, true));
        create.put("eventTypeCd", "");

        MvcResult createRes = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andReturn();

        long id = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 2) PERSON 을 BBOX-only 로 변경
        ObjectNode update = objectMapper.createObjectNode();
        update.put("name", "수정대상 프리셋 " + id);  // ignored — service 가 동일 이름 체크
        // 동일 이름 충돌 회피 위해 PUT 은 동일 이름 사용 (existsByNameAndPresetIdNot 가 self exclude)
        update.set("name", objectMapper.readTree(createRes.getResponse().getContentAsString())
                .path("data").path("name"));
        update.put("description", "updated");
        ArrayNode updOpts = update.putArray("labelCodeOptions");
        updOpts.add(option("PERSON", true, false));  // BBOX only
        update.put("eventTypeCd", "");

        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCodeOptions[0].code").value("PERSON"))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].bboxEnabled").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].polygonEnabled").value(false));
    }

    @Test
    @DisplayName("PresetController_POST_옵션_code_가_빈_문자열이면_400_INVALID_INPUT")
    void createWithBlankCodeReturns400() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "빈 코드 거부");
        body.put("description", "");
        ArrayNode opts = body.putArray("labelCodeOptions");
        opts.add(option("", true, true));
        body.put("eventTypeCd", "");

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }
}

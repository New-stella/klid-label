package kr.co.cudo.authoring.preset.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 — PresetController 의 labelId 기반 코드 입력 + 마스터 형태 파생 응답 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
// 프리셋 eventTypeCd 검증은 관제 이벤트타입 마스터(MNG_EX_EVNT_TYPE)에서 도출한 유효 categoryKey
// (예: 010001 침수)에 의존한다. 일반 테스트 컨텍스트는 dev-seed 를 비활성하므로 마스터가 비어
// 유효 categoryKey 검증(createWithValidCategoryKeySucceeds)이 실패한다 → EventTypeControllerIT
// 와 동일하게 시드를 개별 활성화해 관제 이벤트타입 마스터/매핑을 Testcontainer 에 멱등 적재한다.
@TestPropertySource(properties = "authoring.dev.seed.enabled=true")
@Transactional("controlTransactionManager")
class PresetControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsLabelPresetRepository presetRepository;
    @Autowired private LsLabelRepository labelRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private long personLabelId;   // BBOX
    private long vehicleLabelId;  // POLYGON

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        String suffix = String.valueOf(System.nanoTime());
        personLabelId = seedLabel("PERSON_" + suffix, "BBOX", "Y");
        vehicleLabelId = seedLabel("VEHICLE_" + suffix, "POLYGON", "Y");
    }

    private long seedLabel(String name, String type, String useYn) {
        LsLabel saved = labelRepository.saveAndFlush(LsLabel.create(name, "#FF0000", type, 0, "tester"));
        if (!"Y".equals(useYn)) {
            saved.softDelete("tester");
            labelRepository.saveAndFlush(saved);
        }
        return saved.getLabelId();
    }

    private ObjectNode presetBody(String name, long... labelIds) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("description", "phase2");
        ArrayNode ids = body.putArray("labelIds");
        for (long id : labelIds) {
            ids.add(id);
        }
        body.put("eventTypeCd", "");
        return body;
    }

    @Test
    @DisplayName("PresetController_POST_labelId_정상요청은_마스터_형태파생과_함께_201")
    void createWithLabelIdsSucceeds() throws Exception {
        ObjectNode body = presetBody("옵션 프리셋 " + System.nanoTime(), personLabelId, vehicleLabelId);

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions.length()").value(2))
                // PERSON = BBOX → bbox only
                .andExpect(jsonPath("$.data.labelCodeOptions[0].labelId").value((int) personLabelId))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].linked").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].labelType").value("BBOX"))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].bboxEnabled").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].polygonEnabled").value(false))
                // VEHICLE = POLYGON → polygon only
                .andExpect(jsonPath("$.data.labelCodeOptions[1].labelId").value((int) vehicleLabelId))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].labelType").value("POLYGON"))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].bboxEnabled").value(false))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].polygonEnabled").value(true))
                // 레거시 labelCodes 는 라벨명 목록으로 노출
                .andExpect(jsonPath("$.data.labelCodes.length()").value(2));
    }

    @Test
    @DisplayName("PresetController_POST_labelIds_비어있으면_400_INVALID_INPUT")
    void createWithEmptyLabelIdsReturns400() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "라벨 없음");
        body.put("description", "");
        body.putArray("labelIds");
        body.put("eventTypeCd", "");

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_POST_마스터에_없는_labelId면_400_INVALID_INPUT")
    void createWithUnknownLabelIdReturns400() throws Exception {
        ObjectNode body = presetBody("미존재 라벨 " + System.nanoTime(), 99999999L);

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_POST_soft_delete된_labelId면_400_INVALID_INPUT")
    void createWithSoftDeletedLabelIdReturns400() throws Exception {
        long deletedId = seedLabel("DELETED_" + System.nanoTime(), "BBOX", "N");
        ObjectNode body = presetBody("삭제 라벨 " + System.nanoTime(), deletedId);

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_POST_음수_labelId면_400_INVALID_INPUT")
    void createWithNegativeLabelIdReturns400() throws Exception {
        ObjectNode body = presetBody("음수 라벨 " + System.nanoTime(), -5L);

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_POST_저장후_DB에는_labelId만_저장되고_LBL_CD는_null")
    void createStoresLabelIdWithoutCodeSnapshot() throws Exception {
        ObjectNode body = presetBody("저장검증 " + System.nanoTime(), personLabelId);

        MvcResult res = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        long id = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        var saved = presetRepository.findById(id).orElseThrow();
        assertThat(saved.getCodes()).hasSize(1);
        assertThat(saved.getCodes().get(0).getLabelId()).isEqualTo(personLabelId);
        assertThat(saved.getCodes().get(0).getCode()).isNull();  // 라벨명/코드 스냅샷 미저장
    }

    @Test
    @DisplayName("PresetController_PUT_코드목록_labelId_변경이_반영된다")
    void updateAppliesNewLabelIds() throws Exception {
        // 1) PERSON 으로 생성
        ObjectNode create = presetBody("수정대상 " + System.nanoTime(), personLabelId);
        MvcResult createRes = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andReturn();

        var created = objectMapper.readTree(createRes.getResponse().getContentAsString()).path("data");
        long id = created.path("id").asLong();

        // 2) VEHICLE 로 교체
        ObjectNode update = objectMapper.createObjectNode();
        update.set("name", created.path("name"));
        update.put("description", "updated");
        ArrayNode ids = update.putArray("labelIds");
        ids.add(vehicleLabelId);
        update.put("eventTypeCd", "");

        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCodeOptions.length()").value(1))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].labelId").value((int) vehicleLabelId))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].labelType").value("POLYGON"))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].bboxEnabled").value(false))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].polygonEnabled").value(true));
    }

    @Test
    @DisplayName("프리셋_저장시_유효_categoryKey면_201")
    void createWithValidCategoryKeySucceeds() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "침수 프리셋 " + System.nanoTime());
        body.put("description", "phase4a");
        ArrayNode ids = body.putArray("labelIds");
        ids.add(personLabelId);
        body.put("eventTypeCd", "010001");  // 관제 유효 categoryKey(침수)

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.eventTypeCd").value("010001"));
    }

    @Test
    @DisplayName("프리셋_저장시_구_EVT코드면_400_INVALID_INPUT")
    void createWithLegacyEvtCodeReturns400() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "구코드 거부 " + System.nanoTime());
        body.put("description", "");
        ArrayNode ids = body.putArray("labelIds");
        ids.add(personLabelId);
        body.put("eventTypeCd", "EVT_FALL");  // 폐기된 EVT_* 코드

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("프리셋_저장시_미등록_categoryKey면_400_INVALID_INPUT")
    void createWithUnknownCategoryKeyReturns400() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "미등록 거부 " + System.nanoTime());
        body.put("description", "");
        ArrayNode ids = body.putArray("labelIds");
        ids.add(personLabelId);
        body.put("eventTypeCd", "999999");  // 마스터에 없는 categoryKey

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ----- 이슈 2 보강: 중복/전체교체/삭제/복제/목록/검증 -----

    /** 프리셋을 생성하고 생성된 id 를 반환한다. */
    private long createPreset(String name, long... labelIds) throws Exception {
        MvcResult res = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(name, labelIds))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    @Test
    @DisplayName("PresetController_POST_중복labelId_요청시_DB에는_코드1건만_저장된다")
    void createDedupsDuplicateLabelIdInDb() throws Exception {
        ObjectNode body = presetBody("중복라벨 " + System.nanoTime(), personLabelId, personLabelId, personLabelId);

        MvcResult res = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        long id = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        var saved = presetRepository.findById(id).orElseThrow();
        assertThat(saved.getCodes()).hasSize(1);
        assertThat(saved.getCodes().get(0).getLabelId()).isEqualTo(personLabelId);
    }

    @Test
    @DisplayName("PresetController_PUT_요청에서_제외된_labelId는_코드목록에서_소실된다")
    void updateDropsOmittedLabelId() throws Exception {
        long id = createPreset("교체대상 " + System.nanoTime(), personLabelId, vehicleLabelId);

        // person 만 남기고 교체 → vehicle 소실.
        ObjectNode update = presetBody("교체대상 갱신 " + System.nanoTime(), personLabelId);
        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCodeOptions.length()").value(1))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].labelId").value((int) personLabelId));

        var saved = presetRepository.findById(id).orElseThrow();
        assertThat(saved.getCodes()).hasSize(1);
        assertThat(saved.getCodes().get(0).getLabelId()).isEqualTo(personLabelId);
    }

    @Test
    @DisplayName("PresetController_DELETE_존재_204_그리고_미존재도_204_idempotent")
    void deleteIsIdempotent() throws Exception {
        long id = createPreset("삭제대상 " + System.nanoTime(), personLabelId);

        mockMvc.perform(delete("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());
        assertThat(presetRepository.findById(id)).isEmpty();

        // 이미 삭제된(존재하지 않는) 프리셋 삭제도 204.
        mockMvc.perform(delete("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PresetController_POST_clone_정상_복사본이_생성되고_이름중복시_복사본2로_명명된다")
    void cloneCreatesCopyAndNamesDuplicateAsCopy2() throws Exception {
        String base = "복제원본 " + System.nanoTime();
        long id = createPreset(base, personLabelId);

        MvcResult first = mockMvc.perform(post("/v1/manage/presets/" + id + "/clone")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(base + " (복사본)"))
                .andExpect(jsonPath("$.data.labelCodeOptions.length()").value(1))
                .andReturn();
        // 원본 코드(labelId)가 복사됐는지 확인.
        long copyId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        var copy = presetRepository.findById(copyId).orElseThrow();
        assertThat(copy.getCodes().get(0).getLabelId()).isEqualTo(personLabelId);

        // 두 번째 복제 → 이름 충돌로 "(복사본 2)".
        mockMvc.perform(post("/v1/manage/presets/" + id + "/clone")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(base + " (복사본 2)"));
    }

    @Test
    @DisplayName("PresetController_GET_목록조회시_생성한_프리셋이_마스터_형태파생과_함께_노출된다")
    void listReturnsCreatedPresetWithMasterJoin() throws Exception {
        long id = createPreset("목록검증 " + System.nanoTime(), personLabelId, vehicleLabelId);

        MvcResult res = mockMvc.perform(get("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        var data = objectMapper.readTree(res.getResponse().getContentAsString()).path("data");
        boolean found = false;
        for (var node : data) {
            if (node.path("id").asLong() == id) {
                found = true;
                var opts = node.path("labelCodeOptions");
                assertThat(opts).hasSize(2);
                assertThat(opts.get(0).path("labelType").asText()).isEqualTo("BBOX");
                assertThat(opts.get(0).path("bboxEnabled").asBoolean()).isTrue();
                assertThat(opts.get(1).path("labelType").asText()).isEqualTo("POLYGON");
                assertThat(opts.get(1).path("polygonEnabled").asBoolean()).isTrue();
            }
        }
        assertThat(found).as("생성한 프리셋이 목록에 있어야 한다").isTrue();
    }

    @Test
    @DisplayName("PresetController_PUT_labelIds_21개초과면_400_INVALID_INPUT")
    void updateWithTooManyLabelIdsReturns400() throws Exception {
        ObjectNode update = objectMapper.createObjectNode();
        update.put("name", "상한초과 " + System.nanoTime());
        update.put("description", "");
        ArrayNode ids = update.putArray("labelIds");
        for (int i = 1; i <= 21; i++) {
            ids.add(i);
        }
        update.put("eventTypeCd", "");

        // @Size(max=20) 본문 검증이 서비스 도달 전에 400 을 낸다(경로 id 유효성 무관).
        mockMvc.perform(put("/v1/manage/presets/1")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_PUT_미존재_labelId면_400_INVALID_INPUT")
    void updateWithUnknownLabelIdReturns400() throws Exception {
        long id = createPreset("PUT미존재 " + System.nanoTime(), personLabelId);
        ObjectNode update = presetBody("PUT미존재 갱신 " + System.nanoTime(), 99999999L);

        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_PUT_음수_labelId면_400_INVALID_INPUT")
    void updateWithNegativeLabelIdReturns400() throws Exception {
        long id = createPreset("PUT음수 " + System.nanoTime(), personLabelId);
        ObjectNode update = presetBody("PUT음수 갱신 " + System.nanoTime(), -3L);

        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }
}

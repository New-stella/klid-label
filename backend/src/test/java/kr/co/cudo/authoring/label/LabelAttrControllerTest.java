package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.entity.LsLabelAttr;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelAttrRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
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
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 라벨 속성 정의 + 객체별 속성값 Controller 통합 테스트 (MockMvc + Security).
 *
 * <p>CVAT-Like 라벨 풀 포팅 Phase 3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LabelAttrControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsLabelRepository labelRepository;
    @Autowired private LsLabelAttrRepository attrRepository;
    @Autowired private LsDataLblRepository labelRowRepository;
    @Autowired private LsDataLblAttrValRepository valueRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;
    private Long labelId;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1001", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "2001", "WORKER",   "INTERNAL", issuer, 60);

        LsLabel label = labelRepository.save(
                LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed"));
        labelId = label.getLabelId();
    }

    private ObjectNode attrBody(String name, String inputType, String valuesJson,
                                 String defaultVal, String mutable, Integer sortNo) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.put("inputType", inputType);
        if (valuesJson != null) {
            node.put("valuesJson", valuesJson);
        }
        if (defaultVal != null) {
            node.put("defaultVal", defaultVal);
        }
        if (mutable != null) {
            node.put("mutable", mutable);
        }
        if (sortNo != null) {
            node.put("sortNo", sortNo);
        }
        return node;
    }

    private ObjectNode valuesBody(Long attrId, String value) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode arr = root.putArray("values");
        ObjectNode e = arr.addObject();
        e.put("attrId", attrId);
        e.put("value", value);
        return root;
    }

    // ────────────────────────────── 속성 정의 ──────────────────────────────

    @Test
    @DisplayName("GET_속성목록_REVIEWER_200")
    void getAttrs_REVIEWER_200() throws Exception {
        attrRepository.save(LsLabelAttr.create(labelId, "occluded", "SELECT",
                "[\"yes\",\"no\"]", "no", "Y", 1, "seed"));

        mockMvc.perform(get("/v1/manage/labels/" + labelId + "/attrs")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("occluded"));
    }

    @Test
    @DisplayName("GET_속성목록_WORKER_200")
    void getAttrs_WORKER_200() throws Exception {
        attrRepository.save(LsLabelAttr.create(labelId, "direction", "RADIO",
                "[\"N\",\"S\",\"E\",\"W\"]", null, "Y", 2, "seed"));

        mockMvc.perform(get("/v1/manage/labels/" + labelId + "/attrs")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("direction"));
    }

    @Test
    @DisplayName("POST_REVIEWER_201")
    void postAttr_REVIEWER_201() throws Exception {
        ObjectNode req = attrBody("occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 1);

        mockMvc.perform(post("/v1/manage/labels/" + labelId + "/attrs")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("occluded"))
                .andExpect(jsonPath("$.data.inputType").value("SELECT"));

        assertThat(attrRepository.existsByLabelIdAndName(labelId, "occluded")).isTrue();
    }

    @Test
    @DisplayName("POST_WORKER_403")
    void postAttr_WORKER_403() throws Exception {
        ObjectNode req = attrBody("occluded", "TEXT", null, null, "Y", 0);

        mockMvc.perform(post("/v1/manage/labels/" + labelId + "/attrs")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST_빈_name_400")
    void postAttr_빈name_400() throws Exception {
        ObjectNode req = attrBody("", "TEXT", null, null, "Y", 0);

        mockMvc.perform(post("/v1/manage/labels/" + labelId + "/attrs")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("POST_잘못된_input_type_400")
    void postAttr_잘못된inputType_400() throws Exception {
        ObjectNode req = attrBody("occluded", "INVALID_TYPE", null, null, "Y", 0);

        mockMvc.perform(post("/v1/manage/labels/" + labelId + "/attrs")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("POST_SELECT_타입_valuesJson_누락_시_400")
    void postAttr_SELECT_valuesJson누락_400() throws Exception {
        ObjectNode req = attrBody("occluded", "SELECT", null, null, "Y", 0);

        mockMvc.perform(post("/v1/manage/labels/" + labelId + "/attrs")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("DELETE_REVIEWER_204")
    void deleteAttr_REVIEWER_204() throws Exception {
        LsLabelAttr saved = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", null, "Y", 0, "seed"));

        mockMvc.perform(delete("/v1/manage/labels/" + labelId + "/attrs/" + saved.getAttrId())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        LsLabelAttr reloaded = attrRepository.findById(saved.getAttrId()).orElseThrow();
        assertThat(reloaded.getUseYn()).isEqualTo("N");
    }

    // ────────────────────────────── 객체별 속성값 ──────────────────────────────

    @Test
    @DisplayName("PUT_lbl_attrs_REVIEWER_200")
    void putLblAttrs_REVIEWER_200() throws Exception {
        LsLabelAttr attr = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 0, "seed"));
        LsDataLbl labelRow = labelRowRepository.save(LsDataLbl.builder()
                .srcSn(999_001L)
                .lblTypeCd("BBOX")
                .labelId(labelId)
                .label("person")
                .pointsJson("[[0,0],[10,10]]")
                .autoLblYn("N")
                .build());

        ObjectNode req = valuesBody(attr.getAttrId(), "yes");

        mockMvc.perform(put("/v1/labels/" + labelRow.getLblSn() + "/attrs")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        assertThat(valueRepository.findByLblSnAndAttrId(labelRow.getLblSn(), attr.getAttrId()))
                .isPresent()
                .get()
                .extracting("value")
                .isEqualTo("yes");
    }

    @Test
    @DisplayName("PUT_lbl_attrs_WORKER_200")
    void putLblAttrs_WORKER_200() throws Exception {
        LsLabelAttr attr = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 0, "seed"));
        LsDataLbl labelRow = labelRowRepository.save(LsDataLbl.builder()
                .srcSn(999_002L)
                .lblTypeCd("BBOX")
                .labelId(labelId)
                .label("person")
                .pointsJson("[[0,0],[10,10]]")
                .autoLblYn("N")
                .build());

        ObjectNode req = valuesBody(attr.getAttrId(), "no");

        mockMvc.perform(put("/v1/labels/" + labelRow.getLblSn() + "/attrs")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET_lbl_attrs_시나리오_라벨_속성_2개_정의_후_조회")
    void getLblAttrs_시나리오() throws Exception {
        LsLabelAttr a1 = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 1, "seed"));
        LsLabelAttr a2 = attrRepository.save(LsLabelAttr.create(labelId,
                "direction", "RADIO", "[\"N\",\"S\",\"E\",\"W\"]", null, "Y", 2, "seed"));
        LsDataLbl labelRow = labelRowRepository.save(LsDataLbl.builder()
                .srcSn(999_003L)
                .lblTypeCd("BBOX")
                .labelId(labelId)
                .label("person")
                .pointsJson("[[0,0],[10,10]]")
                .autoLblYn("N")
                .build());

        // upsert: 두 속성값 저장.
        ObjectNode req = objectMapper.createObjectNode();
        ArrayNode arr = req.putArray("values");
        arr.addObject().put("attrId", a1.getAttrId()).put("value", "yes");
        arr.addObject().put("attrId", a2.getAttrId()).put("value", "S");

        mockMvc.perform(put("/v1/labels/" + labelRow.getLblSn() + "/attrs")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // 조회.
        mockMvc.perform(get("/v1/labels/" + labelRow.getLblSn() + "/attrs")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].attrId").value(a1.getAttrId()))
                .andExpect(jsonPath("$.data[0].name").value("occluded"))
                .andExpect(jsonPath("$.data[0].value").value("yes"))
                .andExpect(jsonPath("$.data[1].name").value("direction"))
                .andExpect(jsonPath("$.data[1].value").value("S"));
    }

    @Test
    @DisplayName("GET_lbl_attrs_비인증_401")
    void getLblAttrs_비인증_401() throws Exception {
        mockMvc.perform(get("/v1/labels/1/attrs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET_manage_attrs_비인증_401")
    void getManageAttrs_비인증_401() throws Exception {
        mockMvc.perform(get("/v1/manage/labels/" + labelId + "/attrs"))
                .andExpect(status().isUnauthorized());
    }
}

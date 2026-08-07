package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.entity.LsLabelAttr;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelAttrRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
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

import java.time.LocalDateTime;

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
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;
    private Long labelId;

    /** WORKER(2001) 에게 LABELER 로 배정된 영상의 프레임. */
    private Long assignedSrcSn;
    /** 어느 WORKER 에게도 배정되지 않은 영상의 프레임 (CWE-639 IDOR 차단 검증용). */
    private Long notAssignedSrcSn;

    /** 존재하지 않는 라벨 객체 PK — 인가 검사가 404 를 403 으로 바꾸지 않는지 확인용. */
    private static final long MISSING_LBL_SN = 9_000_000_000_000L;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1001", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "2001", "WORKER",   "INTERNAL", issuer, 60);

        // [테스트 격리] DevSeedRunner(@Profile("local"), seed.enabled 기본 true)가 @SpringBootTest 부팅 시
        // dev-seed.sql 의 LS_LABEL 마스터(person 등)를 공유 PostgreSQL Testcontainer 에 비트랜잭션 커밋한다.
        // 잔존 행과 본 테스트의 person 재시드가 uk_ls_label_name UNIQUE 충돌을 일으키므로(테스트 순서 의존 오염),
        // @Transactional 트랜잭션 내에서 FK 의존(LS_LABEL_ATTR → LS_LABEL) 순으로 비운 뒤 시드한다.
        // 종료 시 롤백되어 dev-seed 행은 원복된다.
        attrRepository.deleteAllInBatch();
        labelRepository.deleteAllInBatch();

        LsLabel label = labelRepository.save(
                LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed"));
        labelId = label.getLabelId();

        // 영상 단위 인가(LabelAccessGuard) 검증을 위해 실제 영상·프레임·배정을 시드한다.
        // 라벨 객체(LS_DATA_LBL)는 SRC_SN 으로 프레임에, 프레임은 RAW_SN 으로 영상에 매달린다.
        long unique = System.nanoTime();
        assignedSrcSn = seedFrame("CLIP-ATTR-A-" + unique);
        notAssignedSrcSn = seedFrame("CLIP-ATTR-B-" + unique);

        // WORKER 2001 은 assignedSrcSn 이 속한 영상에만 배정된다.
        Long assignedRawSn = srcRepository.findById(assignedSrcSn).orElseThrow().getRawSn();
        assignmentRepository.save(LsTaskAssignment.createLabeler(assignedRawSn, 2001L, 1001L));
    }

    /** 영상 1건 + 프레임 1건을 시드하고 프레임 PK 를 반환한다. */
    private Long seedFrame(String clipId) {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-ATTR", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.now(), 30));
        LsDataSrc src = srcRepository.save(LsDataSrc.create(
                raw.getRawSn(), 0, "/var/raw/" + clipId + "_0.jpg", LocalDateTime.now()));
        return src.getSrcSn();
    }

    /** 지정 프레임에 속한 라벨 객체(LS_DATA_LBL) 1건 저장. */
    private LsDataLbl seedLabelRow(Long srcSn) {
        return labelRowRepository.save(LsDataLbl.builder()
                .srcSn(srcSn)
                .lblTypeCd("BBOX")
                .labelId(labelId)
                .label("person")
                .pointsJson("[[0,0],[10,10]]")
                .autoLblYn("N")
                .build());
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

        assertThat(attrRepository.existsByLabelIdAndAttrNm(labelId, "occluded")).isTrue();
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
    @DisplayName("PUT_lbl_attrs_REVIEWER_배정과_무관하게_200")
    void putLblAttrs_REVIEWER_200() throws Exception {
        LsLabelAttr attr = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 0, "seed"));
        // REVIEWER 는 전체 영상 검수 책임 — 미배정 영상의 객체여도 통과해야 한다.
        LsDataLbl labelRow = seedLabelRow(notAssignedSrcSn);

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
    @DisplayName("GET_lbl_attrs_REVIEWER_배정과_무관하게_200")
    void getLblAttrs_REVIEWER_200() throws Exception {
        LsLabelAttr attr = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 0, "seed"));
        LsDataLbl labelRow = seedLabelRow(notAssignedSrcSn);
        valueRepository.save(LsDataLblAttrVal.create(labelRow.getLblSn(), attr.getAttrId(), "yes"));

        mockMvc.perform(get("/v1/labels/" + labelRow.getLblSn() + "/attrs")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].value").value("yes"));
    }

    @Test
    @DisplayName("PUT_lbl_attrs_WORKER_본인_배정_200")
    void putLblAttrs_WORKER_200() throws Exception {
        LsLabelAttr attr = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 0, "seed"));
        LsDataLbl labelRow = seedLabelRow(assignedSrcSn);

        ObjectNode req = valuesBody(attr.getAttrId(), "no");

        mockMvc.perform(put("/v1/labels/" + labelRow.getLblSn() + "/attrs")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        assertThat(valueRepository.findByLblSnAndAttrId(labelRow.getLblSn(), attr.getAttrId()))
                .isPresent();
    }

    @Test
    @DisplayName("GET_lbl_attrs_WORKER_본인_배정_200")
    void getLblAttrs_WORKER_본인배정_200() throws Exception {
        LsLabelAttr attr = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 0, "seed"));
        LsDataLbl labelRow = seedLabelRow(assignedSrcSn);
        valueRepository.save(LsDataLblAttrVal.create(labelRow.getLblSn(), attr.getAttrId(), "yes"));

        mockMvc.perform(get("/v1/labels/" + labelRow.getLblSn() + "/attrs")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].value").value("yes"));
    }

    // ── CWE-639 IDOR — 역할만 맞으면 임의 lblSn 으로 타인 영상 객체에 도달하던 결함의 회귀 가드 ──

    @Test
    @DisplayName("GET_lbl_attrs_WORKER_본인_배정_아닌_영상의_객체_403")
    void getLblAttrs_WORKER_미배정_403() throws Exception {
        LsLabelAttr attr = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 0, "seed"));
        LsDataLbl labelRow = seedLabelRow(notAssignedSrcSn);
        valueRepository.save(LsDataLblAttrVal.create(labelRow.getLblSn(), attr.getAttrId(), "yes"));

        mockMvc.perform(get("/v1/labels/" + labelRow.getLblSn() + "/attrs")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("PUT_lbl_attrs_WORKER_본인_배정_아닌_영상의_객체_403_이고_값이_저장되지_않는다")
    void putLblAttrs_WORKER_미배정_403() throws Exception {
        LsLabelAttr attr = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 0, "seed"));
        LsDataLbl labelRow = seedLabelRow(notAssignedSrcSn);

        ObjectNode req = valuesBody(attr.getAttrId(), "yes");

        mockMvc.perform(put("/v1/labels/" + labelRow.getLblSn() + "/attrs")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        // 차단이 실제 쓰기 차단인지 확인 — 403 만 보고 저장 여부를 확인하지 않으면 가드가 헛돌아도 통과한다.
        assertThat(valueRepository.findByLblSnAndAttrId(labelRow.getLblSn(), attr.getAttrId()))
                .isEmpty();
    }

    @Test
    @DisplayName("GET_lbl_attrs_미존재_lblSn_은_인가와_무관하게_404_존재_오라클_방지")
    void getLblAttrs_미존재_404() throws Exception {
        mockMvc.perform(get("/v1/labels/" + MISSING_LBL_SN + "/attrs")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        mockMvc.perform(get("/v1/labels/" + MISSING_LBL_SN + "/attrs")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT_lbl_attrs_미존재_lblSn_은_인가와_무관하게_404_존재_오라클_방지")
    void putLblAttrs_미존재_404() throws Exception {
        LsLabelAttr attr = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 0, "seed"));
        ObjectNode req = valuesBody(attr.getAttrId(), "yes");

        mockMvc.perform(put("/v1/labels/" + MISSING_LBL_SN + "/attrs")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET_lbl_attrs_시나리오_라벨_속성_2개_정의_후_조회")
    void getLblAttrs_시나리오() throws Exception {
        LsLabelAttr a1 = attrRepository.save(LsLabelAttr.create(labelId,
                "occluded", "SELECT", "[\"yes\",\"no\"]", "no", "Y", 1, "seed"));
        LsLabelAttr a2 = attrRepository.save(LsLabelAttr.create(labelId,
                "direction", "RADIO", "[\"N\",\"S\",\"E\",\"W\"]", null, "Y", 2, "seed"));
        LsDataLbl labelRow = seedLabelRow(assignedSrcSn);

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

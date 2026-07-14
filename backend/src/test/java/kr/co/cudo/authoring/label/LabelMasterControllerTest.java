package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.label.entity.LsLabel;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 라벨 마스터 Controller 통합 테스트 (MockMvc + Security).
 *
 * <p>CVAT-Like 라벨 풀 포팅 Phase 1. V34 이후 PJT_ID 제거.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LabelMasterControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsLabelRepository labelRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1001", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "2001", "WORKER",   "INTERNAL", issuer, 60);

        // [테스트 격리] DevSeedRunner(@Profile("local"), seed.enabled 기본 true)가 @SpringBootTest 부팅 시
        // dev-seed.sql 의 LS_LABEL 마스터(person/car 등 13건)를 공유 PostgreSQL Testcontainer 에 커밋한다.
        // 이 행은 비트랜잭션 커밋이라 다른 컨텍스트 종료 후에도 잔존 → 본 테스트가 person/car 를 재시드하면
        // uk_ls_label_name UNIQUE 충돌 + 목록 길이 단언(length()==1) 어긋남(테스트 순서 의존 오염)을 일으킨다.
        // 본 클래스는 @Transactional("controlTransactionManager") 이므로, 트랜잭션 내에서 마스터 행을
        // 비운 뒤 시드하면 각 테스트가 빈 LS_LABEL 을 전제로 동작하고, 종료 시 롤백되어 dev-seed 행은 원복된다.
        labelRepository.deleteAllInBatch();
    }

    private ObjectNode body(String name, String color, String type, Integer sortNo) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.put("color", color);
        node.put("type", type);
        if (sortNo != null) {
            node.put("sortNo", sortNo);
        }
        return node;
    }

    @Test
    @DisplayName("GET_라벨목록_REVIEWER_200")
    void getLabels_REVIEWER_200() throws Exception {
        labelRepository.save(LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed"));

        mockMvc.perform(get("/v1/manage/labels")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("person"));
    }

    @Test
    @DisplayName("GET_라벨목록_WORKER_200")
    void getLabels_WORKER_200() throws Exception {
        labelRepository.save(LsLabel.create("car", "#3498DB", "BBOX", 2, "seed"));

        mockMvc.perform(get("/v1/manage/labels")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("car"));
    }

    @Test
    @DisplayName("POST_REVIEWER_201")
    void postLabel_REVIEWER_201() throws Exception {
        ObjectNode req = body("person", "#E74C3C", "BBOX", 1);

        mockMvc.perform(post("/v1/manage/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("person"))
                .andExpect(jsonPath("$.data.color").value("#E74C3C"))
                .andExpect(jsonPath("$.data.type").value("BBOX"))
                .andExpect(jsonPath("$.data.useYn").value("Y"));

        assertThat(labelRepository.existsByLabelNm("person")).isTrue();
    }

    @Test
    @DisplayName("POST_SKELETON_타입_REVIEWER_201")
    void postLabel_SKELETON_201() throws Exception {
        ObjectNode req = body("human-pose", "#2ECC71", "SKELETON", 3);

        mockMvc.perform(post("/v1/manage/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("SKELETON"));

        assertThat(labelRepository.existsByLabelNm("human-pose")).isTrue();
    }

    @Test
    @DisplayName("마스터_알수없는_타입은_400")
    void postLabel_알수없는타입_400() throws Exception {
        ObjectNode req = body("mystery", "#2ECC71", "UNKNOWN", 1);

        mockMvc.perform(post("/v1/manage/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        assertThat(labelRepository.existsByLabelNm("mystery")).isFalse();
    }

    @Test
    @DisplayName("POST_WORKER_403")
    void postLabel_WORKER_403() throws Exception {
        ObjectNode req = body("person", "#E74C3C", "BBOX", 1);

        mockMvc.perform(post("/v1/manage/labels")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST_빈_name_400")
    void postLabel_빈name_400() throws Exception {
        ObjectNode req = body("", "#E74C3C", "BBOX", 1);

        mockMvc.perform(post("/v1/manage/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("POST_잘못된_color_형식_400_예시_RGB_FFF")
    void postLabel_잘못된color_400() throws Exception {
        // 소문자 hex (대문자 강제 — 소문자는 거부)
        ObjectNode req = body("person", "#fff111", "BBOX", 1);

        mockMvc.perform(post("/v1/manage/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        // 3자리 hex (#FFF) 도 거부
        ObjectNode req2 = body("person", "#FFF", "BBOX", 1);
        mockMvc.perform(post("/v1/manage/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST_중복_name_409")
    void postLabel_중복name_409() throws Exception {
        labelRepository.save(LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed"));

        ObjectNode req = body("person", "#AABBCC", "BBOX", 2);
        mockMvc.perform(post("/v1/manage/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("DELETE_REVIEWER_204")
    void deleteLabel_REVIEWER_204() throws Exception {
        LsLabel saved = labelRepository.save(LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed"));

        mockMvc.perform(delete("/v1/manage/labels/" + saved.getLabelId())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        // soft delete 검증 — 행 자체는 남아있고 USE_YN='N'
        LsLabel reloaded = labelRepository.findById(saved.getLabelId()).orElseThrow();
        assertThat(reloaded.getUseYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("GET_비인증_401")
    void getLabels_비인증_401() throws Exception {
        mockMvc.perform(get("/v1/manage/labels"))
                .andExpect(status().isUnauthorized());
    }
}

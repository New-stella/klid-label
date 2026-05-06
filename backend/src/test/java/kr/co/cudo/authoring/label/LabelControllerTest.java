package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.time.Instant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class LabelControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsPjtUserAuthrtRepository authrtRepository;

    /** Phase 8 Gitea 자동 커밋 — 라벨 저장 후 호출됨. 외부 호출 차단을 위해 mock. */
    @MockBean private GiteaClient giteaClient;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;     // user 100 - assigned
    private String workerNotAssignedToken;  // user 101 - NOT assigned

    private Long srcSn;
    private Long rawSn;

    @BeforeEach
    void setup() {
        reviewerToken           = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken     = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerNotAssignedToken  = JwtTestSupport.token(secret, "101", "WORKER",   "INTERNAL", issuer, 60);

        // Phase 8 — Gitea 자동 커밋은 빈 SHA 로 mock (라벨 저장 자체에는 영향 없음)
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse("test-sha", "msg", "actor", Instant.now())));

        // raw + frame 시드
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-LBL-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        LsDataSrc src = LsDataSrc.create(rawSn, 0, "/var/raw/frame_0.jpg", LocalDateTime.now());
        src = srcRepository.save(src);
        srcSn = src.getSrcSn();

        // 작업자 100 만 배정 (작업자 101 은 미배정 — IDOR 차단 검증용)
        authrtRepository.save(LsPjtUserAuthrt.createLabeler(10L, rawSn, 100L, 1L));
    }

    @Test
    @DisplayName("LabelController_본인_배정_아닌_프레임_편집시_403")
    void notAssignedWorkerForbidden() throws Exception {
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "BBOX", "person",
                        List.of(List.of(10.0, 10.0), List.of(50.0, 50.0)), null)
        ));
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerNotAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("LabelController_bbox_좌표_저장시_AUTO_LBL_YN_N_저장_수동")
    void manualBboxStoredAsAutoNo() throws Exception {
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "BBOX", "person",
                        List.of(List.of(10.0, 10.0), List.of(50.0, 50.0)), null)
        ));
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));

        List<LsDataLbl> saved = labelRepository.findBySrcSn(srcSn);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getAutoLblYn()).isEqualTo("N");
        assertThat(saved.get(0).getLblTypeCd()).isEqualTo("BBOX");
    }

    @Test
    @DisplayName("LabelController_오토_라벨_수정시_AUTO_LBL_YN은_Y_유지")
    void editingAutoLabelKeepsAutoYes() throws Exception {
        // 사전: AUTO 라벨 1건 INSERT
        LsDataLbl auto = LsDataLbl.createAutoBbox(srcSn, "car",
                "[[5.0,5.0],[40.0,40.0]]", new BigDecimal("0.9000"));
        auto = labelRepository.save(auto);
        Long autoId = auto.getLblSn();

        // PUT 으로 좌표만 수정 (id 동봉)
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(autoId, "BBOX", "car",
                        List.of(List.of(15.0, 15.0), List.of(60.0, 60.0)), null)
        ));
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        LsDataLbl after = labelRepository.findById(autoId).orElseThrow();
        assertThat(after.getAutoLblYn()).isEqualTo("Y"); // 유지
        assertThat(after.getPointsJson()).contains("15.0").contains("60.0");
    }

    @Test
    @DisplayName("LabelController_좌표_음수_입력시_INVALID_INPUT_400")
    void negativeCoordinateRejected() throws Exception {
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "BBOX", "person",
                        List.of(List.of(-1.0, 10.0), List.of(50.0, 50.0)), null)
        ));
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("LabelController_좌표_개수_과다_입력시_INVALID_INPUT_400")
    void excessivePointsRejected() throws Exception {
        // CWE-770 DoS — polygon 최대 1000 점 제한
        java.util.ArrayList<List<Double>> manyPoints = new java.util.ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            manyPoints.add(List.of((double) i, (double) i));
        }
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "POLYGON", "wall", manyPoints, null)
        ));
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("LabelController_라벨_조회_GET_정상")
    void getLabelsByFrame() throws Exception {
        labelRepository.save(LsDataLbl.createAutoBbox(srcSn, "person",
                "[[10.0,10.0],[50.0,50.0]]", new BigDecimal("0.85")));

        mockMvc.perform(get("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].label").value("person"))
                .andExpect(jsonPath("$.data.items[0].autoLblYn").value("Y"));
    }

    @Test
    @DisplayName("LabelController_REVIEWER는_미배정_프레임도_조회_가능")
    void reviewerCanAccessAnyFrame() throws Exception {
        labelRepository.save(LsDataLbl.createAutoBbox(srcSn, "car",
                "[[1.0,1.0],[2.0,2.0]]", null));

        mockMvc.perform(get("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }
}

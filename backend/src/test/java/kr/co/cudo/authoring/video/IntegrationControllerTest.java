package kr.co.cudo.authoring.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.queue.entity.MngClipScheduleQue;
import kr.co.cudo.authoring.batch.queue.repository.MngClipScheduleQueRepository;
import kr.co.cudo.authoring.video.dto.VideoIngestRequest;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data-video.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class IntegrationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private MngClipScheduleQueRepository queueRepository;

    @Value("${authoring.integration.control-server.m2m-token}")
    private String m2mToken;

    private VideoIngestRequest baseReq(String vmsClipId, String prvcTypeCd) {
        return new VideoIngestRequest(
                vmsClipId,
                "CCTV-001",
                "EVT-001",
                "LCL-11",
                prvcTypeCd,
                "/clips/2026/05/clip001.mp4",
                Instant.parse("2026-05-01T10:00:00Z"),
                30
        );
    }

    @Test
    @DisplayName("M2M_토큰_없이_ingest_호출시_401_또는_403")
    void missingM2mTokenRejected() throws Exception {
        mockMvc.perform(post("/v1/integration/control/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(baseReq("clip-001", "ANONY"))))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s != 401 && s != 403) {
                        throw new AssertionError("expected 401 or 403, got " + s);
                    }
                });
    }

    @Test
    @DisplayName("유효한_M2M_토큰으로_ingest_호출시_201_또는_200")
    void validIngestSucceeds() throws Exception {
        mockMvc.perform(post("/v1/integration/control/videos")
                        .header("X-M2M-Token", m2mToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(baseReq("clip-100", "ANONY"))))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s != 201 && s != 200) {
                        throw new AssertionError("expected 201 or 200, got " + s);
                    }
                })
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vmsClipId").value("clip-100"))
                .andExpect(jsonPath("$.data.created").value(true));
    }

    @Test
    @DisplayName("중복_vmsClipId는_upsert로_처리됨")
    void duplicateVmsClipIdUpserted() throws Exception {
        mockMvc.perform(post("/v1/integration/control/videos")
                        .header("X-M2M-Token", m2mToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(baseReq("clip-200", "ANONY"))))
                .andExpect(jsonPath("$.data.created").value(true));

        mockMvc.perform(post("/v1/integration/control/videos")
                        .header("X-M2M-Token", m2mToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(baseReq("clip-200", "PRVC"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(false))
                .andExpect(jsonPath("$.data.prvcYn").value("Y"));

        // 같은 VMS_CLIP_ID 인 LS_DATA_RAW 가 1건만 존재해야 함
        assertThat(videoRepository.findByVmsClipId("clip-200")).isPresent();
    }

    @Test
    @DisplayName("PRVC_TYPE_CD_ANONY는_LS_DATA_RAW_PRVC_YN_N_저장")
    void anonyMapsToN() throws Exception {
        mockMvc.perform(post("/v1/integration/control/videos")
                        .header("X-M2M-Token", m2mToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(baseReq("clip-anony", "ANONY"))))
                .andExpect(jsonPath("$.data.prvcYn").value("N"));

        LsDataRaw raw = videoRepository.findByVmsClipId("clip-anony").orElseThrow();
        assertThat(raw.getPrvcYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("PRVC_TYPE_CD_PRVC는_PRVC_YN_Y_저장")
    void prvcMapsToY() throws Exception {
        mockMvc.perform(post("/v1/integration/control/videos")
                        .header("X-M2M-Token", m2mToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(baseReq("clip-prvc", "PRVC"))))
                .andExpect(jsonPath("$.data.prvcYn").value("Y"));

        LsDataRaw raw = videoRepository.findByVmsClipId("clip-prvc").orElseThrow();
        assertThat(raw.getPrvcYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("PRVC_TYPE_CD_PSDO는_PRVC_YN_Y_저장")
    void psdoMapsToY() throws Exception {
        mockMvc.perform(post("/v1/integration/control/videos")
                        .header("X-M2M-Token", m2mToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(baseReq("clip-psdo", "PSDO"))))
                .andExpect(jsonPath("$.data.prvcYn").value("Y"));

        LsDataRaw raw = videoRepository.findByVmsClipId("clip-psdo").orElseThrow();
        assertThat(raw.getPrvcYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("수신_즉시_MNG_CLIP_SCHEDULE_QUE에_LABELING_BATCH_INSERT")
    void enqueueOnIngest() throws Exception {
        mockMvc.perform(post("/v1/integration/control/videos")
                        .header("X-M2M-Token", m2mToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(baseReq("clip-queued", "ANONY"))))
                .andExpect(jsonPath("$.data.queSn").isNumber());

        LsDataRaw raw = videoRepository.findByVmsClipId("clip-queued").orElseThrow();
        long count = queueRepository.countByRawSnAndJobType(raw.getRawSn(), MngClipScheduleQue.JOB_LABELING_BATCH);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("cctvId_VMS_CCTV_ID_매핑_실패시_INVALID_INPUT_400")
    void unknownCctvRejected() throws Exception {
        VideoIngestRequest req = new VideoIngestRequest(
                "clip-bad", "CCTV-UNKNOWN", "EVT-001", "LCL-11", "ANONY",
                "/clips/x.mp4", Instant.parse("2026-05-01T10:00:00Z"), 30);
        mockMvc.perform(post("/v1/integration/control/videos")
                        .header("X-M2M-Token", m2mToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("filePath_경로순회_시도시_INVALID_INPUT_400")
    void pathTraversalRejected() throws Exception {
        VideoIngestRequest req = new VideoIngestRequest(
                "clip-traversal", "CCTV-001", "EVT-001", "LCL-11", "ANONY",
                "../../etc/passwd", Instant.parse("2026-05-01T10:00:00Z"), 30);
        mockMvc.perform(post("/v1/integration/control/videos")
                        .header("X-M2M-Token", m2mToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }
}

package kr.co.cudo.authoring.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.video.dto.VideoIngestRequest;
import org.junit.jupiter.api.BeforeEach;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data-video.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VideoControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.integration.control-server.m2m-token}") private String m2mToken;

    private String workerToken;

    @BeforeEach
    void setup() {
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("WORKER_토큰으로_GET_videos_정상_조회_페이징_적용")
    void workerListsVideosWithPaging() throws Exception {
        // M2M 으로 영상 1건 적재
        VideoIngestRequest req = new VideoIngestRequest(
                "clip-list-001", "CCTV-001", "EVT-001", "LCL-11", "ANONY",
                "/clips/list/01.mp4", Instant.parse("2026-05-01T10:00:00Z"), 60);
        mockMvc.perform(post("/v1/integration/control/videos")
                        .header("X-M2M-Token", m2mToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s != 201 && s != 200) {
                        throw new AssertionError("ingest failed status=" + s);
                    }
                });

        mockMvc.perform(get("/v1/videos?page=0&size=10")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].vmsClipId").value("clip-list-001"));
    }
}

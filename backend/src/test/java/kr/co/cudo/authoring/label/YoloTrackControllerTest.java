package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class YoloTrackControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @MockBean private AiServerClient aiServerClient;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private static Path tmpRawDir;

    @DynamicPropertySource
    static void overrideStorageRawPath(DynamicPropertyRegistry registry) throws IOException {
        tmpRawDir = Files.createTempDirectory("yolo-track-ctrl-raw-");
        registry.add("authoring.storage.raw-path", () -> tmpRawDir.toAbsolutePath().toString());
    }

    private String reviewerToken;
    private String workerAssignedToken;
    private String portalToken;

    private Long srcSn;
    private Long nextSrcSn;
    private Long rawSn;

    @BeforeEach
    void setup() throws IOException {
        reviewerToken       = JwtTestSupport.token(secret, "1",   "REVIEWER",    "INTERNAL", issuer, 60);
        workerAssignedToken = JwtTestSupport.token(secret, "100", "WORKER",      "INTERNAL", issuer, 60);
        portalToken         = JwtTestSupport.token(secret, "200", "PORTAL_USER", "PORTAL",   issuer, 60);

        Files.write(tmpRawDir.resolve("0.jpg"), new byte[]{0x01, 0x02});
        Files.write(tmpRawDir.resolve("1.jpg"), new byte[]{0x03, 0x04});

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-YT-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        srcSn = srcRepository.save(
                LsDataSrc.create(rawSn, 0, "0.jpg", LocalDateTime.now())).getSrcSn();
        nextSrcSn = srcRepository.save(
                LsDataSrc.create(rawSn, 1, "1.jpg", LocalDateTime.now())).getSrcSn();

        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    @Test
    @DisplayName("YoloTrack_path_srcSn_과_body_srcSn_불일치_시_400")
    void pathBodySrcSnMismatch() throws Exception {
        YoloTrackRequest req = new YoloTrackRequest(srcSn + 999, List.of(nextSrcSn));
        mockMvc.perform(post("/v1/frames/" + srcSn + "/yolo-track")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("YoloTrack_미인증_401")
    void unauthenticated() throws Exception {
        YoloTrackRequest req = new YoloTrackRequest(srcSn, List.of(nextSrcSn));
        mockMvc.perform(post("/v1/frames/" + srcSn + "/yolo-track")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("YoloTrack_권한없는역할_PORTAL_USER_403")
    void portalUserForbidden() throws Exception {
        YoloTrackRequest req = new YoloTrackRequest(srcSn, List.of(nextSrcSn));
        mockMvc.perform(post("/v1/frames/" + srcSn + "/yolo-track")
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("YoloTrack_정상_200")
    void tracksOk() throws Exception {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(
                new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 3)))));

        YoloTrackRequest req = new YoloTrackRequest(srcSn, List.of(nextSrcSn));
        mockMvc.perform(post("/v1/frames/" + srcSn + "/yolo-track")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frames.length()").value(2))
                .andExpect(jsonPath("$.data.frames[0].frameIndex").value(0))
                .andExpect(jsonPath("$.data.frames[0].detections[0].label").value("person"))
                .andExpect(jsonPath("$.data.frames[0].detections[0].trackId").value(3));
    }
}

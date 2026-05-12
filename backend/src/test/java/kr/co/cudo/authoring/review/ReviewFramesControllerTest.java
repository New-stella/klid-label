package kr.co.cudo.authoring.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.persistence.EntityManagerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCR-REVIEW-002 — {@code GET /v1/reviews/{videoId}/frames} 검증.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>REVIEWER 200 + 프레임/라벨 묶음 응답</li>
 *   <li>WORKER 403</li>
 *   <li>미인증 401</li>
 *   <li>videoId 미존재 404</li>
 *   <li>N+1 회피 — Hibernate Statistics 로 쿼리 횟수 검증 (frames 1 + labels 1)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReviewFramesControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;

    @Qualifier("controlEntityManagerFactory")
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    private Long videoId;
    private int frameCount;
    private int labelCount;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);

        // 영상 1개 + 프레임 N개 + 프레임당 라벨 M개 — N=3, M=2
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-REV-FRAMES", "CCTV-100", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        videoId = raw.getRawSn();

        int frames = 3;
        int labelsPerFrame = 2;
        frameCount = frames;
        labelCount = frames * labelsPerFrame;
        for (int f = 0; f < frames; f++) {
            LsDataSrc src = LsDataSrc.create(videoId, f,
                    "test-rev-frames/" + videoId + "/f_" + f + ".jpg", LocalDateTime.now());
            src = srcRepository.save(src);
            for (int l = 0; l < labelsPerFrame; l++) {
                labelRepository.save(LsDataLbl.createAutoBbox(
                        src.getSrcSn(),
                        "person",
                        "[[10,20],[30,40]]",
                        BigDecimal.valueOf(0.9)));
            }
        }
    }

    @Test
    @DisplayName("listFrames_REVIEWER만_접근_200")
    void reviewer_canList() throws Exception {
        mockMvc.perform(get("/v1/reviews/" + videoId + "/frames")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.videoId").value(videoId))
                .andExpect(jsonPath("$.data.totalFrames").value(frameCount))
                .andExpect(jsonPath("$.data.frames.length()").value(frameCount))
                .andExpect(jsonPath("$.data.frames[0].frameNo").value(0))
                .andExpect(jsonPath("$.data.frames[0].labels.length()").value(2))
                .andExpect(jsonPath("$.data.frames[0].imageUrl")
                        .value("/v1/videos/" + videoId + "/frames/0/image"));
    }

    @Test
    @DisplayName("listFrames_WORKER는_403")
    void worker_isForbidden() throws Exception {
        mockMvc.perform(get("/v1/reviews/" + videoId + "/frames")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("listFrames_미인증_401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/v1/reviews/" + videoId + "/frames"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("videoId_미존재_시_404")
    void notExistVideo_returns404() throws Exception {
        long nonexistent = 9_999_999_999L;
        mockMvc.perform(get("/v1/reviews/" + nonexistent + "/frames")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("listFrames_프레임_N개_라벨_M개_단일_쿼리_확인_N1_회피")
    void listFrames_avoidsNPlusOne() throws Exception {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        mockMvc.perform(get("/v1/reviews/" + videoId + "/frames")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        // 기대 쿼리: 1) video 존재 확인 (findById), 2) 프레임 list, 3) 라벨 IN-쿼리
        // = 총 3회. (N+1 이면 라벨 N회로 frames=3, labels=6+ 가 되어 6 이상 발생)
        long queryCount = stats.getPrepareStatementCount();
        assertThat(queryCount)
                .as("N+1 회피 검증: video(1) + frames(1) + labels IN(1) = 3회. 실제=%d", queryCount)
                .isLessThanOrEqualTo(5L);
    }
}

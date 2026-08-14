package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G-2 — GET /v1/videos/{rawSn}/auto-summary 실데이터 집계 E2E (MockMvc).
 *
 * <p>placeholder(status=PENDING 고정) → 실집계 전환:
 * <ul>
 *   <li>라벨이 있는 영상 → 총 프레임/총 라벨/클래스 분포/신뢰도 분포 실집계 반환.</li>
 *   <li>영상 없음 → 404 NOT_FOUND.</li>
 *   <li>프레임 0건(배치 미완료) → status='PENDING'.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AutoLabelSummaryControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    private Long createVideo() {
        // VMS_CLIP_ID 는 영상 단위 UNIQUE — 메서드 간 롤백이 없으므로 매 호출 고유 ID 발급.
        String clipId = "CLIP-AS-" + java.util.UUID.randomUUID();
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-AS", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        return rawRepository.save(raw).getRawSn();
    }

    private Long createFrame(Long rawSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, "/var/raw/frame_" + frameNo + ".jpg", LocalDateTime.now());
        return srcRepository.save(src).getSrcSn();
    }

    /** 자동 라벨 + AI_INFO(신뢰도) 페어 영속 — 실제 오토라벨링 흐름과 동일. */
    private void saveAutoLabel(Long rawSn, Long srcSn, String label, BigDecimal conf) {
        LsDataLbl lbl = lblRepository.save(
                LsDataLbl.createAutoBbox(srcSn, null, label, "[]", conf, null));
        // V6 — 생산이력이 라벨 행의 컬럼이라 AI 정보 행 대신 그 라벨에 직접 부여한다.
        lbl.applyAiSource(LsDataLbl.SRC_YOLO, conf);
        lblRepository.saveAndFlush(lbl);
    }

    @Test
    @DisplayName("라벨있는_영상_요약_실집계_반환_프레임_라벨_카운트_클래스_분포")
    void summaryWithLabels() throws Exception {
        Long rawSn = createVideo();
        Long f0 = createFrame(rawSn, 0);
        Long f1 = createFrame(rawSn, 1);
        // person 2건(고신뢰 0.95/0.80), car 1건(저신뢰 0.4).
        // 신뢰도는 @Transient 인 LsDataLbl.confScore 가 아닌 LS_DATA_LBL_AI_INFO 에 영속해야 집계된다.
        // labelId 는 LS_LABEL FK — 마스터 시드가 없으므로 null 로 두고 labelNm 으로 클래스 구분.
        saveAutoLabel(rawSn, f0, "person", new BigDecimal("0.95"));
        saveAutoLabel(rawSn, f0, "person", new BigDecimal("0.80"));
        saveAutoLabel(rawSn, f1, "car", new BigDecimal("0.40"));

        mockMvc.perform(get("/v1/videos/" + rawSn + "/auto-summary")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.videoId").value(rawSn))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.totalFrames").value(2))
                .andExpect(jsonPath("$.data.totalLabels").value(3))
                // 클래스 분포 2종 (person, car)
                .andExpect(jsonPath("$.data.classDistribution.length()").value(2))
                // 신뢰도 분포 3구간 (high/mid/low) 항상 존재
                .andExpect(jsonPath("$.data.buckets.length()").value(3))
                // 저신뢰(0.4 < 0.7) 프레임 1건 (f1)
                .andExpect(jsonPath("$.data.lowConfidenceFrames.length()").value(1))
                .andExpect(jsonPath("$.data.lowConfidenceFrames[0].srcSn").value(f1));
    }

    @Test
    @DisplayName("영상없음_404_NOT_FOUND")
    void videoNotFound404() throws Exception {
        mockMvc.perform(get("/v1/videos/9999999/auto-summary")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("프레임0_PENDING")
    void zeroFramesPending() throws Exception {
        Long rawSn = createVideo();

        mockMvc.perform(get("/v1/videos/" + rawSn + "/auto-summary")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.totalFrames").value(0))
                .andExpect(jsonPath("$.data.buckets").doesNotExist());
    }
}

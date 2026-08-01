package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 증강 목록/결과 조회 컨트롤러 검증.
 *
 * <p><b>시드 srcSn 은 9_000_xxx 대역 고정</b> — 공유 Testcontainers DB 에서 {@code LS_DATA_SRC.SRC_SN}
 * 은 시퀀스로 발급되므로, 700·850 같은 낮은 리터럴을 쓰면 다른 테스트가 새로 만든 실제 프레임의
 * SRC_SN 과 충돌해 "내 영상엔 증강행이 없어야 한다" 류 단언을 무작위로 깨뜨린다(실제 발생:
 * {@code ResolutionDerivativeFlowIntegrationTest} 가 srcSn=850 을 발급받아 이 클래스의 잔존행과 충돌).
 * 시퀀스가 절대 도달하지 않는 대역을 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AugmentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LsDataAugRepository repository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        repository.deleteAll();
    }

    @Test
    @DisplayName("AugmentController_srcSn_지정시_영상단위_잡카드_1건_types_정렬_WINTER_NIGHT_RAIN_RESOLUTION")
    void findBySrcSnReturnsSingleJobWithSortedTypes() throws Exception {
        Long srcSn = 9_000_700L;
        // 의도적으로 알파벳/생성 순서와 다르게 저장 — 같은 srcSn(영상) 은 1개 잡 카드로 그룹핑
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_RESOLUTION, new BigDecimal("88.00"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER,     new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_RAIN,       new BigDecimal("85.50"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_NIGHT,      new BigDecimal("80.20"), "system"));

        mockMvc.perform(get("/v1/augments")
                        .param("srcSn", srcSn.toString())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].videoCount").value(1))
                .andExpect(jsonPath("$.data.content[0].jobId").value(9000700))
                .andExpect(jsonPath("$.data.content[0].videoId").value(9000700))
                .andExpect(jsonPath("$.data.content[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.content[0].types.length()").value(4))
                .andExpect(jsonPath("$.data.content[0].types[0]").value("WINTER"))
                .andExpect(jsonPath("$.data.content[0].types[1]").value("NIGHT"))
                .andExpect(jsonPath("$.data.content[0].types[2]").value("RAIN"))
                .andExpect(jsonPath("$.data.content[0].types[3]").value("RESOLUTION"));
    }

    @Test
    @DisplayName("AugmentController_WORKER도_조회_가능")
    void workerCanQueryAugmentResults() throws Exception {
        Long srcSn = 9_000_701L;
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));

        mockMvc.perform(get("/v1/augments")
                        .param("srcSn", srcSn.toString())
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].types[0]").value("WINTER"));
    }

    @Test
    @DisplayName("AugmentController_srcSn_미지정시_영상단위_그룹_페이징_응답")
    void listAllPagedWhenSrcSnMissing() throws Exception {
        // 서로 다른 srcSn(영상) 2건 → 잡 카드 2건
        repository.save(LsDataAug.createPending(9_000_800L, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createPending(9_000_801L, LsDataAug.AUG_NIGHT,  new BigDecimal("85.00"), "system"));

        mockMvc.perform(get("/v1/augments")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].videoCount").value(1));
    }

    @Test
    @DisplayName("AugmentController_result_미종료_aug면_status_PROCESSING_반환_구_stub_PENDING_제거")
    void resultReturnsProcessingForPendingAug() throws Exception {
        Long jobId = 9_000_850L; // LS_DATA_SRC 매핑 부재 → srcSn 폴백 집계
        repository.save(LsDataAug.createPending(jobId, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));

        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(9000850))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    @DisplayName("AugmentController_result_전부_종료된_aug면_status_COMPLETED_반환")
    void resultReturnsCompletedForTerminalAug() throws Exception {
        Long jobId = 9_000_851L;
        LsDataAug aug = LsDataAug.createPending(jobId, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system");
        aug.applyGenerationResult(LsDataAug.STTS_ACCEPTED); // PENDING → ACCEPTED(terminal)
        repository.save(aug);

        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }
}

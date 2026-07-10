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
        Long srcSn = 700L;
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
                .andExpect(jsonPath("$.data.content[0].jobId").value(700))
                .andExpect(jsonPath("$.data.content[0].videoId").value(700))
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
        Long srcSn = 701L;
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
        repository.save(LsDataAug.createPending(800L, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createPending(801L, LsDataAug.AUG_NIGHT,  new BigDecimal("85.00"), "system"));

        mockMvc.perform(get("/v1/augments")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].videoCount").value(1));
    }
}

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
    @DisplayName("AugmentController_4종_증강_결과_묶음_조회_정렬_올바름_WINTER_NIGHT_RAIN_RESOLUTION")
    void findBySrcSnSortedByEnumOrder() throws Exception {
        Long srcSn = 700L;
        // 의도적으로 알파벳/생성 순서와 다르게 저장
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_RESOLUTION, new BigDecimal("88.00"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER,     new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_RAIN,       new BigDecimal("85.50"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_NIGHT,      new BigDecimal("80.20"), "system"));

        mockMvc.perform(get("/v1/augments")
                        .param("srcSn", srcSn.toString())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].augTypeCd").value("WINTER"))
                .andExpect(jsonPath("$.data[1].augTypeCd").value("NIGHT"))
                .andExpect(jsonPath("$.data[2].augTypeCd").value("RAIN"))
                .andExpect(jsonPath("$.data[3].augTypeCd").value("RESOLUTION"));
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
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("AugmentController_srcSn_미지정시_전체_페이징_응답")
    void listAllPagedWhenSrcSnMissing() throws Exception {
        repository.save(LsDataAug.createPending(800L, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createPending(801L, LsDataAug.AUG_NIGHT,  new BigDecimal("85.00"), "system"));

        mockMvc.perform(get("/v1/augments")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content").isArray());
    }
}

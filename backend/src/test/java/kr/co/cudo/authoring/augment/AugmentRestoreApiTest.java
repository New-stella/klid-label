package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.auth.JwtTestSupport;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 폐기 복구 API 의 <b>인가·입력 검증</b> (Phase 7).
 *
 * <p>복구는 "반려를 되돌려 다시 채택할 수 있게" 만드는 결정 경로이므로 accept/reject 와 동일하게
 * REVIEWER 전용이다. 도메인 동작(표식 해제 + 검수 재오픈 + 실삭제 제외)은
 * {@code AugmentDiscardPurgeIT} 가 실 DB 로 검증하고, 여기서는 경계(인증·권한·검증)만 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AugmentRestoreApiTest {

    @Autowired private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("복구는_미인증이면_401")
    void restoreRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/v1/augments/1/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"복구\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("복구는_WORKER면_403")
    void restoreIsReviewerOnly() throws Exception {
        mockMvc.perform(post("/v1/augments/1/restore")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"복구\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("복구_사유가_비면_400")
    void restoreRequiresReason() throws Exception {
        mockMvc.perform(post("/v1/augments/1/restore")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는_증강_복구는_404")
    void restoreUnknownAugmentIsNotFound() throws Exception {
        mockMvc.perform(post("/v1/augments/987654321/restore")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"복구\"}"))
                .andExpect(status().isNotFound());
    }
}

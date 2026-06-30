package kr.co.cudo.authoring.user;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Test
    @DisplayName("REVIEWER가_GET_users_workers_호출시_작업자_목록과_활성_태스크_수_반환")
    void reviewerListsWorkers() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/users/workers")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 시드: WORKER 권한 보유 활성 사용자 100, 101 (200 은 USE_YN='N')
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].userNo").value(100))
                .andExpect(jsonPath("$.data[0].activeTaskCount").value(0));
    }

    @Test
    @DisplayName("WORKER가_GET_users_workers_호출시_403")
    void workerForbiddenOnWorkersList() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/users/workers")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER가_GET_users_호출시_사용자_마스터_페이징_응답")
    void reviewerListsUsersPaged() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/users")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 시드: 1, 100, 101, 200 — 4명
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content[0].userNo").value(1));
    }

    @Test
    @DisplayName("REVIEWER가_GET_users_keyword_검색시_부분일치_결과만")
    void reviewerSearchesUsersByKeyword() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/users")
                        .param("keyword", "worker100")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].userId").value("worker100"));
    }

    @Test
    @DisplayName("WORKER가_GET_users_호출시_403")
    void workerForbiddenOnUsersList() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/users")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER가_GET_users_size_초과시_400")
    void reviewerSizeOverLimit() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/users")
                        .param("size", "200")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("WORKER가_PATCH_users_userNo_호출시_403")
    void workerForbiddenOnUserPatch() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        mockMvc.perform(patch("/v1/users/101")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"REVIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER가_PATCH_users_role_화이트리스트_위반시_400")
    void reviewerPatchInvalidRoleRejected() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("REVIEWER가_PATCH_users_미존재_userNo_404")
    void reviewerPatchMissingUser() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(patch("/v1/users/9999")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"WORKER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("REVIEWER가_PATCH_users_role_변경시_200_역할_반영")
    void reviewerPatchChangesRole() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        // 시드: userNo=100 은 WORKER → REVIEWER 로 변경
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"REVIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userNo").value(100))
                .andExpect(jsonPath("$.data.role").value("REVIEWER"));
    }

    @Test
    @DisplayName("REVIEWER가_PATCH_users_role_미제공시_200_역할_미변경")
    void reviewerPatchWithoutRoleKeepsRole() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        // role 없이 알 수 없는 필드만 전송 → 무시되고 역할 미변경 (시드 userNo=100 은 WORKER)
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"useYn\":\"Y\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("WORKER"));
    }
}

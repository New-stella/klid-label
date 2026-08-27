package kr.co.cudo.authoring.user;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
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

/**
 * 사용자 창구 — 인가 + <b>역할 변경에만</b> 얹히는 관리자 유효창. [@design API-004] [@design ADR-046]
 *
 * <p>★ 이 파일은 {@code @SpringBootTest + @AutoConfigureMockMvc} 여야 한다. standalone MockMvc 는
 * 인터셉터가 배선되지 않아 유효창 게이트가 <b>아예 돌지 않고</b>, 그러면 게이트를 잘못 넓게 걸어도
 * 전건 통과해 무방비인 채 초록이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminSessionTokenService adminSessionTokenService;
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    /** 그 사용자(JWT sub)에게 결박된 관리자 유효창 토큰. */
    private String adminSession(String sub) {
        return adminSessionTokenService.issue(sub, java.time.Instant.now()).token();
    }

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
                        .header(AdminSessionGate.HEADER, adminSession("100"))
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
                        .header(AdminSessionGate.HEADER, adminSession("1"))
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
                        .header(AdminSessionGate.HEADER, adminSession("1"))
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
                        .header(AdminSessionGate.HEADER, adminSession("1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"REVIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userNo").value(100))
                .andExpect(jsonPath("$.data.role").value("REVIEWER"));
    }

    // ------------------------------------------------------------------------
    // 관리자 유효창 — 역할 변경에만 얹히고 조회에는 얹히지 않는다
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("★REVIEWER라도_관리자_유효창_없이_PATCH_users_하면_403 — 인가에_가산되는_조건이다")
    void reviewerPatchWithoutAdminSessionForbidden() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"REVIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★남의_유효창으로는_PATCH_users_가_안_된다 — 토큰은_발급받은_사람에게_묶여_있다")
    void reviewerPatchWithOtherSubjectSessionForbidden() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .header(AdminSessionGate.HEADER, adminSession("999"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"REVIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★유효창_없이도_GET_users_workers_는_200 — 게이트를_클래스에_걸면_배정이_통째로_깨진다")
    void workersListStaysOpenWithoutAdminSession() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/users/workers")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("★유효창_없이도_GET_users_목록·단건은_200 — 조회에는_요구하지_않는다")
    void userReadsStayOpenWithoutAdminSession() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/users")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/users/100")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("REVIEWER가_PATCH_users_role_미제공시_200_역할_미변경")
    void reviewerPatchWithoutRoleKeepsRole() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        // role 없이 알 수 없는 필드만 전송 → 무시되고 역할 미변경 (시드 userNo=100 은 WORKER)
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .header(AdminSessionGate.HEADER, adminSession("1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"useYn\":\"Y\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("WORKER"));
    }
}

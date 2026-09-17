package kr.co.cudo.authoring.user;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.MediaType;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자 창구 — 인가 + <b>역할 변경에만</b> 얹히는 관리자 유효창.
 * [@design API-004] [@design ADR-046] [@design ADR-055] [@design AC-056]
 *
 * <p>★ 축이 둘로 갈렸다(ADR-055) — <b>조회는 검수자, 역할 변경은 관리자</b>다. 조회까지 관리자로
 * 올리면 작업 배정 흐름이 끊기고, 역할 변경을 검수자에게 두면 검수자가 스스로를 관리자로 올릴 수
 * 있어 권한 분리가 성립하지 않는다. 두 축을 "일관성" 을 이유로 통일하지 말 것.
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

    /**
     * 이 시험 전용 <b>관리자</b> 주체. 공용 시드에 관리자를 넣지 않는 이유는 그러면 시스템에 늘
     * 관리자가 있는 셈이 되어 관리자 부트스트랩 창구가 영구히 닫히고, 그 창구를 검증하는 시험들이
     * 통째로 깨지기 때문이다. 심고 지우는 책임을 이 클래스가 진다.
     */
    private static final long ADMIN_NO = 969_600_001L;

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminSessionTokenService adminSessionTokenService;
    @Autowired private UserRoleResolver userRoleResolver;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private JdbcTemplate jdbc;

    /**
     * ★ @Sql(test-data.sql)은 LS_USER_ROLE 을 통째로 비우고 다시 채운다. Spring 의 스크립트 실행은
     * JUnit 의 {@code @BeforeEach} <b>보다 먼저</b> 돌므로, 여기서 심으면 그 wipe 뒤에 남는다.
     */
    @BeforeEach
    void seedAdmin() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanupAdmin();
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'ADMIN', CURRENT_TIMESTAMP)",
                ADMIN_NO);
        userRoleResolver.evict(ADMIN_NO);
    }

    @AfterEach
    void tearDownAdmin() {
        cleanupAdmin();
    }

    private void cleanupAdmin() {
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", ADMIN_NO);
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", ADMIN_NO);
        userRoleResolver.evict(ADMIN_NO);
    }

    /** 관리자 토큰 — 역할 변경 창구의 정상 호출자. */
    private String adminToken() {
        return JwtTestSupport.token(secret, String.valueOf(ADMIN_NO), "ADMIN", "INTERNAL", issuer, 60);
    }

    /** 관리자 주체에게 결박된 유효창. */
    private String adminSessionForAdmin() {
        return adminSession(String.valueOf(ADMIN_NO));
    }

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
    @DisplayName("ADMIN이_PATCH_users_role_화이트리스트_위반시_400")
    void adminPatchInvalidRoleRejected() throws Exception {
        // ★ADMIN 은 이제 <허용> 값이다(ADR-055) — 목록 밖 값으로 판정해야 화이트리스트를 본다.
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSessionForAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ADMIN은_role_을_ADMIN_으로_지정할_수_있다_허용_역할_집합에_관리자가_들어왔다")
    void adminCanAssignAdminRole() throws Exception {
        mockMvc.perform(patch("/v1/users/101")
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSessionForAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    @DisplayName("★REVIEWER는_유효창이_있어도_PATCH_users_가_403 — 역할이_1차_축이고_유효창은_가산일_뿐이다")
    void reviewerWithAdminSessionStillForbidden() throws Exception {
        // ADR-055 — 검수자가 스스로 역할을 바꿀 수 있으면 권한 분리가 성립하지 않는다.
        //   유효창은 역할을 승격시키지 않으므로 관리자 패스워드를 알아도 통과하지 못한다.
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .header(AdminSessionGate.HEADER, adminSession("1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"REVIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN이_PATCH_users_미존재_userNo_404")
    void adminPatchMissingUser() throws Exception {
        mockMvc.perform(patch("/v1/users/9999")
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSessionForAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"WORKER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ADMIN이_PATCH_users_role_변경시_200_역할_반영")
    void adminPatchChangesRole() throws Exception {
        // 시드: userNo=100 은 WORKER → REVIEWER 로 변경
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSessionForAdmin())
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
    @DisplayName("★ADMIN이라도_관리자_유효창_없이_PATCH_users_하면_403 — 인가에_가산되는_조건이다")
    void adminPatchWithoutAdminSessionForbidden() throws Exception {
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"REVIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★남의_유효창으로는_PATCH_users_가_안_된다 — 토큰은_발급받은_사람에게_묶여_있다")
    void adminPatchWithOtherSubjectSessionForbidden() throws Exception {
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + adminToken())
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

    // ------------------------------------------------------------------------
    // 역할 변경 주체(MDFR_ID) — 인증 주체에서만 온다  [@design AC-1018]
    // ------------------------------------------------------------------------

    /** 그 사용자의 역할 행에 남은 마지막 수정자. 행이 없으면 null. */
    private String mdfrIdOf(long userNo) {
        List<String> rows = jdbc.queryForList(
                "SELECT MDFR_ID FROM LS_USER_ROLE WHERE USER_NO = ?", String.class, userNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    @DisplayName("★역할을_바꾸면_바꾼_사람이_MDFR_ID로_남는다")
    void roleChangeRecordsWhoChangedIt() throws Exception {
        // 시드 행에는 주체가 없다 — 이 컬럼이 생기기 전에 만들어진 행과 같은 상태다(AC 7).
        assertThat(mdfrIdOf(100L)).isNull();

        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSessionForAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"REVIEWER\"}"))
                .andExpect(status().isOk());

        // 인계 토큰 subject 가 그대로 남는다 — 권한 상승의 주체를 시각으로 이어 붙이지 않아도 된다.
        assertThat(mdfrIdOf(100L))
                .isEqualTo(String.valueOf(ADMIN_NO));
    }

    @Test
    @DisplayName("★요청_바디로_주체를_주입해도_무시된다 — 바디_값은_위조_가능하다")
    void actorInRequestBodyIsIgnored() throws Exception {
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSessionForAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        // 주체를 사칭하려는 바디 — 어떤 이름으로 넣어도 채택되지 않아야 한다.
                        .content("{\"role\":\"REVIEWER\",\"mdfrId\":\"999999\","
                                + "\"actor\":\"999999\",\"modifier\":\"999999\"}"))
                .andExpect(status().isOk());

        assertThat(mdfrIdOf(100L))
                .as("바디가 주장한 주체가 아니라 인증 주체가 남아야 한다")
                .isEqualTo(String.valueOf(ADMIN_NO))
                .isNotEqualTo("999999");
    }

    @Test
    @DisplayName("주체가_없던_기존_행도_조회·갱신에서_깨지지_않는다")
    void legacyRowWithoutModifierStillWorks() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        // MDFR_ID 가 null 인 상태에서 목록·단건 조회가 그대로 200 이다.
        mockMvc.perform(get("/v1/users").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));
        mockMvc.perform(get("/v1/users/100").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("WORKER"));

        // 그 행을 갱신하면 그때부터 주체가 채워진다(백필하지 않는다).
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSessionForAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"REVIEWER\"}"))
                .andExpect(status().isOk());
        assertThat(mdfrIdOf(100L)).isNotNull();
    }

    @Test
    @DisplayName("★역할_필터가_서버_전체_기준으로_걸리고_총건수가_실제_매칭_수다")
    void roleFilterIsServerWideWithMatchingTotal() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        // 시드: 1=REVIEWER, 100·101=WORKER, 200=WORKER(비활성) — 목록은 활성/비활성 모두 포함한다.
        mockMvc.perform(get("/v1/users")
                        .param("role", "REVIEWER")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].userNo").value(1));

        mockMvc.perform(get("/v1/users")
                        .param("role", "WORKER")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3));

        // 화이트리스트 밖 값은 400 — 필터가 조회로 내려가도 입구 검증은 그대로다.
        mockMvc.perform(get("/v1/users")
                        .param("role", "SUPERUSER")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ADMIN이_PATCH_users_role_미제공시_200_역할_미변경")
    void adminPatchWithoutRoleKeepsRole() throws Exception {
        // role 없이 알 수 없는 필드만 전송 → 무시되고 역할 미변경 (시드 userNo=100 은 WORKER)
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSessionForAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"useYn\":\"Y\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("WORKER"));
    }

    // ------------------------------------------------------------------------
    // 표시 이름 수정 — 같은 창구, 같은 유효창  [@design API-004] [@design AC-1018] [@design AC-1019]
    // ------------------------------------------------------------------------

    /** 저장소에 실제로 남은 표시 이름. 응답만 보면 저장 실패를 못 잡는다. */
    private String userNmOf(long userNo) {
        return jdbc.queryForObject("SELECT USER_NM FROM LS_ACNT_USER WHERE USER_NO = ?", String.class, userNo);
    }

    /** 저장소에 남은 수정일시. 시드 행은 null 이다(이 컬럼을 채우는 것은 표시정보 변경뿐). */
    private java.sql.Timestamp mdfcnDtOf(long userNo) {
        return jdbc.queryForObject("SELECT MDFCN_DT FROM LS_ACNT_USER WHERE USER_NO = ?",
                java.sql.Timestamp.class, userNo);
    }

    private org.springframework.test.web.servlet.ResultActions patchAsAdmin(long userNo, String body)
            throws Exception {
        return mockMvc.perform(patch("/v1/users/" + userNo)
                .header("Authorization", "Bearer " + adminToken())
                .header(AdminSessionGate.HEADER, adminSessionForAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("★이름을_고치면_200이고_저장소에_실제로_남는다 — 응답만_보면_저장_실패를_못_잡는다")
    void adminPatchChangesDisplayName() throws Exception {
        assertThat(userNmOf(100L)).isEqualTo("작업자100");

        // 앞뒤 공백·제어문자는 저장 직전에 걷힌다.
        patchAsAdmin(100L, "{\"userNm\":\"  새이름\\t \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userNm").value("새이름"))
                .andExpect(jsonPath("$.data.role").value("WORKER"));

        assertThat(userNmOf(100L)).isEqualTo("새이름");
        assertThat(mdfcnDtOf(100L)).as("실제 변경이므로 수정일시가 찍힌다").isNotNull();
    }

    @Test
    @DisplayName("★같은_이름을_다시_보내면_수정일시가_밀리지_않는다 — 멱등")
    void resendingSameDisplayNameIsNoOp() throws Exception {
        patchAsAdmin(100L, "{\"userNm\":\"새이름\"}").andExpect(status().isOk());
        java.sql.Timestamp first = mdfcnDtOf(100L);
        assertThat(first).isNotNull();

        // 같은 값 · 정규화하면 같아지는 값 — 어느 쪽도 행을 건드리면 안 된다.
        patchAsAdmin(100L, "{\"userNm\":\"새이름\"}").andExpect(status().isOk());
        patchAsAdmin(100L, "{\"userNm\":\"  새이름  \"}").andExpect(status().isOk());

        assertThat(mdfcnDtOf(100L))
                .as("무변경 저장이 수정일시를 밀면 「언제 프로필이 바뀌었나」가 오염된다")
                .isEqualTo(first);
        assertThat(userNmOf(100L)).isEqualTo("새이름");
    }

    @Test
    @DisplayName("★정규화_후_비는_이름은_400이고_저장된_이름이_그대로다")
    void blankDisplayNameRejected() throws Exception {
        for (String blank : List.of("   ", "\\t\\n")) {
            patchAsAdmin(100L, "{\"userNm\":\"" + blank + "\"}")
                    .andExpect(status().isBadRequest())
                    // 거부 응답은 어느 칸이 틀렸는지 필드 경로·배열 순번을 담지 않는다.
                    .andExpect(jsonPath("$.message",
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("userNm"))));
        }
        assertThat(userNmOf(100L)).isEqualTo("작업자100");
        assertThat(mdfcnDtOf(100L)).as("거절된 요청은 행을 건드리지 않는다").isNull();
    }

    /**
     * ★보이지 않는 공백만 채운 이름 — 창구 끝까지 가서 <b>저장소에 남지 않는지</b>까지 본다.
     *
     * <p>입력은 JSON {@code \}{@code uXXXX} 이스케이프로 쓴다(문자를 그대로 붙여넣으면 편집기·인코딩
     * 경로에서 조용히 일반 공백으로 바뀌어 "정규화가 걸렀다" 고 오판하는 공허한 시험이 된다).
     */
    @Test
    @DisplayName("★보이지_않는_공백만_보내면_400이고_저장된_이름이_그대로다 — NBSP·전각공백")
    void invisibleSpaceOnlyDisplayNameRejected() throws Exception {
        for (String invisible : List.of("\\u00A0", "\\u3000", "\\u2007", "\\u202F", "\\u200B", "\\uFEFF")) {
            patchAsAdmin(100L, "{\"userNm\":\"" + invisible + "\"}")
                    .andExpect(status().isBadRequest())
                    // 거부 응답은 어느 칸이 틀렸는지 필드 경로·배열 순번을 담지 않는다.
                    .andExpect(jsonPath("$.message",
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("userNm"))));
        }
        assertThat(userNmOf(100L)).isEqualTo("작업자100");
        assertThat(mdfcnDtOf(100L)).as("거절된 요청은 행을 건드리지 않는다").isNull();
    }

    @Test
    @DisplayName("★가장자리_NBSP는_걷힌_채_저장된다 — 저장소에서_되읽어_확인한다")
    void edgeInvisibleSpaceIsStrippedBeforeStore() throws Exception {
        patchAsAdmin(100L, "{\"userNm\":\"\\u00A0새이름\\u3000\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userNm").value("새이름"));

        // 응답만 보면 저장 실패를 못 잡는다 — 실제로 남은 값에 보이지 않는 문자가 섞이면 안 된다.
        assertThat(userNmOf(100L)).isEqualTo("새이름");
    }

    @Test
    @DisplayName("★저장_폭을_넘는_이름은_400이고_잘린_값이_저장되지_않는다")
    void overlongDisplayNameRejectedNotTruncated() throws Exception {
        int max = kr.co.cudo.authoring.user.service.UserDisplayNames.MAX_USER_NM_LENGTH;

        patchAsAdmin(100L, "{\"userNm\":\"" + "가".repeat(max + 1) + "\"}")
                .andExpect(status().isBadRequest());
        assertThat(userNmOf(100L)).as("잘라 담으면 다른 사람 이름이 된다").isEqualTo("작업자100");

        // 경계값 — 정확히 폭과 같은 길이는 통과한다(절단값으로 판정하면 여기서 깨진다).
        patchAsAdmin(100L, "{\"userNm\":\"" + "가".repeat(max) + "\"}")
                .andExpect(status().isOk());
        assertThat(userNmOf(100L)).hasSize(max);
    }

    @Test
    @DisplayName("★이름만_보낸_요청에도_관리자_유효창이_필요하다 — 요구는_창구_전체에_걸린다")
    void nameOnlyPatchStillRequiresAdminSession() throws Exception {
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userNm\":\"우회시도\"}"))
                .andExpect(status().isForbidden());

        assertThat(userNmOf(100L)).isEqualTo("작업자100");
    }

    @Test
    @DisplayName("★REVIEWER는_유효창이_있어도_이름을_고치지_못한다 — 역할이_1차_축이다")
    void reviewerCannotChangeDisplayName() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(patch("/v1/users/100")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .header(AdminSessionGate.HEADER, adminSession("1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userNm\":\"검수자가고침\"}"))
                .andExpect(status().isForbidden());

        assertThat(userNmOf(100L)).isEqualTo("작업자100");
    }

    /** 그 사용자의 역할 코드. 행이 없으면 null. */
    private String roleCdOf(long userNo) {
        List<String> rows = jdbc.queryForList(
                "SELECT ROLE_CD FROM LS_USER_ROLE WHERE USER_NO = ?", String.class, userNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * ★두 축을 함께 보냈는데 한쪽이 무효면 <b>요청 전체가 무효</b>다 — 반만 반영되지 않는다.
     *
     * <p>⚠ 이 시험은 "결과가 안전한가" 만 본다. 검증을 역할 분기 뒤로 되돌려도 트랜잭션이 롤백해
     * 여기서는 <b>여전히 초록</b>이다. "역할 쓰기가 일어났는가" 를 보는 짝은 {@code UserServiceTest}
     * 의 목 기반 시험이며, 둘 중 하나만 두면 구조가 조용히 되돌려진다.
     */
    @Test
    @DisplayName("★역할이_유효해도_이름이_무효면_400이고_역할도_이름도_그대로다 — 반만_반영되지_않는다")
    void invalidNameRejectsWholeRequestIncludingRoleAxis() throws Exception {
        assertThat(roleCdOf(100L)).isEqualTo("WORKER");

        patchAsAdmin(100L, "{\"role\":\"REVIEWER\",\"userNm\":\"   \"}")
                .andExpect(status().isBadRequest());

        assertThat(roleCdOf(100L)).as("거절된 요청이 역할만 바꿔 놓으면 안 된다").isEqualTo("WORKER");
        assertThat(userNmOf(100L)).isEqualTo("작업자100");
        assertThat(mdfcnDtOf(100L)).as("거절된 요청은 행을 건드리지 않는다").isNull();

        // 짝 — 이름을 보내지 않은 역할 변경은 종전대로 성공한다(검증 선행이 정상 경로를 막지 않는다).
        patchAsAdmin(100L, "{\"role\":\"REVIEWER\"}").andExpect(status().isOk());
        assertThat(roleCdOf(100L)).isEqualTo("REVIEWER");
    }

    @Test
    @DisplayName("이름을_고쳐도_로그인_식별자·활성여부는_그대로다")
    void displayNameChangeLeavesIdentityColumnsAlone() throws Exception {
        patchAsAdmin(200L, "{\"userNm\":\"비활성사용자\"}").andExpect(status().isOk());

        // 시드 200 은 USE_YN='N' — 이름 수정이 계정을 되살리면 안 된다(활성여부는 외부 소유값).
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT USER_ID, USE_YN, USER_EML_ADDR FROM LS_ACNT_USER WHERE USER_NO = ?", 200L);
        assertThat(row.get("user_id")).isEqualTo("worker200");
        assertThat(((String) row.get("use_yn")).trim()).isEqualTo("N");
        assertThat(row.get("user_eml_addr")).isEqualTo("w200@example.com");
        assertThat(userNmOf(200L)).isEqualTo("비활성사용자");
    }
}

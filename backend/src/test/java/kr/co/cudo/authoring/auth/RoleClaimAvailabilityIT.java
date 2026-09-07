package kr.co.cudo.authoring.auth;

import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>부트스트랩 창구 개폐 조회</b> — {@code GET /v1/auth/role-claim/availability}
 * (@design API-245 · AC-1098 · ADR-055).
 *
 * <h3>이 시험이 없으면 무엇을 놓치는가</h3>
 * <p>개폐를 묻는 수단이 없던 동안 화면은 그 사실을 <b>제출 응답으로 사후에</b> 알았다. 그래서
 * 관리자가 이미 있는 시스템에서도 등록 화면이 "아직 관리자가 없습니다" 를 먼저 띄우고, 사용자가
 * 패스워드를 넣어 제출한 뒤에야 거절을 받았다 — 화면이 거짓을 말하고 그 뒤에 막다른 길이 놓였다.
 *
 * <h3>★ 네 축을 함께 친다</h3>
 * <ul>
 *   <li><b>개폐</b> — 관리자 0명이면 열림, 한 명이라도 있으면 닫힘.</li>
 *   <li><b>판정의 단일성</b> — 같은 상태에서 이 조회가 "닫힘" 이라 답하고 제출도 409 로 거절한다.
 *       두 창구가 각자 개수를 세면 조회가 "열림" 이라 답한 직후 제출이 거절되는 어긋남이 난다.</li>
 *   <li><b>인증</b> — 토큰 없이 부르면 401. ★이 경로는 {@code SecurityConfig} 의
 *       {@code /v1/auth/**} <b>permitAll</b> 매처에 걸려 필터가 막지 않으므로, 인증 요구를 세우는
 *       것은 컨트롤러의 {@code @PreAuthorize} 뿐이다. 그 애노테이션이 사라지면 여기서 RED 다.</li>
 *   <li><b>인원수 미노출</b> — 응답 본문 어디에도 관리자 수가 없다.</li>
 * </ul>
 *
 * <p>전제(관리자 0명)는 {@link AdminBootstrapWindow} 로 명시적으로 세운다 — 개폐 판정이 전역
 * 카운트라 공유 컨테이너에 다른 시험이 남긴 관리자 행 하나로 전제가 무너진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RoleClaimAvailabilityIT {

    private static final String PATH = "/v1/auth/role-claim/availability";
    /** 이 시험이 만드는 관리자 표본 — 창이 닫힌 뒤를 재현한다. */
    private static final long OTHER_ADMIN_NO = 969_810_001L;
    /** 조회 호출자 — 역할 행이 없는 진입자(이 창구의 주된 호출자). */
    private static final long CALLER_NO = 969_810_002L;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRoleResolver userRoleResolver;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private JdbcTemplate jdbc;
    private AdminBootstrapWindow bootstrapWindow;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        bootstrapWindow = new AdminBootstrapWindow(jdbc, userRoleResolver);
        bootstrapWindow.park();
    }

    @AfterEach
    void tearDown() {
        cleanup();
        bootstrapWindow.restore();
    }

    private void cleanup() {
        for (long userNo : new long[]{OTHER_ADMIN_NO, CALLER_NO}) {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
            jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
            userRoleResolver.evict(userNo);
        }
    }

    private String internalToken(long userNo) {
        // 인계 토큰의 role 클레임은 인가에 쓰이지 않는다(LS_USER_ROLE 이 진실원).
        return JwtTestSupport.tokenWithName(
                secret, String.valueOf(userNo), "WORKER", "INTERNAL", issuer, "개폐조회", 60);
    }

    private String portalToken(long userNo) {
        return JwtTestSupport.tokenWithName(
                secret, String.valueOf(userNo), "PORTAL_USER", "PORTAL", issuer, "포털", 60);
    }

    private void makeAdmin(long userNo) {
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'ADMIN', CURRENT_TIMESTAMP)",
                userNo);
        userRoleResolver.evict(userNo);
    }

    @Test
    @DisplayName("★관리자가_0명이면_개폐조회가_열림이다")
    void openWhenNoAdmin() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + internalToken(CALLER_NO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    @DisplayName("★관리자가_한_명이라도_있으면_개폐조회가_닫힘이고_제출도_409_다_같은_판정")
    void closedWhenAdminExistsAndSubmitAlsoRejects() throws Exception {
        makeAdmin(OTHER_ADMIN_NO);

        // ① 조회는 닫힘이라 답한다
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + internalToken(CALLER_NO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false));

        // ② 같은 상태에서 제출도 거절된다 — 두 창구의 개폐 판정이 갈리지 않는다.
        //    패스워드는 대조 대상이 없어 어떤 값도 통과하지 않지만, 창 닫힘 게이트가 패스워드
        //    검증보다 <앞>이라 409 가 나야 한다(401 이면 게이트 순서가 뒤집힌 것이다).
        mockMvc.perform(post("/v1/auth/role-claim")
                        .header("Authorization", "Bearer " + internalToken(CALLER_NO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"adminPassword\":\"whatever-not-a-real-secret\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("★개폐조회_응답에_관리자_인원수가_없다")
    void responseCarriesNoAdminCount() throws Exception {
        makeAdmin(OTHER_ADMIN_NO);

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + internalToken(CALLER_NO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                // data 는 available 한 필드뿐이다 — 개수·목록이 끼어들 자리가 없다.
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data.adminCount").doesNotExist())
                .andExpect(jsonPath("$.data.count").doesNotExist());
    }

    @Test
    @DisplayName("★토큰_없이_개폐조회를_부르면_401_이다_403_이_아니다_매처가_permitAll_보다_앞이어야_한다")
    void unauthenticatedIsRejected() throws Exception {
        // ★이 창구는 SecurityConfig 의 /v1/auth/** permitAll 구역에 있다. 그 위의
        //   /v1/auth/role-claim/** 매처가 먼저 인증을 요구해야 필터 계층에서 401 로 끝난다.
        //   매처를 정확 경로 하나로 되돌리면 요청이 permitAll 로 통과해 메서드 보안(@PreAuthorize)이
        //   거절하고, 그 AccessDeniedException 이 GlobalExceptionHandler 를 타 <403> 이 된다.
        //   실측으로 확인한 변이 결과다: "Status expected:<401> but was:<403>".
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★포털_채널_토큰의_개폐조회는_거절된다_409_제출_창구와_같은_축")
    void portalChannelIsRejected() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + portalToken(CALLER_NO)))
                .andExpect(status().isConflict());
    }
}

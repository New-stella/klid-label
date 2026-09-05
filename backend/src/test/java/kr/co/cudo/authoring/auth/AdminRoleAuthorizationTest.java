package kr.co.cudo.authoring.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 역할의 인가 동작 (@design ADR-055 · @design AC-125).
 *
 * <p>고정하는 것은 셋이다.
 * <ol>
 *   <li><b>관리자는 검수자 자리에 그대로 들어간다</b> — 계층 덕분이며, 이것이 성립해야 기존
 *       검수자 권한 지점을 하나도 바꾸지 않는다는 설계가 참이다.</li>
 *   <li><b>작업자 전용 자리에는 들어가지 못한다</b> — 계층이 한 단계뿐임을 <b>인가 결과</b>로
 *       확인한다(계층 표기 자체는 {@code RoleHierarchyTest} 가 별도로 고정한다).</li>
 *   <li><b>채널 격리는 그대로다</b> — 포털 토큰은 내부 창구에, 내부 토큰은 포털 창구에 닿지
 *       못한다. 역할 축을 넓혀도 채널 축은 뚫리지 않는다.</li>
 * </ol>
 *
 * <p>★ 관리자 역할은 이 시험이 <b>직접 심고 지운다</b>. 공용 시드에 관리자를 넣으면 시스템에
 * 항상 관리자가 있는 셈이 되어 관리자 부트스트랩 창구가 영구히 닫히고, 그 창구를 검증하는
 * 시험들이 통째로 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class AdminRoleAuthorizationTest {

    /** 이 시험 전용 사용자번호 — 공용 시드·다른 시험과 겹치지 않는 대역. */
    private static final long ADMIN_USER_NO = 969_300_001L;

    @Autowired private MockMvc mockMvc;
    @Autowired private kr.co.cudo.authoring.common.security.UserRoleResolver userRoleResolver;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private JdbcTemplate jdbc;

    @BeforeEach
    void seedAdmin() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'ADMIN', CURRENT_TIMESTAMP)",
                ADMIN_USER_NO);
        userRoleResolver.evict(ADMIN_USER_NO);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", ADMIN_USER_NO);
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", ADMIN_USER_NO);
        userRoleResolver.evict(ADMIN_USER_NO);
    }

    private String adminToken() {
        // JWT 의 role 클레임은 인가에 쓰이지 않는다(LS_USER_ROLE 이 진실원) — 값은 표기일 뿐이다.
        return JwtTestSupport.token(secret, String.valueOf(ADMIN_USER_NO), "ADMIN", "INTERNAL", issuer, 60);
    }

    private String workerToken() {
        return JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    private String portalToken() {
        return JwtTestSupport.token(secret, "portal-admin-test", "PORTAL_USER", "PORTAL", issuer, 60);
    }

    @Test
    @DisplayName("★관리자_토큰은_검수자_전용_창구에서_200_계층이_그대로_통과시킨다")
    void adminPassesReviewerOnlyEndpoint() {
        // 계층을 비우면(구 상태) 이 시험이 RED — 관리자가 검수자 권한 지점 전부에서 막힌다.
        perform("/v1/manage/test", adminToken(), 200);
    }

    @Test
    @DisplayName("★관리자_토큰은_작업자_전용_창구에서_403_계층은_한_단계뿐이다")
    void adminIsDeniedOnWorkerOnlyEndpoint() {
        // 계층에 WORKER 를 끼우면 이 시험이 RED.
        perform("/v1/worker-only/test", adminToken(), 403);
    }

    @Test
    @DisplayName("작업자_토큰은_작업자_전용_창구에서_200_표본이_실제로_열려_있음을_확인")
    void workerPassesWorkerOnlyEndpoint() {
        // 표본이 <누구에게도> 닫혀 있으면 위 403 이 계층 때문인지 알 수 없다(항상 참인 시험 방지).
        perform("/v1/worker-only/test", workerToken(), 200);
    }

    @Test
    @DisplayName("★관리자_토큰은_포털_전용_창구에서_403_채널_격리는_역할로_뚫리지_않는다")
    void adminIsDeniedOnPortalEndpoint() {
        perform("/v1/portal/test", adminToken(), 403);
    }

    @Test
    @DisplayName("★포털_토큰은_내부_창구에서_403_채널_격리_불변")
    void portalTokenIsDeniedOnInternalEndpoint() {
        perform("/v1/manage/test", portalToken(), 403);
        perform("/v1/worker-only/test", portalToken(), 403);
    }

    private void perform(String path, String token, int expectedStatus) {
        try {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().is(expectedStatus));
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + path, e);
        }
    }
}

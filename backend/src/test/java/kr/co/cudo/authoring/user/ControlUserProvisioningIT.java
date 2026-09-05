package kr.co.cudo.authoring.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.auth.StubControllers;
import kr.co.cudo.authoring.auth.entity.LsMngrPswd;
import kr.co.cudo.authoring.auth.repository.LsMngrPswdRepository;
import kr.co.cudo.authoring.auth.service.AdminPasswordVerifier;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관제 인계 <b>미등록</b> 진입자 userNo 자동 프로비저닝 실동작 검증
 * (@design ADR-063 결정⑤ · @design UC-041 step5 · @design AC-1016, Testcontainers PostgreSQL).
 *
 * <h3>왜 단위 시험으로 충분하지 않은가</h3>
 * <p>수용기준이 전부 <b>DB 문장의 성질</b>이라 목으로는 증명되지 않는다 — ① 진입 순간 행이 실제로
 * 만들어지고 시퀀스가 disjoint 상위 범위(≥ 9e9)에서 userNo 를 뽑는가 ② 재진입이 <b>같은 userNo</b>
 * 로 수렴하고 중복 행을 만들지 않는가(원자 upsert) ③ 발급 userNo 로 principal 이 정규화되어
 * role-claim(parseLong)이 성립해 관리자 부트스트랩까지 이어지는가. 특히 ③은 발급된 신원이 인계
 * 토큰의 <b>비숫자 sub</b> 를 대체하지 못하면 {@code Long.parseLong("ctrl-…")} 에서 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class ControlUserProvisioningIT {

    /** 관제 숫자 userNo·dev 시드(≤ 9001)와 겹치지 않는 disjoint 상위 범위의 하한. */
    private static final long DISJOINT_FLOOR = 9_000_000_000L;

    /** 이 시험 전용 관제 로그인 ID 들 — 공용 시드·다른 시험과 겹치지 않는다(USER_ID 폭 20 이하). */
    private static final String USER_ID_A = "ctrl-prov-a";
    private static final String USER_ID_BOOT = "ctrl-prov-boot";
    private static final String USER_ID_PORTAL = "ctrl-prov-portal";
    private static final String USER_ID_NUMERIC = "ctrl-prov-num";
    /** 숫자 sub 회귀 표본의 사용자번호 — 이 경로는 프로비저닝을 타지 않아야 한다. */
    private static final long NUMERIC_SUB_USER_NO = 969_700_001L;

    private static final String HANDOVER_NAME = "관제인계관리자";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRoleResolver userRoleResolver;
    @Autowired private LsMngrPswdRepository mngrPswdRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;

    private JdbcTemplate jdbc;
    private String adminPlaintext;
    /**
     * 부트스트랩 창구 시험의 전제 = 관리자 0명. 공유 컨테이너의 잔여 ADMIN 행을 스냅샷해 잠시
     * 걷어내고 종료 시 <b>발견한 상태</b>로 되돌린다(전역 DELETE 로 다른 시험의 시드를 지우지 않는다).
     */
    private List<Map<String, Object>> parkedAdmins = List.of();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();

        // 부트스트랩 시험의 전제 — 시스템에 관리자 0명(창구가 열려 있다).
        parkAdmins();

        byte[] pw = new byte[24];
        new SecureRandom().nextBytes(pw);
        adminPlaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(pw);
        String hash = new BCryptPasswordEncoder(AdminPasswordVerifier.BCRYPT_COST).encode(adminPlaintext);
        LsMngrPswd row = mngrPswdRepository.findById(LsMngrPswd.SINGLE_ROW_SN).orElse(null);
        if (row == null) {
            mngrPswdRepository.save(LsMngrPswd.of(hash, "1", LocalDateTime.now()));
        } else {
            row.changeHash(hash, "1", LocalDateTime.now());
            mngrPswdRepository.save(row);
        }
    }

    @AfterEach
    void tearDown() {
        cleanup();
        restoreAdmins();
        mngrPswdRepository.deleteById(LsMngrPswd.SINGLE_ROW_SN);
    }

    /** 잔여 ADMIN 행을 스냅샷해 걷어낸다 — 전제(관리자 0명)를 명시적으로 단언한다. */
    private void parkAdmins() {
        parkedAdmins = new ArrayList<>(jdbc.queryForList(
                "SELECT USER_NO, ROLE_CD, REG_DT, UPD_DT FROM LS_USER_ROLE WHERE ROLE_CD = ?",
                Role.ADMIN.name()));
        if (!parkedAdmins.isEmpty()) {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE ROLE_CD = ?", Role.ADMIN.name());
            parkedAdmins.forEach(r -> userRoleResolver.evict(((Number) r.get("user_no")).longValue()));
        }
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM LS_USER_ROLE WHERE ROLE_CD = ?",
                Long.class, Role.ADMIN.name());
        assertThat(n).as("부트스트랩 전제 = 관리자 0명 — 공유 컨테이너의 잔여 ADMIN 을 걷어내지 못했다")
                .isZero();
    }

    /** 걷어냈던 ADMIN 행을 원래대로 되돌린다(이 시험이 만든 행을 지운 뒤에 부른다). */
    private void restoreAdmins() {
        for (Map<String, Object> r : parkedAdmins) {
            jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT, UPD_DT)"
                            + " VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT (USER_NO) DO UPDATE SET ROLE_CD = EXCLUDED.ROLE_CD,"
                            + " REG_DT = EXCLUDED.REG_DT, UPD_DT = EXCLUDED.UPD_DT",
                    r.get("user_no"), r.get("role_cd"), r.get("reg_dt"), r.get("upd_dt"));
            userRoleResolver.evict(((Number) r.get("user_no")).longValue());
        }
        parkedAdmins = List.of();
    }

    private void cleanup() {
        for (String userId : new String[]{USER_ID_A, USER_ID_BOOT, USER_ID_PORTAL, USER_ID_NUMERIC}) {
            for (Long userNo : userNosByUserId(userId)) {
                jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
                jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
                userRoleResolver.evict(userNo);
            }
        }
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", NUMERIC_SUB_USER_NO);
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", NUMERIC_SUB_USER_NO);
        userRoleResolver.evict(NUMERIC_SUB_USER_NO);
    }

    private List<Long> userNosByUserId(String userId) {
        return jdbc.queryForList("SELECT USER_NO FROM LS_ACNT_USER WHERE USER_ID = ?", Long.class, userId);
    }

    private long countByUserId(String userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM LS_ACNT_USER WHERE USER_ID = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    private String roleOf(long userNo) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT ROLE_CD FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
        return rows.isEmpty() ? null : (String) rows.get(0).get("role_cd");
    }

    /** 관제 인계 토큰 — 비숫자 sub + userId/userNm 클레임, iss 없이, INTERNAL 채널. */
    private String controlToken(String subject, String userId) {
        return JwtTestSupport.controlToken(secret, subject, userId, HANDOVER_NAME, "INTERNAL", 60);
    }

    private void enter(String token, int expectedStatus) throws Exception {
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("★관제_비숫자sub_첫진입시_disjoint상위_userNo로_행이_생기고_역할은_없다")
    void firstControlEntryProvisionsUserNoWithoutRole() throws Exception {
        // given: 그 로그인 ID 의 마스터 행이 없다
        assertThat(countByUserId(USER_ID_A)).isZero();

        // when: 아무 창구나 부른다(역할이 없어 인가는 무권한이지만 /v1/me 는 도달한다)
        enter(controlToken("ctrl-login-a", USER_ID_A), 200);

        // then: 로컬 식별 레코드가 정확히 1건 생긴다
        assertThat(countByUserId(USER_ID_A)).isEqualTo(1L);
        List<Long> nos = userNosByUserId(USER_ID_A);
        long userNo = nos.get(0);
        // ★disjoint 상위 시퀀스 발급 — 관제 숫자 userNo·시드와 충돌하지 않는다(AC 5)
        assertThat(userNo)
                .as("발급 userNo 는 관제 숫자 userNo·시드(≤9001)와 겹치지 않는 상위 범위여야 한다")
                .isGreaterThanOrEqualTo(DISJOINT_FLOOR);
        // ★역할은 부여하지 않는다(role=null) — 관제 권한을 우리 역할로 매핑하지 않는다(ADR-021)
        assertThat(roleOf(userNo)).as("발급 진입자는 무권한으로 남는다").isNull();
        assertThat(userRoleResolver.resolve(userNo)).isNull();
        // 표시정보는 인계 이름으로 채우고 USE_YN='Y' 활성 등록
        assertThat(jdbc.queryForObject("SELECT USER_NM FROM LS_ACNT_USER WHERE USER_NO = ?",
                String.class, userNo)).isEqualTo(HANDOVER_NAME);
        assertThat(jdbc.queryForObject("SELECT USE_YN FROM LS_ACNT_USER WHERE USER_NO = ?",
                String.class, userNo)).isEqualTo("Y");
    }

    @Test
    @DisplayName("★같은_로그인ID_재진입은_같은_userNo로_수렴하고_중복행을_만들지_않는다")
    void reentryConvergesToSameUserNo() throws Exception {
        enter(controlToken("ctrl-login-a", USER_ID_A), 200);
        long first = userNosByUserId(USER_ID_A).get(0);

        // 같은 로그인 ID 로 여러 번 더 진입한다(다른 sub 문자열이어도 userId 가 안정 키다)
        for (int i = 0; i < 4; i++) {
            enter(controlToken("ctrl-login-a", USER_ID_A), 200);
        }

        assertThat(countByUserId(USER_ID_A))
                .as("재진입이 중복 행을 만들면 이후 조회가 다중매칭 fail-closed 로 다시 잠긴다")
                .isEqualTo(1L);
        assertThat(userNosByUserId(USER_ID_A).get(0))
                .as("재진입은 같은 userNo 로 수렴한다").isEqualTo(first);
    }

    @Test
    @DisplayName("★발급_userNo로_principal이_정규화되어_role_claim으로_최초_관리자가_된다")
    void provisionedEntrantCanBootstrapAdmin() throws Exception {
        String token = controlToken("ctrl-login-boot", USER_ID_BOOT);

        // 첫 진입 — 발급된다(role=null)
        enter(token, 200);
        long userNo = userNosByUserId(USER_ID_BOOT).get(0);
        assertThat(roleOf(userNo)).isNull();

        // 같은 관제 토큰으로 부트스트랩 — 발급 userNo 로 principal 이 정규화되지 않았다면
        //   role-claim 의 parseLong("ctrl-login-boot") 이 깨져 여기서 실패한다.
        String body = objectMapper.writeValueAsString(Map.of(
                "role", Role.ADMIN.name(),
                "adminPassword", adminPlaintext));
        mockMvc.perform(post("/v1/auth/role-claim")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value(Role.ADMIN.name()));

        assertThat(roleOf(userNo))
                .as("발급 userNo 가 role-claim 식별로 이어져 그 행에 관리자가 부여된다")
                .isEqualTo(Role.ADMIN.name());
    }

    @Test
    @DisplayName("★숫자_sub는_userId클레임이_있어도_프로비저닝을_타지_않는다_회귀0")
    void numericSubIsNotProvisioned() throws Exception {
        // 숫자 sub(기존 경로)면 userId 클레임이 실려 있어도 그 값으로 행을 발급하지 않는다.
        //   (숫자 sub 무역할 진입자는 자동 등록으로 USER_NO 행이 생기되 USER_ID 는 채우지 않는다.)
        String token = JwtTestSupport.controlToken(
                secret, String.valueOf(NUMERIC_SUB_USER_NO), USER_ID_NUMERIC, HANDOVER_NAME, "INTERNAL", 60);

        enter(token, 200);

        assertThat(countByUserId(USER_ID_NUMERIC))
                .as("숫자 sub 경로는 userId 로 프로비저닝하지 않는다")
                .isZero();
    }

    @Test
    @DisplayName("★포털_채널은_비숫자sub_userId가_있어도_프로비저닝을_타지_않는다_회귀0")
    void portalChannelIsNotProvisioned() throws Exception {
        // 프로비저닝 분기 자체가 INTERNAL 전용이다 — 포털 토큰은 닿지 않는다.
        String token = JwtTestSupport.controlToken(
                secret, "portal-login", USER_ID_PORTAL, HANDOVER_NAME, "PORTAL", 60);

        mockMvc.perform(get("/v1/portal/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(countByUserId(USER_ID_PORTAL))
                .as("포털 채널은 프로비저닝 대상이 아니다").isZero();
    }
}

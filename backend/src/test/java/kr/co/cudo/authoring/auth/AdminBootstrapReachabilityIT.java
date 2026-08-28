package kr.co.cudo.authoring.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>최초 관리자를 만들 경로가 항상 존재한다</b> — 부트스트랩 도달 가능성 (@design AC-127 · ADR-055).
 *
 * <h3>이 시험이 없으면 무엇을 놓치는가</h3>
 * <p>창구의 <b>조건</b>이 아무리 옳아도 그 앞의 다른 장치가 요청을 막으면 시스템에 관리자를 만들
 * 경로가 0개가 된다. 실제로 그 교착이 났다 — 진입 시 자동 등록이 <b>같은 요청의 앞단</b>(인증
 * 필터)에서 작업자 역할을 부여했고 창구는 역할 보유자를 거절했다. 두 장치가 각각은 규정대로였다.
 *
 * <h3>★ 표본이 이 시험의 절반이다</h3>
 * <p>반드시 <b>저장소에 역할 행 자체가 없는</b> 사용자로 친다. 역할 코드가 {@link Role} 밖이라
 * 해석이 항상 비는 인위적 표본(관제 고유 역할 보유자)으로 치면 자동 등록이 일어나지 않아
 * <b>교착이 재현되지 않는다</b>. 그 표본 교체 때문에 이 결함이 전건 통과 상태로 숨어 있었다.
 *
 * <p>또 하나의 축은 <b>이미 운영 중인 시스템</b>이다 — 그곳에는 역할 없는 사용자가 애초에 없다.
 * 그래서 "이미 검수자인 사용자" 로도 함께 친다. 자동 등록과 무관하게 같은 결함을 잡는다.
 *
 * <p>관리자 패스워드는 {@link SecureRandom} 평문 + 그 BCrypt 해시를 자격 저장소에 심어 만든다
 * (평문 상수를 두면 Fortify "Hardcoded Password"). 이 시험이 심고 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class AdminBootstrapReachabilityIT {

    /** 저장소에 역할 행이 <b>없는</b> 사용자 — 신규 설치의 진짜 표본. */
    private static final long UNSEEDED_USER_NO = 969_800_001L;
    /** 이미 검수자인 사용자 — 업그레이드 배포의 표본(자동 등록과 무관하게 같은 결함을 잡는다). */
    private static final long EXISTING_REVIEWER_NO = 969_800_002L;
    /** 창이 닫힌 뒤를 만드는 데 쓰는 별개 관리자. */
    private static final long OTHER_ADMIN_NO = 969_800_003L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsMngrPswdRepository mngrPswdRepository;
    @Autowired private UserRoleResolver userRoleResolver;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private JdbcTemplate jdbc;
    private String adminPlaintext;
    private LsMngrPswd savedRow;
    /**
     * ★이 시험의 전제 — <b>시스템에 관리자가 0명</b>이다. 창구는 관리자 0명일 때만 열리는데 그
     * 판정이 전역 카운트라, 다른 시험이 공유 컨테이너에 남긴 관리자 행 하나로 "도달 가능성" 시험이
     * 통째로 닫힌 창구를 치게 된다(그러면 이 클래스가 무엇을 지키는지 알 수 없다).
     */
    private AdminBootstrapWindow bootstrapWindow;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        bootstrapWindow = new AdminBootstrapWindow(jdbc, userRoleResolver);
        bootstrapWindow.park();

        byte[] pw = new byte[24];
        new SecureRandom().nextBytes(pw);
        adminPlaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(pw);
        String hash = new BCryptPasswordEncoder(AdminPasswordVerifier.BCRYPT_COST).encode(adminPlaintext);

        savedRow = mngrPswdRepository.findById(LsMngrPswd.SINGLE_ROW_SN).orElse(null);
        if (savedRow == null) {
            mngrPswdRepository.save(LsMngrPswd.of(hash, "1", LocalDateTime.now()));
        } else {
            savedRow.changeHash(hash, "1", LocalDateTime.now());
            mngrPswdRepository.save(savedRow);
        }
    }

    @AfterEach
    void tearDown() {
        // 이 시험이 만든 행(창이 닫힌 뒤를 재현하는 관리자 표본 포함)을 먼저 지우고 원래 상태를 되돌린다.
        cleanup();
        bootstrapWindow.restore();
        // 자격 행은 다른 시험(관리자 유효창)이 공유하므로 이 시험이 만든 해시를 남기지 않는다.
        mngrPswdRepository.deleteById(LsMngrPswd.SINGLE_ROW_SN);
    }

    private void cleanup() {
        for (long userNo : new long[]{UNSEEDED_USER_NO, EXISTING_REVIEWER_NO, OTHER_ADMIN_NO}) {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
            jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
            userRoleResolver.evict(userNo);
        }
    }

    private String token(long userNo) {
        // 인계 토큰의 role 클레임은 인가에 쓰이지 않는다(LS_USER_ROLE 이 진실원).
        return JwtTestSupport.tokenWithName(
                secret, String.valueOf(userNo), "REVIEWER", "INTERNAL", issuer, "부트스트랩", 60);
    }

    private String body() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "role", Role.ADMIN.name(),
                "adminPassword", adminPlaintext));
    }

    private String roleOf(long userNo) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT ROLE_CD FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
        return rows.isEmpty() ? null : (String) rows.get(0).get("role_cd");
    }

    @Test
    @DisplayName("★역할_행이_없는_사용자가_role_claim_으로_최초_관리자가_된다")
    void unseededUserBecomesFirstAdmin() throws Exception {
        assertThat(roleOf(UNSEEDED_USER_NO)).isNull();

        mockMvc.perform(post("/v1/auth/role-claim")
                        .header("Authorization", "Bearer " + token(UNSEEDED_USER_NO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value(Role.ADMIN.name()));

        assertThat(roleOf(UNSEEDED_USER_NO)).isEqualTo(Role.ADMIN.name());
    }

    @Test
    @DisplayName("★다른_창구를_먼저_불러_작업자로_자동_등록된_뒤에도_최초_관리자가_된다")
    void autoRegisteredWorkerStillBecomesFirstAdmin() throws Exception {
        // ★이것이 실제 FE 동선이다 — 첫 화면이 /v1/me 를 부르고 그 요청에서 자동 등록이 일어난다.
        //   여기서 막히면 신규 설치에서 관리자를 만들 경로가 0개다.
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + token(UNSEEDED_USER_NO)))
                .andExpect(status().isOk());
        assertThat(roleOf(UNSEEDED_USER_NO))
                .as("전제 확인 — 자동 등록이 실제로 일어났다")
                .isEqualTo(Role.WORKER.name());

        mockMvc.perform(post("/v1/auth/role-claim")
                        .header("Authorization", "Bearer " + token(UNSEEDED_USER_NO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value(Role.ADMIN.name()));

        assertThat(roleOf(UNSEEDED_USER_NO))
                .as("기존 역할이 관리자로 교체된다 — DO NOTHING 계열이면 여기서 WORKER 로 남는다")
                .isEqualTo(Role.ADMIN.name());
    }

    @Test
    @DisplayName("★이미_검수자인_사용자도_최초_관리자가_된다_업그레이드_배포_축")
    void existingReviewerBecomesFirstAdmin() throws Exception {
        // 이미 운영 중인 시스템에는 역할 없는 사용자가 없다 — 자동 등록과 무관한 축이다.
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'REVIEWER', CURRENT_TIMESTAMP)",
                EXISTING_REVIEWER_NO);
        userRoleResolver.evict(EXISTING_REVIEWER_NO);

        mockMvc.perform(post("/v1/auth/role-claim")
                        .header("Authorization", "Bearer " + token(EXISTING_REVIEWER_NO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value(Role.ADMIN.name()));

        assertThat(roleOf(EXISTING_REVIEWER_NO)).isEqualTo(Role.ADMIN.name());
    }

    @Test
    @DisplayName("★관리자가_한_명_생긴_뒤에는_역할_보유자도_무보유자도_모두_거절된다_409")
    void windowClosesForEveryoneOnceAdminExists() throws Exception {
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'ADMIN', CURRENT_TIMESTAMP)",
                OTHER_ADMIN_NO);
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'REVIEWER', CURRENT_TIMESTAMP)",
                EXISTING_REVIEWER_NO);
        userRoleResolver.evict(OTHER_ADMIN_NO);
        userRoleResolver.evict(EXISTING_REVIEWER_NO);

        // 역할 없는 사용자
        mockMvc.perform(post("/v1/auth/role-claim")
                        .header("Authorization", "Bearer " + token(UNSEEDED_USER_NO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isConflict());
        // 이미 검수자인 사용자
        mockMvc.perform(post("/v1/auth/role-claim")
                        .header("Authorization", "Bearer " + token(EXISTING_REVIEWER_NO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isConflict());

        assertThat(roleOf(EXISTING_REVIEWER_NO))
                .as("거절된 요청이 역할을 바꾸면 안 된다")
                .isEqualTo(Role.REVIEWER.name());
        assertThat(roleOf(UNSEEDED_USER_NO))
                .as("자동 등록으로 작업자가 될 수는 있어도 관리자가 되지는 않는다")
                .isNotEqualTo(Role.ADMIN.name());
    }
}

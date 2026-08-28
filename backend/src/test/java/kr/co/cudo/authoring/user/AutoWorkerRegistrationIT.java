package kr.co.cudo.authoring.user;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.auth.StubControllers;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.user.dto.WorkerSummaryResponse;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.service.UserService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 진입 시 작업자 자동 등록 실동작 검증 (@design ADR-055 · @design AC-126, Testcontainers PostgreSQL).
 *
 * <h3>왜 단위 시험으로 충분하지 않은가</h3>
 * <p>핵심 성질 셋이 전부 <b>DB 문장의 성질</b>이라 목으로는 증명되지 않는다 — ① 행이 실제로
 * 만들어지는가 ② 두 번째 요청이 <b>쓰기를 되풀이하지 않는가</b> ③ 이미 있는 역할을 <b>덮지
 * 않는가</b>. 특히 ③은 {@code ON CONFLICT DO NOTHING} 이 {@code DO UPDATE} 로 바뀌는 순간
 * 무너지는데, 목 검증은 그 차이를 보지 못한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class AutoWorkerRegistrationIT {

    /** 이 시험 전용 사용자번호 대역 — 공용 시드·다른 시험과 겹치지 않는다. */
    private static final long NEW_USER_NO = 969_400_001L;
    private static final long PROMOTED_USER_NO = 969_400_002L;

    @Autowired private MockMvc mockMvc;
    @Autowired private LsUserRoleRepository lsUserRoleRepository;
    @Autowired private UserRoleResolver userRoleResolver;
    @Autowired private UserService userService;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        for (long userNo : new long[]{NEW_USER_NO, PROMOTED_USER_NO}) {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
            jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
            userRoleResolver.evict(userNo);
        }
    }

    /** 인계 토큰의 이름 클레임 — 자동 등록이 사용자 마스터의 표시 이름으로 쓴다. */
    private static final String HANDOVER_NAME = "관제인계이름";

    private String tokenFor(long userNo) {
        // JWT 의 role 클레임은 인가에 쓰이지 않는다 — LS_USER_ROLE 이 진실원이다.
        return JwtTestSupport.tokenWithName(
                secret, String.valueOf(userNo), "REVIEWER", "INTERNAL", issuer, HANDOVER_NAME, 60);
    }

    private void callAnyApi(long userNo, int expectedStatus) throws Exception {
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + tokenFor(userNo)))
                .andExpect(status().is(expectedStatus));
    }

    private String roleOf(long userNo) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT ROLE_CD FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
        return rows.isEmpty() ? null : (String) rows.get(0).get("role_cd");
    }

    @Test
    @DisplayName("★역할이_없는_내부_채널_진입자는_아무_창구를_불러도_WORKER_로_등록된다")
    void internalEntrantIsRegisteredAsWorker() throws Exception {
        // given: 사용자 행도 역할도 없다
        assertThat(roleOf(NEW_USER_NO)).isNull();

        // when: 아무 창구나 부른다 (검수자 전용이라 인가는 403 이지만, 등록은 인가와 무관하다)
        callAnyApi(NEW_USER_NO, 403);

        // then: 사용자 행과 작업자 역할이 만들어진다 — 이것이 배정 목록에 뜨기 위한 조건이다
        assertThat(roleOf(NEW_USER_NO)).isEqualTo(Role.WORKER.name());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LS_ACNT_USER WHERE USER_NO = ?",
                Long.class, NEW_USER_NO)).isEqualTo(1L);
        assertThat(userRoleResolver.resolve(NEW_USER_NO)).isEqualTo(Role.WORKER);

        // then: ★배정 대상 목록에 실제로 나타난다 — 이것이 자동 등록이 푸는 운영 제약이다
        //   (종전에는 대상자가 먼저 자가부여를 해야 이 목록에 떴다).
        //   ★이름도 함께 채워져야 한다 — 역할 클레임이 부트스트랩 전용으로 닫히면서 표시 정보를
        //     채우던 유일한 경로가 사라졌고, 비워 두면 배정 화면이 전원 공란 이름으로 그려진다.
        assertThat(userService.listWorkersWithTaskCount())
                .filteredOn(w -> NEW_USER_NO == w.userNo())
                .singleElement()
                .extracting(WorkerSummaryResponse::userNm)
                .isEqualTo(HANDOVER_NAME);
        assertThat(jdbc.queryForObject("SELECT USER_NM FROM LS_ACNT_USER WHERE USER_NO = ?",
                String.class, NEW_USER_NO)).isEqualTo(HANDOVER_NAME);
        assertThat(jdbc.queryForObject("SELECT USER_ID FROM LS_ACNT_USER WHERE USER_NO = ?",
                String.class, NEW_USER_NO))
                .as("userId 는 채우지 않는다 — 요청 바디에서 오는 위조 가능 값이다(CWE-639)")
                .isNull();
    }

    @Test
    @DisplayName("★같은_사용자가_연속_호출해도_쓰기는_되풀이되지_않는다_매_요청_UPDATE_가_아니다")
    void repeatedCallsDoNotRewrite() throws Exception {
        // given: 첫 호출로 등록된다
        callAnyApi(NEW_USER_NO, 403);
        Object regDtAfterFirst = jdbc.queryForObject(
                "SELECT REG_DT FROM LS_USER_ROLE WHERE USER_NO = ?", Object.class, NEW_USER_NO);
        assertThat(regDtAfterFirst).isNotNull();

        // when: 같은 사용자가 여러 번 더 호출한다
        for (int i = 0; i < 5; i++) {
            callAnyApi(NEW_USER_NO, 403);
        }

        // then: 역할 행은 갱신되지 않는다 — UPD_DT 가 찍혔다면 매 요청이 쓰기를 한 것이다.
        //   (등록 이후에는 역할이 해석되므로 등록기가 아예 호출되지 않는 것이 설계다.)
        assertThat(jdbc.queryForObject("SELECT UPD_DT FROM LS_USER_ROLE WHERE USER_NO = ?",
                Object.class, NEW_USER_NO))
                .as("자동 등록이 매 요청 쓰기가 되면 안 된다")
                .isNull();
        assertThat(jdbc.queryForObject("SELECT MDFCN_DT FROM LS_ACNT_USER WHERE USER_NO = ?",
                Object.class, NEW_USER_NO))
                .as("사용자 마스터도 매 요청 갱신되면 안 된다")
                .isNull();
    }

    @Test
    @DisplayName("★관리자가_지정한_역할이_자동_등록으로_되돌아가지_않는다")
    void assignedRoleIsNotOverwritten() throws Exception {
        // given: 관리자가 이 사용자를 검수자로 지정해 두었다
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'REVIEWER', CURRENT_TIMESTAMP)",
                PROMOTED_USER_NO);
        userRoleResolver.evict(PROMOTED_USER_NO);

        // when: 그 사용자가 다시 진입한다
        callAnyApi(PROMOTED_USER_NO, 200);

        // then: 역할은 그대로 REVIEWER — DO NOTHING 을 DO UPDATE 로 바꾸면 여기서 WORKER 가 되어 RED
        assertThat(roleOf(PROMOTED_USER_NO)).isEqualTo(Role.REVIEWER.name());
    }

    @Test
    @DisplayName("★역할_코드가_enum_밖이면_등록도_승격도_하지_않는다_fail_closed")
    void unknownRoleCodeIsNotSilentlyPromoted() throws Exception {
        // given: 저작도구 Role enum 에 없는 역할 코드(관제 고유 역할 등)
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'LEARN_MANAGER', CURRENT_TIMESTAMP)",
                PROMOTED_USER_NO);
        userRoleResolver.evict(PROMOTED_USER_NO);

        // when: 역할 해석은 null 이라 등록기가 호출되지만 삽입은 일어나지 않는다
        callAnyApi(PROMOTED_USER_NO, 403);

        // then: 기존 코드가 그대로 남고, 그 요청은 무권한으로 흐른다(WORKER 로 지어내지 않는다)
        assertThat(roleOf(PROMOTED_USER_NO)).isEqualTo("LEARN_MANAGER");
        assertThat(userRoleResolver.resolve(PROMOTED_USER_NO)).isNull();
        // ★이 사용자는 해석이 <매 요청> 비어 1차 게이트를 계속 통과한다. 2차 게이트(역할 행 존재
        //   확인)가 없으면 요청마다 쓰기 경로가 돈다 — 그 되돌림은 행이 안 바뀌어 <여기서는 안
        //   잡힌다>. 호출 횟수 축은 AutoWorkerRegistrarTest 가 소유한다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LS_ACNT_USER WHERE USER_NO = ?",
                Long.class, PROMOTED_USER_NO))
                .as("쓰기 경로에 들어가지 않으므로 사용자 행도 만들어지지 않는다")
                .isZero();
    }

    @Test
    @DisplayName("★포털_채널_사용자에게는_자동_등록이_적용되지_않는다")
    void portalChannelIsNotRegistered() throws Exception {
        // 포털 토큰의 주체는 이 마스터의 사용자번호와 매핑이 확인되지 않았다 — 추측으로 행을
        // 만들면 남의 행에 역할을 심게 된다(CWE-639).
        String portalToken = JwtTestSupport.token(
                secret, String.valueOf(NEW_USER_NO), "PORTAL_USER", "PORTAL", issuer, 60);

        mockMvc.perform(get("/v1/portal/test").header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isOk());

        assertThat(roleOf(NEW_USER_NO)).as("포털 채널은 등록 대상이 아니다").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LS_ACNT_USER WHERE USER_NO = ?",
                Long.class, NEW_USER_NO)).isZero();
    }

    @Test
    @DisplayName("비숫자_sub_는_등록하지_않는다_식별할_수_없는_주체로_행을_만들지_않는다")
    void nonNumericSubjectIsNotRegistered() throws Exception {
        long before = lsUserRoleRepository.countByRoleCd(Role.WORKER.name());
        String token = JwtTestSupport.token(secret, "not-a-number", "REVIEWER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        assertThat(lsUserRoleRepository.countByRoleCd(Role.WORKER.name()))
                .as("식별 불가 주체로 역할 행이 늘면 안 된다")
                .isEqualTo(before);
    }
}

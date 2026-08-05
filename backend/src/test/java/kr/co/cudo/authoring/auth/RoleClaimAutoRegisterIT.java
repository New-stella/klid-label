package kr.co.cudo.authoring.auth;

import kr.co.cudo.authoring.auth.dto.RoleClaimRequest;
import kr.co.cudo.authoring.auth.dto.RoleClaimResponse;
import kr.co.cudo.authoring.auth.service.RoleClaimRateLimiter;
import kr.co.cudo.authoring.auth.service.RoleClaimService;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역할 클레임 <b>사용자 자동등록</b> 실동작 검증 (V169, Testcontainers PostgreSQL).
 *
 * <h3>왜 단위 테스트로 충분하지 않은가</h3>
 * <p>{@code RoleClaimServiceTest} 는 리포지토리를 목으로 두므로 "upsert 를 <b>호출한다</b>"까지만
 * 고정한다. 실제로 <b>행이 만들어지는지</b>, 2노드 동시 클레임에서 <b>PK 위반으로 트랜잭션이
 * abort 되지 않는지</b>는 DB 없이는 증명되지 않는다 — 그리고 그것이 이 변경의 핵심 위험이다.
 *
 * <h3>왜 서비스를 직접 만드는가</h3>
 * <p>컨테이너의 {@code RoleClaimService} 빈을 쓰려면 설정된 관리자 패스워드 <b>평문</b>이 필요한데,
 * 테스트에 평문을 박으면 Fortify "Hardcoded Password" 다. 그래서 {@link SecureRandom} 평문 +
 * 그 BCrypt 해시로 서비스를 조립하고, 빈이 아니어서 적용되지 않는 {@code @Transactional} 은
 * {@link TransactionTemplate}(controlTransactionManager)으로 동일하게 재현한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class RoleClaimAutoRegisterIT {

    /** 이 테스트 전용 사용자 번호 구간 — 시드·다른 테스트와 충돌 회피. */
    private static final long NEW_USER_NO = 969_100_001L;
    private static final long EXISTING_USER_NO = 969_100_002L;
    private static final long RACE_USER_NO = 969_100_003L;
    private static final long OTHER_USER_NO = 969_100_004L;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LsUserRoleRepository lsUserRoleRepository;

    @Autowired
    private UserRoleResolver userRoleResolver;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager txManager;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private RoleClaimService service;
    private String adminPlaintext;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        tx = new TransactionTemplate(txManager);
        cleanup();

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        JwtKeyResolver keyResolver = () -> io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8));

        byte[] pw = new byte[24];
        new SecureRandom().nextBytes(pw);
        adminPlaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(pw);

        service = new RoleClaimService(userRepository, lsUserRoleRepository, userRoleResolver,
                keyResolver, new RoleClaimRateLimiter(null, 50, 500),
                new BCryptPasswordEncoder(12).encode(adminPlaintext), "klid-auth");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        for (long userNo : new long[]{NEW_USER_NO, EXISTING_USER_NO, RACE_USER_NO, OTHER_USER_NO}) {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
            jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
            userRoleResolver.evict(userNo);
        }
    }

    private TokenClaims actor(long userNo) {
        return new TokenClaims(String.valueOf(userNo), null, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));
    }

    private RoleClaimResponse claim(long userNo, Role role, String userId, String userNm) {
        return tx.execute(status -> service.claim(
                new RoleClaimRequest(role, adminPlaintext, userId, userNm), actor(userNo)));
    }

    private Map<String, Object> userRow(long userNo) {
        return jdbc.queryForMap("SELECT * FROM ls_acnt_user WHERE user_no = ?", userNo);
    }

    private long userCount(long userNo) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM ls_acnt_user WHERE user_no = ?",
                Long.class, userNo);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("역할클레임시_사용자가_없으면_자동등록된다")
    void 역할클레임시_사용자가_없으면_자동등록된다() {
        // given: 사용자 마스터에 없는 사용자(구 구현은 여기서 404 로 막혔다)
        assertThat(userCount(NEW_USER_NO)).isZero();

        // when: 관제가 localStorage 로 인계한 표시 정보와 함께 클레임
        RoleClaimResponse res = claim(NEW_USER_NO, Role.WORKER, "sjs123", "신재석");

        // then: 행이 실제로 만들어지고 역할도 부여된다
        Map<String, Object> row = userRow(NEW_USER_NO);
        assertThat(row.get("user_id")).isEqualTo("sjs123");
        assertThat(row.get("user_nm")).isEqualTo("신재석");
        assertThat(((String) row.get("use_yn")).trim()).isEqualTo("Y");
        assertThat(row.get("user_eml_addr")).as("관제 인계 키에 이메일이 없다 — 지어내지 않는다").isNull();
        assertThat(res.userName()).isEqualTo("신재석");
        assertThat(lsUserRoleRepository.findByUserNo(NEW_USER_NO)).isPresent();
    }

    @Test
    @DisplayName("역할클레임시_기존_사용자는_이름이_갱신된다")
    void 역할클레임시_기존_사용자는_이름이_갱신된다() {
        // given: 이미 있는 사용자 — 운영자가 <비활성>으로 내려둔 상태
        jdbc.update("INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USER_EML_ADDR, USE_YN, REG_DT)"
                        + " VALUES (?, ?, ?, ?, 'N', CURRENT_TIMESTAMP)",
                EXISTING_USER_NO, "old-id", "옛이름", "old@example.com");

        // when: 관제가 바뀐 이름을 인계하며 클레임
        RoleClaimResponse res = claim(EXISTING_USER_NO, Role.WORKER, "new-id", "새이름");

        // then: 표시 정보는 갱신된다
        Map<String, Object> row = userRow(EXISTING_USER_NO);
        assertThat(row.get("user_id")).isEqualTo("new-id");
        assertThat(row.get("user_nm")).isEqualTo("새이름");
        assertThat(res.userName()).isEqualTo("새이름");
        assertThat(row.get("mdfcn_dt")).as("실제 갱신이므로 수정일시가 찍힌다").isNotNull();

        // then: ★USE_YN 은 그대로 'N' 이다 — 운영자가 비활성화한 사용자가 재클레임으로 되살아나면
        //   안 된다. 이메일도 관제 인계 키에 없으므로 건드리지 않는다.
        assertThat(((String) row.get("use_yn")).trim()).isEqualTo("N");
        assertThat(row.get("user_eml_addr")).isEqualTo("old@example.com");
    }

    @Test
    @DisplayName("표시정보_미전달시_기존값이_보존된다")
    void 표시정보_미전달시_기존값이_보존된다() {
        // 하위호환 — 구 FE 는 userId/userNm 을 보내지 않는다. 그 요청이 기존 이름을 지우면 안 된다.
        jdbc.update("INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USE_YN, REG_DT)"
                        + " VALUES (?, ?, ?, 'Y', CURRENT_TIMESTAMP)",
                EXISTING_USER_NO, "keep-id", "보존이름");

        claim(EXISTING_USER_NO, Role.WORKER, null, null);

        Map<String, Object> row = userRow(EXISTING_USER_NO);
        assertThat(row.get("user_id")).isEqualTo("keep-id");
        assertThat(row.get("user_nm")).isEqualTo("보존이름");
        assertThat(row.get("mdfcn_dt")).as("바뀐 값이 없으면 UPDATE 자체를 하지 않는다(no-op)").isNull();
    }

    @Test
    @DisplayName("REVIEWER_자가부여가_허용된다")
    void REVIEWER_자가부여가_허용된다() {
        // ★2026-08-04 사용자 확정 — 온프렘 신규 설치의 최초 REVIEWER 부트스트랩 경로다.
        RoleClaimResponse res = claim(NEW_USER_NO, Role.REVIEWER, "rvw1", "검수자");

        assertThat(res.role()).isEqualTo("REVIEWER");
        assertThat(userRoleResolver.resolve(NEW_USER_NO)).isEqualTo(Role.REVIEWER);
    }

    @Test
    @DisplayName("userNo_는_JWT_sub_에서만_취한다")
    void userNo_는_JWT_sub_에서만_취한다() {
        // ★CWE-639 — 표시 정보를 남의 것처럼 위조해도 <자기 행>만 생긴다.
        //   (요청 DTO 에는 userNo 필드 자체가 없다 — RoleClaimServiceTest 가 구조로 고정한다.)
        claim(NEW_USER_NO, Role.WORKER, "victim", "피해자");

        assertThat(userCount(NEW_USER_NO)).isEqualTo(1);
        assertThat(userCount(OTHER_USER_NO)).as("바디 값으로 남의 행이 생기면 안 된다").isZero();
        assertThat(lsUserRoleRepository.findByUserNo(OTHER_USER_NO)).isEmpty();
    }

    @Test
    @DisplayName("동시_클레임에도_사용자가_중복등록되지_않는다")
    void 동시_클레임에도_사용자가_중복등록되지_않는다() throws Exception {
        // ★CWE-362 — 2노드 Active-Active 라 같은 사용자가 두 노드에서 동시에 클레임할 수 있다.
        //   "조회 후 INSERT" 였다면 둘 다 "없음"을 보고 각각 INSERT → PK 위반 → PostgreSQL 이
        //   <트랜잭션 전체를 abort> 시켜 클레임이 실패한다. ON CONFLICT 는 예외 없이 흡수한다.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Runnable upsert = () -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                tx.executeWithoutResult(s ->
                        userRepository.upsertUser(RACE_USER_NO, "race", "동시등록"));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        };
        Thread t1 = new Thread(upsert);
        Thread t2 = new Thread(upsert);
        t1.start();
        t2.start();
        start.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).as("동시 upsert 가 교착 없이 끝나야 한다").isTrue();
        t1.join(5_000);
        t2.join(5_000);

        assertThat(failure.get())
                .as("동시 등록이 예외(PK 위반)로 실패하면 클레임 트랜잭션이 통째로 죽는다")
                .isNull();
        assertThat(userCount(RACE_USER_NO)).as("중복 등록 없이 정확히 1행").isEqualTo(1);
        assertThat(userRow(RACE_USER_NO).get("user_nm")).isEqualTo("동시등록");
    }
}

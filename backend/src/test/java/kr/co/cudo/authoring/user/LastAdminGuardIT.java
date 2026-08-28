package kr.co.cudo.authoring.user;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.user.dto.UserUpdateRequest;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 마지막 관리자 보호 실동작 검증 (@design AC-124 · @design API-004, Testcontainers PostgreSQL).
 *
 * <h3>왜 단위 시험으로 충분하지 않은가</h3>
 * <p>이 기준의 핵심은 <b>동시성</b>이다. 두 관리자가 서로를 동시에 내릴 때 각자 조회 시점에는
 * 둘 다 통과해 결과적으로 관리자가 0명이 되는 write skew 를 막아야 하는데, 그 방어의 근거가
 * DB 잠금({@code FOR UPDATE})이라 목으로는 증명되지 않는다.
 *
 * <p>관리자 역할 행은 이 시험이 직접 심고 지운다 — 공용 시드에 넣으면 관리자 부트스트랩 창구가
 * 영구히 닫혀 그 창구를 검증하는 시험들이 깨진다.
 */
@SpringBootTest
@ActiveProfiles("local")
class LastAdminGuardIT {

    /** 이 시험 전용 사용자번호 대역 — 공용 시드·다른 시험과 겹치지 않는다. */
    private static final long ADMIN_A = 969_500_001L;
    private static final long ADMIN_B = 969_500_002L;

    @Autowired private UserService userService;
    @Autowired private UserRoleResolver userRoleResolver;
    @Autowired private LsUserRoleRepository lsUserRoleRepository;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager txManager;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

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
        for (long userNo : new long[]{ADMIN_A, ADMIN_B}) {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
            jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
            userRoleResolver.evict(userNo);
        }
    }

    /** 사용자 마스터 + 역할을 심는다. {@code update} 는 마스터 행이 없으면 404 를 낸다. */
    private void seedUser(long userNo, String roleCd) {
        jdbc.update("INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USE_YN, REG_DT)"
                        + " VALUES (?, ?, ?, 'Y', CURRENT_TIMESTAMP)",
                userNo, "u" + userNo, "관리자" + userNo);
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, ?, CURRENT_TIMESTAMP)",
                userNo, roleCd);
        userRoleResolver.evict(userNo);
    }

    private String roleOf(long userNo) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT ROLE_CD FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
        return rows.isEmpty() ? null : (String) rows.get(0).get("role_cd");
    }

    private long adminCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_USER_ROLE WHERE ROLE_CD = ? AND USER_NO IN (?, ?)",
                Long.class, Role.ADMIN.name(), ADMIN_A, ADMIN_B);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("★관리자가_한_명뿐이면_강등은_409_거절되고_역할은_그대로다")
    void lastAdminCannotBeDemoted() {
        seedUser(ADMIN_A, Role.ADMIN.name());

        assertThatThrownBy(() -> userService.update(ADMIN_A, new UserUpdateRequest(Role.REVIEWER.name())))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(roleOf(ADMIN_A)).as("거절된 요청이 역할을 바꾸면 안 된다").isEqualTo(Role.ADMIN.name());
    }

    @Test
    @DisplayName("★관리자가_둘이면_한_명을_내릴_수_있다_보호가_과하게_잠그지_않는다")
    void oneOfTwoAdminsCanBeDemoted() {
        seedUser(ADMIN_A, Role.ADMIN.name());
        seedUser(ADMIN_B, Role.ADMIN.name());

        userService.update(ADMIN_B, new UserUpdateRequest(Role.WORKER.name()));

        assertThat(roleOf(ADMIN_B)).isEqualTo(Role.WORKER.name());
        assertThat(adminCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막_관리자를_관리자로_다시_지정하는_요청은_막지_않는다")
    void reassigningSameRoleIsNotBlocked() {
        seedUser(ADMIN_A, Role.ADMIN.name());

        // 동일 역할 재적용은 서비스가 애초에 skip 한다(멱등) — 보호가 이 경로를 막으면 안 된다.
        userService.update(ADMIN_A, new UserUpdateRequest(Role.ADMIN.name()));

        assertThat(roleOf(ADMIN_A)).isEqualTo(Role.ADMIN.name());
    }

    @Test
    @DisplayName("관리자가_아닌_사용자의_역할_변경은_보호와_무관하게_성공한다")
    void nonAdminDemotionIsUnaffected() {
        seedUser(ADMIN_A, Role.REVIEWER.name());

        userService.update(ADMIN_A, new UserUpdateRequest(Role.WORKER.name()));

        assertThat(roleOf(ADMIN_A)).isEqualTo(Role.WORKER.name());
    }

    @Test
    @DisplayName("★관리자_행_잠금이_동시_트랜잭션을_직렬화한다_write_skew_차단의_근거")
    void adminRowLockSerializesConcurrentReads() throws Exception {
        // 위 동시 강등 시험은 스레드 타이밍에 기대므로 잠금 제거를 <매번> 잡지는 못한다(실측).
        //   여기서는 순서를 강제해 잠금의 성질 자체를 결정적으로 본다:
        //   ① tx1 이 관리자 행을 잠근다 → ② tx2 가 같은 행을 잠그려다 <막힌다>
        //   → ③ tx1 이 한 명을 내리고 커밋 → ④ tx2 가 풀려나 <이제 한 명뿐> 을 관측한다.
        //   FOR UPDATE 를 빼면 ②가 막히지 않아 tx2 도 [A, B] 를 보고, 그것이 write skew 다.
        seedUser(ADMIN_A, Role.ADMIN.name());
        seedUser(ADMIN_B, Role.ADMIN.name());

        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        AtomicReference<List<Long>> secondObserved = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread first = new Thread(() -> tx.executeWithoutResult(status -> {
            try {
                assertThat(lsUserRoleRepository.lockUserNosByRoleCd(Role.ADMIN.name()))
                        .containsExactly(ADMIN_A, ADMIN_B);
                firstLocked.countDown();
                // 두 번째가 잠금 시도에 들어간 뒤, 그것이 <막혀 있음>을 보장할 만큼만 머문다.
                secondAttempting.await(5, TimeUnit.SECONDS);
                Thread.sleep(300);
                lsUserRoleRepository.upsertRole(ADMIN_B, Role.WORKER.name());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }), "lock-first");

        Thread second = new Thread(() -> tx.executeWithoutResult(status -> {
            try {
                firstLocked.await(5, TimeUnit.SECONDS);
                secondAttempting.countDown();
                secondObserved.set(lsUserRoleRepository.lockUserNosByRoleCd(Role.ADMIN.name()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }), "lock-second");

        first.start();
        second.start();
        first.join(30_000);
        second.join(30_000);

        assertThat(failure.get()).isNull();
        assertThat(secondObserved.get())
                .as("잠금이 없으면 두 번째도 강등 전 목록([A, B])을 보고 통과한다 — 그것이 0명을 만든다")
                .containsExactly(ADMIN_A);
    }

    @Test
    @DisplayName("★두_관리자가_서로를_동시에_내려도_관리자가_0명이_되지_않는다")
    void concurrentMutualDemotionKeepsAtLeastOneAdmin() throws Exception {
        // ★CWE-362 write skew — 조회 후 판정이면 둘 다 "관리자가 둘"을 보고 통과해 0명이 된다.
        //   FOR UPDATE 잠금을 빼면 이 시험이 RED 다.
        seedUser(ADMIN_A, Role.ADMIN.name());
        seedUser(ADMIN_B, Role.ADMIN.name());

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        Runnable demote = () -> {
            long target = Thread.currentThread().getName().endsWith("-a") ? ADMIN_A : ADMIN_B;
            try {
                start.await(5, TimeUnit.SECONDS);
                userService.update(target, new UserUpdateRequest(Role.WORKER.name()));
                succeeded.incrementAndGet();
            } catch (CustomException e) {
                if (e.getErrorCode() == ErrorCode.CONFLICT) {
                    conflicted.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };

        Thread ta = new Thread(demote, "demote-a");
        Thread tb = new Thread(demote, "demote-b");
        ta.start();
        tb.start();
        start.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).as("교착 없이 끝나야 한다").isTrue();
        ta.join(5_000);
        tb.join(5_000);

        assertThat(adminCount()).as("관리자가 0명이 되면 부트스트랩 창구가 다시 열린다").isEqualTo(1);
        assertThat(succeeded.get()).as("한쪽만 성공한다").isEqualTo(1);
        assertThat(conflicted.get()).as("나머지 한쪽은 409 로 거절된다").isEqualTo(1);
    }
}

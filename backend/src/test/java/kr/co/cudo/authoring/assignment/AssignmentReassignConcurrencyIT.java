package kr.co.cudo.authoring.assignment;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.service.AssignmentService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-ISSUE-02 회귀 IT — 동시 재배정이 낙관적 잠금(@Version)으로 직렬화되어
 * <b>전이 1회 · 이력 1행</b>만 남는지 실 DB(PostgreSQL Testcontainer)에서 고정한다.
 *
 * <p>결함: {@code LS_TASK_ALTMNT} 에 {@code @Version}/비관적 잠금이 없어 재배정(기존 row UPDATE)이
 * UK 위반을 유발하지 않았고, {@code AssignmentService} 의 {@code DataIntegrityViolationException}
 * → CONFLICT 방어가 발화하지 않았다. 동일작업자 가드도 4스레드가 전부 커밋 전 값을 읽어 통과했다.
 * 실측: 4병렬 PATCH → 4건 전부 200, 이력 4행 중복, 이벤트 로그 4행 중복.
 *
 * <p>2노드 Active-Active 배포이므로 {@code synchronized} 등 단일 JVM 락은 방어가 될 수 없어
 * DB 수준(낙관적 잠금)으로만 해결한다.
 */
@SpringBootTest
@ActiveProfiles("local")
// 4스레드가 실제로 동시에 트랜잭션을 열 수 있도록 control 풀을 일시 확장(기본 테스트 설정은 2).
@TestPropertySource(properties = "spring.datasource.control.maximum-pool-size=6")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AssignmentReassignConcurrencyIT {

    private static final int THREADS = 4;

    @Autowired private AssignmentService assignmentService;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsTaskEventLogRepository taskEventLogRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    private final TransactionTemplate txTemplate;

    AssignmentReassignConcurrencyIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("동시_재배정_4병렬시_1건만_200_이고_나머지는_409_이며_이력이_1행만_적재됨")
    void concurrentReassignSerializesToSingleTransition() throws Exception {
        // given — 작업자 100 에게 영상 1000 배정
        var created = assignmentService.assign(
                new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());
        Long assignmentId = created.items().get(0).authrtSeq();

        // when — 4스레드가 동일 배정을 동일 대상 작업자(101)로 동시에 재배정.
        //        Hikari 는 minimum-idle=0 이라 첫 획득에서 물리 커넥션을 생성하느라 수십 ms 가 걸린다.
        //        그 지연으로 일부 스레드가 승자 커밋 <이후>에 진입하면 낙관적 잠금이 아닌
        //        "현재 배정된 작업자와 동일" 가드에 걸리므로, 배리어 전에 커넥션을 미리 데운다.
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    txTemplate.execute(s -> authrtRepository.findById(assignmentId)); // 커넥션 warm-up
                    barrier.await(30, TimeUnit.SECONDS);
                    return assignmentService.reassign(assignmentId, new ReassignRequest(101L), reviewer());
                }));
            }

            int success = 0;
            List<ErrorCode> failures = new ArrayList<>();
            for (Future<Object> f : futures) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                    success++;
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    assertThat(cause).isInstanceOf(CustomException.class);
                    failures.add(((CustomException) cause).getErrorCode());
                }
            }

            // then — 정확히 1건만 성공(수정 전에는 4건 전부 성공).
            assertThat(success).isEqualTo(1);
            assertThat(failures).hasSize(THREADS - 1);
            // H6 — 패자 3건의 "거부 형태"는 스케줄링에 따라 갈린다: 승자 커밋 <전>에 진입했으면 낙관적 잠금
            // 충돌(409 CONFLICT), 승자 커밋 <후>에 진입했으면 "현재 배정된 작업자와 동일"(400 INVALID_INPUT).
            // 직렬화되면 3건 모두 후자가 될 수 있으므로 `contains(CONFLICT)` 는 확률적 단정이라 제거한다.
            // 이 테스트가 고정하는 불변식은 "성공 1건 + 전부 거부 + 이력 1행"이며, @Version 실발화는
            // 결정적 테스트(staleAssignmentEntityFailsOnFlush)가 별도로 고정한다.
            assertThat(failures).allMatch(c -> c == ErrorCode.CONFLICT || c == ErrorCode.INVALID_INPUT);
        } finally {
            pool.shutdownNow();
        }

        // 전이 1회 — 배정 작업자는 101 한 번만 바뀐다.
        LsTaskAssignment after = authrtRepository.findById(assignmentId).orElseThrow();
        assertThat(after.getUserNo()).isEqualTo(101L);

        // 이력(= 이벤트 로그 REASSIGN) 1행 — 중복 적재 금지 (기존엔 4행).
        // 구 LS_TASK_ASSIGN_HISTORY 단언은 V4 제거로 이 단언에 합쳐졌다(같은 불변식, 적재처 1곳).
        long reassignEvents = taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(1000L).stream()
                .filter(e -> "REASSIGN".equals(e.getEventTypeCd()))
                .count();
        assertThat(reassignEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("배정엔티티에_VER_컬럼이_매핑되어_stale_엔티티_flush시_낙관적잠금_예외가_발생함")
    void staleAssignmentEntityFailsOnFlush() {
        // given — 배정 1건 생성
        var created = assignmentService.assign(
                new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());
        Long assignmentId = created.items().get(0).authrtSeq();

        // when / then — 트랜잭션 A 가 엔티티를 로드한 뒤, 그 사이 다른 트랜잭션이 같은 row 를 갱신하면
        //               A 의 flush 는 낙관적 잠금 실패로 거부되어야 한다(@Version 매핑 확인).
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(s -> {
            LsTaskAssignment loaded = authrtRepository.findById(assignmentId).orElseThrow();

            // 별도 스레드의 독립 트랜잭션으로 선행 커밋 (VER 증가)
            runInSeparateTransaction(() -> {
                LsTaskAssignment other = authrtRepository.findById(assignmentId).orElseThrow();
                other.reassignTo(200L);
                authrtRepository.saveAndFlush(other);
            });

            loaded.reassignTo(101L);
            authrtRepository.flush();
        })).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("재배정_가드가_작업상태row를_공유잠금해_동시_검수승인_UPDATE를_직렬화한다(H4-b)")
    void reassignGuardLocksStatusRowSerializingConcurrentApproval() throws Exception {
        // given — 영상 1000 배정으로 LS_RAW_DATA_STATUS row 생성
        assignmentService.assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());

        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // when — 재배정 가드와 동일한 공유 잠금 조회를 열고 1초간 트랜잭션을 붙잡는다.
            Future<?> holder = pool.submit(() -> txTemplate.executeWithoutResult(s -> {
                dataSttsRepository.findByRawDataIdForShare(1000L).orElseThrow();
                locked.countDown();
                sleep(1000);
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            // 검수 승인과 동일한 상태 row UPDATE 를 별도 커넥션(autocommit)에서 실행한다.
            long started = System.currentTimeMillis();
            jdbcTemplate.update(
                    "UPDATE ls_raw_data_status SET data_stts_cd = ?, ver = ver + 1 WHERE raw_data_id = ?",
                    "APPROVED", 1000L);
            long elapsed = System.currentTimeMillis() - started;
            holder.get(30, TimeUnit.SECONDS);

            // then — 잠금이 없으면 즉시(≈0ms) 통과한다. 공유 잠금이 걸려 있어야 보유 트랜잭션 커밋까지 대기하며,
            //        그 덕분에 "가드가 상태를 읽음 → 다른 tx 가 approve 커밋 → reassign 커밋" 창이 닫힌다.
            assertThat(elapsed).isGreaterThanOrEqualTo(400L);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * ★<b>동시 이중 해제가 500 으로 나가지 않는다</b> — 진 쪽은 404 다.
     * [@design ADR-069] [@design API-259]
     *
     * <h3>왜 이 클래스인가</h3>
     * <p>해제도 같은 배정 원장의 {@code @Version} 을 타는 동시성 시험이라 이 클래스가 제자리다. 전용
     * 클래스를 새로 만들면 <b>스프링 테스트 컨텍스트가 하나 늘어</b> 워커 힙에 영구히 상주하며,
     * 이 클래스가 이미 갖고 있는 커넥션 풀 확장 설정을 그대로 쓸 수 있다.
     *
     * <h3>왜 404 인가</h3>
     * <p>진 쪽이 마주한 사실은 「그 배정이 이미 없다」이고 그것이 해제 창구의 404 계약이다. 409 로 두면
     * <b>같은 실제 상황이 타이밍에 따라 갈린다</b> — 순차로 두 번 누르면 404, 동시에 누르면 409.
     * 재배정이 409 인 것과 다른 이유는 결말이 다르기 때문이다: 재배정은 요청이 이루지 못한 것이 남아
     * 재시도가 뜻을 갖지만, 해제는 원하던 끝 상태가 이미 이뤄져 있다.
     *
     * <h3>왜 스레드 경주가 아니라 만들어 낸 충돌인가</h3>
     * <p>여럿을 띄워 「하나만 성공」을 보는 방식은 스케줄러가 직렬화하면 낙관적 잠금이 <b>아예 발화하지
     * 않아</b> 수정 없이도 통과한다(위 4병렬 시험이 그 이유로 거부 형태를 단정하지 못한다). 그래서
     * 충돌을 만든다 — 해제가 배정을 읽은 <b>뒤</b> 작업 상태 행을 공유 잠금하려는 자리를 막아 세우고,
     * 멈춘 사이에 배정 행의 판올림을 올린다.
     */
    @Test
    @DisplayName("★동시_이중_해제는_500이_아니라_404다_낙관적잠금_충돌이_창구_계약으로_번역된다")
    void concurrentDoubleUnassignMapsToNotFound() throws Exception {
        // given — 영상 1000 배정(이 호출이 작업 상태 row 도 함께 만든다)
        var created = assignmentService.assign(
                new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());
        Long assignmentId = created.items().get(0).authrtSeq();

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // ① 작업 상태 행을 배타 잠금으로 붙잡는다 — 해제가 그 행을 공유 잠금하려다 여기서 멈춘다.
            Future<?> holder = pool.submit(() -> txTemplate.executeWithoutResult(s -> {
                entityManager.find(LsRawDataStatus.class, 1000L, LockModeType.PESSIMISTIC_WRITE);
                holding.countDown();
                awaitQuietly(release);
            }));
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            // ② 해제를 시작한다 — 배정 엔티티를 이미 읽은 채로 ①의 잠금 앞에서 멈춘다.
            Future<Throwable> loser = pool.submit(() -> {
                try {
                    assignmentService.unassign(assignmentId, reviewer());
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            assertThat(awaitBlockedOnLock())
                    .as("해제가 잠금 앞에서 멈춰야 이 시험이 성립한다 — 안 멈추면 충돌을 만들 수 없다")
                    .isTrue();

            // ③ 멈춘 사이 배정 행을 남이 바꾼다 — 승자가 이미 해제한 상황과 같은 판올림 불일치를 만든다.
            assertThat(jdbcTemplate.update(
                    "UPDATE ls_task_altmnt SET ver = ver + 1 WHERE assignment_id = ?", assignmentId))
                    .isEqualTo(1);

            // ④ 잠금을 풀어 해제가 이어 달리게 한다 — 삭제가 판올림 불일치로 거부된다.
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            Throwable thrown = loser.get(60, TimeUnit.SECONDS);
            assertThat(thrown)
                    .as("★낙관적 잠금 예외가 그대로 새어 나가면 전역 핸들러에 매핑이 없어 500 이 된다")
                    .isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode())
                    .as("진 쪽이 마주한 사실은 「그 배정이 이미 없다」이고 그것이 해제 창구의 404 계약이다")
                    .isEqualTo(ErrorCode.NOT_FOUND);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** 이 저장소의 어떤 세션이 잠금을 기다리고 있는가 — ②가 실제로 멈췄는지의 신호. */
    private boolean awaitBlockedOnLock() throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Long waiting = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Long.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 별도 스레드에서 독립 트랜잭션으로 실행하고 완료를 기다린다(같은 스레드 tx 중첩 회피). */
    private void runInSeparateTransaction(Runnable action) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> txTemplate.executeWithoutResult(s -> action.run()))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }
}

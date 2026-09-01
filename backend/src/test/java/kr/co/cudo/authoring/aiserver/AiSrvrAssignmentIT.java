package kr.co.cudo.authoring.aiserver;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrAltmnt;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrAltmntRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 영상↔노드 배정의 <b>멱등성</b> 검증 (Testcontainers PostgreSQL). [@design ADR-057]
 *
 * <h3>왜 「예외」가 아니라 「이긴 값을 읽는다」인가</h3>
 * <p>2노드 Active-Active 라 같은 영상의 배정이 동시에 일어날 수 있다. 경합에서 진 쪽이 예외를
 * 받으면 그 배치가 실패로 종결되지만, <b>실제로는 아무 문제가 없다</b> — 이미 누군가 같은 일을
 * 해 두었을 뿐이다. 그래서 유니크 제약 + {@code ON CONFLICT DO NOTHING} 후 <b>재조회</b>로
 * 이긴 쪽의 배정을 그대로 쓴다.
 *
 * <p>배정이 한 건으로 유지돼야 하는 이유는 그것이 <b>같은 영상의 프레임이 같은 노드로 간다</b>는
 * 보장의 전부이기 때문이다. 두 건이 남으면 어느 것을 쓸지 조회 순서가 정하게 되고, 그 순간
 * 트래커가 두 GPU 로 흩어져 track_id 가 끊긴다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AiSrvrAssignmentIT {

    private static final long RAW_SN = 900_001L;

    /** 먼저 온 트랜잭션이 커밋을 미루는 시간 — 뒤에 온 쪽이 충돌 대기에 실제로 걸리도록. */
    private static final long OVERLAP_MILLIS = 300L;

    @Autowired private LsAiSrvrRepository srvrRepository;
    @Autowired private LsAiSrvrAltmntRepository altmntRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("controlTransactionManager") private PlatformTransactionManager txManager;

    @BeforeEach
    @AfterEach
    void resetLedger() {
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_altmnt");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr");
    }

    @Test
    @DisplayName("같은_영상을_두_스레드가_동시에_배정해도_한_건만_남고_진_쪽은_이긴_값을_읽는다")
    void 같은_영상을_두_스레드가_동시에_배정해도_한_건만_남고_진_쪽은_이긴_값을_읽는다() throws Exception {
        // given — 두 WAS 가 같은 영상을 각자 다른 노드로 배정하려 한다
        srvrRepository.saveAndFlush(node("gpu01"));
        srvrRepository.saveAndFlush(node("gpu02"));

        // ★두 트랜잭션을 실제로 <겹치게> 만든다. 그냥 동시에 출발시키면 앞선 쪽이 1ms 안에 커밋해
        //   사실상 순차가 되고, 그러면 「진 쪽이 잠금에서 기다렸다가 이긴 값을 읽는다」는 경로가
        //   한 번도 실행되지 않는다.
        CountDownLatch secondAboutToStart = new CountDownLatch(1);
        Map<String, String> observed = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                try {
                    String assigned = new TransactionTemplate(txManager).execute(status -> {
                        String srvrId = altmntRepository
                                .assignIfAbsent(RAW_SN, "gpu01", LocalDateTime.now()).getSrvrId();
                        awaitQuietly(secondAboutToStart);
                        sleepQuietly(OVERLAP_MILLIS); // 뒤에 온 쪽이 충돌 대기에 걸릴 시간을 준다
                        return srvrId;
                    });
                    observed.put("gpu01", assigned);
                } catch (Exception e) {
                    observed.put("gpu01", "EX:" + e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    secondAboutToStart.countDown();
                    observed.put("gpu02", assign(RAW_SN, "gpu02").getSrvrId());
                } catch (Exception e) {
                    observed.put("gpu02", "EX:" + e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });

            // when
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // then — 행은 하나뿐이고, 두 스레드가 본 노드가 같다(진 쪽도 예외 없이 이긴 값을 읽는다)
        assertThat(countAssignments()).isOne();
        String winner = altmntRepository.findByRawSn(RAW_SN).orElseThrow().getSrvrId();
        assertThat(observed)
                .as("겹친 두 배정 모두 예외 없이 같은 노드를 봐야 한다")
                .containsOnlyKeys("gpu01", "gpu02");
        assertThat(observed.values()).containsOnly(winner);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("상대 스레드가 시간 안에 출발하지 않았습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("같은_영상을_다시_배정해도_처음_배정이_유지된다")
    void 같은_영상을_다시_배정해도_처음_배정이_유지된다() {
        // given — 배치 재시도가 같은 영상을 다시 배정하려 한다
        srvrRepository.saveAndFlush(node("gpu01"));
        srvrRepository.saveAndFlush(node("gpu02"));
        LsAiSrvrAltmnt first = assign(RAW_SN, "gpu01");

        // when
        LsAiSrvrAltmnt again = assign(RAW_SN, "gpu02");

        // then — 노드를 갈아타면 진행 중인 추적이 끊긴다. 처음 배정이 이긴다.
        assertThat(again.getSrvrId()).isEqualTo("gpu01");
        assertThat(again.getAltmntSn()).isEqualTo(first.getAltmntSn());
        assertThat(countAssignments()).isOne();
    }

    @Test
    @DisplayName("배정이_없는_영상_조회는_비어_있다")
    void 배정이_없는_영상_조회는_비어_있다() {
        assertThat(altmntRepository.findByRawSn(RAW_SN)).isEmpty();
    }

    @Test
    @DisplayName("서로_다른_영상은_각각_배정된다")
    void 서로_다른_영상은_각각_배정된다() {
        // given
        srvrRepository.saveAndFlush(node("gpu01"));
        srvrRepository.saveAndFlush(node("gpu02"));

        // when
        assign(RAW_SN, "gpu01");
        assign(RAW_SN + 1, "gpu02");

        // then — 유니크는 영상 단위이지 노드 단위가 아니다
        assertThat(countAssignments()).isEqualTo(2);
        assertThat(altmntRepository.findByRawSn(RAW_SN + 1).orElseThrow().getSrvrId())
                .isEqualTo("gpu02");
    }

    @Test
    @DisplayName("원장에_없는_노드로는_배정할_수_없다")
    void 원장에_없는_노드로는_배정할_수_없다() {
        // given · when · then — 외래키가 유령 노드 배정을 막는다(그 배정은 영원히 호출되지 않는다)
        assertThatThrownBy(() -> assign(RAW_SN, "nosuch01"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private LsAiSrvrAltmnt assign(long rawSn, String srvrId) {
        return new TransactionTemplate(txManager).execute(status ->
                altmntRepository.assignIfAbsent(rawSn, srvrId, LocalDateTime.now()));
    }

    private int countAssignments() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ls_ai_srvr_altmnt", Integer.class);
        return count == null ? 0 : count;
    }

    private static LsAiSrvr node(String srvrId) {
        return LsAiSrvr.register(srvrId, null, "http://ai.internal:9300",
                LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now());
    }
}

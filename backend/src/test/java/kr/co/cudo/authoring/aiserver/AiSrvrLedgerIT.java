package kr.co.cudo.authoring.aiserver;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.service.AiSrvrIdPolicy;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 서버 원장의 <b>DB 강제</b> 검증 (Testcontainers PostgreSQL). [@design ADR-057]
 *
 * <h3>왜 단위 시험으로 충분하지 않은가</h3>
 * <p>식별자 형식은 <b>3중 강제</b>(DB 체크 제약 · 기동 가드 · 입력 DTO)다. 단위 시험은 그중 판정
 * 함수만 고정한다 — 애플리케이션을 우회한 저장(운영 SQL·다른 노드의 구 jar)을 <b>DB 가 막는지</b>는
 * 실제 PostgreSQL 없이는 증명되지 않는다.
 *
 * <h3>마지막 가용 노드 보호는 조건부 UPDATE 로만 성립한다</h3>
 * <p>조회 후 UPDATE 로 만들면 두 관리자가 <b>서로 다른</b> 노드를 동시에 내릴 때 둘 다 통과해 가용
 * 노드가 0이 된다(AI 기능 전면 정지). 이 시험은 그 경합을 실제 스레드로 재현한다 — 순차 호출로
 * 바꾸면 결함을 통과시킨다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AiSrvrLedgerIT {

    @Autowired private LsAiSrvrRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("controlTransactionManager") private PlatformTransactionManager txManager;

    /** 먼저 온 트랜잭션이 커밋을 미루는 시간 — 뒤에 온 쪽이 잠금에 실제로 걸리도록. */
    private static final long OVERLAP_MILLIS = 300L;

    @BeforeEach
    @AfterEach
    void resetLedger() {
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_altmnt");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr");
    }

    @Test
    @DisplayName("서버ID에_하이픈이_있으면_저장이_거부된다")
    void 서버ID에_하이픈이_있으면_저장이_거부된다() {
        // given — 실제 장비 호스트명을 그대로 쓴 식별자. 서킷 이름 ai-batch-{SRVR_ID} 가 복원 불가해진다.
        LsAiSrvr node = node("klid-ai-gpu-01");

        // when · then — 애플리케이션 검증이 아니라 DB 가 막는다
        assertThatThrownBy(() -> repository.saveAndFlush(node))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("서버ID에_대문자가_있으면_저장이_거부된다")
    void 서버ID에_대문자가_있으면_저장이_거부된다() {
        assertThatThrownBy(() -> repository.saveAndFlush(node("GPU01")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("형식을_지킨_서버ID는_저장된다")
    void 형식을_지킨_서버ID는_저장된다() {
        // given · when
        repository.saveAndFlush(node("gpu01"));

        // then
        assertThat(repository.findById("gpu01")).isPresent();
    }

    @Test
    @DisplayName("알려지지_않은_상태코드는_저장이_거부된다")
    void 알려지지_않은_상태코드는_저장이_거부된다() {
        // given · when · then — 상태 축은 네 값뿐이다. 오타 상태가 들어가면 전이 판정이 통째로 무너진다.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ls_ai_srvr (srvr_id, srvr_addr, srvr_type_cd, srvr_stts_cd,
                                        wtng_nocs, chck_fail_nocs, reg_dt)
                VALUES ('gpu09', 'http://ai.internal:9300', 'INFERENCE', 'ZOMBIE', 0, 0, now())
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("데이터베이스_체크제약은_정책과_같은_정규식을_쓴다")
    void 데이터베이스_체크제약은_정책과_같은_정규식을_쓴다() {
        // given · when — 제약 정의를 그대로 읽는다
        String constraintDef = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(c.oid)
                  FROM pg_constraint c
                 WHERE c.conrelid = to_regclass('ls_ai_srvr')
                   AND c.conname = 'ck_ls_ai_srvr_srvr_id_format'
                """, String.class);

        // then — 판정 규칙이 두 벌이 되면 한쪽만 통과하는 값이 조용히 생긴다
        assertThat(constraintDef).contains(AiSrvrIdPolicy.SRVR_ID_REGEX);
    }

    @Test
    @DisplayName("마지막_가용노드를_정비중으로_내리면_거부된다")
    void 마지막_가용노드를_정비중으로_내리면_거부된다() {
        // given — 가용 노드가 하나뿐인 원장(다른 하나는 이미 비활성)
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));
        demoteDirectly("gpu02", AiSrvrStatus.DISABLED);

        // when
        int affected = demote("gpu01", AiSrvrStatus.DRAINING);

        // then — 0이면 호출측이 409 로 돌려준다. 상태는 그대로여야 한다.
        assertThat(affected).isZero();
        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
    }

    @Test
    @DisplayName("마지막_가용노드의_비활성도_거부된다")
    void 마지막_가용노드의_비활성도_거부된다() {
        // given
        repository.saveAndFlush(node("gpu01"));

        // when · then — drain 뿐 아니라 비활성도 가용 0을 만든다
        assertThat(demote("gpu01", AiSrvrStatus.DISABLED)).isZero();
        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
    }

    @Test
    @DisplayName("가용노드가_둘이면_하나는_정비중으로_내려간다")
    void 가용노드가_둘이면_하나는_정비중으로_내려간다() {
        // given
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));

        // when
        int affected = demote("gpu01", AiSrvrStatus.DRAINING);

        // then
        assertThat(affected).isOne();
        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.DRAINING.name());
        assertThat(statusOf("gpu02")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
    }

    @Test
    @DisplayName("이미_정비중인_노드를_다시_내려도_영향행이_없다")
    void 이미_정비중인_노드를_다시_내려도_영향행이_없다() {
        // given
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));
        demoteDirectly("gpu01", AiSrvrStatus.DRAINING);

        // when · then — 출발 상태가 AVAILABLE 일 때만 내려간다(금지 전이 DRAINING→UNAVAILABLE 차단 포함)
        assertThat(demote("gpu01", AiSrvrStatus.DISABLED)).isZero();
    }

    @Test
    @DisplayName("존재하지_않는_서버를_내리면_영향행이_없다")
    void 존재하지_않는_서버를_내리면_영향행이_없다() {
        // given
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));

        // when · then — 404 판정은 호출측 몫이고 여기서는 조용히 0행이다
        assertThat(demote("nosuch01", AiSrvrStatus.DRAINING)).isZero();
    }

    @Test
    @DisplayName("두_관리자가_서로_다른_노드를_동시에_내려도_하나는_거부된다")
    void 두_관리자가_서로_다른_노드를_동시에_내려도_하나는_거부된다() throws Exception {
        // given — 가용 노드 둘. 순차라면 둘째가 「마지막 가용」이라 거부되지만, 동시 실행에서는
        //         서로 다른 행을 건드려 행 잠금이 부딪히지 않고 각자 「가용 2」를 본다.
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));

        // ★두 트랜잭션을 실제로 <겹치게> 만든다. 그냥 두 스레드를 동시에 출발시키면 갱신이 1ms 안에
        //   끝나 사실상 순차로 돌고, 그러면 보호 장치를 빼도 시험이 통과한다(실제로 그랬다).
        //   먼저 온 쪽이 갱신 후 커밋을 미루는 동안 뒤에 온 쪽이 들어와야 경합이 재현된다.
        CountDownLatch secondAboutToStart = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                try {
                    Integer affected = new TransactionTemplate(txManager).execute(status -> {
                        int rows = repository.demoteIfNotLastAvailable(
                                "gpu01", AiSrvrStatus.DRAINING.name());
                        awaitQuietly(secondAboutToStart);
                        sleepQuietly(OVERLAP_MILLIS); // 뒤에 온 쪽이 잠금에 걸릴 시간을 준다
                        return rows;
                    });
                    if (affected != null && affected > 0) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    secondAboutToStart.countDown();
                    if (demote("gpu02", AiSrvrStatus.DRAINING) > 0) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });

            // when
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // then — 정확히 하나만 내려가고 가용 노드가 반드시 남는다
        assertThat(successCount.get())
                .as("겹친 두 강등 중 하나만 성공해야 한다(둘 다 통과하면 가용 노드가 0이 된다)")
                .isOne();
        assertThat(countAvailable()).isOne();
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

    private int demote(String srvrId, AiSrvrStatus next) {
        Integer affected = new TransactionTemplate(txManager).execute(
                status -> repository.demoteIfNotLastAvailable(srvrId, next.name()));
        return affected == null ? 0 : affected;
    }

    /** 사전 조건 조성용 — 마지막 가용 노드 보호를 거치지 않고 상태만 바꾼다. */
    private void demoteDirectly(String srvrId, AiSrvrStatus next) {
        jdbcTemplate.update("UPDATE ls_ai_srvr SET srvr_stts_cd = ? WHERE srvr_id = ?",
                next.name(), srvrId);
    }

    private String statusOf(String srvrId) {
        return jdbcTemplate.queryForObject(
                "SELECT srvr_stts_cd FROM ls_ai_srvr WHERE srvr_id = ?", String.class, srvrId);
    }

    private int countAvailable() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ls_ai_srvr WHERE srvr_stts_cd = ?",
                Integer.class, AiSrvrStatus.AVAILABLE.name());
        return count == null ? 0 : count;
    }

    private static LsAiSrvr node(String srvrId) {
        return LsAiSrvr.register(srvrId, null, "http://ai.internal:9300",
                LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now());
    }
}

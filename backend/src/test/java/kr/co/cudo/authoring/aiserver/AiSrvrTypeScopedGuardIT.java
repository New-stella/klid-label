package kr.co.cudo.authoring.aiserver;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthTxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * <b>유형별</b> 마지막 가용 장비 보호의 DB 강제 검증 (Testcontainers PostgreSQL).
 * [@design API-229] [@design API-230] [@design AC-1091]
 *
 * <h3>왜 전체 축 시험으로 충분하지 않은가</h3>
 * <p>기존 보호({@code demoteIfNotLastAvailable})는 <b>원장 전체</b>에서 가용 수를 센다. 유형이
 * 하나뿐이던 시절에는 같은 판정이었지만, 추론과 시계열이 함께 등록되면 <b>추론이 하나만 남았는데
 * 시계열이 있어서 통과</b>한다 — 그러면 추론 위탁이 통째로 멈춘다. 이 파일이 고정하는 것은
 * 「막힌다」가 아니라 <b>「전체 축으로는 통과하는 조합에서 막힌다」</b>이며, 유형을 하나만 심고
 * 시험하면 그 회귀를 한 건도 잡지 못한다.
 *
 * <h3>★그리고 「막지 않는 쪽」도 함께 고정한다</h3>
 * <p>상태점검 배치의 자동 이용불가 전이는 사람의 결정이 아니라 <b>관측</b>이다. 여기에까지 유형별
 * 보호를 얹으면 죽은 장비로 계속 보내게 된다. 두 경로가 서로 다른 문장을 쓰는 것이 의도이며,
 * 그 비대칭을 시험이 지키지 않으면 다음 사람이 「일관성」을 이유로 합친다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AiSrvrTypeScopedGuardIT {

    @Autowired private LsAiSrvrRepository repository;
    @Autowired private kr.co.cudo.authoring.aiserver.service.AiSrvrAdminService adminService;
    @Autowired private AiSrvrHealthTxService healthTxService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("controlTransactionManager") private PlatformTransactionManager txManager;

    @Value("${authoring.integration.ai-server.fail-threshold:3}")
    private int failThreshold;

    /** 먼저 온 트랜잭션이 커밋을 미루는 시간 — 뒤에 온 쪽이 잠금에 실제로 걸리도록. */
    private static final long OVERLAP_MILLIS = 300L;

    @BeforeEach
    @AfterEach
    void resetLedger() {
        jdbcTemplate.update("DELETE FROM ls_webhook_idempotency WHERE srvr_id IS NOT NULL");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_altmnt");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_usg");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr");
    }

    @Test
    @DisplayName("★다른_유형에_가용장비가_있어도_그_유형의_마지막이면_강등이_거부된다")
    void 다른_유형에_가용장비가_있어도_그_유형의_마지막이면_강등이_거부된다() {
        // given — 추론 1 + 시계열 1. 전체 축으로 세면 「가용 2」라 통과해 버리는 조합이다.
        repository.saveAndFlush(node("gpu01", LsAiSrvr.SrvrType.INFERENCE));
        repository.saveAndFlush(node("vlm01", LsAiSrvr.SrvrType.TIMESERIES));

        // when
        int affected = demoteOfType("gpu01", LsAiSrvr.SrvrType.INFERENCE, AiSrvrStatus.DISABLED);

        // then — 내렸다면 추론 위탁이 고를 장비가 0이 된다
        assertThat(affected).isZero();
        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
    }

    @Test
    @DisplayName("같은_유형에_가용장비가_둘이면_하나는_내려간다")
    void 같은_유형에_가용장비가_둘이면_하나는_내려간다() {
        // given
        repository.saveAndFlush(node("gpu01", LsAiSrvr.SrvrType.INFERENCE));
        repository.saveAndFlush(node("gpu02", LsAiSrvr.SrvrType.INFERENCE));

        // when
        int affected = demoteOfType("gpu01", LsAiSrvr.SrvrType.INFERENCE, AiSrvrStatus.DRAINING);

        // then
        assertThat(affected).isOne();
        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.DRAINING.name());
        assertThat(statusOf("gpu02")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
    }

    @Test
    @DisplayName("★두_관리자가_같은_유형의_서로_다른_장비를_동시에_내려도_하나는_거부된다")
    void 두_관리자가_같은_유형의_서로_다른_장비를_동시에_내려도_하나는_거부된다() throws Exception {
        // given — 같은 유형 가용 둘. 순차라면 둘째가 거부되지만, 동시 실행에서는 서로 다른 행을
        //         건드려 행 잠금이 부딪히지 않고 각자 「가용 2」를 본다.
        repository.saveAndFlush(node("gpu01", LsAiSrvr.SrvrType.INFERENCE));
        repository.saveAndFlush(node("gpu02", LsAiSrvr.SrvrType.INFERENCE));

        // ★두 트랜잭션을 실제로 <겹치게> 만든다. 그냥 동시에 출발시키면 갱신이 1ms 안에 끝나 사실상
        //   순차로 돌고, 그러면 보호 장치를 빼도 시험이 통과한다.
        CountDownLatch secondAboutToStart = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                try {
                    Integer affected = new TransactionTemplate(txManager).execute(status -> {
                        int rows = repository.demoteIfNotLastAvailableOfType("gpu01",
                                LsAiSrvr.SrvrType.INFERENCE.name(), AiSrvrStatus.DRAINING.name(),
                                "admin1", LocalDateTime.now());
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
                    if (demoteOfType("gpu02", LsAiSrvr.SrvrType.INFERENCE, AiSrvrStatus.DRAINING) > 0) {
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

        // then — 정확히 하나만 내려가고 그 유형의 가용 장비가 반드시 남는다
        assertThat(successCount.get())
                .as("겹친 두 강등 중 하나만 성공해야 한다(둘 다 통과하면 그 유형의 가용 장비가 0이 된다)")
                .isOne();
        assertThat(countAvailableOfType(LsAiSrvr.SrvrType.INFERENCE)).isOne();
    }

    @Test
    @DisplayName("★그_유형의_마지막_가용장비는_삭제도_거부된다")
    void 그_유형의_마지막_가용장비는_삭제도_거부된다() {
        // given — 다른 유형에는 가용 장비가 있다(전체 축으로는 통과하는 조합)
        repository.saveAndFlush(node("gpu01", LsAiSrvr.SrvrType.INFERENCE));
        repository.saveAndFlush(node("vlm01", LsAiSrvr.SrvrType.TIMESERIES));

        // when
        int deleted = deleteOfType("gpu01", LsAiSrvr.SrvrType.INFERENCE);

        // then
        assertThat(deleted).isZero();
        assertThat(repository.findById("gpu01")).isPresent();
    }

    @Test
    @DisplayName("★시계열_장비의_부하는_노드를_고르는_쪽과_같은_원천에서_온다")
    void 시계열_장비의_부하는_노드를_고르는_쪽과_같은_원천에서_온다() {
        // given — 시계열 장비 하나에 <결과를 기다리는> 위탁 2건. 폴러가 채우는 용도별 관측표에는
        //   아무것도 넣지 않는다. 폴러가 시계열 노드를 찌르지 않기 때문이다(그래서 그 표만 읽으면
        //   화면이 늘 「관측 없음」을 보인다 — 라우팅은 분산하는데 화면은 모른다고 말하는 어긋남).
        repository.saveAndFlush(node("vlm01", LsAiSrvr.SrvrType.TIMESERIES));
        repository.saveAndFlush(node("vlm02", LsAiSrvr.SrvrType.TIMESERIES));
        insertAcceptedSubmit("req-a", "vlm01");
        insertAcceptedSubmit("req-b", "vlm01");

        // when
        var rows = adminService.list(LsAiSrvr.SrvrType.TIMESERIES);

        // then — 위탁이 있는 쪽은 그 건수를, 없는 쪽은 0 을 말한다.
        var byId = rows.stream().collect(java.util.stream.Collectors
                .toMap(r -> r.srvrId(), r -> r.loads()));
        assertThat(byId.get("vlm01")).hasSize(1);
        assertThat(byId.get("vlm01").get(0).effectiveLoad()).isEqualTo(2);
        assertThat(byId.get("vlm02").get(0).effectiveLoad()).isZero();

        // ★관측 시각은 비어 있어야 한다 — 상대를 찌른 적이 없다는 사실 그대로다.
        //   지금 시각을 적으면 방금 확인한 것처럼 보인다.
        assertThat(byId.get("vlm01").get(0).chckDt()).isNull();
    }

    /** 결과를 기다리는 위탁 1건 — 부하로 세는 것은 수락(ACCEPTED) 행뿐이다. */
    private void insertAcceptedSubmit(String idmpKey, String srvrId) {
        jdbcTemplate.update(
                "INSERT INTO ls_webhook_idempotency (idmp_key, chnl_cd, stts_cd, srvr_id, reg_dt, mdfcn_dt)"
                        + " VALUES (?, 'VLM', 'ACCEPTED', ?, ?, ?)",
                idmpKey, srvrId,
                java.sql.Timestamp.valueOf(LocalDateTime.now()),
                java.sql.Timestamp.valueOf(LocalDateTime.now()));
    }

    @Test
    @DisplayName("★배정_이력이_있어도_장비는_삭제된다_외래키_제거_회귀가드")
    void 배정_이력이_있어도_장비는_삭제된다_외래키_제거_회귀가드() {
        // given — 그 유형에 가용 둘(마지막 장비 보호에 걸리지 않게) + 지울 쪽에 배정 이력 1건.
        //   배정 표는 <처리가 도는 동안> 프레임을 한 노드에 묶어 두는 자리이고, 처리가 끝나면
        //   그 묶음은 의미가 없다(추적기 상태가 그 프로세스 메모리에 있었고 이미 사라졌다).
        //   끝난 배정까지 삭제를 막으면 한 번이라도 영상을 처리한 장비는 영구히 교체 불가가 된다.
        repository.saveAndFlush(node("gpu01", LsAiSrvr.SrvrType.INFERENCE));
        repository.saveAndFlush(node("gpu02", LsAiSrvr.SrvrType.INFERENCE));
        jdbcTemplate.update(
                "INSERT INTO ls_ai_srvr_altmnt (altmnt_sn, raw_sn, srvr_id, altmnt_dt)"
                        + " VALUES (nextval('ls_ai_srvr_altmnt_seq'), ?, ?, ?)",
                987654L, "gpu01", java.sql.Timestamp.valueOf(LocalDateTime.now()));

        // when
        int deleted = deleteOfType("gpu01", LsAiSrvr.SrvrType.INFERENCE);

        // then — 삭제된다. 외래키가 살아 있으면 여기서 무결성 위반으로 터진다.
        assertThat(deleted).isOne();
        assertThat(repository.findById("gpu01")).isEmpty();

        // 그리고 ★이력은 남는다 — 어느 장비가 그 영상을 처리했는지는 지워지면 안 된다.
        //   장비 행이 없어졌으므로 그 식별자는 <이제 없는 장비를 가리키는 값>이 되는데 그것이 의도다.
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ls_ai_srvr_altmnt WHERE srvr_id = 'gpu01'", Integer.class);
        assertThat(remaining).isOne();
    }

    @Test
    @DisplayName("가용이_아닌_장비는_그_유형의_가용이_하나뿐이어도_삭제된다")
    void 가용이_아닌_장비는_그_유형의_가용이_하나뿐이어도_삭제된다() {
        // given — 이미 그 유형의 위탁을 받고 있지 않은 장비다
        repository.saveAndFlush(node("gpu01", LsAiSrvr.SrvrType.INFERENCE));
        repository.saveAndFlush(node("gpu02", LsAiSrvr.SrvrType.INFERENCE));
        setStatusDirectly("gpu02", AiSrvrStatus.DISABLED);

        // when
        int deleted = deleteOfType("gpu02", LsAiSrvr.SrvrType.INFERENCE);

        // then
        assertThat(deleted).isOne();
        assertThat(repository.findById("gpu02")).isEmpty();
        assertThat(repository.findById("gpu01")).isPresent();
    }

    @Test
    @DisplayName("★상태점검의_자동_이용불가_전이는_유형별_보호에_걸리지_않는다")
    void 상태점검의_자동_이용불가_전이는_유형별_보호에_걸리지_않는다() {
        // given — 추론 1 + 시계열 1. 사람이 내리면 「그 유형의 마지막」이라 거부되는 바로 그 조합.
        repository.saveAndFlush(node("gpu01", LsAiSrvr.SrvrType.INFERENCE));
        repository.saveAndFlush(node("vlm01", LsAiSrvr.SrvrType.TIMESERIES));
        assertThat(demoteOfType("gpu01", LsAiSrvr.SrvrType.INFERENCE, AiSrvrStatus.UNAVAILABLE))
                .as("사람의 조작 경로에서는 거부되는 조합이어야 이 시험이 의미를 갖는다")
                .isZero();

        // when — 상태점검이 연속 실패한다(관측이지 결정이 아니다)
        for (int i = 0; i < failThreshold; i++) {
            healthTxService.applyHealth("gpu01", false, LocalDateTime.now());
        }

        // then — 실제 장애를 소프트웨어로 부정하지 않는다. 막으면 죽은 장비로 계속 보내게 된다.
        assertThat(statusOf("gpu01"))
                .as("상태점검의 자동 전이까지 유형별 보호로 막으면 죽은 장비가 계속 선택된다")
                .isEqualTo(AiSrvrStatus.UNAVAILABLE.name());
    }

    // ---------------------------------------------------------------------------------------

    private int demoteOfType(String srvrId, LsAiSrvr.SrvrType type, AiSrvrStatus next) {
        Integer affected = new TransactionTemplate(txManager).execute(status ->
                repository.demoteIfNotLastAvailableOfType(srvrId, type.name(), next.name(),
                        "admin1", LocalDateTime.now()));
        return affected == null ? 0 : affected;
    }

    private int deleteOfType(String srvrId, LsAiSrvr.SrvrType type) {
        Integer affected = new TransactionTemplate(txManager).execute(status ->
                repository.deleteIfNotLastAvailableOfType(srvrId, type.name()));
        return affected == null ? 0 : affected;
    }

    /** 사전 조건 조성용 — 보호를 거치지 않고 상태만 바꾼다. */
    private void setStatusDirectly(String srvrId, AiSrvrStatus next) {
        jdbcTemplate.update("UPDATE ls_ai_srvr SET srvr_stts_cd = ? WHERE srvr_id = ?",
                next.name(), srvrId);
    }

    private String statusOf(String srvrId) {
        return jdbcTemplate.queryForObject(
                "SELECT srvr_stts_cd FROM ls_ai_srvr WHERE srvr_id = ?", String.class, srvrId);
    }

    private int countAvailableOfType(LsAiSrvr.SrvrType type) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM ls_ai_srvr WHERE srvr_stts_cd = ? AND srvr_type_cd = ?
                """, Integer.class, AiSrvrStatus.AVAILABLE.name(), type.name());
        return count == null ? 0 : count;
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

    private static LsAiSrvr node(String srvrId, LsAiSrvr.SrvrType type) {
        return LsAiSrvr.register(srvrId, null, "http://ai.internal:9300", type, LocalDateTime.now());
    }
}

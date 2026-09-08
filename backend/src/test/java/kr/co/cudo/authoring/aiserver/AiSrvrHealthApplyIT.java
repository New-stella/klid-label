package kr.co.cudo.authoring.aiserver;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthTxService;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태점검 결과 반영을 <b>실제 DB</b> 로 검증한다 (Testcontainers PostgreSQL). [@design ADR-057]
 *
 * <h3>왜 단위 시험으로 충분하지 않은가</h3>
 * <p>단위 시험은 저장소를 목으로 세워 "어느 쿼리를 불렀는가"만 고정한다. 그런데 이 경로의 두 축은
 * <b>SQL 자체가 규칙</b>이다 — 강등의 출발 상태 조건(조건부 UPDATE)과 복합키 upsert 는 실제 DB 없이는
 * 증명되지 않는다. 특히 강등 쿼리는 목이 돌려주는 숫자만 보면 <b>항상 성공</b>한다.
 *
 * <p>⚠ 이 경로는 <b>마지막 가용 노드 보호를 타지 않는다</b>(2026-09-07 정정) — 그 보호는 사람의 조작
 * 축이고 상태점검은 관측 축이다. [@design AC-1093]
 */
@SpringBootTest
@ActiveProfiles("local")
class AiSrvrHealthApplyIT {

    @Autowired private AiSrvrHealthTxService txService;
    @Autowired private LsAiSrvrRepository repository;
    @Autowired private LsAiSrvrUsgRepository usgRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("controlTransactionManager") private PlatformTransactionManager txManager;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 4, 12, 33);

    @BeforeEach
    @AfterEach
    void resetLedger() {
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_usg");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_altmnt");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr");
    }

    @Test
    @DisplayName("연속_세번_실패한_노드는_원장에서_이용불가가_된다")
    void 연속_세번_실패한_노드는_원장에서_이용불가가_된다() {
        // given — 가용 노드가 둘이라 마지막 하나 보호에 걸리지 않는다
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));

        // when
        for (int i = 0; i < 3; i++) {
            txService.applyHealth("gpu01", false, NOW);
        }

        // then
        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.UNAVAILABLE.name());
        assertThat(statusOf("gpu02")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
    }

    /**
     * ★★ <b>마지막 가용 노드도 내려간다</b> — 실제 DB 로 고정한다. [@design AC-1093] [@design AC-1091]
     *
     * <p>⚠ <b>구 동작 폐기(2026-09-07)</b>: 「마지막 가용노드는 연속 실패해도 내려가지 않는다 — 내리면
     * AI 기능 전체가 멈춘다」. 그 보호가 <b>실제 장애를 감췄다</b> — 현장에서 두 장비가 같은 시각에 둘 다
     * 응답하지 않는데 나중 쪽이 「가용」으로 남아, 화면을 본 운영자가 「한 대는 살아 있다」로 읽었다.
     *
     * <p>보호는 <b>사람의 조작</b> 축이고 상태점검은 <b>관측</b> 축이다. 관측을 막으면 죽은 장비로 계속
     * 보내게 되며, 가용이 0이 되는 것도 두렵지 않다 — 후보가 0이면 위탁은 <b>폴백 없이 거부</b>된다.
     * <b>되살리지 말 것.</b>
     */
    @Test
    @DisplayName("★★마지막_가용노드도_연속_실패하면_내려간다 — 구 보호 동작 폐기")
    void 마지막_가용노드도_연속_실패하면_내려간다() {
        // given — 노드가 하나뿐이다. 구 동작은 이 조합에서 강등을 거부했다.
        repository.saveAndFlush(node("gpu01"));

        // when
        for (int i = 0; i < 3; i++) {
            txService.applyHealth("gpu01", false, NOW);
        }

        // then — 실제 장애를 소프트웨어로 부정하지 않는다.
        assertThat(statusOf("gpu01"))
                .as("살아 있지 않은 장비를 「가용」으로 남기면 화면이 거짓을 말한다")
                .isEqualTo(AiSrvrStatus.UNAVAILABLE.name());
        assertThat(failNocsOf("gpu01")).isEqualTo(3);
    }

    /**
     * 임계 <b>미만</b>에서는 내려가지 않는다 — 한 번의 네트워크 흔들림으로 장비를 잃지 않기 위한 축이며
     * 이번 변경이 건드리지 않는다.
     */
    @Test
    @DisplayName("임계_미만의_연속_실패로는_마지막_노드도_내려가지_않는다")
    void 임계_미만의_연속_실패로는_내려가지_않는다() {
        repository.saveAndFlush(node("gpu01"));

        for (int i = 0; i < 2; i++) {
            txService.applyHealth("gpu01", false, NOW);
        }

        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
        assertThat(failNocsOf("gpu01")).isEqualTo(2);
    }

    @Test
    @DisplayName("이용불가_노드는_연속_세번_성공하면_원장에서_복귀하고_카운터가_비워진다")
    void 이용불가_노드는_연속_세번_성공하면_원장에서_복귀하고_카운터가_비워진다() {
        // given — 실패로 내려간 노드
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));
        for (int i = 0; i < 3; i++) {
            txService.applyHealth("gpu01", false, NOW);
        }

        // when
        for (int i = 0; i < 3; i++) {
            txService.applyHealth("gpu01", true, NOW);
        }

        // then — ★복귀 직후 카운터가 남아 있으면 다음 실패 한 번에 임계를 넘겨 곧바로 다시 내려간다
        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
        assertThat(failNocsOf("gpu01")).isZero();
        assertThat(scsNocsOf("gpu01")).isZero();
    }

    @Test
    @DisplayName("부하는_같은_행에_덮어써지고_용도마다_따로_쌓인다")
    void 부하는_같은_행에_덮어써지고_용도마다_따로_쌓인다() {
        // given
        repository.saveAndFlush(node("gpu01"));

        // when — 두 번 관측한다(신규 삽입 -> 갱신)
        txService.applyLoad("gpu01", Map.of(
                AiSrvrUsageType.BATCH, new AiSrvrSlotLoad(1, 12),
                AiSrvrUsageType.INTERACTIVE, new AiSrvrSlotLoad(0, 0)), NOW);
        txService.applyLoad("gpu01", Map.of(
                AiSrvrUsageType.BATCH, new AiSrvrSlotLoad(1, 3),
                AiSrvrUsageType.INTERACTIVE, new AiSrvrSlotLoad(1, 0)), NOW.plusSeconds(5));

        // then — 행이 늘지 않고 값만 갱신된다(복합키 upsert)
        assertThat(usgRepository.findBySrvrId("gpu01")).hasSize(2);
        assertThat(usgRepository.findById(
                        new kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg.Key(
                                "gpu01", AiSrvrUsageType.BATCH))
                .orElseThrow().effectiveLoad()).isEqualTo(4);
        assertThat(usgRepository.findById(
                        new kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg.Key(
                                "gpu01", AiSrvrUsageType.INTERACTIVE))
                .orElseThrow().effectiveLoad()).isEqualTo(1);
    }

    /**
     * ★★ <b>정비중 장비도 죽으면 이용불가로 내려간다</b> — 실제 DB 로 고정한다.
     * [@design API-229] [@design AC-1100]
     *
     * <p>이 축은 <b>두 자리가 함께</b> 열려야 성립한다 — 전이표({@code AiSrvrStatus})와 강등 문장의
     * 출발 상태 조건({@code demoteByHealthCheck}). <b>전이표만 열면 판정은 통과하는데 갱신이 0 행이라
     * 아무 일도 일어나지 않고</b>, 목을 쓰는 단위 시험은 그 어긋남을 <b>한 건도 잡지 못한다</b>
     * (목이 돌려주는 숫자만 보기 때문이다). 그래서 이 자리가 필요하다.
     *
     * <p>그 길이 없으면 정비 중에 실제로 멈춘 장비에 고정된 영상이 <b>죽은 주소에 영구히 묶인다</b>.
     */
    @Test
    @DisplayName("★★정비중_장비도_연속_실패하면_원장에서_이용불가가_된다")
    void 정비중_장비도_연속_실패하면_이용불가가_된다() {
        // given — 가용 하나 + 정비중 하나
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));
        jdbcTemplate.update("UPDATE ls_ai_srvr SET srvr_stts_cd = 'DRAINING' WHERE srvr_id = 'gpu02'");

        // when
        for (int i = 0; i < 3; i++) {
            txService.applyHealth("gpu02", false, NOW);
        }

        // then
        assertThat(statusOf("gpu02")).isEqualTo(AiSrvrStatus.UNAVAILABLE.name());
    }

    /**
     * ★ <b>반대 방향 짝</b> — 살아 있는 정비중 장비는 <b>정비중 그대로</b>다.
     *
     * <p>배치가 자동으로 가용으로 올리면 사람이 세운 정비 상태가 조용히 사라진다. 두 단언을 함께
     * 두지 않으면 다음 사람이 한쪽을 깬다.
     */
    @Test
    @DisplayName("★정비중_장비가_살아_있으면_정비중_그대로다_배치가_올리지_않는다")
    void 정비중_장비가_살아_있으면_정비중_그대로다() {
        repository.saveAndFlush(node("gpu02"));
        jdbcTemplate.update("UPDATE ls_ai_srvr SET srvr_stts_cd = 'DRAINING' WHERE srvr_id = 'gpu02'");

        for (int i = 0; i < 5; i++) {
            txService.applyHealth("gpu02", true, NOW);
        }

        assertThat(statusOf("gpu02")).isEqualTo(AiSrvrStatus.DRAINING.name());
    }

    /**
     * ★★ <b>연속 횟수 갱신이 겹쳐도 유실되지 않는다</b> — 이것이 원자 증감의 존재 이유다.
     * [@design ADR-057]
     *
     * <p>구 동작은 행을 읽어 카운터를 계산한 뒤 되쓰는 방식이었다. 두 반영이 겹치면 뒤에 온 쪽이
     * <b>앞의 값을 못 보고</b> 같은 수를 다시 써 한쪽 갱신이 사라진다 — 그러면 연속 실패 계수가
     * 임계에 <b>영영 닿지 못하거나 늦게 닿아 죽은 장비가 가용으로 남는다</b>.
     *
     * <p>겹침을 <b>확률에 맡기지 않는다</b> — 앞 트랜잭션이 반영 뒤 커밋을 미루는 동안 뒤 트랜잭션이
     * 들어오게 해서 <b>결정적으로</b> 재현한다. 구 동작이면 뒤쪽이 커밋 전 값(0)을 읽어 1 을 쓰고,
     * 원자 증감이면 행 잠금에 걸렸다가 갱신된 값 위에 올려 2 가 된다.
     *
     * <p>⚠ 점검 발화가 한 노드에서만 일어난다는 사실은 면제 사유가 아니다 — 클러스터링이 막는 것은
     * 트리거 중복 발화이고, 이것이 막는 것은 <b>같은 값을 동시에 고칠 때의 갱신 유실</b>이다.
     */
    @Test
    @DisplayName("★★두_반영이_겹쳐도_연속_실패_횟수가_유실되지_않는다")
    void 두_반영이_겹쳐도_연속_실패_횟수가_유실되지_않는다() throws Exception {
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));
        TransactionTemplate tx = new TransactionTemplate(txManager);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 앞 트랜잭션 — 반영한 뒤 커밋을 미뤄 겹침 구간을 연다.
            Future<?> slow = pool.submit(() -> tx.execute(status -> {
                txService.applyHealth("gpu01", false, NOW);
                sleep(OVERLAP_MILLIS);
                return null;
            }));
            sleep(OVERLAP_MILLIS / 3);
            // 뒤 트랜잭션 — 겹침 구간 안에서 들어온다.
            Future<?> fast = pool.submit(() -> tx.execute(status -> {
                txService.applyHealth("gpu01", false, NOW);
                return null;
            }));
            slow.get(20, TimeUnit.SECONDS);
            fast.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(failNocsOf("gpu01"))
                .as("한쪽 갱신이 덮이면 계수가 임계에 닿지 못해 죽은 장비가 가용으로 남는다")
                .isEqualTo(2);
    }

    /** 겹침을 확률이 아니라 대기 시간으로 보장한다 — 앞 트랜잭션이 커밋을 미루는 시간. */
    private static final long OVERLAP_MILLIS = 600L;

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String statusOf(String srvrId) {
        return jdbcTemplate.queryForObject(
                "SELECT srvr_stts_cd FROM ls_ai_srvr WHERE srvr_id = ?", String.class, srvrId);
    }

    private Integer failNocsOf(String srvrId) {
        return jdbcTemplate.queryForObject(
                "SELECT chck_fail_nocs FROM ls_ai_srvr WHERE srvr_id = ?", Integer.class, srvrId);
    }

    private Integer scsNocsOf(String srvrId) {
        return jdbcTemplate.queryForObject(
                "SELECT chck_scs_nocs FROM ls_ai_srvr WHERE srvr_id = ?", Integer.class, srvrId);
    }

    private static LsAiSrvr node(String srvrId) {
        return LsAiSrvr.register(srvrId, null, "http://ai.internal:9300",
                LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now());
    }
}

package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9-B / B-ISSUE-82 — KPST 폴링 대상 <b>원자 클레임</b> 통합 테스트
 * (실 DB, PostgreSQL Testcontainer).
 *
 * <p>배경: 배포는 2노드 Active-Active 인데 Quartz 클러스터링({@code isClustered})이 기본 꺼져 있어
 * 같은 폴링 틱이 양 노드에서 발화한다. 대상 선점이 없으면 두 노드가 같은 위탁 건을 동시에 폴링해
 * 외부 호출·완료 처리가 중복된다. 클레임은 조건부 UPDATE(리스)로 <b>한쪽만 1행</b>을 얻어야 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // KpstDeidentTxService 는 @ConditionalOnProperty(kpst.deid.enabled=true) — 활성화해야 빈이 등록된다.
        "kpst.deid.enabled=true",
        "kpst.deid.base-url=https://localhost:9201",
        "kpst.deid.ca-cert-path=src/test/resources/kpst/test-ca.crt",
        // 폴링 잡이 검증 중 자동 발화하지 않도록 충분히 늦춘다(본 테스트는 클레임 API 를 직접 호출).
        "kpst.deid.poll-interval-sec=3600"
})
class KpstDeidentPollClaimIT {

    @Autowired
    private KpstDeidentTxService txService;

    @Autowired
    private LsDeidentProcLogRepository procLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final List<Long> createdProcLogSns = new ArrayList<>();
    /** 시드한 부모 영상 — V146 FK(LS_DEIDENT_PROC_LOG → LS_DATA_RAW) 충족용. */
    private final List<Long> seededRawSns = new ArrayList<>();

    KpstDeidentPollClaimIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void cleanup() {
        createdProcLogSns.forEach(sn -> procLogRepository.findById(sn).ifPresent(procLogRepository::delete));
        createdProcLogSns.clear();
        // 부모 삭제 = 남은 자식 CASCADE 삭제.
        seededRawSns.forEach(sn -> RawVideoFixture.deleteRaws(jdbcTemplate, sn));
        seededRawSns.clear();
    }

    /** 위탁 완료(WAITING) 상태의 procLog 1건을 커밋 저장한다. */
    private Long persistWaiting() {
        // 비식별 처리 로그가 참조할 <b>실재하는</b> 부모 영상을 먼저 만든다(V146 FK).
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        seededRawSns.add(rawSn);
        Long procLogSn = txTemplate.execute(s -> {
            LsDeidentProcLog log = LsDeidentProcLog.request(rawSn, null, "/raw/claim-" + rawSn + ".mp4", "batch");
            log.markKpstSubmitted(101L, null);
            return procLogRepository.saveAndFlush(log).getProcLogSn();
        });
        createdProcLogSns.add(procLogSn);
        return procLogSn;
    }

    /** 리스가 아직 살아 있는 상태를 표현하는 cutoff(= now - 리스). */
    private static LocalDateTime leaseCutoff() {
        return LocalDateTime.now().minusSeconds(25);
    }

    @Test
    @DisplayName("2스레드_동시_폴링시_동일_위탁건이_한_번만_클레임된다")
    void concurrentClaimGrantsExactlyOneNode() throws Exception {
        // given — WAITING 위탁 건 1개
        Long procLogSn = persistWaiting();
        LocalDateTime cutoff = leaseCutoff();

        // when — 두 노드(스레드)가 거의 동시에 같은 건을 클레임
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        int granted = 0;
        try {
            Future<Boolean> a = pool.submit(() -> {
                start.await();
                return txService.tryClaimPoll(procLogSn, cutoff);
            });
            Future<Boolean> b = pool.submit(() -> {
                start.await();
                return txService.tryClaimPoll(procLogSn, cutoff);
            });
            start.countDown();
            if (Boolean.TRUE.equals(a.get(60, TimeUnit.SECONDS))) granted++;
            if (Boolean.TRUE.equals(b.get(60, TimeUnit.SECONDS))) granted++;
        } finally {
            pool.shutdownNow();
        }

        // then — 정확히 하나만 폴링 권한 획득(DB 가 조건부 UPDATE 를 직렬화). 클레임이 없으면 둘 다 true.
        assertThat(granted).isEqualTo(1);
    }

    @Test
    @DisplayName("단일노드에서도_기존_동작이_유지된다 — 리스가_만료되면_다음_틱에_다시_클레임된다")
    void singleNodeReclaimsOnNextTick() {
        // given — WAITING 위탁 건. 첫 틱에서 클레임 성공.
        Long procLogSn = persistWaiting();
        assertThat(txService.tryClaimPoll(procLogSn, leaseCutoff())).isTrue();

        // when / then — 리스가 살아 있는 동안(같은 틱 구간)은 재클레임 불가
        assertThat(txService.tryClaimPoll(procLogSn, leaseCutoff())).isFalse();

        // when / then — 리스 만료 후(다음 틱)는 같은 노드가 다시 클레임한다(폴링 주기 불변)
        assertThat(txService.tryClaimPoll(procLogSn, LocalDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("종결된_건은_클레임되지_않는다 — DOWNLOADED·FAILED는 재폴링 대상 아님")
    void terminalRowsAreNotClaimable() {
        // given — 완료 종결(DOWNLOADED/SUCCEEDED)된 건
        Long procLogSn = persistWaiting();
        txTemplate.executeWithoutResult(s -> procLogRepository.findById(procLogSn)
                .ifPresent(p -> p.markDownloaded("/deid/done.mp4")));

        // when / then — 리스가 만료돼 있어도 클레임되지 않는다(fail-closed 상태 가드)
        assertThat(txService.tryClaimPoll(procLogSn, LocalDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("폴링_조회에_상한이_적용된다")
    void pollQueryHonoursLimit() {
        // given — WAITING 3건
        persistWaiting();
        persistWaiting();
        persistWaiting();

        // when — 상한 2로 조회(잡이 사용하는 정렬과 동일)
        List<LsDeidentProcLog> page = procLogRepository.findByPollSttsCdIn(
                List.of(LsDeidentProcLog.POLL_WAITING, LsDeidentProcLog.POLL_POLLING),
                PageRequest.of(0, 2, Sort.by(Sort.Order.asc("pollLastDt").nullsFirst())));

        // then — 상한만큼만 반환된다(무제한 조회 금지)
        assertThat(page).hasSize(2);
    }
}

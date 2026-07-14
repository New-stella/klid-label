package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.repository.DatasetMetaSourceRepository;
import kr.co.cudo.authoring.dataset.repository.DatasetMetaSourceRow;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2 materialize <b>프로덕션 경로</b> 동시성 통합 테스트 (실 DB, PostgreSQL Testcontainer).
 *
 * <p>{@link LsDatasetVideoMetaRepositoryIT} 는 부분 유니크 인덱스를 <b>리포지토리 직접 호출</b>(락 우회)로만
 * 검증했다. 본 테스트는 Phase 1 database-reviewer(HIGH)·Phase 2 계획이 못박은 원 시나리오 —
 * 서비스 경로 {@link DatasetVideoMetaSnapshotService#materialize(Long)}(advisory 락 포함)로 <b>같은
 * rawSn 에 서로 다른 해시가 동시 승인</b>되는 케이스 — 를 실제로 구동한다.
 *
 * <p>{@code pg_advisory_xact_lock} 이 두 승인을 직렬화하므로 두 번째는 대기 후 deactivate-then-insert 를
 * 재수행한다. 결과: 예외 없이 직렬화되고 <b>활성 스냅샷 정확히 1건</b>(이력 2행) 이 남는다.
 *
 * <p>동결 소스만 {@link MockBean} 으로 대체해 호출마다 서로 다른 값(→ 서로 다른 해시)을 반환하게 한다.
 * 락·upsert·deactivate·부분 유니크 인덱스·outbox 는 전부 실제 DB 경로다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DatasetVideoMetaSnapshotConcurrencyIT {

    @Autowired
    private DatasetVideoMetaSnapshotService service;

    @Autowired
    private LsDatasetVideoMetaRepository metaRepository;

    /** 동결 소스만 mock — 나머지(락·upsert·deactivate·인덱스·outbox)는 실제 DB. */
    @MockBean
    private DatasetMetaSourceRepository sourceRepository;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final java.util.List<Long> seededRawSns = new java.util.ArrayList<>();

    DatasetVideoMetaSnapshotConcurrencyIT(
            @Qualifier("controlDataSource") DataSource dataSource,
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_META_REPL_OUTBOX WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATASET_VIDEO_META WHERE RAW_SN = ?", rawSn);
        }
    }

    /** 호출마다 CCTV_NM 이 달라져 서로 다른 SNPSHT_HASH 를 만드는 소스 행(나머지 필드는 null 허용). */
    private DatasetMetaSourceRow varyingRow(long rawSn, int seq) {
        DatasetMetaSourceRow row = mock(DatasetMetaSourceRow.class);
        when(row.getRawSn()).thenReturn(rawSn);
        when(row.getCctvNm()).thenReturn("CCTV-" + seq);
        when(row.getShtDt()).thenReturn(LocalDateTime.of(2026, 1, 15, 22, 0));
        return row;
    }

    @Test
    @DisplayName("동시_다른해시_materialize_승인시_advisory락_직렬화되어_활성1건")
    void concurrentDifferentHashMaterialize_serializedByAdvisoryLock_keepsSingleActive() throws Exception {
        // given — 같은 rawSn, findSnapshotSource 호출마다 다른 값(→ 다른 해시).
        long rawSn = System.nanoTime();
        seededRawSns.add(rawSn);
        AtomicInteger seq = new AtomicInteger();
        when(sourceRepository.findSnapshotSource(eq(rawSn)))
                .thenAnswer(inv -> varyingRow(rawSn, seq.incrementAndGet()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // when — 두 스레드가 동시에 같은 rawSn 을 materialize.
            Future<?> a = pool.submit(() -> {
                start.await();
                txTemplate.executeWithoutResult(s -> service.materialize(rawSn));
                return null;
            });
            Future<?> b = pool.submit(() -> {
                start.await();
                txTemplate.executeWithoutResult(s -> service.materialize(rawSn));
                return null;
            });
            start.countDown();

            // then — advisory 락이 직렬화하므로 두 승인 모두 예외 없이 완료된다.
            assertThatCode(() -> {
                a.get(30, TimeUnit.SECONDS);
                b.get(30, TimeUnit.SECONDS);
            }).doesNotThrowAnyException();
        } finally {
            pool.shutdownNow();
        }

        // then — 서로 다른 해시라 이력 2행이 쌓이되 활성은 정확히 1건(부분 유니크 인덱스 불변식 유지).
        List<LsDatasetVideoMeta> all = txTemplate.execute(s -> metaRepository.findByRawSn(rawSn));
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(r -> r.getActiveYn().equals("Y")).hasSize(1);
    }
}

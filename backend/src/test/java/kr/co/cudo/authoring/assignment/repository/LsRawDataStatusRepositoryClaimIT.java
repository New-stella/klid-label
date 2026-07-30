package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-1 검증 공백 차단 — {@code transitionToBatchQueuedIfNotSkipped} 조건부 원자 UPDATE 의
 * <b>실 DB(PostgreSQL Testcontainer) 통합 테스트</b>.
 *
 * <p>기존 회귀 테스트({@code BatchTransitionServiceTest})는 모두 mock 이라 실제 JPQL 이
 * 한 번도 실행되지 않았다 — 필드 매핑(JPQL 프로퍼티 → 컬럼), {@code NOT IN} 컬렉션 바인딩,
 * 멱등 영향 행수(affected) 가 검증되지 않았다. 본 IT 는 이 조건부 UPDATE 가 dirty-write(무조건
 * UPDATE)로 회귀하면 즉시 실패하도록 실 DB 에서 다음을 고정한다.
 *
 * <ol>
 *   <li>JPQL 실행·멱등성: 非skip(ASSIGNED) → affected=1, BATCH_QUEUED 영속; 동일 호출 재실행 → affected=0.</li>
 *   <li>skip 상태 차단: PROCESSING/COMPLETED 행은 affected=0(미전이).</li>
 *   <li>필드 매핑: 호출 후 updDt 갱신, rawDataId 매칭 정확.</li>
 *   <li>동시 2 트랜잭션 직렬화: 동일 rawSn 에 거의 동시 UPDATE → affected 합=1.</li>
 * </ol>
 *
 * <p>컨테이너는 {@code spring.factories} 의 {@code PostgresContainerContextCustomizerFactory} 가
 * 모든 Spring 컨텍스트에 자동 주입하므로 별도 {@code @Testcontainers}/{@code @DynamicPropertySource}
 * 선언 없이 실 PostgreSQL 위에서 동작한다. {@code @Modifying} 쿼리는 활성 트랜잭션이 필요하므로
 * control 데이터소스의 {@code controlTransactionManager} 를 쓰는 {@link TransactionTemplate} 안에서 호출한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class LsRawDataStatusRepositoryClaimIT {

    private static final Set<String> SKIP = Set.of(
            LsRawDataStatus.STTS_BATCH_QUEUED,
            LsRawDataStatus.STTS_PROCESSING,
            LsRawDataStatus.STTS_COMPLETED);

    @Autowired
    private LsRawDataStatusRepository repository;

    @Autowired
    private BatchTransitionService batchTransitionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 시드한 부모 영상 — V146 FK(LS_RAW_DATA_STATUS → LS_DATA_RAW) 충족용. */
    private final List<Long> seededRawSns = new ArrayList<>();

    private final TransactionTemplate txTemplate;

    LsRawDataStatusRepositoryClaimIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void cleanSeededVideos() {
        // 부모 삭제 = 작업 상태 행 CASCADE 삭제.
        seededRawSns.forEach(sn -> RawVideoFixture.deleteRaws(jdbcTemplate, sn));
        seededRawSns.clear();
    }

    /** 작업 상태 행이 참조할 <b>실재하는</b> 부모 영상을 1건 만든다(V146 FK). */
    private long newVideo() {
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        seededRawSns.add(rawSn);
        return rawSn;
    }

    /** 고유한 rawSn 으로 주어진 상태의 작업 상태 row 를 독립 트랜잭션에 커밋 저장한다. */
    private long persistStatus(String status) {
        long rawSn = newVideo();
        txTemplate.executeWithoutResult(s -> repository.save(
                LsRawDataStatus.builder()
                        .rawDataId(rawSn)
                        .dataSttsCd(status)
                        .stpCycl(0)
                        .igiCycl(0)
                        .updDt(LocalDateTime.now())
                        .build()));
        return rawSn;
    }

    /** 조건부 UPDATE 를 활성 트랜잭션 안에서 실행하고 영향 행수를 반환한다. */
    private int claim(long rawSn) {
        return txTemplate.execute(s ->
                repository.transitionToBatchQueuedIfNotSkipped(
                        rawSn, LsRawDataStatus.STTS_BATCH_QUEUED, SKIP));
    }

    private LsRawDataStatus reload(long rawSn) {
        return txTemplate.execute(s -> repository.findById(rawSn).orElseThrow());
    }

    @Test
    @DisplayName("JPQL_실행·멱등성 — ASSIGNED행_첫호출_affected1_BATCH_QUEUED영속_재호출_affected0")
    void jpqlExecutes_andIsIdempotent() {
        // given — 非skip(ASSIGNED) 상태 행 1건을 실 DB 에 저장
        long rawSn = persistStatus(LsRawDataStatus.STTS_ASSIGNED);

        // when — 첫 조건부 UPDATE
        int first = claim(rawSn);

        // then — 정확히 1행 전이, 실제 DB 값이 BATCH_QUEUED 로 영속(필드 매핑·NOT IN 바인딩 동작)
        assertThat(first).isEqualTo(1);
        assertThat(reload(rawSn).getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);

        // when — 동일 호출 재실행 (이미 BATCH_QUEUED ∈ skip)
        int second = claim(rawSn);

        // then — 멱등: 0행 영향, 상태 불변. (조건부→무조건 dirty-write 회귀 시 1이 되어 실패)
        assertThat(second).isEqualTo(0);
        assertThat(reload(rawSn).getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
    }

    @Test
    @DisplayName("skip상태_차단 — PROCESSING·COMPLETED행은_affected0_미전이")
    void skipStatuses_blockTransition() {
        // given — skip 집합에 속한 상태의 행들
        long processing = persistStatus(LsRawDataStatus.STTS_PROCESSING);
        long completed = persistStatus(LsRawDataStatus.STTS_COMPLETED);

        // when / then — 둘 다 0행 영향, 상태 불변
        assertThat(claim(processing)).isEqualTo(0);
        assertThat(reload(processing).getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_PROCESSING);

        assertThat(claim(completed)).isEqualTo(0);
        assertThat(reload(completed).getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_COMPLETED);
    }

    @Test
    @DisplayName("필드매핑_검증 — 전이성공시_updDt_갱신_rawDataId_정확매칭_다른행_불변")
    void fieldMapping_updatesUpdDtAndMatchesRawDataIdOnly() {
        // given — 전이 대상(ASSIGNED) + 비대상(다른 rawSn, ASSIGNED). updDt 갱신을 관찰하기 위해
        //         과거 시각으로 직접 저장한다.
        long target = newVideo();
        long other = newVideo();
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        txTemplate.executeWithoutResult(s -> {
            repository.save(LsRawDataStatus.builder()
                    .rawDataId(target).dataSttsCd(LsRawDataStatus.STTS_ASSIGNED)
                    .stpCycl(0).igiCycl(0).updDt(past).build());
            repository.save(LsRawDataStatus.builder()
                    .rawDataId(other).dataSttsCd(LsRawDataStatus.STTS_ASSIGNED)
                    .stpCycl(0).igiCycl(0).updDt(past).build());
        });

        // when — target 만 전이
        int affected = claim(target);

        // then — 1행 영향, target 의 updDt 가 CURRENT_TIMESTAMP 로 갱신(과거보다 이후), 상태 BATCH_QUEUED
        assertThat(affected).isEqualTo(1);
        LsRawDataStatus reloaded = reload(target);
        assertThat(reloaded.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
        assertThat(reloaded.getUpdDt()).isAfter(past);

        // rawDataId 매칭 정확 — 다른 행은 전혀 영향받지 않음(상태·updDt 불변)
        LsRawDataStatus otherReloaded = reload(other);
        assertThat(otherReloaded.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
        assertThat(otherReloaded.getUpdDt()).isEqualTo(past);
    }

    @Test
    @DisplayName("동시_2트랜잭션_직렬화 — 동일rawSn_거의동시UPDATE_affected합1_정확히한번만전이")
    void concurrentTwoTransactions_serializeToExactlyOneClaim() throws Exception {
        // given — 非skip(ASSIGNED) 행 1건
        long rawSn = persistStatus(LsRawDataStatus.STTS_ASSIGNED);

        // when — 두 별도 스레드가 각자 트랜잭션에서 동일 rawSn 을 거의 동시에 claim.
        //        BatchTransitionService.tryClaimBatchQueued 는 REQUIRES_NEW 로 독립 커밋된다.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger trueCount = new AtomicInteger();
        try {
            Future<Boolean> a = pool.submit(() -> {
                start.await();
                return batchTransitionService.tryClaimBatchQueued(rawSn, SKIP);
            });
            Future<Boolean> b = pool.submit(() -> {
                start.await();
                return batchTransitionService.tryClaimBatchQueued(rawSn, SKIP);
            });
            start.countDown();
            if (Boolean.TRUE.equals(a.get(30, TimeUnit.SECONDS))) {
                trueCount.incrementAndGet();
            }
            if (Boolean.TRUE.equals(b.get(30, TimeUnit.SECONDS))) {
                trueCount.incrementAndGet();
            }
        } finally {
            pool.shutdownNow();
        }

        // then — 정확히 하나만 트리거 권한 획득(DB 가 조건부 UPDATE 를 직렬화). 무조건 UPDATE 면 둘 다 true.
        assertThat(trueCount.get()).isEqualTo(1);
        assertThat(reload(rawSn).getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
    }

    @Test
    @DisplayName("row없음 — 존재하지_않는_rawSn은_affected0")
    void missingRow_returnsZero() {
        // given — 저장하지 않은 rawSn (SELECT/UPDATE 뿐이라 INSERT 가 없어 부모 시드가 필요 없다)
        long missing = System.nanoTime();
        // also ensure not present via a quick list (defensive)
        List<LsRawDataStatus> existing = txTemplate.execute(s ->
                repository.findByRawDataIdIn(List.of(missing)));
        assertThat(existing).isEmpty();

        // when / then — 0행 영향
        assertThat(claim(missing)).isEqualTo(0);
    }
}

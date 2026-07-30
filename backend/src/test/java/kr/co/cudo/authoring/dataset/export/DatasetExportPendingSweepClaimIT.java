package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
 * Phase 9-B — 학습데이터 산출 stale PENDING 회수의 <b>원자 클레임</b> 통합 테스트
 * (실 DB, PostgreSQL Testcontainer).
 *
 * <p>배경: 배포 토폴로지는 2노드 Active-Active 인데 Quartz 클러스터링({@code isClustered})은 기본
 * <b>꺼져</b> 있어 같은 트리거가 양 노드에서 발화한다. 회수(sweep)가 "조회 후 엔티티 setter" 였으므로
 * 두 노드가 같은 행을 각자 FAILED 로 두 번 썼다(이중 쓰기). 회수는 상태 전이 자체를 조건부 UPDATE 로
 * 만들어 <b>한쪽만 1행</b>을 얻어야 한다({@code LsDataAugJobRepository#claimExpired} 와 동일 패턴).
 *
 * <p>시간 경과는 <b>cutoff 주입</b>으로 재현한다(행 시각 조작·클럭 목킹 없음).
 */
@SpringBootTest
@ActiveProfiles("local")
class DatasetExportPendingSweepClaimIT {

    @Autowired
    private DatasetExportTxService txService;

    @Autowired
    private LsDatasetExportRepository exportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdExportSns = new ArrayList<>();
    /** 시드한 부모 영상 — V146 FK(LS_DATASET_EXPORT → LS_DATA_RAW) 충족용. */
    private final List<Long> seededRawSns = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdExportSns.forEach(sn -> exportRepository.findById(sn).ifPresent(exportRepository::delete));
        createdExportSns.clear();
        // 부모 삭제 = 남은 export 이력 CASCADE 삭제.
        seededRawSns.forEach(sn -> RawVideoFixture.deleteRaws(jdbcTemplate, sn));
        seededRawSns.clear();
    }

    /** 고유 rawSn(실재하는 영상)으로 PENDING export 1건을 커밋 저장한다(REG_DT=now). */
    private LsDatasetExport persistPending() {
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        seededRawSns.add(rawSn);
        LsDatasetExport saved = exportRepository.saveAndFlush(
                LsDatasetExport.create(rawSn, 1, "/tmp/export/" + rawSn, "hash-" + rawSn));
        createdExportSns.add(saved.getExportSn());
        return saved;
    }

    /** 저장 직후 행을 stale 로 보이게 하는 cutoff(= now + 여유). 운영 코드의 {@code now - staleMinutes} 대응값. */
    private static LocalDateTime staleCutoff() {
        return LocalDateTime.now().plusMinutes(1);
    }

    private String statusOf(Long exportSn) {
        return exportRepository.findById(exportSn).orElseThrow().getExportSttsCd();
    }

    /**
     * 공유 컨테이너 DB 에 남아 있을 수 있는 <b>다른 테스트의 잔여 PENDING</b> 을 미리 회수해
     * 후보 집합을 이 테스트 행만으로 좁힌다(회수 건수 단언의 결정성 확보). 테스트는 순차 실행이므로
     * 잔여 행은 이미 종료된 테스트의 것이다.
     */
    private void drainPreexistingStale() {
        txService.sweepStalePending(LocalDateTime.now().plusMinutes(1));
    }

    @Test
    @DisplayName("2노드_동시_export_sweep_에서_같은_행이_한_번만_실패처리된다")
    void concurrentSweepClaimsEachRowExactlyOnce() throws Exception {
        // given — stale PENDING 1건 (다른 테스트 잔여분은 사전 회수해 후보를 이 행으로 한정)
        drainPreexistingStale();
        LsDatasetExport pending = persistPending();
        LocalDateTime cutoff = staleCutoff();

        // when — 두 노드(스레드)가 거의 동시에 sweep 한다
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        int total;
        try {
            Future<Integer> a = pool.submit(() -> {
                start.await();
                return txService.sweepStalePending(cutoff);
            });
            Future<Integer> b = pool.submit(() -> {
                start.await();
                return txService.sweepStalePending(cutoff);
            });
            start.countDown();
            total = a.get(60, TimeUnit.SECONDS) + b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // then — 회수 성공 건수 합이 정확히 1(= 한 노드만 클레임). 비원자면 2가 된다.
        assertThat(total).as("2노드가 같은 행을 각각 회수하면 이중 쓰기다").isEqualTo(1);
        assertThat(statusOf(pending.getExportSn())).isEqualTo(LsDatasetExport.STATUS_FAILED);
    }

    @Test
    @DisplayName("단일노드에서도_기존_동작이_유지된다 — stale은_FAILED로_회수되고_최신PENDING은_불변")
    void singleNodeBehaviourPreserved() {
        // given — stale 대상 1건 + 회수 대상이 아닌 최신 PENDING 1건
        drainPreexistingStale();
        LsDatasetExport stale = persistPending();
        LsDatasetExport fresh = persistPending();

        // when — stale 만 포함하는 cutoff 로 1회 회수(fresh 는 cutoff 이후 생성으로 간주)
        int swept = txService.sweepStalePending(stale.getRegDt().plusNanos(1_000));

        // then — stale 만 FAILED, fresh 는 PENDING 유지
        assertThat(swept).isEqualTo(1);
        assertThat(statusOf(stale.getExportSn())).isEqualTo(LsDatasetExport.STATUS_FAILED);
        assertThat(statusOf(fresh.getExportSn())).isEqualTo(LsDatasetExport.STATUS_PENDING);
    }

    @Test
    @DisplayName("이미_회수된_행은_재회수되지_않는다 — 멱등")
    void alreadySweptRowIsNotReclaimed() {
        // given
        drainPreexistingStale();
        LsDatasetExport pending = persistPending();
        LocalDateTime cutoff = staleCutoff();
        assertThat(txService.sweepStalePending(cutoff)).isEqualTo(1);

        // when — 같은 cutoff 로 재실행
        int second = txService.sweepStalePending(cutoff);

        // then — 0건(이미 FAILED 라 후보가 아니다)
        assertThat(second).isZero();
        assertThat(statusOf(pending.getExportSn())).isEqualTo(LsDatasetExport.STATUS_FAILED);
    }
}

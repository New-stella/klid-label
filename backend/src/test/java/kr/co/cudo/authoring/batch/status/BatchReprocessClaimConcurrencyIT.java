package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-ISSUE-101 회귀 가드 — 배치 <b>수동 재처리 원자 클레임</b>의 상호배제를 실 DB(PostgreSQL
 * Testcontainer) + 실제 동시 스레드로 고정한다 (CWE-362).
 *
 * <h3>왜 실 DB·실 스레드여야 하는가</h3>
 * <p>기존 회귀 가드였던 {@code BatchReprocessServiceTest#배치재처리_동시요청시_한쪽만_기동된다} 는
 * {@code tryClaimReprocessFromFailed} 를 <b>mock 으로 false 고정</b>해 검증하므로, 결함의 실체인
 * "RAW 클레임 0행 → 원인 미구분 → 작업상태 컬럼 폴백" 경로를 <b>한 번도 실행하지 않는다</b>.
 * 실측 결함(동일 rawSn 5요청 → 200 이 2건, 파이프라인 2벌 동시 실행)이 그대로 통과했다.
 *
 * <h3>고정하는 불변식</h3>
 * <ol>
 *   <li><b>정상 배치 실패 형상</b>(두 컬럼이 함께 FAILED)에서 동시 N 요청 → <b>정확히 1건만</b> true.
 *       구 구현은 A 가 RAW 컬럼을, B 가 작업상태 컬럼을 각각 선점해 2건이 true 였다.</li>
 *   <li><b>작업상태 행이 없는 형상</b>(RAW 만 FAILED)에서도 정확히 1건만 true(기존 동작 무회귀).</li>
 *   <li><b>작업상태만 FAILED</b>인 예외 형상에서는 여전히 폴백이 살아 있어 1건이 true
 *       (수정이 폴백 자체를 죽이지 않았다는 무회귀 단언).</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class BatchReprocessClaimConcurrencyIT {

    /** 동시 호출자 수 — 실측 재현과 동일하게 5요청으로 경합시킨다. */
    private static final int CONCURRENCY = 5;

    @Autowired
    private BatchTransitionService batchTransitionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> seeded = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long rawSn : seeded) {
            RawVideoFixture.deleteRaws(jdbcTemplate, rawSn);
        }
        seeded.clear();
    }

    private long seedRaw(String rawStage) {
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        seeded.add(rawSn);
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ? WHERE RAW_SN = ?", rawStage, rawSn);
        return rawSn;
    }

    private void seedWorkStatus(long rawSn, String workStatus) {
        jdbcTemplate.update("""
                INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER)
                VALUES (?, ?, CURRENT_TIMESTAMP, 0)
                """, rawSn, workStatus);
    }

    /** 동시 N 스레드가 같은 rawSn 에 클레임을 시도했을 때 true 를 받은 호출 수. */
    private int concurrentClaimSuccesses(long rawSn) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return batchTransitionService.tryClaimReprocessFromFailed(rawSn);
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.get(60, TimeUnit.SECONDS))) {
                    successes++;
                }
            }
            return successes;
        } finally {
            pool.shutdownNow();
        }
    }

    private String rawStage(long rawSn) {
        return jdbcTemplate.queryForObject(
                "SELECT DATA_STTS_CD FROM LS_DATA_RAW WHERE RAW_SN = ?", String.class, rawSn);
    }

    @Test
    @DisplayName("두_컬럼이_함께_FAILED인_정상실패_영상에_동시_5요청 — 정확히_1건만_클레임한다")
    void bothColumnsFailed_concurrentClaims_exactlyOneWins() throws Exception {
        // given — 정상 배치 실패의 기본형: 배치 단계·작업 상태가 모두 FAILED
        long rawSn = seedRaw("FAILED");
        seedWorkStatus(rawSn, "FAILED");

        // when — 5개 호출이 동시에 클레임을 시도한다(버튼 더블클릭·프론트 재전송으로도 자연 발생)
        int successes = concurrentClaimSuccesses(rawSn);

        // then — 정확히 1건. 2건이면 동일 영상 파이프라인이 2벌 동시 실행된다(중복 INSERT·중복 외부 위탁·
        //        재시도 예산 이중 소모). 구 구현은 여기서 2 를 반환했다.
        assertThat(successes).isEqualTo(1);
        assertThat(rawStage(rawSn)).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("작업상태_행이_없는_영상에_동시_5요청 — 정확히_1건만_클레임한다(무회귀 대조군)")
    void rawOnlyFailed_concurrentClaims_exactlyOneWins() throws Exception {
        // given — 작업 상태 row 자체가 없는 형상(파생 RAW 등). 대조군: 구 구현도 여기선 1건이었다.
        long rawSn = seedRaw("FAILED");

        // when
        int successes = concurrentClaimSuccesses(rawSn);

        // then
        assertThat(successes).isEqualTo(1);
        assertThat(rawStage(rawSn)).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("작업상태만_FAILED인_예외형상은_여전히_폴백으로_1건_클레임된다 — 폴백을_죽이지_않았음")
    void workStatusOnlyFailed_stillClaimableOnce() throws Exception {
        // given — 배치 단계는 FAILED 가 아니고(COMPLETED) 작업 상태만 FAILED 인 예외 형상.
        //         원인 구분 가드가 이 정당한 폴백까지 막아버리면 재처리 동선이 사라진다.
        long rawSn = seedRaw("COMPLETED");
        seedWorkStatus(rawSn, "FAILED");

        // when
        int successes = concurrentClaimSuccesses(rawSn);

        // then — 폴백 축에서도 단일 조건부 UPDATE 라 정확히 1건만 성공한다.
        assertThat(successes).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DATA_STTS_CD FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", String.class, rawSn))
                .isEqualTo("PROCESSING");
    }
}

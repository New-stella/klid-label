package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.service.ReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 방어 IT (CWE-362) — WORKER 제출취소(PENDING→ASSIGNED)와 REVIEWER 검수시작(PENDING→IN_REVIEW)이
 * 동일 영상에서 거의 동시에 경합할 때, {@code LsRawDataStatus.@Version} 낙관적 잠금 + 상태 머신 가드로
 * <b>정확히 한쪽만 성공</b>하고 다른 한쪽은 거부됨을 실 DB(PostgreSQL Testcontainer) 위에서 고정한다.
 *
 * <p>둘 다 성공(=이중 전이)하면 작업 상태 머신이 깨지므로, 무조건 UPDATE 로 회귀하면 즉시 실패한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ReviewCancelSubmitConcurrencyIT {

    @Autowired private ReviewService reviewService;
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 커밋 시드한 부모 영상 — V146 FK(작업상태·배정 → LS_DATA_RAW) 충족용. */
    private Long seededRawSn;

    @AfterEach
    void cleanSeededVideo() {
        if (seededRawSn != null) {
            RawVideoFixture.deleteRaws(jdbcTemplate, seededRawSn); // CASCADE 로 작업상태·배정 정리
            seededRawSn = null;
        }
    }

    private final TransactionTemplate txTemplate;

    ReviewCancelSubmitConcurrencyIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @Test
    @DisplayName("취소와_검수시작_경합시_하나만_성공 — PENDING서_동시_cancelSubmit·startReview_정확히한쪽만_전이")
    void cancelAndStartReviewRace_onlyOneSucceeds() throws Exception {
        // given — PENDING 상태 작업 행 + user 100 LABELER 배정을 독립 트랜잭션에 커밋
        // 부모 영상은 시드 트랜잭션 밖에서 커밋해 둔다(자식 INSERT 가 FK 를 만족해야 한다).
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        seededRawSn = rawSn;
        long workerNo = 100L;
        long reviewerNo = 1L;
        txTemplate.executeWithoutResult(s -> {
            LsRawDataStatus stts = LsRawDataStatus.builder()
                    .rawDataId(rawSn)
                    .dataSttsCd(LsRawDataStatus.STTS_PENDING)
                    .stpCycl(0)
                    .igiCycl(0)
                    .updDt(LocalDateTime.now())
                    .build();
            statusRepository.save(stts);
            assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, workerNo, reviewerNo));
        });

        TokenClaims worker = new TokenClaims(String.valueOf(workerNo), Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(600));
        TokenClaims reviewer = new TokenClaims(String.valueOf(reviewerNo), Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(600));

        // when — 두 스레드가 동일 rawSn 에 각자 트랜잭션으로 거의 동시에 상태 전이 시도
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        try {
            Future<Boolean> a = pool.submit(() -> {
                start.await();
                try {
                    reviewService.cancelSubmit(rawSn, worker);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });
            Future<Boolean> b = pool.submit(() -> {
                start.await();
                try {
                    reviewService.startReview(rawSn, reviewer);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });
            start.countDown();
            if (Boolean.TRUE.equals(a.get(30, TimeUnit.SECONDS))) successCount.incrementAndGet();
            if (Boolean.TRUE.equals(b.get(30, TimeUnit.SECONDS))) successCount.incrementAndGet();
        } finally {
            pool.shutdownNow();
        }

        // then — 정확히 하나만 성공, 최종 상태는 ASSIGNED(취소 승) 또는 IN_REVIEW(검수시작 승) 중 하나
        assertThat(successCount.get()).isEqualTo(1);
        String finalStatus = txTemplate.execute(s ->
                statusRepository.findById(rawSn).orElseThrow().getDataSttsCd());
        assertThat(finalStatus).isIn(LsRawDataStatus.STTS_ASSIGNED, LsRawDataStatus.STTS_IN_REVIEW);
    }
}

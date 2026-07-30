package kr.co.cudo.authoring.batch.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * B-ISSUE-03 회귀 IT — 배치 상태 전이가 <b>검수 소유 상태</b>(IN_REVIEW/APPROVED)를 덮어쓰지 않는지
 * 실 DB(PostgreSQL Testcontainer)에서 고정한다.
 *
 * <p>결함: {@code BatchTransitionService} 의 작업 상태 전이에 상태 검증이 전혀 없어
 * (주석은 {@code LsRawDataStatus} 책임이라 하고, 엔티티 주석은 {@code ReviewStateMachine} 책임이라 하는데
 * 배치 경로는 상태 머신을 호출하지 않는다) APPROVED 영상에 배치를 재실행하면
 * {@code APPROVED → PROCESSING → ASSIGNED} 로 검수 승인이 조용히 사라졌다.
 *
 * <p>정책: 예외를 던지지 않는다(배치가 검수 워크플로우를 막으면 안 됨). 상태를 건드리지 않고
 * WARN 로그만 남기고 진행한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class BatchTransitionReviewGuardIT {

    @Autowired
    private LsRawDataStatusRepository repository;

    @Autowired
    private BatchTransitionService batchTransitionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 시드한 부모 영상 — V146 FK(LS_RAW_DATA_STATUS → LS_DATA_RAW) 충족용. */
    private final List<Long> seededRawSns = new ArrayList<>();

    private final TransactionTemplate txTemplate;

    BatchTransitionReviewGuardIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void cleanSeededVideos() {
        // 부모 삭제 = 작업 상태 행 CASCADE 삭제.
        seededRawSns.forEach(sn -> RawVideoFixture.deleteRaws(jdbcTemplate, sn));
        seededRawSns.clear();
    }

    private long persistStatus(String status) {
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        seededRawSns.add(rawSn);
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

    private String reload(long rawSn) {
        return txTemplate.execute(s -> repository.findById(rawSn).orElseThrow().getDataSttsCd());
    }

    @Test
    @DisplayName("APPROVED_영상에_배치_재트리거시_상태가_APPROVED_로_유지되고_예외는_던지지_않으며_WARN_이_남음")
    void approvedVideoIsNotDemotedByBatch() {
        // given — 검수 승인 완료된 영상
        long rawSn = persistStatus(LsRawDataStatus.STTS_APPROVED);
        Logger logger = (Logger) LoggerFactory.getLogger(BatchTransitionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when — 배치 재트리거 (시작 → 완료)
            assertThatCode(() -> batchTransitionService.markRawDataProcessingBlocked(rawSn))
                    .doesNotThrowAnyException();
            assertThat(reload(rawSn)).isEqualTo(LsRawDataStatus.STTS_APPROVED);

            assertThatCode(() -> batchTransitionService.markRawDataCompleted(rawSn))
                    .doesNotThrowAnyException();

            // then — 검수 승인 상태 불변 (기존엔 APPROVED → PROCESSING → ASSIGNED 로 소실)
            assertThat(reload(rawSn)).isEqualTo(LsRawDataStatus.STTS_APPROVED);

            long warns = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains(String.valueOf(rawSn)))
                    .count();
            assertThat(warns).isGreaterThanOrEqualTo(2);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("IN_REVIEW_영상에_배치_전이_시도시_상태가_IN_REVIEW_로_유지됨")
    void inReviewVideoIsNotOverwrittenByBatch() {
        // given — 검수 진행 중인 영상
        long rawSn = persistStatus(LsRawDataStatus.STTS_IN_REVIEW);

        // when — 배치 시작/완료/실패 전이를 모두 시도
        assertThatCode(() -> {
            batchTransitionService.markRawDataProcessingBlocked(rawSn);
            batchTransitionService.markRawDataCompleted(rawSn);
            batchTransitionService.markRawDataFailed(rawSn);
        }).doesNotThrowAnyException();

        // then — 검수 진행 상태 불변
        assertThat(reload(rawSn)).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
    }

    @Test
    @DisplayName("검수대기_PENDING_영상에_배치_전이_시도시_차단되고_상태가_PENDING_으로_유지됨")
    void pendingVideoIsNotOverwrittenByBatch() {
        // given — 작업자가 검수 제출한(PENDING) 영상. 배치가 PENDING 에서 출발하는 정상 전이는 코드에 없다(H1-b).
        long rawSn = persistStatus(LsRawDataStatus.STTS_PENDING);

        // when — 배치 시작/완료/실패 전이를 모두 시도
        boolean blocked = batchTransitionService.markRawDataProcessingBlocked(rawSn);
        batchTransitionService.markRawDataCompleted(rawSn);
        batchTransitionService.markRawDataFailed(rawSn);

        // then — 진입이 차단(true)되고 검수 대기 상태 불변
        assertThat(blocked).isTrue();
        assertThat(reload(rawSn)).isEqualTo(LsRawDataStatus.STTS_PENDING);
    }

    @Test
    @DisplayName("반려_REJECTED_영상에_배치_전이_시도시_차단되고_상태가_REJECTED_로_유지됨")
    void rejectedVideoIsNotOverwrittenByBatch() {
        // given — 검수 반려된 영상
        long rawSn = persistStatus(LsRawDataStatus.STTS_REJECTED);

        // when
        boolean blocked = batchTransitionService.markRawDataProcessingBlocked(rawSn);
        batchTransitionService.markRawDataCompleted(rawSn);

        // then
        assertThat(blocked).isTrue();
        assertThat(reload(rawSn)).isEqualTo(LsRawDataStatus.STTS_REJECTED);
    }

    @Test
    @DisplayName("정상_배치흐름_ASSIGNED에서_PROCESSING_거쳐_ASSIGNED_복귀가_그대로_동작함")
    void normalBatchFlowStillTransitions() {
        // given — 배정 완료(ASSIGNED) 상태 영상
        long rawSn = persistStatus(LsRawDataStatus.STTS_ASSIGNED);

        // when / then — 배치 시작 → PROCESSING (차단되지 않아야 함)
        assertThat(batchTransitionService.markRawDataProcessingBlocked(rawSn)).isFalse();
        assertThat(reload(rawSn)).isEqualTo(LsRawDataStatus.STTS_PROCESSING);

        // 배치 완료 → ASSIGNED 복귀 (COMPLETED 점프 금지 — 검수 제출 ASSIGNED→PENDING 이 막히면 안 됨)
        batchTransitionService.markRawDataCompleted(rawSn);
        assertThat(reload(rawSn)).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
    }

    @Test
    @DisplayName("BATCH_QUEUED_에서_시작하는_마킹완료_배치흐름도_그대로_동작함")
    void batchQueuedFlowStillTransitions() {
        // given — 마킹 완료로 큐잉된 상태
        long rawSn = persistStatus(LsRawDataStatus.STTS_BATCH_QUEUED);

        // when / then — 큐잉 → 처리중 → 완료(ASSIGNED 복귀)
        batchTransitionService.markRawDataProcessingBlocked(rawSn);
        assertThat(reload(rawSn)).isEqualTo(LsRawDataStatus.STTS_PROCESSING);
        batchTransitionService.markRawDataCompleted(rawSn);
        assertThat(reload(rawSn)).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
    }

    @Test
    @DisplayName("벌크UPDATE_직후_같은_트랜잭션에서_엔티티를_읽으면_최신값이_보인다 — clearAutomatically_호출순서_계약")
    void bulkUpdateThenEntityReadSeesFreshValue() {
        // given — 배정 완료(ASSIGNED) 상태 영상
        long rawSn = persistStatus(LsRawDataStatus.STTS_ASSIGNED);

        // when / then — "벌크 UPDATE 를 먼저 실행하고 그 뒤 엔티티를 읽는다"는 암묵 계약을 고정한다.
        //   @Modifying(clearAutomatically=true) 가 1차 캐시를 비우므로 이 순서에서는 stale read 가 없다.
        //   (반대로 읽고 나서 UPDATE 하고 다시 같은 엔티티 참조를 쓰면 stale 값이 보일 수 있다.)
        txTemplate.executeWithoutResult(s -> {
            repository.findById(rawSn).orElseThrow(); // 1차 캐시 적재
            int affected = repository.transitionByBatchIfNotBlocked(
                    rawSn, LsRawDataStatus.STTS_PROCESSING, BatchTransitionService.REVIEW_OWNED_STATUSES);
            assertThat(affected).isEqualTo(1);
            assertThat(repository.findById(rawSn).orElseThrow().getDataSttsCd())
                    .isEqualTo(LsRawDataStatus.STTS_PROCESSING);
        });
    }

    @Test
    @DisplayName("배치_실패_전이는_비검수_상태에서_그대로_FAILED_로_동작함")
    void failedTransitionStillWorksForNonReviewState() {
        // given
        long rawSn = persistStatus(LsRawDataStatus.STTS_PROCESSING);

        // when
        batchTransitionService.markRawDataFailed(rawSn);

        // then
        assertThat(reload(rawSn)).isEqualTo(LsRawDataStatus.STTS_FAILED);
    }
}

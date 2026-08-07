package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7a-1 — {@link TaskModifiedEvent#needsRecheck()} 축이 실제 트랜잭션 커밋/롤백 경계와
 * 정확히 맞물리는지 검증하는 통합 테스트(PostgreSQL Testcontainer, 실 {@code @TransactionalEventListener}).
 *
 * <h3>왜 Mockito 단위 테스트로는 부족한가</h3>
 * <p>{@link ReviewRecheckMarkListener}(단위 테스트로 이미 커버)는 "이벤트를 받으면 게이트를 호출하는가"만
 * 검증한다. 이 클래스가 검증하는 것은 <b>그 이전 단계</b> — {@code @TransactionalEventListener(AFTER_COMMIT)}
 * 가 실제로 롤백 시 리스너 자체를 호출하지 않는가다. 이건 실제 {@link PlatformTransactionManager} +
 * Spring 트랜잭션 동기화가 관여해야만 관측 가능하며, {@code AugmentAsyncSubmitBoundaryIT} 의
 * {@code TransactionTemplate + status.setRollbackOnly()} 패턴을 그대로 재사용한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ReviewApprovalGateAfterCommitIT {

    @Autowired private ReviewApprovalGate approvalGate;
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager controlTxManager;

    /** 검수 완료(APPROVED) 상태의 영상 1건을 커밋된 상태로 seed 한다. */
    private Long seedApprovedVideo() {
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "RARG-" + UUID.randomUUID(), "CCTV-RARG", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/RARG.mp4", LocalDateTime.now(), 30));
        Long rawSn = raw.getRawSn();
        LsRawDataStatus status = LsRawDataStatus.initial(rawSn);
        status.transitionTo(LsRawDataStatus.STTS_APPROVED);
        statusRepository.saveAndFlush(status);
        return rawSn;
    }

    @Test
    @DisplayName("커밋되면_needsRecheck_true_이벤트가_재검토_표시를_세운다")
    void commit_marksNeedsRecheck() {
        Long rawSn = seedApprovedVideo();

        TransactionTemplate tx = new TransactionTemplate(controlTxManager);
        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new TaskModifiedEvent(
                rawSn, null, ChangeType.META_UPDATED, 1L, true, true)));

        // AFTER_COMMIT 리스너는 발행 트랜잭션이 실제로 커밋된 뒤에만 실행된다.
        assertThat(approvalGate.needsRecheck(rawSn)).isTrue();
    }

    @Test
    @DisplayName("발행_트랜잭션이_롤백되면_재검토_표시가_남지_않는다")
    void rollback_doesNotMarkNeedsRecheck() {
        Long rawSn = seedApprovedVideo();

        TransactionTemplate tx = new TransactionTemplate(controlTxManager);
        tx.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, null, ChangeType.META_UPDATED, 1L, true, true));
            // 발행 트랜잭션 자체를 롤백시킨다 — AFTER_COMMIT 리스너는 호출되지 않아야 한다.
            status.setRollbackOnly();
        });

        assertThat(approvalGate.needsRecheck(rawSn)).isFalse();
    }

    @Test
    @DisplayName("needsRecheck_false_이벤트는_커밋되어도_표시를_세우지_않는다")
    void commitWithNeedsRecheckFalse_doesNotMark() {
        Long rawSn = seedApprovedVideo();

        TransactionTemplate tx = new TransactionTemplate(controlTxManager);
        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new TaskModifiedEvent(
                rawSn, null, ChangeType.META_UPDATED, 1L, true, false)));

        assertThat(approvalGate.needsRecheck(rawSn)).isFalse();
    }
}

package kr.co.cudo.authoring.batch.vlm;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C-1 — 비동기 제출 경로의 <b>트랜잭션 경계가 실제로 발효되는지</b> 검증하는 통합 테스트.
 *
 * <h3>왜 이 테스트가 필요한가 (이 레포의 실사고 패턴)</h3>
 * <p>과거 이 레포에서 {@code @Transactional} 메서드를 <b>같은 클래스 안에서</b> 호출해(self-invocation)
 * 프록시를 거치지 않아 경계가 통째로 사라졌고, <b>테스트는 전부 GREEN</b>이었다 — 테스트가 ambient
 * 트랜잭션 안에서 돌아 경계 유실이 관측되지 않았기 때문이다. 그래서 여기서는:
 * <ul>
 *   <li>테스트 클래스에 {@code @Transactional} 을 <b>붙이지 않는다</b>(ambient tx 없음).</li>
 *   <li>{@code REQUIRES_NEW} 의 핵심 성질 — <b>바깥 트랜잭션이 롤백돼도 안쪽 쓰기는 살아남는다</b> —
 *       을 직접 관측한다. 경계가 유실되면 안쪽 쓰기가 바깥과 함께 롤백되어 RED 가 된다.</li>
 * </ul>
 *
 * <p>공유 Testcontainers PG 격리를 위해 시드는 고유 clipId 를 쓰고 단언은 시드 rawSn 으로만 좁힌다.
 */
@SpringBootTest
@ActiveProfiles("local")
class VlmAsyncSubmitTransactionBoundaryIT {

    @Autowired private VlmMarkingTxService markingTxService;
    @Autowired private BatchStatusService batchStatusService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsMarkingRepository markingRepository;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager controlTxManager;

    private Long seedVideo() {
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "VLMTXB-" + UUID.randomUUID(), "CCTV-VLMTXB", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/vlmtxb.mp4", LocalDateTime.now(), 30));
        return raw.getRawSn();
    }

    private LsMarking seedMarkingVlmRequested(Long rawSn) {
        LsMarking m = LsMarking.createAuto(rawSn, 5,
                "[{\"frameIndex\":0,\"timestamp\":0.0}]", "1");
        m.markVlmRequested();
        return markingRepository.save(m);
    }

    @Test
    @DisplayName("마킹_실패전이는_바깥_트랜잭션이_롤백돼도_독립_커밋된다_REQUIRES_NEW_경계_발효")
    void markingFailureTransitionSurvivesOuterRollback() {
        // given — ambient tx 없는 상태에서 시드
        Long rawSn = seedVideo();
        Long markingSn = seedMarkingVlmRequested(rawSn).getMarkingSn();

        // when — 바깥 트랜잭션을 열고 안쪽 REQUIRES_NEW 를 호출한 뒤 바깥을 롤백시킨다.
        TransactionTemplate outer = new TransactionTemplate(controlTxManager);
        outer.executeWithoutResult(status -> {
            markingTxService.markVlmFailedIfRequested(markingSn);
            status.setRollbackOnly();
        });

        // then — 경계가 살아 있으면 안쪽 쓰기는 커밋돼 있다.
        //        자기호출 등으로 경계가 유실됐다면 바깥 롤백에 휩쓸려 VLM_REQUESTED 로 RED.
        assertThat(markingRepository.findById(markingSn).orElseThrow().getSttsCd())
                .isEqualTo(LsMarking.STATUS_VLM_FAILED);
    }

    @Test
    @DisplayName("비동기_완료핸들러의_감사행_기록도_바깥_롤백에_휩쓸리지_않는다")
    void asyncAuditRecordSurvivesOuterRollback() {
        Long rawSn = seedVideo();

        TransactionTemplate outer = new TransactionTemplate(controlTxManager);
        outer.executeWithoutResult(status -> {
            batchStatusService.recordVlmSkippedInNewTx(
                    rawSn, VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED);
            status.setRollbackOnly();
        });

        assertThat(batchStatusService.isStageSkippedWithAnyReason(
                rawSn, BatchStage.VLM, VlmTimeseriesStep.RESUMABLE_SKIP_REASONS)).isTrue();
    }

    @Test
    @DisplayName("완료핸들러와_스위퍼는_자기호출_트랜잭션을_갖지_않고_별도_프록시_빈에_위임한다")
    void asyncCollaboratorsDelegateTransactionsToProxiedBeans() {
        // 트랜잭션 경계를 소유한 협력자는 반드시 AOP 프록시여야 어드바이스가 발효된다.
        assertThat(AopUtils.isAopProxy(markingTxService))
                .as("VlmMarkingTxService 가 프록시가 아니면 REQUIRES_NEW 가 발효되지 않는다")
                .isTrue();
        assertThat(AopUtils.isAopProxy(batchStatusService)).isTrue();

        // 완료 핸들러/스위퍼가 스스로 @Transactional 을 들고 있으면, 그 안에서의 협력자 호출이
        // 자기호출처럼 보이는 착시 + 중첩 tx 커넥션 점유를 유발한다 — 구조적으로 금지한다.
        assertNoDeclaredTransactional(
                kr.co.cudo.authoring.batch.step.VlmSubmitOutcomeRecorder.class);
        assertNoDeclaredTransactional(VlmSubmitPendingSweeper.class);
    }

    private void assertNoDeclaredTransactional(Class<?> type) {
        assertThat(type.getAnnotation(Transactional.class))
                .as("%s 에 클래스 레벨 @Transactional 이 있으면 안 된다", type.getSimpleName())
                .isNull();
        for (Method m : type.getDeclaredMethods()) {
            assertThat(m.getAnnotation(Transactional.class))
                    .as("%s#%s 에 @Transactional 이 있으면 안 된다(트랜잭션은 별도 빈에 위임)",
                            type.getSimpleName(), m.getName())
                    .isNull();
        }
    }
}

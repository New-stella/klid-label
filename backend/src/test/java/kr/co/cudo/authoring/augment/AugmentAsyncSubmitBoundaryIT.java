package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.service.AugmentJobRecorder;
import kr.co.cudo.authoring.augment.service.AugmentSubmitOutcomeRecorder;
import kr.co.cudo.authoring.augment.service.AugmentSubmitRollupTxService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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
 * Phase C-3 — 증강 <b>비동기 제출</b>의 트랜잭션 경계·원자 클레임·종결 보장 통합 테스트 (실 DB).
 *
 * <h3>왜 ambient 트랜잭션 없이 도는가 (이 레포의 실사고 패턴)</h3>
 * <p>과거 이 레포에서 {@code @Transactional} 메서드를 같은 클래스 안에서 호출해(self-invocation)
 * 프록시를 거치지 않아 경계가 통째로 사라졌고, <b>테스트는 전부 GREEN</b>이었다 — 테스트가 ambient
 * 트랜잭션 안에서 돌아 경계 유실이 관측되지 않았기 때문이다. 그래서 이 클래스에는
 * {@code @Transactional} 을 붙이지 않고, {@code REQUIRES_NEW} 의 핵심 성질(<b>바깥이 롤백돼도 안쪽
 * 쓰기는 살아남는다</b>)을 직접 관측한다.
 *
 * <h3>고정하는 불변식</h3>
 * <ol>
 *   <li><b>지각 신호 상태 강등 금지</b> — 콜백이 먼저 SUCCEEDED/RUNNING 으로 올린 job 을 뒤늦은 ACK·
 *       제출 실패가 되돌리지 못한다(조건부 원자 UPDATE = 2노드 클레임).</li>
 *   <li><b>REQUIRES_NEW 발효</b> — 제출 실패 기록이 바깥 롤백에 휩쓸리지 않는다.</li>
 *   <li><b>종결 보장</b> — 전 청크 제출 실패(전부 terminal FAILED)면 시퀀스 종료 롤업이 증강을
 *       확정한다. 이 경로가 없으면 만료 스윕의 두 축(비종결 job / job 0건)이 모두 집지 못해
 *       PENDING 에 영구 고착된다.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class AugmentAsyncSubmitBoundaryIT {

    @Autowired private AugmentJobRecorder jobRecorder;
    @Autowired private AugmentSubmitRollupTxService rollupTxService;
    @Autowired private AugmentSubmitOutcomeRecorder outcomeRecorder;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugJobRepository jobRepository;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager controlTxManager;

    private record Seed(Long dataAugSn, Long augJobSn) { }

    /** 부모(비식별 완료) + 프레임 1건 + PENDING 증강 1건 + 선기록(RECEIVED) job 1건. */
    private Seed seed() {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AASB-" + UUID.randomUUID(), "CCTV-AASB", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/AASB.mp4", LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 0, parent.getRawSn() + "/f0.jpg", null));

        String requestId = "AASB-K-" + UUID.randomUUID();
        LsDataAug aug = augRepository.save(LsDataAug.createRequested(
                frame.getSrcSn(), LsDataAug.AUG_WINTER, "1", requestId, null));
        LsDataAugJob job = jobRepository.save(
                LsDataAugJob.createIssued(aug.getDataAugSn(), 1, requestId, 1));
        return new Seed(aug.getDataAugSn(), job.getAugJobSn());
    }

    private LsDataAugJob reload(Long augJobSn) {
        return jobRepository.findById(augJobSn).orElseThrow();
    }

    // ─── ① 지각 신호 상태 강등 금지 (조건부 원자 클레임) ───────────────

    @Test
    @DisplayName("선기록_상태의_job_은_제출_ACK_로_외부_job_id_가_적재된다")
    void ackClaimSucceedsOnIssuedJob() {
        Seed s = seed();

        assertThat(jobRecorder.markSubmitAccepted(s.augJobSn(), "ext-job-1")).isTrue();

        assertThat(reload(s.augJobSn()).getExternalJobId()).isEqualTo("ext-job-1");
    }

    @Test
    @DisplayName("콜백이_먼저_SUCCEEDED_로_올린_job_은_지각_ACK_로_강등되지_않는다")
    void lateAckNeverDowngradesSucceededJob() {
        // given — 벤더 콜백이 ACK 보다 먼저 도착해 job 을 종결시킨 상황(논블로킹 제출에서 현실적).
        Seed s = seed();
        LsDataAugJob job = reload(s.augJobSn());
        job.markSucceeded("ext-from-callback");
        jobRepository.saveAndFlush(job);

        // when — 뒤늦게 도착한 제출 ACK
        boolean claimed = jobRecorder.markSubmitAccepted(s.augJobSn(), "ext-from-ack");

        // then — 클레임 실패(0행). 상태·job_id 가 그대로여야 롤업이 정상 진행된다.
        assertThat(claimed).isFalse();
        LsDataAugJob after = reload(s.augJobSn());
        assertThat(after.getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
        assertThat(after.getExternalJobId()).isEqualTo("ext-from-callback");
    }

    @Test
    @DisplayName("콜백이_먼저_RUNNING_으로_올린_job_은_지각_제출실패로_FAILED_되지_않는다")
    void lateSubmitFailureNeverKillsRunningJob() {
        // given — 우리 쪽 타임아웃이지만 요청은 실제로 도달해 벤더가 RUNNING 을 통보한 상황.
        Seed s = seed();
        LsDataAugJob job = reload(s.augJobSn());
        job.markRunning("ext-from-callback");
        jobRepository.saveAndFlush(job);

        // when
        boolean claimed = jobRecorder.markSubmitFailed(
                s.augJobSn(), LsDataAugJob.ERR_SUBMIT_FAILED, "timeout");

        // then — 살아 있는 job 을 죽이면 정상 산출물이 멱등 흡수로 버려진다.
        assertThat(claimed).isFalse();
        assertThat(reload(s.augJobSn()).getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_RUNNING);
    }

    @Test
    @DisplayName("두_노드가_동시에_같은_job_을_노려도_한쪽만_클레임한다")
    void onlyOneNodeWinsTheClaim() {
        Seed s = seed();

        boolean first = jobRecorder.markSubmitFailed(
                s.augJobSn(), LsDataAugJob.ERR_SUBMIT_FAILED, "conn reset");
        boolean second = jobRecorder.markSubmitFailed(
                s.augJobSn(), LsDataAugJob.ERR_SUBMIT_FAILED, "conn reset");

        assertThat(first).isTrue();
        assertThat(second).as("상태 전이 자체가 클레임이라 두 번째는 0행이다").isFalse();
        assertThat(reload(s.augJobSn()).getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_FAILED);
    }

    // ─── ② REQUIRES_NEW 경계 발효 ──────────────────────────────

    @Test
    @DisplayName("제출_실패_기록은_바깥_트랜잭션이_롤백돼도_독립_커밋된다")
    void submitFailureRecordSurvivesOuterRollback() {
        Seed s = seed();

        TransactionTemplate outer = new TransactionTemplate(controlTxManager);
        outer.executeWithoutResult(status -> {
            jobRecorder.markSubmitFailed(s.augJobSn(), LsDataAugJob.ERR_SUBMIT_FAILED, "boom");
            status.setRollbackOnly();
        });

        // 경계가 자기호출 등으로 유실됐다면 바깥 롤백에 휩쓸려 RECEIVED 로 RED.
        assertThat(reload(s.augJobSn()).getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_FAILED);
    }

    @Test
    @DisplayName("완료핸들러와_종결판정_빈의_트랜잭션_배선이_구조적으로_고정된다")
    void asyncCollaboratorsDelegateTransactionsToProxiedBeans() {
        // 트랜잭션 경계를 소유한 협력자는 반드시 AOP 프록시여야 어드바이스가 발효된다.
        assertThat(AopUtils.isAopProxy(rollupTxService))
                .as("AugmentSubmitRollupTxService 가 프록시가 아니면 REQUIRES_NEW 가 발효되지 않는다")
                .isTrue();
        assertThat(AopUtils.isAopProxy(jobRecorder)).isTrue();

        // 완료 핸들러가 스스로 @Transactional 을 들면 자기호출 착시 + 중첩 tx 커넥션 점유가 생긴다.
        assertThat(AugmentSubmitOutcomeRecorder.class.getAnnotation(Transactional.class)).isNull();
        for (Method m : AugmentSubmitOutcomeRecorder.class.getDeclaredMethods()) {
            assertThat(m.getAnnotation(Transactional.class))
                    .as("AugmentSubmitOutcomeRecorder#%s 에 @Transactional 이 있으면 안 된다", m.getName())
                    .isNull();
        }
    }

    // ─── ③ 종결 보장 (구 accepted==0 즉시 롤업의 이관처) ──────────

    @Test
    @DisplayName("전_청크_제출이_실패하면_시퀀스_종료_롤업이_증강을_실패로_확정한다")
    void rollUpConfirmsFailureWhenAllChunksFailedToSubmit() {
        // given — 선기록된 유일한 청크의 제출이 비동기로 실패해 terminal FAILED 가 됐다.
        //         이 상태는 만료 스윕의 두 축(비종결 job / job 0건) 어느 쪽에도 잡히지 않는다.
        Seed s = seed();
        outcomeRecorder.onSubmitFailed(s.dataAugSn(), s.augJobSn(), 1, 1,
                new IllegalStateException("외부 전면 장애"));
        assertThat(reload(s.augJobSn()).getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_FAILED);

        // when — 시퀀스 종료 판정
        outcomeRecorder.onSubmitSequenceFinished(s.dataAugSn());

        // then — PENDING 고착이 아니라 실패로 확정된다.
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_REJECTED);
    }

    @Test
    @DisplayName("아직_비종결_job_이_남아있으면_종결판정은_보류된다")
    void rollUpIsDeferredWhileJobStillInFlight() {
        // given — 수락돼 콜백 대기 중(RECEIVED).
        Seed s = seed();
        outcomeRecorder.onAccepted(s.dataAugSn(), s.augJobSn(), "ext-job-1", 1, 1);

        // when
        outcomeRecorder.onSubmitSequenceFinished(s.dataAugSn());

        // then — 콜백이 확정할 몫이다. 여기서 실패로 못박으면 정상 산출물이 버려진다.
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_PENDING);
    }
}

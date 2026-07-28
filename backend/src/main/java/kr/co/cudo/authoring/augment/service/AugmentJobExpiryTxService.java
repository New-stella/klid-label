package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.service.AugmentApplyResult;
import kr.co.cudo.authoring.webhook.service.AugmentJobRollup;
import kr.co.cudo.authoring.webhook.service.AugmentOutcome;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 비종결 증강 위탁 job <b>1건</b>을 만료 종결하고 롤업까지 이어 붙이는 트랜잭션 경계 (Phase 8-A).
 *
 * <p>스윕 루프({@link AugmentJobExpirySweeper})는 트랜잭션 밖에서 돌고, 회수 1건마다 이 빈을
 * 호출한다 — 자기호출(self-invocation)이면 프록시가 적용되지 않아 {@code @Transactional} 이
 * 통째로 무효가 되므로 <b>반드시 별도 빈</b>이어야 한다.
 *
 * <h3>잠금 순서 (롤업 유실 방지)</h3>
 * <p>웹훅 수신부와 <b>같은 순서</b>로 잠근다: ①증강 행 {@code FOR UPDATE} → ②job 갱신 → ③재조회 후
 * 롤업. 순서를 뒤집으면(job 먼저 갱신) 두 트랜잭션이 서로의 미커밋 갱신을 못 봐서 양쪽 다
 * "아직 남은 job 있음" 으로 판정하고 롤업이 통째로 사라진다. 같은 순서를 지키므로 스윕과 웹훅이
 * 동시에 들어와도 증강 행 잠금으로 직렬화된다(데드락 없음).
 *
 * <h3>2노드 Active-Active 중복 회수 방지</h3>
 * <p>회수는 조건부 UPDATE({@link LsDataAugJobRepository#claimExpired})로만 수행한다. 상태 전이
 * 자체가 클레임이라 두 노드가 같은 job 을 노려도 1행을 얻는 쪽이 하나뿐이다. 클레임에 실패하면
 * (0행) 이미 다른 노드가 회수했거나 그 사이 웹훅이 정상 종결시킨 것이므로 <b>아무것도 하지 않는다</b>.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentJobExpiryTxService {

    /** 만료 사유 메시지 — 내부 경로/식별정보 없이 원인만 남긴다(CWE-209/359). */
    private static final String EXPIRY_REASON =
            "외부 응답 없음 — 무갱신 경과 임계 초과로 만료 종결(취소 통보 미수신·콜백 거부·무응답)";

    private final LsDataAugRepository augRepository;
    private final LsDataAugJobRepository jobRepository;
    private final LsDataSrcRepository srcRepository;
    private final AugmentJobRollup rollup;
    private final AugmentResultService augmentResultService;
    /** 정책 보류(비식별 누락 신고) 판정 단일 원천 — 자체 재구현 금지. */
    private final DeidentReportGate deidentReportGate;
    private final AugmentMetrics metrics;

    /**
     * job 1건을 만료 종결하고, 그 결과 전 job 이 종결되면 증강 1건을 확정한다.
     *
     * @param cutoff 이 시각 이전부터 갱신이 멈춘 job 만 회수한다(= now - 무갱신 경과 임계)
     * @return true = 이번 호출이 회수함 / false = 클레임 실패(다른 노드 선점·정상 종결됨)
     */
    @Transactional("controlTransactionManager")
    public boolean expire(Long augJobSn, Long dataAugSn, LocalDateTime cutoff) {
        // 1) 롤업 직렬화 — job 갱신 전에 증강 행을 잠근다(웹훅 경로와 동일 순서).
        if (augRepository.findByDataAugSnForUpdate(dataAugSn).isEmpty()) {
            log.warn("[Augment][Expiry] aug row not found — skip dataAugSn={}", dataAugSn);
            return false;
        }

        // 2) 원자 클레임 = 상태 전이. 0행이면 이미 누군가 종결시킨 것이다.
        if (jobRepository.claimExpired(augJobSn, cutoff, LsDataAugJob.ERR_EXPIRED,
                EXPIRY_REASON, LocalDateTime.now()) == 0) {
            return false;
        }
        // [DEV_FIX LOW-1] 집계는 <커밋 이후>에만 한다. 아래 rollup 이 던지면 이 트랜잭션은 통째로
        // 롤백되는데(스윕이 예외를 잡고 다음 후보로 넘어간다), 여기서 즉시 증가시키면 <회수되지 않은
        // 건>이 회수로 집계돼 카운터가 DB 상태와 어긋난다.
        countJobExpiredAfterCommit();

        // 3) 잠금 이후 job 전량 재조회 → 전 job 종결이면 확정. 만료는 FAILED 라 성공으로 둔갑하지 않는다.
        //    실패 확정은 AugmentResultService 가 dead-letter 로 못박으므로(Phase 8-B) 만료된 증강이
        //    집계에서 COMPLETED 로 보이지 않는다.
        List<LsDataAugJob> jobs = jobRepository.findByDataAugSnOrderByJobSeqAsc(dataAugSn);
        AugmentApplyResult rolledUp = rollup.rollUpIfAllTerminal(dataAugSn, jobs, externalJobIdOf(jobs));
        log.warn("[Augment][Expiry] job expired (non-terminal reclaimed) augJobSn={} dataAugSn={} rollup={}",
                augJobSn, dataAugSn, rolledUp);
        return true;
    }

    /**
     * <b>job 행 0건 장기 PENDING</b> 증강 1건을 회수해 실패로 확정한다 (적대검증 2차 MEDIUM-2).
     *
     * <p>이 상태는 위탁 전 롤업이 예외로 끝나 job 도 콜백도 없는 고아다. 재개 트리거(비식별 신고 해제)가
     * 없는 영상이면 영원히 깨어나지 못하므로, 실패 인계의 단일 깔때기({@link AugmentResultService})로
     * 넘겨 {@code REJECTED} + dead-letter 로 못박아 집계에 드러낸다.
     *
     * <h3>정상 보류는 회수하지 않는다 (fail-safe)</h3>
     * <p>후보 SQL 이 이미 걸러내지만, 후보 조회~여기 사이에 신고가 커밋될 수 있다. 그래서 <b>잠금
     * 하에</b> 다시 판정한다: 증강 행 {@code FOR UPDATE} → 부모 RAW {@code FOR UPDATE}
     * ({@link DeidentReportGate#isUnderDeidentReportLocked}) 순서로만 잠그므로 인계 경로와 락 순서가
     * 같다(데드락 없음). 보류면 아무것도 하지 않고 신고 해제 재개에 맡긴다.
     *
     * @return true = 이번 호출이 회수함 / false = 대상 아님(이미 종결·job 생김·정책 보류)
     */
    @Transactional("controlTransactionManager")
    public boolean expireOrphanPending(Long dataAugSn) {
        LsDataAug aug = augRepository.findByDataAugSnForUpdate(dataAugSn).orElse(null);
        if (aug == null
                || !LsDataAug.STTS_PENDING.equals(aug.getAugProcSttsCd())
                || !AugmentPrompts.isExternalAugType(aug.getAugTypeCd())) {
            return false;
        }
        // 잠금 이후 재조회 — 그 사이 위탁이 성공해 job 이 생겼다면 콜백/job 만료 스윕의 몫이다.
        if (!jobRepository.findByDataAugSnOrderByJobSeqAsc(dataAugSn).isEmpty()) {
            return false;
        }
        Long parentRawSn = srcRepository.findById(aug.getSrcSn())
                .map(LsDataSrc::getRawSn)
                .orElse(null);
        if (deidentReportGate.isUnderDeidentReportLocked(parentRawSn)) {
            log.info("[Augment][Expiry] orphan reclaim skipped — 정책 보류(비식별 신고 구간) dataAugSn={}",
                    dataAugSn);
            return false;
        }

        AugmentApplyResult applied = augmentResultService.handle(AugmentOutcome.failed(dataAugSn, null));
        countAugOrphanExpiredAfterCommit();
        log.warn("[Augment][Expiry] orphan pending augment reclaimed (job 0건 · 재개 트리거 없음) "
                + "dataAugSn={} result={}", dataAugSn, applied);
        return true;
    }

    /** 고아 증강 회수 집계도 <b>커밋 이후</b>에만 센다(위 LOW-1 과 동일 사유). */
    private void countAugOrphanExpiredAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            metrics.augOrphanExpired();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.augOrphanExpired();
            }
        });
    }

    /**
     * 만료 회수 집계를 <b>커밋 이후</b>로 미룬다 (DEV_FIX LOW-1).
     *
     * <p>트랜잭션 동기화가 활성이면 {@code afterCommit} 에 등록한다 — 롤백되면 콜백이 호출되지 않아
     * 카운터도 오르지 않는다. 동기화 미활성(트랜잭션 밖 직접 호출 — 단위 테스트 등)이면 즉시 집계한다.
     */
    private void countJobExpiredAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            metrics.jobExpired();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.jobExpired();
            }
        });
    }

    /**
     * 롤업 멱등 앵커로 적재할 외부 job_id — 확보된 값이 있으면 그것을 쓴다.
     *
     * <p>만료 대상 job 은 202 응답을 못 받아 {@code null} 일 수 있는데, 그대로 넘기면 증강 행의
     * 기존 앵커를 null 로 덮어쓴다. 그래서 형제 job 이 가진 값을 폴백으로 쓴다.
     */
    private String externalJobIdOf(List<LsDataAugJob> jobs) {
        return jobs.stream()
                .map(LsDataAugJob::getExternalJobId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
    }
}

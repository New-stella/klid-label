package kr.co.cudo.authoring.augment.listener;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.service.AugmentCallbackUrlResolver;
import kr.co.cudo.authoring.augment.service.AugmentJobSubmitService;
import kr.co.cudo.authoring.label.event.DeidentReportResolvedEvent;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.webhook.service.AugmentOutcome;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 증강 요청 건별 AFTER_COMMIT 리스너 — 외부 위탁 트리거.
 *
 * <p>{@code AugmentRequestService.request()} 의 요청 트랜잭션이 <b>커밋된 이후에만</b> 외부 위탁을
 * 수행한다. 요청 트랜잭션이 롤백되면 본 리스너는 발화하지 않으므로 {@code LS_DATA_AUG} 행 없이
 * 외부 위탁만 나가는 일이 없다.
 *
 * <p>분리 이유(self-invocation 주의): {@code @TransactionalEventListener(AFTER_COMMIT)} 는
 * 발행 서비스와 <b>별도 빈</b>으로 두어야 정상 동작하므로 본 클래스를 별도 {@code @Component} 로 둔다.
 *
 * <h3>request_id 발급 원장은 {@code LS_DATA_AUG_JOB.IDMP_KEY} 다 (구 ledger write 제거)</h3>
 * <p>과거에는 여기서 {@code LS_WEBHOOK_IDEMPOTENCY}({@code CHANNEL_AUGMENT}) 에 멱등 키를 선기록하고
 * 그 성공을 위탁의 전제로 삼았다. 지금 수신부({@code GenAiCallbackService})의 발급 게이트는
 * {@code LS_DATA_AUG_JOB.IDMP_KEY}(위탁 직전 청크 단위 선기록)이며 그 원장은 <b>어느 경로에서도
 * 읽히지 않는다</b>. 읽히지 않는 write 의 실패가 살아있는 기능(외부 위탁)을 막는 구조였으므로
 * 해당 write 와 게이트를 함께 제거했다 — 위탁은 {@link AugmentJobSubmitService} 가 job 행에
 * 성공/실패 사유를 남기며 수행한다.
 *
 * <h3>위탁 0건 = 즉시 실패 롤업 (DEV_FIX MEDIUM-4)</h3>
 * <p>전 청크 위탁이 실패하면 <b>콜백이 영영 오지 않으므로</b> {@code GenAiCallbackService} 의 롤업이
 * 호출되지 않고 {@code LS_DATA_AUG} 가 PENDING 으로 영구 고착된다(만료 스윕 없음). 그래서 위탁 결과가
 * "수락 0건" 이면 여기서 즉시 실패 롤업한다. 단 <b>정책 보류</b>(비식별 누락 신고)는 실패가 아니므로
 * 롤업하지 않고 아래 해제 트리거로 재개한다.
 *
 * <h3>Phase 8 DEV_FIX HIGH-1 — 두 리스너는 반드시 {@code @Async} 다 (AFTER_COMMIT 트랜잭션 함정)</h3>
 * <p>{@code TransactionSynchronization.afterCommit} 은 <b>원 트랜잭션 리소스가 아직 바인딩된 채</b>
 * 호출된다. 그래서 이 안에서 {@code PROPAGATION_REQUIRED} 서비스를 부르면 <b>이미 커밋된</b>
 * 트랜잭션에 "참여" 하고 <b>뒤따르는 커밋이 없어</b> 변경이 통째로 사라진다. 게다가 인계 경로의 첫
 * 동작이 {@code findByDataAugSnForUpdate}({@code PESSIMISTIC_WRITE})라, 물리 트랜잭션이 이미 끝난
 * 상태에서 잠금 조회가 {@code TransactionRequiredException} 으로 튄다 — 그 예외를 아래 건별 catch 가
 * 삼키면 보류분이 <b>조용히 PENDING 에 영구 고착</b>된다(구 결함). Spring Javadoc 이 명시한 규약
 * ("Use PROPAGATION_REQUIRES_NEW for any transactional operation called from here") 그대로다.
 *
 * <p>그래서 리스너를 {@code @Async("batchAsyncExecutor")} 로 <b>다른 스레드</b>에 넘긴다
 * ({@code DatasetExportBridge} 가 <b>같은 이벤트</b>를 {@code AsyncDatasetExportRunner} 로 넘기는 것과
 * 동일 구조). 부수적으로 외부 위탁 HTTP 왕복이 커밋 스레드를 붙잡지 않는다.
 *
 * <h3>적대검증 2차 MEDIUM-1 — {@code @Async} 는 "다른 스레드" 를 <b>보장하지 않는다</b></h3>
 * <p>{@code batchAsyncExecutor} 의 거부 정책은 {@code CallerRunsPolicy}(역압)라, 풀이 포화되면
 * <b>호출 스레드가 리스너 본문을 직접 실행</b>한다. 그 호출 스레드가 곧 위 AFTER_COMMIT 문맥이므로
 * 스레드 배정에 기댄 방어는 <b>부하에서 그대로 무너진다</b>(원 결함 재현).
 *
 * <p>따라서 인계 진입점을 {@code AugmentResultService.handleInNewTransaction}({@code REQUIRES_NEW})로
 * 바꿔 <b>어느 스레드에서 실행되든 항상 새 물리 트랜잭션</b>이 열리게 한다 — 스레드 가정 자체를 제거한
 * 것이므로 {@code @Async} 는 성능(커밋 스레드 비점유) 목적으로만 남는다. 결과 인계
 * ({@code AugmentResultService.handle})의 전파는 <b>바꾸지 않았다</b>: 웹훅 수신
 * ({@code GenAiCallbackService})이 자신의 트랜잭션에 조인시켜 멱등 앵커·409 종결 의미론을 세우고
 * 있어(그 클래스 javadoc 참조) 전파를 바꾸면 그 계약이 깨진다.
 *
 * <p>병행 방어로 {@code AugmentResultService.recordResumeAttempt} 도 {@code REQUIRES_NEW} 다
 * ({@code AugmentJobRecorder} 동형) — 어떤 호출 문맥에서도 독립 커밋을 보장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AugmentRequestBridge {

    private final AugmentJobSubmitService jobSubmitService;
    private final AugmentMetrics metrics;
    private final AugmentResultService augmentResultService;
    private final LsDataAugRepository augRepository;
    private final LsDataAugJobRepository jobRepository;
    private final AugmentCallbackUrlResolver callbackUrlResolver;

    /**
     * 요청 트랜잭션 커밋 후 외부 위탁(100장 단위 분할) 수행.
     * 발행 측이 트랜잭션 컨텍스트 안에서 이벤트를 publish 하므로 AFTER_COMMIT 으로 수신한다.
     *
     * <p>위탁의 request_id 선기록은 {@link AugmentJobSubmitService} 가 청크마다
     * {@code LS_DATA_AUG_JOB.IDMP_KEY} 로 수행한다 — 여기서 별도 선행 원장 write 를 하지 않는다
     * (클래스 주석 "구 ledger write 제거" 참조).
     *
     * <p>{@code @Async} 인 이유는 클래스 주석 "HIGH-1" 참조 — 이 경로의 실패 롤업
     * ({@link #rollUpFailureIfNothingInFlight})도 {@code findByDataAugSnForUpdate} 로 시작하므로
     * AFTER_COMMIT 스레드에서 그대로 돌면 같은 함정에 빠진다(=위탁 0건인데 롤업이 안 돼 PENDING 고착).
     */
    @Async("batchAsyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAugmentRequested(AugmentRequestedItemEvent event) {
        submitAndRollUpIfNothingSent(event);
    }

    /**
     * DEV_FIX HIGH-2 — 비식별 누락 신고 해소 후 <b>보류됐던 위탁을 재개</b>한다.
     *
     * <p>신고 구간 차단은 "실패" 가 아니라 정책 보류이므로 {@code LS_DATA_AUG_JOB} 에 terminal 행을
     * 남기지 않는다. 그래서 보류된 증강은 job 행이 0건인 PENDING 상태로 남고, 콜백도 회수기도 이를
     * 집지 못한다 — <b>해제 시점의 이 재트리거가 유일한 복구 경로</b>다({@code DatasetExportBridge}
     * 의 export 복구와 동일 구조·동일 사유).
     *
     * <p>재개 대상 판별: ①PENDING(검수 결과 미확정) ②외부 위탁 대상 유형(WINTER/NIGHT/RAIN — 해상도
     * 파생 {@code RESL_*} 는 외부로 나가지 않으므로 제외) ③{@code LS_DATA_AUG.IDMP_KEY} 보유
     * ({@link LsDataAug#getIdempotencyKey()} — 구 webhook 원장이 아니다) ④job 행 0건(=아직 한 건도
     * 위탁되지 않음). 재개 시 같은 멱등키로 재위탁하며, 청크 키 선기록은 {@code AugmentJobSubmitService}
     * 가 {@code LS_DATA_AUG_JOB.IDMP_KEY} UNIQUE 로 직렬화한다.
     *
     * <p>AFTER_COMMIT 이라 {@code DE_IDNTF_YN 'Y'} 복원이 커밋된 뒤에 실행된다 — 커밋 전에 돌면
     * 위탁 진입부 게이트가 아직 {@code 'F'} 를 읽어 스스로 다시 보류된다.
     *
     * <h3>Phase 8-B — <b>결과 인계</b> 보류분도 같은 트리거로 재개한다 (E-ISSUE-11)</h3>
     * <p>보류 지점은 둘이다: ①<b>위탁 전</b> 보류(job 행 0건) ②<b>결과 인계 시점</b> 보류(job 은 전부
     * SUCCEEDED 인데 부모가 {@code 'F'} 라 증강본 생성을 보류). ②는 이미 위탁·수신이 끝났으므로
     * 다시 위탁하면 안 되고 <b>결과 인계만</b> 다시 태워야 한다. 재개 경로를 새로 만들지 않고 이
     * 리스너에 두 갈래를 얹는다 — 해제 시점의 재트리거가 두 보류 모두의 유일한 복구 경로다.
     */
    @Async("batchAsyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeidentReportResolved(DeidentReportResolvedEvent event) {
        Long rawSn = event.rawSn();
        List<LsDataAug> candidates;
        try {
            candidates = augRepository.findByOriginalRawSn(rawSn);
        } catch (Exception e) {
            // 후보 조회 실패 = 이 영상의 보류분 전체가 이번 해제로 깨어나지 못했다 — 반드시 관측된다.
            metrics.resumeFailed(AugmentMetrics.STAGE_LOOKUP);
            log.error("[Augment] withheld resume lookup failed (보류분 잔존 가능) rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            return;
        }
        String callbackUrl = callbackUrlResolver.resolve();
        for (LsDataAug aug : candidates) {
            // [적대검증 2차 LOW-1] 판별(job 행 조회)도 try 안에 둔다 — 밖에 두면 후보 2번째의 판별이
            // DB 예외로 터졌을 때 <나머지 후보가 통째로 스킵>되고 메트릭에도 잡히지 않아, 아래 catch 가
            // 주장하는 "건별 격리" 가 판별 단계에는 성립하지 않았다.
            try {
                if (isWithheldSubmit(aug)) {
                    resumeWithheldSubmit(aug, rawSn, callbackUrl);
                } else if (isWithheldResult(aug)) {
                    resumeWithheldResult(aug, rawSn);
                }
            } catch (Exception e) {
                // 재개 단계 실패는 각 resume* 가 자기 stage 로 이미 집계했다(내부 catch). 여기 도달은
                // <판별 단계> 실패뿐이다.
                recordResumeFailure(AugmentMetrics.STAGE_CLASSIFY, aug.getDataAugSn(), e);
            }
        }
    }

    /** ① 위탁 전 보류 재개 — 같은 멱등키로 재위탁한다(청크 키 UNIQUE 가 중복을 직렬화). */
    private void resumeWithheldSubmit(LsDataAug aug, Long rawSn, String callbackUrl) {
        log.info("[Augment] deident report resolved — resuming withheld submit originAugSn={} rawSn={}",
                aug.getDataAugSn(), rawSn);
        try {
            augmentResultService.recordResumeAttempt(aug.getDataAugSn());
            submitAndRollUpIfNothingSent(new AugmentRequestedItemEvent(
                    aug.getDataAugSn(), rawSn, aug.getAugTypeCd(), aug.getIdempotencyKey(),
                    callbackUrl, aug.getRegUserNo()));
        } catch (Exception e) {
            // 건별 격리 — 한 건 재개 실패가 다른 건을 막지 않는다. 단 <조용히> 삼키지는 않는다:
            // 재개는 이 증강의 유일한 복구 경로라, 여기서 사라지면 PENDING 고착이 관측되지 않는다.
            recordResumeFailure(AugmentMetrics.STAGE_SUBMIT, aug.getDataAugSn(), e);
        }
    }

    /**
     * ② 결과 인계 보류 재개 — 재위탁 없이 <b>성공 인계만</b> 다시 태운다.
     *
     * <p>대상이 "전 job SUCCEEDED + 증강 PENDING" 이므로 직전 롤업은 성공 판정이었고 부모 게이트에서
     * 보류된 것이다(부분 실패였다면 이미 REJECTED 로 종결됐다). 따라서 롤업 규칙을 여기서 다시
     * 계산하지 않고 성공 인계를 그대로 재시도한다. 부모가 아직 {@code 'F'} 면 인계가 다시 보류될 뿐
     * 상태를 망가뜨리지 않으며, 이미 종결된 행은 멱등 앵커(non-PENDING skip)가 흡수한다.
     */
    private void resumeWithheldResult(LsDataAug aug, Long rawSn) {
        log.info("[Augment] deident report resolved — resuming withheld result originAugSn={} rawSn={}",
                aug.getDataAugSn(), rawSn);
        try {
            augmentResultService.recordResumeAttempt(aug.getDataAugSn());
            // REQUIRES_NEW 진입점 — CallerRunsPolicy 로 커밋 스레드에서 실행돼도 독립 커밋된다(MEDIUM-1).
            augmentResultService.handleInNewTransaction(
                    AugmentOutcome.succeeded(aug.getDataAugSn(), resumeAnchor(aug)));
        } catch (Exception e) {
            recordResumeFailure(AugmentMetrics.STAGE_RESULT, aug.getDataAugSn(), e);
        }
    }

    /**
     * 재개 실패를 <b>관측 가능</b>하게 남긴다 (HIGH-1).
     *
     * <p>메트릭({@code augment.resume.failed{stage}}) + ERROR 로그. 사유는 <b>예외 클래스명</b>만
     * 남긴다 — 예외 메시지에는 내부 경로·식별정보가 실릴 수 있어 로그에 그대로 흘리지 않는다
     * (CWE-209/359, 리포 공통 규약: {@code AsyncDatasetExportRunner} 동형).
     */
    private void recordResumeFailure(String stage, Long originAugSn, Exception e) {
        metrics.resumeFailed(stage);
        log.error("[Augment] withheld resume failed (PENDING 잔존 — 다음 신고 해제까지 복구 없음) "
                        + "stage={} originAugSn={} cause={}",
                stage, originAugSn, e.getClass().getSimpleName());
    }

    /**
     * 재전송 멱등 앵커({@code OTSD_JOB_ID}) — 증강 행이 이미 확보한 값을 우선 쓰고, 없으면 job 이
     * 202 로 받아 둔 값을 쓴다. null 을 그대로 넘겨 기존 앵커를 지우지 않기 위함이다.
     */
    private String resumeAnchor(LsDataAug aug) {
        if (aug.getExternalJobId() != null && !aug.getExternalJobId().isBlank()) {
            return aug.getExternalJobId();
        }
        return jobRepository.findByDataAugSnOrderByJobSeqAsc(aug.getDataAugSn()).stream()
                .map(LsDataAugJob::getExternalJobId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** 보류된(=한 건도 위탁되지 않은) 외부 증강 요청인가. */
    private boolean isWithheldSubmit(LsDataAug aug) {
        return isResumableExternalAug(aug)
                && aug.getIdempotencyKey() != null
                && jobRepository.findByDataAugSnOrderByJobSeqAsc(aug.getDataAugSn()).isEmpty();
    }

    /**
     * 결과 인계가 보류된 외부 증강인가 — 위탁 job 이 <b>전부 SUCCEEDED</b> 인데 증강이 아직 PENDING.
     *
     * <p>한 건이라도 실패/비종결이면 대상이 아니다: 실패가 섞였다면 롤업이 이미 REJECTED 로 종결했을
     * 것이고(=PENDING 이 아니다), 비종결이 남았다면 아직 진행중이라 콜백/만료 스윕이 처리할 몫이다.
     */
    private boolean isWithheldResult(LsDataAug aug) {
        if (!isResumableExternalAug(aug)) {
            return false;
        }
        List<LsDataAugJob> jobs = jobRepository.findByDataAugSnOrderByJobSeqAsc(aug.getDataAugSn());
        return !jobs.isEmpty() && jobs.stream()
                .allMatch(job -> LsDataAugJob.STTS_SUCCEEDED.equals(job.getJobSttsCd()));
    }

    /** 재개 후보 공통 조건 — 미확정(PENDING) + 외부 위탁 대상 3종(해상도 파생 RESL_* 제외). */
    private boolean isResumableExternalAug(LsDataAug aug) {
        return LsDataAug.STTS_PENDING.equals(aug.getAugProcSttsCd())
                && AugmentPrompts.isExternalAugType(aug.getAugTypeCd());
    }

    /**
     * 위탁 실행 + "한 건도 나가지 않았다" 면 즉시 실패 롤업 (MEDIUM-4).
     *
     * <p>위탁 실패는 {@code AugmentJobSubmitService} 가 {@code LS_DATA_AUG_JOB} 에 사유와 함께 남긴다
     * (과거처럼 WARN 으로 삼키지 않는다). 여기 catch 는 예기치 못한 오류로 리스너가 죽는 것만 막는 안전망이다.
     */
    private void submitAndRollUpIfNothingSent(AugmentRequestedItemEvent event) {
        AugmentJobSubmitService.SubmitOutcome outcome;
        try {
            outcome = jobSubmitService.submit(event);
        } catch (Exception e) {
            metrics.externalRequestFailure();
            log.warn("[Augment] external submit aborted (unexpected) originAugSn={} err={}",
                    event.originAugSn(), sanitize(e.getMessage()));
            // 위탁 여부를 알 수 없는 예외이므로 in-flight job 유무로 판정한다(아래 가드).
            rollUpFailureIfNothingInFlight(event.originAugSn());
            return;
        }
        if (outcome.requiresFailureRollup()) {
            rollUpFailureIfNothingInFlight(event.originAugSn());
        }
    }

    /**
     * 실패 롤업 — 단, <b>미종결 job 이 하나라도 있으면 하지 않는다</b>.
     *
     * <p>동시 경로(원 요청 AFTER_COMMIT ↔ 신고 해제 재개)가 같은 증강을 건드리면 한쪽은 멱등키
     * UNIQUE 충돌로 "수락 0건" 이 될 수 있다. 그때 무조건 롤업하면 <b>실제로 위탁 중인 증강을 REJECTED
     * 로 못박아</b> 정상 콜백을 멱등 흡수로 버리게 된다. 종결 판정은 콜백 롤업에 맡긴다.
     */
    private void rollUpFailureIfNothingInFlight(Long originAugSn) {
        try {
            List<LsDataAugJob> jobs = jobRepository.findByDataAugSnOrderByJobSeqAsc(originAugSn);
            if (jobs.stream().anyMatch(job -> !job.isTerminal())) {
                log.info("[Augment] failure rollup skipped — in-flight job exists originAugSn={}", originAugSn);
                return;
            }
            // externalJobId 는 없다(위탁 자체가 성립하지 않음) — null 로 둔다(OTSD_JOB_ID nullable).
            // REQUIRES_NEW 진입점 — 이 롤업도 커밋 스레드에서 실행될 수 있다(MEDIUM-1).
            augmentResultService.handleInNewTransaction(AugmentOutcome.failed(originAugSn, null));
            log.warn("[Augment] no job accepted — rolled up to REJECTED originAugSn={}", originAugSn);
        } catch (Exception e) {
            log.error("[Augment] failure rollup failed originAugSn={} err={}",
                    originAugSn, sanitize(e.getMessage()));
        }
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. */
    private static String sanitize(String value) {
        if (value == null) return null;
        return value.replace('\n', '_').replace('\r', '_');
    }
}

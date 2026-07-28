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
     */
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
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeidentReportResolved(DeidentReportResolvedEvent event) {
        Long rawSn = event.rawSn();
        List<LsDataAug> candidates;
        try {
            candidates = augRepository.findByOriginalRawSn(rawSn);
        } catch (Exception e) {
            log.warn("[Augment] withheld submit resume lookup failed rawSn={} err={}",
                    rawSn, sanitize(e.getMessage()));
            return;
        }
        String callbackUrl = callbackUrlResolver.resolve();
        for (LsDataAug aug : candidates) {
            if (!isWithheldSubmit(aug)) {
                continue;
            }
            log.info("[Augment] deident report resolved — resuming withheld submit originAugSn={} rawSn={}",
                    aug.getDataAugSn(), rawSn);
            try {
                submitAndRollUpIfNothingSent(new AugmentRequestedItemEvent(
                        aug.getDataAugSn(), rawSn, aug.getAugTypeCd(), aug.getIdempotencyKey(),
                        callbackUrl, aug.getRegUserNo()));
            } catch (Exception e) {
                // 건별 격리 — 한 건 재개 실패가 다른 건을 막지 않는다.
                log.warn("[Augment] withheld submit resume failed originAugSn={} err={}",
                        aug.getDataAugSn(), sanitize(e.getMessage()));
            }
        }
    }

    /** 보류된(=한 건도 위탁되지 않은) 외부 증강 요청인가. */
    private boolean isWithheldSubmit(LsDataAug aug) {
        return LsDataAug.STTS_PENDING.equals(aug.getAugProcSttsCd())
                && AugmentPrompts.isExternalAugType(aug.getAugTypeCd())
                && aug.getIdempotencyKey() != null
                && jobRepository.findByDataAugSnOrderByJobSeqAsc(aug.getDataAugSn()).isEmpty();
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
            augmentResultService.handle(AugmentOutcome.failed(originAugSn, null));
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

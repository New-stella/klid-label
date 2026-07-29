package kr.co.cudo.authoring.augment.listener;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.service.AugmentJobSubmitService;
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
 * "수락 0건" 이면 여기서 즉시 실패 롤업한다. 비식별 누락 신고 구간 차단도 <b>거부(실패)</b> 로
 * 종결되므로(2026-07-29 정책 — 파생 생성은 원본 신고와 무관해져 재개 배선이 철회됐다) 이 롤업이
 * 동일하게 적용된다.
 *
 * <h3>Phase 8 DEV_FIX HIGH-1 — 이 리스너는 반드시 {@code @Async} 다 (AFTER_COMMIT 트랜잭션 함정)</h3>
 * <p>{@code TransactionSynchronization.afterCommit} 은 <b>원 트랜잭션 리소스가 아직 바인딩된 채</b>
 * 호출된다. 그래서 이 안에서 {@code PROPAGATION_REQUIRED} 서비스를 부르면 <b>이미 커밋된</b>
 * 트랜잭션에 "참여" 하고 <b>뒤따르는 커밋이 없어</b> 변경이 통째로 사라진다. 게다가 인계 경로의 첫
 * 동작이 {@code findByDataAugSnForUpdate}({@code PESSIMISTIC_WRITE})라, 물리 트랜잭션이 이미 끝난
 * 상태에서 잠금 조회가 {@code TransactionRequiredException} 으로 튄다 — 그 예외를 아래 건별 catch 가
 * 삼키면 실패 롤업이 유실돼 <b>조용히 PENDING 에 영구 고착</b>된다(구 결함). Spring Javadoc 이 명시한 규약
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AugmentRequestBridge {

    private final AugmentJobSubmitService jobSubmitService;
    private final AugmentMetrics metrics;
    private final AugmentResultService augmentResultService;
    private final LsDataAugJobRepository jobRepository;

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

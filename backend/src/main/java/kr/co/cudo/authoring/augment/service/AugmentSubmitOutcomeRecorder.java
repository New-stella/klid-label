package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 증강 외부 위탁(제출)의 <b>비동기 완료 핸들러</b> — 202 ACK / 제출 실패 / 시퀀스 종료를 기록한다 (C-3).
 *
 * <h3>실행 컨텍스트</h3>
 * <p>본 빈의 메서드는 {@code augmentSubmitScheduler}(전용 풀) 스레드에서 호출된다 — 위탁을 개시한
 * {@code batch-async-} 스레드도, reactor-netty 이벤트 루프도 아니다. 따라서 <b>ambient 트랜잭션이
 * 없다</b>. 모든 DB 쓰기는 {@code @Transactional(REQUIRES_NEW)} 를 선언한 별도 빈
 * ({@link AugmentJobRecorder}·{@link AugmentSubmitRollupTxService})을 <b>프록시 경유</b>로 호출한다.
 * 이 클래스 자체에는 {@code @Transactional} 을 두지 않는다 — 두면 여기 안의 호출이 자기호출로 보이는
 * 착시가 생기고(이 레포의 실사고 패턴: 경계 유실로 배치 전면 불통), 중첩 tx 로 커넥션 점유만 늘어난다.
 *
 * <h3>★ 상태 강등 금지 (회귀 위험)</h3>
 * <p>제출 신호는 <b>벤더 콜백이 이미 도착한 뒤</b>에 기록될 수 있다(논블로킹이라 순서 보장이 없다).
 * 그래서 기록은 전부 조건부 원자 UPDATE({@code RECEIVED} + {@code OTSD_JOB_ID IS NULL})로만 이뤄지며,
 * 콜백이 선점한 job 은 어떤 지각 신호로도 강등되지 않는다. 클레임 실패(0행)는 <b>정상</b>이므로
 * 경고가 아니라 정보로 남긴다.
 *
 * <h3>기록 실패는 삼킨다</h3>
 * <p>여기서 예외를 던져도 받을 곳이 없다(비동기). 기록이 실패하면 job 은 선기록 상태(RECEIVED)로 남고
 * 기존 만료 스윕({@code AugmentJobExpirySweeper})이 회수한다 — <b>기록 유실은 위탁 유실이 아니다</b>.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AugmentSubmitOutcomeRecorder {

    private final AugmentJobRecorder jobRecorder;
    private final AugmentSubmitRollupTxService rollupTxService;
    private final AugmentMetrics metrics;

    /**
     * 202 수락(ACK) 수신 — 외부가 발급한 job_id 를 적재해 콜백 오배송 검증의 기준을 세운다.
     *
     * <p>{@code externalJobId} 가 비어 있는 구현체(noop)는 적재할 값이 없으므로 job 이 선기록 상태로
     * 남는다 — 콜백이 오지 않는 환경이므로 만료 스윕이 회수한다(기존 동작 유지).
     */
    public void onAccepted(Long dataAugSn, Long augJobSn, String externalJobId,
                           int jobSeq, int jobCount) {
        metrics.externalRequestSuccess();
        try {
            if (!jobRecorder.markSubmitAccepted(augJobSn, externalJobId)) {
                log.info("[Augment] late submit ack ignored (콜백 선점) dataAugSn={} jobSeq={}/{}",
                        dataAugSn, jobSeq, jobCount);
            }
        } catch (RuntimeException e) {
            // CWE-209: 외부 원문/스택트레이스 미노출(예외 클래스명만). 회수는 만료 스윕이 담당.
            log.warn("[Augment] submit ack record failed dataAugSn={} jobSeq={}/{} cause={}",
                    dataAugSn, jobSeq, jobCount, e.getClass().getSimpleName());
        }
    }

    /**
     * 제출 <b>확정 실패</b>(4xx·타임아웃·서킷 오픈·빈 응답 등 onError 수신, 또는 구독 자체 거부).
     *
     * <p>기존 동기 계약("청크 위탁 실패 = 그 job 을 {@code SUBMIT_FAILED} 로 종결하고 다음 청크는 계속")과
     * <b>동등한 종단 상태</b>를 비동기에서 재현한다. 건별 격리는 청크 체인이
     * ({@code onErrorResume}) 담당하므로 여기서는 기록만 한다.
     */
    public void onSubmitFailed(Long dataAugSn, Long augJobSn, int jobSeq, int jobCount,
                               Throwable cause) {
        metrics.externalRequestFailure();
        String errType = (cause == null) ? "unknown" : cause.getClass().getSimpleName();
        try {
            if (!jobRecorder.markSubmitFailed(augJobSn, LsDataAugJob.ERR_SUBMIT_FAILED,
                    sanitize(cause == null ? errType : cause.getMessage()))) {
                log.info("[Augment] late submit failure ignored (콜백 선점) dataAugSn={} jobSeq={}/{}",
                        dataAugSn, jobSeq, jobCount);
                return;
            }
        } catch (RuntimeException e) {
            log.warn("[Augment] submit failure record failed dataAugSn={} jobSeq={}/{} cause={}",
                    dataAugSn, jobSeq, jobCount, e.getClass().getSimpleName());
        }
        log.warn("[Augment] 위탁 실패(격리, async) dataAugSn={} jobSeq={}/{} errType={}",
                dataAugSn, jobSeq, jobCount, errType);
    }

    /**
     * 제출 시퀀스 종료 — <b>종결 판정 1회</b>.
     *
     * <p>구 동기 구현의 "{@code accepted==0} 이면 즉시 실패 롤업" 을 대체하는 지점이다(클래스
     * {@link AugmentSubmitRollupTxService} 주석 참조). 비종결 job 이 하나라도 남아 있으면 보류하고
     * 콜백이 확정하게 둔다.
     *
     * <p>정상 완료·중단·오류 어느 경로로 끝나도 반드시 호출된다({@code doFinally}).
     */
    public void onSubmitSequenceFinished(Long dataAugSn) {
        try {
            rollupTxService.rollUpIfAllTerminal(dataAugSn);
        } catch (RuntimeException e) {
            // 롤업 실패는 삼킨다 — 비종결 job 이 남아 있으면 만료 스윕이, 전부 terminal 이면 다음
            // 콜백/스윕이 다시 판정한다. 여기서 던지면 받을 곳이 없다(비동기).
            log.error("[Augment] submit rollup failed dataAugSn={} cause={}",
                    dataAugSn, e.getClass().getSimpleName());
        }
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. 절대경로는 애초에 싣지 않는다. */
    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        return value.replace('\n', '_').replace('\r', '_');
    }
}

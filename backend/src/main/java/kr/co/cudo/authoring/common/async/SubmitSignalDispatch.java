package kr.co.cudo.authoring.common.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Scheduler;

import java.util.concurrent.RejectedExecutionException;

/**
 * 논블로킹 외부 위탁의 <b>완료 신호 기록을 전용 풀에서만 실행</b>시키는 디스패처 (M2).
 *
 * <h3>막는 실패 모드 — 이벤트 루프에서 도는 JPA</h3>
 * <p>전용 풀은 {@code AbortPolicy}(포화 시 거부)다. 완료 신호를 {@code publishOn(전용풀)} 로 옮기는
 * 구조에서는 <b>거부가 곧 onError</b> 이고, 그 onError 는 시그널을 나른 스레드
 * (= reactor-netty <b>이벤트 루프</b>)에서 downstream 으로 흐른다. 그 downstream 이 실패 기록(JPA 쓰기)
 * 이면 이벤트 루프가 커넥션 대기에 묶여 같은 루프를 쓰는 <b>모든</b> 외부 호출(VLM·KPST·증강·ai-server)이
 * 동반 지연된다 — 논블로킹으로 얻으려던 것을 정확히 되돌린다. 동기 {@code subscribe()} 구간을 감싼
 * {@code try/catch} 는 이 거부를 <b>절대 잡지 못한다</b>(거부는 응답이 도착한 뒤에 발생한다).
 *
 * <h3>규칙</h3>
 * <ol>
 *   <li>기록 작업은 반드시 {@link #run} 을 통해 <b>전용 풀 안에서</b> 실행한다. 호출 스레드는
 *       {@code executor.execute} 만 수행하므로 어떤 스레드에서 호출돼도 블로킹이 없다.</li>
 *   <li>풀이 거부하면 <b>기록을 포기</b>하고 WARN 만 남긴다. 이벤트 루프에서 JPA 를 돌리는 것보다
 *       기록 1건을 잃는 편이 안전하다 — 잃은 것은 "기록"이지 "위탁"이 아니며, 선커밋된 원장 행은
 *       각 경로의 회수기(VLM 미결 스위퍼 · KPST 폴러의 ACK 유예 회수 · 증강 만료 스윕)가 반드시 집는다.</li>
 * </ol>
 */
public final class SubmitSignalDispatch {

    private static final Logger log = LoggerFactory.getLogger(SubmitSignalDispatch.class);

    private SubmitSignalDispatch() {
    }

    /**
     * 완료 기록 작업을 전용 스케줄러에서 실행한다.
     *
     * @param scheduler 완료 신호 전용 풀 스케줄러
     * @param tag       로그 태그(도메인 식별용 — 사용자 입력 미반영)
     * @param id        추적 식별자(rawSn/augSn 등, PII 아님)
     * @param task      기록 작업(JPA 쓰기 포함 가능)
     * @return 풀에 투입됐으면 true, 거부돼 포기했으면 false
     */
    public static boolean run(Scheduler scheduler, String tag, Object id, Runnable task) {
        if (scheduler == null || task == null) {
            return false;
        }
        try {
            scheduler.schedule(task);
            return true;
        } catch (RejectedExecutionException e) {
            // 전용 풀 포화(AbortPolicy) — 여기서 직접 실행하면 이벤트 루프에서 JPA 가 돈다. 회수기에 맡긴다.
            log.warn("[{}] submit signal record dropped — dedicated pool saturated id={} (회수는 스위퍼/폴러 담당)",
                    tag, id);
            return false;
        }
    }

    /**
     * 이 오류가 <b>전용 풀 거부</b>인가 — 즉 "외부 호출 실패"가 아니라 "우리 기록 경로 포화"인가.
     *
     * <p>Reactor 는 {@code publishOn} 스케줄 거부를 {@code Exceptions.failWithRejected} 로 감싸
     * {@link RejectedExecutionException} 하위 타입으로 전달한다. 이 판정이 참이면 그 신호는
     * <b>이벤트 루프</b>에서 흐르는 중이므로 JPA 를 호출해서는 안 된다.
     */
    public static boolean isPoolRejection(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof RejectedExecutionException) {
                return true;
            }
        }
        return false;
    }
}

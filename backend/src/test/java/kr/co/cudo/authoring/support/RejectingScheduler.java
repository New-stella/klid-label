package kr.co.cudo.authoring.support;

import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

import java.util.concurrent.RejectedExecutionException;

/**
 * 완료 신호 <b>전용 풀이 포화(AbortPolicy)</b>된 상태를 재현하는 테스트 스케줄러 (M2).
 *
 * <p>운영 전용 풀은 큐가 가득 차면 {@link RejectedExecutionException} 을 던진다. 그 거부가
 * {@code publishOn} 을 통해 흐르면 onError 가 <b>시그널을 나른 스레드(reactor-netty 이벤트 루프)</b>
 * 에서 downstream 으로 전달되어, 실패 핸들러의 JPA 쓰기가 이벤트 루프를 블로킹한다. 이 스케줄러는
 * "무조건 거부"만 하므로, 각 위탁 경로가 거부를 만났을 때 <b>기록을 포기</b>하는지(=호출 스레드에서
 * JPA 를 돌리지 않는지)를 결정적으로 관측할 수 있다 — 실시간 대기가 필요 없다.
 *
 * <p>{@code AugmentSubmitSerializationGuardTest} 가 쓰던 private 구현을 VLM·KPST 가 동일 형태로
 * 재사용하기 위해 지원 클래스로 승격한 것이다(테스트 전용 — 운영 코드 아님).
 */
public final class RejectingScheduler implements Scheduler {

    @Override
    public Disposable schedule(Runnable task) {
        throw new RejectedExecutionException("pool saturated (test)");
    }

    @Override
    public Worker createWorker() {
        return new Worker() {
            @Override
            public Disposable schedule(Runnable task) {
                throw new RejectedExecutionException("pool saturated (test)");
            }

            @Override
            public void dispose() {
            }
        };
    }
}

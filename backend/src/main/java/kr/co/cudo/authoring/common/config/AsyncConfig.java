package kr.co.cudo.authoring.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * {@code @Async} 전용 스레드풀 구성 (DoS 방어 — CWE-400 Unrestricted Resource Consumption).
 *
 * <p>문제: {@code @EnableAsync} 만 두면 기본 executor 가 {@code SimpleAsyncTaskExecutor} 로,
 * 매 작업마다 새 스레드를 무제한 생성한다. 대량 영상 적재 시 비식별(70s 블로킹) 러너가
 * 동시 폭주하면 스레드 폭증으로 서버가 고갈된다.
 *
 * <p>해결: 경계가 있는 {@link ThreadPoolTaskExecutor}({@code batchAsyncExecutor}) 를 등록하고,
 * 큐가 가득 차면 {@link ThreadPoolExecutor.CallerRunsPolicy} 로 호출 스레드가 직접 실행(역압)해
 * 무제한 적재를 차단한다. 비식별은 외부 API 블로킹이라 풀 크기를 작게 유지한다.
 *
 * <p>각 러너는 {@code @Async("batchAsyncExecutor")} 로 이 풀을 명시 지정한다.
 * {@code @EnableAsync} 는 본 클래스로 일원화한다(AuthoringApplication 에서 제거).
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 배치/비식별 비동기 작업 전용 스레드풀 빈. */
    @Bean(name = "batchAsyncExecutor")
    public Executor batchAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 외부 비식별 API 가 70s 블로킹이므로 풀을 작게 유지 — 동시 비식별 4건 상한.
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // 큐 50 초과 시 CallerRunsPolicy 로 역압(호출 스레드가 직접 실행) → 무제한 스레드 생성 차단.
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("batch-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 종료 시 진행 중 작업 대기 (graceful shutdown)
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * <b>VLM 논블로킹 제출 완료 핸들러 전용</b> 스레드풀 (Phase C-1).
     *
     * <h3>왜 {@code batchAsyncExecutor} 를 재사용하지 않는가 (숨은 HIGH)</h3>
     * <p>{@code batchAsyncExecutor} 는 core2/max4/queue50 + {@link ThreadPoolExecutor.CallerRunsPolicy} 다.
     * 논블로킹 제출의 완료 핸들러를 그 풀에 얹으면, 큐가 포화된 순간 CallerRuns 가 <b>호출 스레드</b>
     * (= reactor-netty 이벤트 루프)에서 핸들러를 실행한다. 핸들러는 JPA 쓰기(커넥션 대기)를 하므로
     * 이벤트 루프가 블로킹되고, 같은 루프를 쓰는 <b>모든</b> 외부 호출(VLM·KPST·증강·ai-server)이 동반
     * 지연된다. 논블로킹으로 얻으려던 것을 정확히 되돌리는 역효과라 풀을 분리하고 정책도 바꾼다.
     *
     * <h3>포화 정책 = Abort (CallerRuns 금지)</h3>
     * <p>포화 시 {@link ThreadPoolExecutor.AbortPolicy} 로 거부한다. 거부는 "기록 유실"이지 "위탁 유실"이
     * 아니다 — 위탁 상관키는 제출 <b>전에</b> {@code LS_WEBHOOK_IDEMPOTENCY} 에 ISSUED 로 선커밋돼 있고,
     * 콜백은 그 원장으로 역조회되며, 끝내 아무 신호가 없으면 미결 스위퍼가 회수한다. 즉 이벤트 루프를
     * 붙잡는 대가로 기록을 지키는 거래는 성립하지 않는다.
     *
     * <h3>★ 거부는 <b>호출부에서</b> 처리해야 한다 (M2)</h3>
     * <p>{@code AbortPolicy} 는 그 자체로 이벤트 루프를 지켜주지 않는다. 완료 신호를
     * {@code publishOn(이 스케줄러)} 로 옮기는 형태에서는 <b>거부 자체가 onError 신호</b>가 되어
     * <b>이벤트 루프에서</b> downstream 으로 흐르고, 그 downstream 이 실패 기록(JPA)이면 결국 루프가
     * 블로킹된다. 그래서 호출부는 기록 호출을 {@code SubmitSignalDispatch} 로 감싸(전용 풀 안에서만 실행)
     * 거부 시 기록을 포기하거나, 거부를 식별해 JPA 를 건너뛴다.
     *
     * <p>작업 1건은 짧은 DB 쓰기 2건 이하이고 제출 자체가 분당 수 건 규모라 풀을 작게 유지한다.
     */
    @Bean(name = "vlmSubmitExecutor")
    public ThreadPoolTaskExecutor vlmSubmitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("vlm-submit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * {@link #vlmSubmitExecutor()} 를 Reactor {@link Scheduler} 로 노출 — 완료 기록 실행 고정용.
     *
     * <p>호출부({@code VlmTimeseriesStep})는 완료 핸들러를 {@code SubmitSignalDispatch} 로 이 스케줄러에
     * 투입해, 핸들러의 JPA 쓰기가 reactor-netty 이벤트 루프에서 실행되지 않게 한다. 풀이 포화되면
     * 기록을 <b>포기</b>하고(루프에서 실행하지 않는다) 미결 스위퍼가 회수한다 — {@code publishOn} 만으로는
     * 거부 신호가 루프에서 흐르기 때문에 이 보장이 성립하지 않는다(M2).
     *
     * <p>풀 종료는 {@code ThreadPoolTaskExecutor} 빈이 {@code DisposableBean} 으로 처리하므로
     * 스케줄러는 얇은 래퍼로 둔다(이중 종료 금지).
     */
    @Bean(name = "vlmSubmitScheduler")
    public Scheduler vlmSubmitScheduler(
            @Qualifier("vlmSubmitExecutor") ThreadPoolTaskExecutor vlmSubmitExecutor) {
        return Schedulers.fromExecutor(vlmSubmitExecutor);
    }

    /**
     * <b>KPST 비식별 위탁(제출) 완료 핸들러 전용</b> 스레드풀 (Phase C-2).
     *
     * <p>{@link #vlmSubmitExecutor()} 와 동일한 이유로 {@code batchAsyncExecutor} 를 재사용하지 않는다 —
     * 그 풀은 {@link ThreadPoolExecutor.CallerRunsPolicy} 라 포화 시 <b>호출 스레드</b>(= reactor-netty
     * 이벤트 루프)에서 JPA 쓰기를 실행해, 논블로킹으로 얻으려던 것을 정확히 되돌린다.
     *
     * <p>VLM 풀과도 <b>분리</b>한다: 두 외부 시스템은 장애가 독립적인데 풀을 공유하면 한쪽 벤더의 지연이
     * 다른 쪽의 ACK 기록을 굶긴다(기록 유실 → 불필요한 미결 회수).
     *
     * <p>포화 정책은 {@link ThreadPoolExecutor.AbortPolicy} — 거부는 "기록 유실"이지 "위탁 유실"이 아니다.
     * 위탁 원장({@code LS_DEIDENT_PROC_LOG})은 제출 <b>전에</b> WAITING 으로 선커밋돼 있고, ACK 가 끝내
     * 기록되지 않으면 폴링 잡이 ACK 대기 창 만료로 회수한다({@code KpstDeidentService} 의 ACK 유예).
     */
    @Bean(name = "kpstSubmitExecutor")
    public ThreadPoolTaskExecutor kpstSubmitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("kpst-submit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * {@link #kpstSubmitExecutor()} 를 Reactor {@link Scheduler} 로 노출 — 완료 기록 실행 고정용.
     *
     * <p>호출부({@code KpstDeidentService.subscribeSubmit})는 완료 핸들러를
     * {@code SubmitSignalDispatch} 로 이 스케줄러에 투입한다(포화 시 기록 포기 → 폴러의 ACK 유예
     * 회수가 받는다). {@code publishOn} 만으로는 거부 신호가 이벤트 루프에서 흘러 JPA 가 루프에서
     * 돌기 때문에 그 형태를 쓰지 않는다(M2). 풀 종료는 {@code ThreadPoolTaskExecutor} 빈이 처리하므로
     * 스케줄러는 얇은 래퍼로 둔다(이중 종료 금지).
     */
    @Bean(name = "kpstSubmitScheduler")
    public Scheduler kpstSubmitScheduler(
            @Qualifier("kpstSubmitExecutor") ThreadPoolTaskExecutor kpstSubmitExecutor) {
        return Schedulers.fromExecutor(kpstSubmitExecutor);
    }

    /**
     * <b>증강(생성형 AI) 위탁 제출 완료 핸들러 전용</b> 스레드풀 (Phase C-3).
     *
     * <p>{@link #vlmSubmitExecutor()}·{@link #kpstSubmitExecutor()} 와 동일한 이유로
     * {@code batchAsyncExecutor} 를 재사용하지 않는다 — 그 풀은 {@link ThreadPoolExecutor.CallerRunsPolicy}
     * 라 포화 시 <b>호출 스레드</b>(= reactor-netty 이벤트 루프)에서 JPA 쓰기를 실행해, 논블로킹으로
     * 얻으려던 것을 정확히 되돌린다.
     *
     * <p>VLM·KPST 풀과도 <b>분리</b>한다: 세 외부 시스템은 장애가 독립적인데 풀을 공유하면 한쪽 벤더의
     * 지연이 다른 쪽의 ACK 기록을 굶긴다.
     *
     * <p><b>증강 고유 — 이 풀은 "완료 기록" 뿐 아니라 <u>청크 직렬 전송</u>의 실행 스레드다</b>.
     * 증강은 영상 1건이 100장 단위 청크 N 개로 쪼개져 <b>순차</b> 위탁되고, 청크 사이마다 비식별 누락
     * 신고를 <b>재판정</b>(DB 조회)한다. 그 재판정과 다음 청크 제출이 여기서 실행돼야 이벤트 루프가
     * 블로킹되지 않는다. 그래도 풀을 작게 유지하는 이유는 작업 1건이 짧은 DB 조회/쓰기 몇 건뿐이고
     * 외부 왕복은 논블로킹이라 스레드를 점유하지 않기 때문이다.
     *
     * <p>포화 정책은 {@link ThreadPoolExecutor.AbortPolicy} — 거부는 "기록 유실"이지 "위탁 유실"이 아니다.
     * 청크 job 행({@code LS_DATA_AUG_JOB})은 제출 <b>전에</b> RECEIVED 로 전량 선커밋돼 있고, 끝내 아무
     * 신호도 기록되지 않으면 기존 만료 스윕({@code AugmentJobExpirySweeper})이 회수한다.
     */
    @Bean(name = "augmentSubmitExecutor")
    public ThreadPoolTaskExecutor augmentSubmitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("augment-submit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * {@link #augmentSubmitExecutor()} 를 Reactor {@link Scheduler} 로 노출 — {@code publishOn} 고정용.
     *
     * <p>제출 Mono 의 완료 신호(onNext/onError)를 이 스케줄러로 옮겨, 완료 기록의 JPA 쓰기와
     * <b>다음 청크의 신고 재판정</b>이 reactor-netty 이벤트 루프에서 실행되지 않게 한다. 증강만
     * {@code publishOn} 을 유지하는 이유는 <b>다음 청크의 구독</b>까지 이 풀로 옮겨야 하기 때문이다.
     * 단, 풀이 포화돼 {@code publishOn} 이 거부되면 그 신호는 이벤트 루프에서 흐르므로 호출부가
     * 거부를 식별해 <b>기록·다음 청크를 모두 중단</b>한다(회수는 만료 스윕 — M2).
     * 풀 종료는 {@code ThreadPoolTaskExecutor} 빈이 처리하므로 스케줄러는 얇은 래퍼로 둔다.
     */
    @Bean(name = "augmentSubmitScheduler")
    public Scheduler augmentSubmitScheduler(
            @Qualifier("augmentSubmitExecutor") ThreadPoolTaskExecutor augmentSubmitExecutor) {
        return Schedulers.fromExecutor(augmentSubmitExecutor);
    }

    /**
     * 포털 프레임 추출 전용 스레드풀 빈 (security M-3 — 관제 배치 풀과 자원 격리).
     *
     * <p>포털 사용자 업로드 영상의 ffmpeg 프레임 추출({@code PortalFrameExtractRunner})만 사용한다.
     * 관제 배치({@code batchAsyncExecutor})와 풀을 공유하면 포털 트래픽이 관제 비식별/배치 스레드를
     * 잠식할 수 있으므로 별도 풀로 분리한다. 큐가 가득 차면 {@link ThreadPoolExecutor.CallerRunsPolicy}
     * 로 역압한다.
     */
    @Bean(name = "portalExtractExecutor")
    public Executor portalExtractExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("portal-extract-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}

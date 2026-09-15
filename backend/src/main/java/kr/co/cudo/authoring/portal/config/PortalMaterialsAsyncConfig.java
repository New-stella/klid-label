package kr.co.cudo.authoring.portal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 포털 <b>소재 조달</b> 전용 스레드풀 — 관제 배치·포털 프레임 추출과 자원을 격리한다.
 *
 * <h3>왜 전용 풀인가</h3>
 * <p>조달 한 건은 <b>기가바이트급 복사와 압축 해제</b>다. 다른 일과 풀을 공유하면 조달 몇 건이
 * 그 일의 스레드를 통째로 잠식한다.
 *
 * <h3>★ 큐가 차면 <b>거부</b>한다 — 호출 스레드에 떠넘기지 않는다</h3>
 * <p>형제 풀들은 큐가 차면 호출 스레드가 대신 실행하는 역압을 쓴다. 여기서는 그 정책을 쓰지
 * <b>않는다</b> — 착수 창구는 「접수했다」만 답하고 즉시 돌아가는 자리인데, 호출 스레드가 대신
 * 실행하면 <b>그 HTTP 요청이 기가바이트 복사가 끝날 때까지 매달린다</b>. 거부되면 착수 창구가
 * 선점을 놓고 「지금은 받을 수 없다」로 답한다(fail-closed).
 *
 * @design INT-014
 */
@Configuration
public class PortalMaterialsAsyncConfig {

    @Bean(name = "portalMaterialsExecutor")
    public Executor portalMaterialsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("portal-materials-");
        // 기본 거부 정책(AbortPolicy)을 그대로 둔다 — 위 javadoc 의 이유로 CallerRuns 를 쓰지 않는다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}

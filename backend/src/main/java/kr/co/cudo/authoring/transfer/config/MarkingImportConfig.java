package kr.co.cudo.authoring.transfer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 마킹 산출물 일괄 가져오기 설정 등록 + <b>전용 스레드풀</b>.
 *
 * <p>설정 바인딩은 도메인 국소 {@code @EnableConfigurationProperties} 로 등록한다 — 이 저장소의
 * 확립된 관례이며(포털 업로드·증강 폐기·시작 버전) 전역 스캔을 켜지 않는다.
 *
 * <h3>왜 배치 공용 풀을 쓰지 않는가</h3>
 * <p>공용 풀({@code batchAsyncExecutor})은 큐가 차면 <b>호출 스레드가 직접 실행</b>하는 역압 정책이다.
 * 이 경로의 호출 스레드는 <b>요청 스레드</b>라, 그 정책이 발동하면 곧바로 반환하기로 한 요청이
 * 백 건의 파일 복사를 끌어안고 멈춘다 — 이 창구가 곧바로 반환하는 이유를 그대로 무너뜨린다.
 * 또 그 풀은 선두 비식별이 쓰는 자리라, 백 건짜리 묶음 하나가 <b>다른 영상의 비식별을 굶긴다</b>.
 * 그래서 자원을 나눈다(수동 재기동 풀이 같은 이유로 갈라져 있다).
 *
 * <h3>큐를 <b>얕게</b> 잡는 이유</h3>
 * <p>이 풀에 들어가는 것은 항목 하나가 아니라 <b>일꾼</b>이다. 일꾼은 자기가 처리할 항목을 원장에서
 * 직접 집어 가므로, 큐에 눕는 일꾼은 아무 일도 하지 않으면서 자리만 차지한다. 항목의 대기열은
 * 메모리가 아니라 <b>원장 행</b>이고 그것이 이 설계의 요점이다 — 노드가 다시 떠도 대기열이 사라지지
 * 않는다.
 *
 * <h3>거부는 조용히 버리지 않는다</h3>
 * <p>{@link ThreadPoolExecutor.AbortPolicy} 로 거부하고 호출부가 그것을 알아채게 한다. 조용히 버리면
 * 작업은 등록됐는데 일꾼이 하나도 없어 <b>영원히 진행되지 않는 작업</b>이 남고, 사람은 접수됐다고
 * 믿는다. 다만 이 경로에서는 그것이 곧 실패는 아니다 — 되돌리기 잡이 다음 순번에 그 항목을 다시
 * 집으므로, 거부는 <b>지연</b>이지 유실이 아니다.
 *
 * @design DOMAIN-017
 * @design API-217
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MarkingImportProperties.class)
public class MarkingImportConfig {

    /** 일꾼 풀의 빈 이름 — 다른 풀과 섞이지 않게 명시 지정한다. */
    public static final String EXECUTOR = "markingImportExecutor";

    /**
     * 마킹 일괄 적재 <b>일꾼</b> 전용 스레드풀.
     *
     * <p>풀 크기는 설정의 동시 처리 수 상한과 <b>같은 값</b>이다. 두 값이 갈리면 「동시에 몇 건을
     * 처리하는가」의 답이 둘이 되고, 실제 동시성은 둘 중 작은 쪽이 되어 설정이 거짓이 된다.
     */
    @Bean(name = EXECUTOR)
    public Executor markingImportExecutor(MarkingImportProperties properties) {
        int size = properties.effectiveConcurrency();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        // 일꾼은 원장에서 항목을 직접 집어 가므로 큐에 눕혀 둘 이유가 없다. 크기를 1로 두어
        // 재기동 시 사라지는 대기분을 최소화한다(원장 행은 그대로 남아 다시 집힌다).
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("marking-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("[MarkingImport] worker pool initialized concurrency={}", size);
        return executor;
    }
}

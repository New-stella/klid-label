package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.controlnotify.debounce.ControlNotifyDebounceStore;
import kr.co.cudo.authoring.controlnotify.debounce.FakeControlNotifyDebounceStore;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Phase 5C — 적대검증 지적 사각 해소: {@code @PostConstruct startFlushScheduler} 가
 * <b>스프링 컨테이너에 의해 실제로 호출되는지</b> 검증한다.
 *
 * <h3>이 테스트가 닫는 사각</h3>
 * 기존 {@link ControlNotifyDebounceFlushSchedulerTest}는 {@code debouncer.startFlushScheduler()} 를
 * <b>수동으로 직접 호출</b>해 스프링 컨텍스트를 완전히 우회한다. 모든 {@code @SpringBootTest} 는
 * {@code src/test/resources/application-local.yml} 의 {@code regen-flush.enabled=false} 로 인해
 * {@code @PostConstruct} 가 즉시 return 해 배선 자체를 검증하지 못한다. 결과적으로
 * {@code @PostConstruct} 애노테이션을 지워도(=스프링이 더 이상 이 메서드를 자동 호출하지 않아도)
 * 전 테스트가 GREEN 이었다.
 *
 * <p>이 테스트는 {@link ApplicationContextRunner}(경량 {@code AnnotationConfigApplicationContext})로
 * {@link ControlNotifyDebouncer} 빈을 <b>실제로 등록</b>하고, 빈 생성 후 스프링이 {@code @PostConstruct}
 * 콜백을 자동 실행했는지를 (1) 필드 상태와 (2) 실제 tick 두 축으로 결정론적으로 단언한다. 어떤 테스트도
 * {@code startFlushScheduler()} 를 직접 호출하지 않는다.
 *
 * <p><b>{@code @PostConstruct} 를 제거하면 두 테스트 모두 실패한다</b> — 생성자는 {@code flushScheduler}
 * 필드를 세팅하지 않으므로 필드는 null 로 남고, tick 이 발생하지 않아 {@code runReExportThenNotify} 도
 * 호출되지 않는다(실측 확인 — 작업 보고 참조).
 *
 * <p>다른 {@code @SpringBootTest} 들의 {@code regen-flush.enabled=false} 격리는 건드리지 않는다 — 이 클래스는
 * 그 전역 컨텍스트를 전혀 로드하지 않고 {@code ApplicationContextRunner} 로 이 빈 하나만 담은 별도의
 * 최소 컨텍스트를 매 테스트마다 새로 만들어 쓰고 버린다(전역 설정 파일 미참조). 스케줄러 스레드는
 * {@code ApplicationContextRunner} 가 {@code run()} 콜백 종료 시 컨텍스트를 닫아 {@code @PreDestroy}
 * ({@link ControlNotifyDebouncer#flushAll()})를 자동 호출하므로 테스트 종료와 함께 확실히 정리된다.
 */
class ControlNotifyDebouncerPostConstructWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestBeans.class);

    @Test
    @DisplayName("regenFlush_토글이_true인_스프링_컨텍스트에서_빈_생성만으로_flush스케줄러가_자동_기동한다")
    void postConstructAutoStartsSchedulerViaSpringContext() {
        // given/when — regen-flush.enabled=true 로 덮어쓴 별도 컨텍스트에서 빈을 등록한다.
        //   startFlushScheduler() 를 테스트가 직접 호출하는 코드는 어디에도 없다.
        runner.withPropertyValues(
                "authoring.dataset-export.regen-flush.enabled=true",
                "authoring.control-notify.debounce-flush-interval-ms=20")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ControlNotifyDebouncer.class);
                    ControlNotifyDebouncer debouncer = ctx.getBean(ControlNotifyDebouncer.class);

                    // then — 스프링이 @PostConstruct 를 자동 호출해 전용 스케줄러 필드가 채워져 있다.
                    //   @PostConstruct 를 지우면 생성자는 flushScheduler 를 세팅하지 않으므로 null 로 남아 실패한다.
                    assertThat(schedulerOf(debouncer)).isNotNull();
                });
    }

    @Test
    @DisplayName("regenFlush_토글이_true인_스프링_컨텍스트에서_flush가_실제로_tick해_export_재생성을_트리거한다")
    void postConstructScheduledFlushActuallyTicksAndTriggersReExport() {
        // given — windowSec=0(즉시 만료) + flush 간격 20ms 로 짧게 잡아 tick 을 빨리 관측한다.
        runner.withPropertyValues(
                "authoring.dataset-export.regen-flush.enabled=true",
                "authoring.control-notify.debounce-flush-interval-ms=20",
                "authoring.control-notify.debounce-window-sec=0")
                .run(ctx -> {
                    ControlNotifyDebouncer debouncer = ctx.getBean(ControlNotifyDebouncer.class);
                    AsyncDatasetExportRunner exportRunner = ctx.getBean(AsyncDatasetExportRunner.class);

                    // when — 재생성 동반 수정 이벤트를 축적한다. 스케줄러 tick 을 유발하는 어떤 수동 호출도 없다
                    //   (flushExpiredWindows() 직접 호출 없음 — 오직 컨텍스트가 자동 기동한 전용 스케줄러가 tick).
                    debouncer.accumulate(new TaskModifiedEvent(200L, null, ChangeType.META_UPDATED, 10L, true));

                    // then — 스프링이 자동 기동한 전용 스케줄러가 flush 를 tick 해 재생성이 트리거된다.
                    //   @PostConstruct 제거 시 스케줄러가 없어 이 verify 는 5초 타임아웃으로 실패한다(실측 확인).
                    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                            .untilAsserted(() ->
                                    verify(exportRunner).runReExportThenNotify(eq(200L), eq(true), any()));
                });
    }

    private static ScheduledExecutorService schedulerOf(ControlNotifyDebouncer target) {
        try {
            Field f = ControlNotifyDebouncer.class.getDeclaredField("flushScheduler");
            f.setAccessible(true);
            return (ScheduledExecutorService) f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Configuration
    @Import(ControlNotifyDebouncer.class)
    static class TestBeans {

        @Bean
        AsyncDatasetExportRunner asyncDatasetExportRunner() {
            return mock(AsyncDatasetExportRunner.class);
        }

        /** Phase 9-C — 윈도우 저장소. 이 테스트는 @PostConstruct 배선만 보므로 인메모리 페이크로 충분하다. */
        @Bean
        ControlNotifyDebounceStore controlNotifyDebounceStore() {
            return new FakeControlNotifyDebounceStore();
        }

        // ControlNotifyService/ControlNotifyMetrics 는 @Nullable 생성자 파라미터라
        // 빈을 등록하지 않으면 스프링이 null 을 주입한다(운영 dev/stg/prd 의 통지 토글 off 형상과 동일).
    }
}

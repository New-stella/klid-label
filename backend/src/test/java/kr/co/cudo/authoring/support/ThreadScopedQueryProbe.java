package kr.co.cudo.authoring.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <b>요청 스레드에서 나간 SQL 문장만</b> 세는 N+1 회귀 가드용 계측기.
 *
 * <h3>왜 Hibernate {@code Statistics} 를 쓰면 안 되는가 — 전역 카운터라 남의 쿼리가 섞인다</h3>
 * <p>{@code SessionFactory.getStatistics().getPrepareStatementCount()} 는 <b>SessionFactory 전역</b>
 * 누적값이고 {@code clear()} 도 전역이다. 그래서 {@code clear() → 요청 → 카운트} 창 안에서 <b>다른
 * 스레드</b>가 쿼리를 하나라도 날리면 그 쿼리가 측정값에 들어간다. 이 저장소의 시험 컨텍스트는
 * 캐시되어 오래 살아 있고(같은 컨텍스트를 223개 시험 클래스가 공유한다) {@code @Async} 러너·전용
 * executor 스윕이 같은 컨텍스트에서 자기 스레드로 DB 를 친다 — 단독 실행에서는 그런 배경 작업이
 * 없어 <b>전건 통과하고, 전체 회귀에서만 값이 흔들린다</b>.
 *
 * <p>실측(2026-09-10, 전체 회귀 2회): 같은 시험이 {@code 페이지2→11 / 페이지20→10} 과
 * {@code 페이지2→10 / 페이지20→15} 로 서로 <b>다른 방향</b>으로 깨졌다. 진짜 N+1 이면 페이지 크기가
 * 10배일 때 델타가 +18 이어야 하는데 +5·-1 이 나왔다 — 신호가 아니라 잡음이라는 증거다.
 *
 * <h3>어떻게 좁히는가</h3>
 * <p>Hibernate 는 문장을 준비하기 직전에 {@code org.hibernate.SQL} 로거로 SQL 을 DEBUG 출력한다
 * (시험 프로파일은 이미 그 로거를 DEBUG 로 켜 둔다 — {@code src/test/resources/application-local.yml}).
 * 그 로거에 appender 를 붙이면 각 문장의 <b>발행 스레드 이름</b>({@link ILoggingEvent#getThreadName()})을
 * 알 수 있다. MockMvc 는 요청을 <b>호출 스레드에서 그대로</b> 처리하므로, {@link #reset()} 시점의
 * 현재 스레드 이름과 같은 이벤트만 세면 배경 작업이 구조적으로 배제된다.
 *
 * <p>계측 축이 {@code prepareStatementCount} 에서 <b>준비된 문장 로그 1건</b>으로 바뀌지만 둘 다
 * "JDBC 문장 1건 = 1" 이고, 이 가드는 애초에 <b>같은 코드 경로 두 실행의 차이</b>만 보므로 절대값의
 * 정의 차이는 판정에 영향을 주지 않는다.
 *
 * <p>배경 스레드가 실제로 창 안에 끼어들었는지는 {@link #foreignSummary()} 로 확인할 수 있다 —
 * 값이 비어 있지 않다는 것 자체가 전역 카운터였다면 오염됐을 실행이라는 뜻이다.
 *
 * <h3>사용</h3>
 * <pre>{@code
 * try (ThreadScopedQueryProbe probe = ThreadScopedQueryProbe.attach()) {
 *     probe.reset();
 *     mockMvc.perform(...);
 *     long queries = probe.countOnThisThread();
 * }
 * }</pre>
 *
 * <p>⚠ {@link #close()} 를 반드시 호출한다(try-with-resources). appender 가 로거에 남으면 이후 모든
 * 시험의 SQL 이 이 객체에 쌓인다.
 */
public final class ThreadScopedQueryProbe implements AutoCloseable {

    /** Hibernate 가 준비 직전 SQL 을 DEBUG 로 찍는 로거. */
    private static final String HIBERNATE_SQL_LOGGER = "org.hibernate.SQL";

    private final Logger sqlLogger;
    private final Level previousLevel;
    private final CapturingAppender appender;

    private ThreadScopedQueryProbe(Logger sqlLogger, Level previousLevel, CapturingAppender appender) {
        this.sqlLogger = sqlLogger;
        this.previousLevel = previousLevel;
        this.appender = appender;
    }

    /** {@code org.hibernate.SQL} 에 계측 appender 를 붙인다(필요하면 DEBUG 로 올리고 close 에서 복원). */
    public static ThreadScopedQueryProbe attach() {
        Logger sqlLogger = (Logger) LoggerFactory.getLogger(HIBERNATE_SQL_LOGGER);
        Level previousLevel = sqlLogger.getLevel();
        if (!sqlLogger.isDebugEnabled()) {
            sqlLogger.setLevel(Level.DEBUG);
        }
        CapturingAppender appender = new CapturingAppender();
        appender.setName("thread-scoped-query-probe");
        appender.start();
        sqlLogger.addAppender(appender);
        return new ThreadScopedQueryProbe(sqlLogger, previousLevel, appender);
    }

    /** 측정 창을 연다 — 지금 이 스레드에서 나가는 문장만 이후 {@link #countOnThisThread()} 에 잡힌다. */
    public void reset() {
        appender.reset(Thread.currentThread().getName());
    }

    /** {@link #reset()} 이후 <b>같은 스레드</b>에서 준비된 SQL 문장 수. */
    public long countOnThisThread() {
        return appender.ownCount();
    }

    /**
     * {@link #reset()} 이후 <b>다른 스레드</b>가 발행한 문장 요약({@code "스레드명 xN"} 나열).
     * 비어 있지 않으면 전역 카운터로 쟀을 때 그만큼 오염됐을 실행이라는 뜻이다.
     */
    public String foreignSummary() {
        return appender.foreignSummary();
    }

    @Override
    public void close() {
        sqlLogger.detachAppender(appender);
        appender.stop();
        sqlLogger.setLevel(previousLevel);
    }

    /** 스레드 이름별로 SQL 문장 수를 센다. 여러 스레드가 동시에 부르므로 전부 동기화한다. */
    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final Object lock = new Object();
        private String ownThreadName = "";
        private long ownCount;
        private final Map<String, Long> foreignCounts = new LinkedHashMap<>();

        void reset(String threadName) {
            synchronized (lock) {
                ownThreadName = threadName;
                ownCount = 0;
                foreignCounts.clear();
            }
        }

        long ownCount() {
            synchronized (lock) {
                return ownCount;
            }
        }

        String foreignSummary() {
            synchronized (lock) {
                List<String> parts = new ArrayList<>();
                foreignCounts.forEach((thread, count) -> parts.add(thread + " x" + count));
                return parts.stream().collect(Collectors.joining(", "));
            }
        }

        @Override
        protected void append(ILoggingEvent event) {
            String thread = event.getThreadName();
            synchronized (lock) {
                if (ownThreadName.isEmpty()) {
                    return; // reset() 이전 — 측정 창이 아직 열리지 않았다.
                }
                if (ownThreadName.equals(thread)) {
                    ownCount++;
                } else {
                    foreignCounts.merge(thread, 1L, Long::sum);
                }
            }
        }
    }
}

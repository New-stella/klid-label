package kr.co.cudo.authoring.architecture;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 테스트 데이터소스 Hikari 설정이 <b>실제로 바인딩됐는지</b> 고정하는 가드.
 *
 * <h2>막으려는 실패 모드 — "썼는데 조용히 안 먹는다"</h2>
 * <p>{@code controlDataSource} 는
 * {@code DataSourceBuilder.create().type(HikariDataSource.class).build()} +
 * {@code @ConfigurationProperties(prefix = "spring.datasource.control")} 조합이라
 * Hikari 프로퍼티가 <b>평면(flat)</b> 으로 바인딩된다. 즉 {@code maximum-pool-size} 와 <b>같은 레벨</b>에
 * 둬야 하고, Spring Boot 단일 데이터소스 관습대로 {@code hikari:} 아래에 중첩하면
 * <b>예외 없이 무시</b>된다(이 프로젝트에 실제 전례가 있다). yml 만 보고는 판별할 수 없으므로
 * 런타임 값으로 고정한다.
 *
 * <h2>왜 이 값들인가</h2>
 * <ul>
 *   <li><b>connection-timeout 5초</b> — Hikari 기본은 30초다. 커넥션을 얻지 못하는 상황(JVM 종료 훅에서
 *       Testcontainers 가 이미 내려간 뒤의 잔여 작업 등)에서 호출 1건마다 30초씩 붙잡히면 종료가 테스트
 *       실행보다 길어진다. 테스트는 실패를 빨리 드러내는 편이 낫다.</li>
 *   <li><b>maximum-pool-size 2</b> — <b>의도적으로 작다</b>. 커넥션 기아 교착(중첩
 *       {@code REQUIRES_NEW} 등)을 테스트에서 드러내기 위한 설정이므로 <b>늘리면 실제 결함을 가린다</b>.
 *       성능을 이유로 올리지 말 것.</li>
 * </ul>
 *
 * <p>운영 프로파일에는 영향이 없다 — 이 값들은 {@code src/test/resources/application-local.yml}
 * (테스트 클래스패스가 main 의 동명 파일을 가린다) 에만 있다.
 */
@SpringBootTest
@ActiveProfiles("local")
class TestDataSourceTimeoutBindingGuardTest {

    /** 테스트 프로파일 커넥션 대기 상한(ms). */
    private static final long EXPECTED_CONNECTION_TIMEOUT_MS = 5_000L;
    /** 테스트 프로파일 풀 크기 — 커넥션 기아를 드러내기 위한 의도적 하한. */
    private static final int EXPECTED_MAX_POOL_SIZE = 2;

    private final HikariDataSource controlDataSource;

    // 빈 선언 반환 타입이 DataSource 라 HikariDataSource 로 직접 주입받지 않고 unwrap 한다
    // (VideoDurationResolverTxIsolationIT 와 동일 패턴).
    TestDataSourceTimeoutBindingGuardTest(
            @Autowired @Qualifier("controlDataSource") DataSource controlDataSource) throws SQLException {
        this.controlDataSource = controlDataSource.unwrap(HikariDataSource.class);
    }

    @Test
    @DisplayName("control_데이터소스에_테스트용_커넥션_타임아웃과_풀크기가_실제로_바인딩된다")
    void hikariPropertiesAreActuallyBound() {
        // given / when: 컨테이너가 만든 실제 Hikari 인스턴스의 런타임 값
        // then: yml 에 쓴 값이 그대로 반영돼 있어야 한다(hikari: 중첩이면 기본값 30000 이 나와 실패한다)
        assertThat(controlDataSource.getConnectionTimeout())
                .as("control 커넥션 타임아웃 — Hikari 기본 30000 이 나오면 yml 키가 바인딩되지 않은 것이다")
                .isEqualTo(EXPECTED_CONNECTION_TIMEOUT_MS);

        assertThat(controlDataSource.getMaximumPoolSize())
                .as("control 풀 크기는 의도적으로 2다(커넥션 기아 노출용) — 늘리면 실제 결함을 가린다")
                .isEqualTo(EXPECTED_MAX_POOL_SIZE);
    }
}

package kr.co.cudo.authoring.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.quartz.QuartzProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저작도구 전용 스키마({@code klid_at}) 배선이 <b>실제로 먹었는지</b> 고정하는 가드.
 *
 * <h2>막으려는 실패 모드 — "테스트는 통과하는데 런타임 네이티브 쿼리만 깨진다"</h2>
 * <p>스키마는 서로 다른 <b>네 지점</b>에 배선돼 있고 각각이 덮는 범위가 다르다.
 * 특히 {@code hibernate.default_schema} 는 <b>JPA 매핑 SQL 만</b> 한정하고,
 * {@code @Query(nativeQuery = true)} 의 비한정 테이블명·Quartz JobStore·Flyway 는 전부
 * <b>커넥션의 search_path</b> 를 따른다. 그래서 커넥션 축이 빠져도 JPA 기반 테스트는 전부
 * 통과하고, 네이티브 쿼리를 쓰는 런타임 경로만 조용히 깨진다.
 *
 * <p>yml 만 보고는 바인딩 여부를 판별할 수 없으므로({@link TestDataSourceTimeoutBindingGuardTest}
 * 와 동일한 이유 — 키를 잘못 두면 <b>예외 없이 무시</b>된다) 런타임 값으로 고정한다.
 *
 */
@SpringBootTest
@ActiveProfiles("local")
class DataSourceSchemaWiringGuardTest {

    /** 저작도구 전용 스키마 — {@code ${DB_SCHEMA:klid_at}} 의 기본값. */
    private static final String EXPECTED_SCHEMA = "klid_at";

    private final HikariDataSource controlDataSource;
    private final JdbcTemplate controlJdbc;
    private final EntityManagerFactory controlEmf;
    private final QuartzProperties quartzProperties;
    private final Flyway flyway;

    // 빈 선언 반환 타입이 DataSource 라 HikariDataSource 로 직접 주입받지 않고 unwrap 한다
    // (TestDataSourceTimeoutBindingGuardTest 와 동일 패턴).
    DataSourceSchemaWiringGuardTest(
            @Autowired @Qualifier("controlDataSource") DataSource controlDataSource,
            @Autowired @Qualifier("controlEntityManagerFactory") EntityManagerFactory controlEmf,
            @Autowired QuartzProperties quartzProperties,
            @Autowired Flyway flyway) throws SQLException {
        this.controlDataSource = controlDataSource.unwrap(HikariDataSource.class);
        this.controlJdbc = new JdbcTemplate(controlDataSource);
        this.controlEmf = controlEmf;
        this.quartzProperties = quartzProperties;
        this.flyway = flyway;
    }

    @Test
    @DisplayName("currentSchema_가_HikariCP_dataSourceProperties_에_실제로_실려_있다")
    void currentSchemaIsActuallyBoundOntoHikariConfig() {
        // given / when: 컨테이너가 만든 실제 Hikari 인스턴스가 드라이버로 넘기는 접속 프로퍼티
        //   (HikariCP 는 이 Properties 를 그대로 Driver.connect 에 전달하고,
        //    pgjdbc 는 currentSchema 를 접속 시작 파라미터 search_path 로 보낸다)
        String currentSchema = controlDataSource.getDataSourceProperties().getProperty("currentSchema");

        // then: yml 키를 잘못 두면 예외 없이 무시되어 여기서 null 이 나온다
        assertThat(currentSchema)
                .as("spring.datasource.control.data-source-properties.currentSchema 가 바인딩되지 않았다")
                .isEqualTo(EXPECTED_SCHEMA);
    }

    @Test
    @DisplayName("Flyway_와_데이터소스가_같은_스키마를_쓴다")
    void flywayAndDataSourceAgreeOnSchema() {
        // given: 이 변경의 최대 위험 — 두 값이 갈리면 Flyway 가 만든 테이블을 런타임이 보지 못한다.
        //        (마이그레이션은 성공하고 앱도 기동하는데 조회만 비는 형태라 늦게 발견된다)
        String datasourceSchema = controlDataSource.getDataSourceProperties().getProperty("currentSchema");

        // when: Flyway 가 실제로 들고 있는 설정값
        String flywayDefaultSchema = flyway.getConfiguration().getDefaultSchema();
        String[] flywaySchemas = flyway.getConfiguration().getSchemas();

        // then: 둘 다 같은 ${DB_SCHEMA} 를 읽으므로 항상 일치해야 한다
        assertThat(flywayDefaultSchema)
                .as("flyway_schema_history 가 놓이는 스키마")
                .isEqualTo(EXPECTED_SCHEMA);
        assertThat(flywaySchemas)
                .as("Flyway 가 마이그레이션을 적용하는 스키마")
                .containsExactly(EXPECTED_SCHEMA);
        assertThat(flywayDefaultSchema)
                .as("Flyway 대상 스키마와 런타임 커넥션 스키마가 갈리면 만든 테이블을 못 본다")
                .isEqualTo(datasourceSchema);
    }

    @Test
    @DisplayName("control_커넥션의_기본_스키마가_klid_at_이다")
    void controlConnectionResolvesToAuthoringSchema() {
        // given / when: 풀에서 실제로 빌려온 커넥션이 해석하는 기본 스키마
        String currentSchema = controlJdbc.queryForObject("SELECT current_schema()", String.class);

        // then: currentSchema 배선이 빠지면 PostgreSQL 기본값 'public' 이 나온다
        assertThat(currentSchema)
                .as("control 커넥션 search_path — 'public' 이 나오면 currentSchema 가 바인딩되지 않은 것이다")
                .isEqualTo(EXPECTED_SCHEMA);
    }

    @Test
    @DisplayName("비한정_네이티브_쿼리가_klid_at_테이블을_찾는다")
    void unqualifiedNativeQueryResolvesAuthoringTables() {
        // given: 스키마를 한정하지 않은 SQL — 리포지토리의 @Query(nativeQuery = true) 와 같은 축이다.
        //        이 축은 hibernate.default_schema 가 아니라 커넥션 search_path 로만 해석된다.
        // when / then: 테이블을 찾지 못하면 42P01 로 실패한다(건수 자체는 검증 대상이 아니다)
        assertThat(controlJdbc.queryForObject("SELECT count(*) FROM LS_DATA_RAW", Long.class))
                .as("비한정 네이티브 쿼리가 klid_at 의 테이블을 해석해야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("control_EMF에_hibernate_default_schema_가_설정된다")
    void defaultSchemaAppliesToControlEntityManagerFactory() {
        // given / when: EMF 가 실제로 들고 있는 Hibernate 설정
        Object controlSchema = controlEmf.getProperties().get("hibernate.default_schema");

        // then
        assertThat(controlSchema)
                .as("control EMF 는 스키마를 명시 한정한다(search_path 가 틀렸을 때 조용히 흘러가지 않게)")
                .isEqualTo(EXPECTED_SCHEMA);
    }

    @Test
    @DisplayName("Quartz_JobStore_가_klid_at_의_QRTZ_테이블을_가리킨다")
    void quartzJobStorePointsToAuthoringSchema() {
        // given / when: initialize-schema=never 라 Quartz 는 테이블을 만들지 않고 찾기만 한다
        String tablePrefix = quartzProperties.getProperties().get("org.quartz.jobStore.tablePrefix");

        // then: 어긋나면 스케줄러 기동이 그 자리에서 깨진다(fail-closed)
        assertThat(tablePrefix)
                .as("Quartz tablePrefix — 스키마 한정이 빠지면 다른 스키마의 동명 QRTZ_* 를 잡을 수 있다")
                .isEqualTo(EXPECTED_SCHEMA + ".QRTZ_");
    }
}

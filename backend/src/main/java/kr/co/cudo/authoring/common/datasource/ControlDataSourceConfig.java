package kr.co.cudo.authoring.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
@EnableJpaRepositories(
        basePackages = "kr.co.cudo.authoring",
        includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.ANNOTATION,
                classes = ControlRepo.class
        ),
        entityManagerFactoryRef = "controlEntityManagerFactory",
        transactionManagerRef = "controlTransactionManager"
)
public class ControlDataSourceConfig {

    @Primary
    @Bean(name = "controlDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.control")
    public DataSource controlDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /**
     * control EMF 가 생성하는 SQL 의 스키마 한정자({@code hibernate.default_schema}).
     *
     * <p><b>왜 yml 의 {@code spring.jpa.properties} 가 아니라 여기인가</b>: {@code JpaBuilderConfig}
     * 의 {@link EntityManagerFactoryBuilder} 는 control 과 <b>portal 이 공유</b>한다. yml 에 두면
     * portal EMF 의 SQL 까지 {@code klid_at.} 로 한정되는데, 포털은 <b>별개 물리 DB</b> 이고
     * 그 복제본 스키마는 설치 단계가 {@code public} 에 로드하므로 전부 깨진다.
     * {@code Builder.properties()} 는 빌더 인스턴스별 맵에만 담기므로(공유 맵을 오염시키지 않는다)
     * 이 값은 control EMF 에만 적용된다.
     *
     * <p><b>커넥션 search_path 가 이미 있는데 왜 또 두는가</b>: 이중 방어다. search_path 는
     * 네이티브 쿼리·Quartz·Flyway 까지 덮지만 <b>틀렸을 때 다른 스키마의 동명 테이블로 조용히
     * 흘러갈</b> 수 있다. 주 데이터 경로인 JPA 는 명시 한정해 그런 경우 즉시 실패시킨다.
     *
     * <p>값은 {@code spring.datasource.control.data-source-properties.currentSchema} 와 같은
     * {@code DB_SCHEMA} 를 읽는다 — 둘이 갈리면 JPA 와 네이티브 쿼리가 다른 스키마를 본다.
     */
    private static final String HIBERNATE_DEFAULT_SCHEMA = "hibernate.default_schema";

    @Primary
    @Bean(name = "controlEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean controlEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("controlDataSource") DataSource dataSource,
            @Value("${DB_SCHEMA:klid_at}") String schema) {
        return builder
                .dataSource(dataSource)
                .packages("kr.co.cudo.authoring")
                .persistenceUnit("control")
                .properties(Map.of(HIBERNATE_DEFAULT_SCHEMA, schema))
                .build();
    }

    @Primary
    @Bean(name = "controlTransactionManager")
    public PlatformTransactionManager controlTransactionManager(
            @Qualifier("controlEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}

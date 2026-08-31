package kr.co.cudo.authoring.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                classes = PortalRepo.class
        ),
        entityManagerFactoryRef = "portalEntityManagerFactory",
        transactionManagerRef = "portalTransactionManager"
)
public class PortalDataSourceConfig {

    @Bean(name = "portalDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.portal")
    public DataSource portalDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /**
     * 포털 EMF 에만 거는 방언(dialect). <b>기동이 포털 DB 의 생사에 묶이지 않게 하는 장치다.</b>
     *
     * <p>방언을 명시하지 않으면 Hibernate 는 부트스트랩에서 JDBC 메타데이터를 읽으려고 커넥션을
     * 열고, 실패하면 {@code Unable to determine Dialect without JDBC metadata} 로 <b>기동 자체를
     * 막는다</b>(실측). 그 결과 두 가지가 깨진다:
     * <ul>
     *   <li>포털을 함께 반입하지 않는 배포 형상 — 포털 DB 가 <b>존재하지 않아</b> 앱이 뜨지 못한다.
     *       이 앱의 포털 소비처는 메타 복제 하나뿐이고 그것은 이미 토글로 꺼지는데도 그렇다.</li>
     *   <li>완전한 형상에서도 <b>포털 DB 순단 중에는 재기동이 불가능</b>하다 — 관제 연동 배포의
     *       가용성이 포털 DB 에 묶인다(포털은 부수 경로인데 주 경로를 인질로 잡는다).</li>
     * </ul>
     *
     * <p><b>control EMF 에는 걸지 않는다</b>(전역 {@code spring.jpa.properties} 로 올리지 말 것):
     * 방언을 명시하면 Hibernate 가 실 DB 버전을 탐지하지 않고 그 방언의 기본 최소 버전으로
     * 기능을 게이팅해, 주 경로의 SQL 생성이 바뀔 수 있다. 포털 EMF 는 복제 upsert 한 곳만 쓰고
     * 그 SQL 이 네이티브라 버전 게이팅의 영향을 받지 않으므로, 여기서만 감수한다.
     *
     * <p>전 환경·전 채널 PostgreSQL 이 확정 전제이므로 값을 설정으로 열지 않는다.
     * 회귀 고정: {@code PortalEmfBootWithoutPortalDbTest}.
     */
    static final String PORTAL_DIALECT = "org.hibernate.dialect.PostgreSQLDialect";

    @Bean(name = "portalEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean portalEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("portalDataSource") DataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages("kr.co.cudo.authoring")
                .persistenceUnit("portal")
                .properties(Map.of("hibernate.dialect", PORTAL_DIALECT))
                .build();
    }

    @Bean(name = "portalTransactionManager")
    public PlatformTransactionManager portalTransactionManager(
            @Qualifier("portalEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}

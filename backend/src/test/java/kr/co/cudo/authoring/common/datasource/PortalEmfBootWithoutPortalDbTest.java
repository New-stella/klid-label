package kr.co.cudo.authoring.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>포털 DB 에 닿지 못해도 앱이 기동한다</b>는 불변식을 고정한다.
 *
 * <p>지키는 것이 둘이다:
 * <ul>
 *   <li><b>배포 형상</b> — 포털을 함께 반입하지 않는 배포에서는 {@code PORTAL_DB_*} 가 가리키는
 *       DB 가 비어 있는 것이 아니라 <b>존재하지 않는다</b>. 그래도 기동해야 한다.</li>
 *   <li><b>가용성</b> — 완전한 형상에서도 포털 DB 순단 중에 재기동이 막히면 안 된다. 포털 복제는
 *       부수 경로이며 주 경로(관제 연동)를 인질로 잡아서는 안 된다.</li>
 * </ul>
 *
 * <p><b>왜 {@code @SpringBootTest} 가 아닌가 (되돌리지 말 것)</b>: 테스트 인프라
 * {@link kr.co.cudo.authoring.support.PostgresContainerContextCustomizerFactory} 가
 * {@code spring.datasource.portal.jdbc-url} 을 {@code addFirst()} 로 환경 맨 앞에 꽂는데,
 * 컨텍스트 커스터마이저는 {@code @TestPropertySource}·{@code @DynamicPropertySource} 보다
 * <b>나중에</b> 실행된다. 그래서 통합 테스트에서 포털 주소를 죽은 주소로 덮어써도
 * <b>조용히 무시되고</b> 살아 있는 컨테이너 DB 를 가리킨 채 통과한다(실측). 그 함정을 피하려고
 * 컨텍스트를 띄우지 않고 <b>운영 빈 조립 코드를 직접 호출</b>한다 — 조립을 베껴 쓰지 않으므로
 * 운영과 갈릴 수 없다.
 */
@DisplayName("포털 DB 부재 시 기동 불변식")
class PortalEmfBootWithoutPortalDbTest {

    /** 아무도 듣지 않는 포트 — "DB 가 없다"를 재현한다(빈 DB 가 아니다). */
    private static final String DEAD_JDBC_URL = "jdbc:postgresql://127.0.0.1:59997/klid_portal_absent";

    /**
     * {@code application.yml} 의 {@code spring.jpa.properties.*} 를 옮긴 것.
     * <b>방언은 여기 없다</b> — 운영 설정에도 없기 때문이다(있으면 control 까지 영향받는다).
     */
    private static JpaProperties yamlJpaProperties() {
        JpaProperties jpa = new JpaProperties();
        Map<String, String> props = new LinkedHashMap<>();
        props.put("hibernate.format_sql", "true");
        props.put("hibernate.jdbc.batch_size", "50");
        props.put("hibernate.jdbc.time_zone", "Asia/Seoul");
        props.put("hibernate.order_inserts", "true");
        props.put("hibernate.order_updates", "true");
        jpa.getProperties().putAll(props);
        return jpa;
    }

    private static HikariDataSource deadPortalDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(DEAD_JDBC_URL);
        ds.setUsername("nobody");
        ds.setPassword("nobody");
        ds.setDriverClassName("org.postgresql.Driver");
        // 운영 기본값은 30초다. 판정축은 "연결이 되느냐"이지 "얼마나 기다리느냐"가 아니므로
        // 결과를 바꾸지 않으면서 테스트만 빨리 끝내려고 줄인다.
        ds.setConnectionTimeout(2000);
        return ds;
    }

    /** 운영과 동일한 빌더 — {@link JpaBuilderConfig} 를 직접 호출한다. */
    private static EntityManagerFactoryBuilder productionBuilder() {
        return new JpaBuilderConfig().entityManagerFactoryBuilder(yamlJpaProperties());
    }

    @Test
    @Timeout(120)
    @DisplayName("전제_확인_지정한_주소에는_실제로_DB가_없다")
    void 전제_확인_지정한_주소에는_실제로_DB가_없다() {
        // 이 단언이 없으면 "DB 가 살아 있는데 통과한" 가짜 초록을 구분할 수 없다.
        try (HikariDataSource ds = deadPortalDataSource()) {
            assertThat(ds.getJdbcUrl()).isEqualTo(DEAD_JDBC_URL);
            assertThatThrownBy(ds::getConnection)
                    .as("죽은 주소여야 이 테스트가 의미를 갖는다")
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("포털_DB에_닿지_못해도_portal_EMF_부트스트랩이_성공한다")
    void 포털_DB에_닿지_못해도_portal_EMF_부트스트랩이_성공한다() {
        try (HikariDataSource ds = deadPortalDataSource()) {
            // 운영 빈 조립을 그대로 호출한다(베껴 쓰지 않는다).
            LocalContainerEntityManagerFactoryBean emf =
                    new PortalDataSourceConfig().portalEntityManagerFactory(productionBuilder(), ds);

            assertThatCode(emf::afterPropertiesSet)
                    .as("여기서 터지면 포털 미반입 배포가 기동하지 못하고, 포털 DB 순단 중 재기동도 막힌다")
                    .doesNotThrowAnyException();
            try {
                assertThat(emf.getObject()).isNotNull();
            } finally {
                emf.destroy();
            }
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("방언을_빼면_실제로_기동이_막힌다_위_한_줄이_장식이_아님을_증명")
    void 방언을_빼면_실제로_기동이_막힌다_위_한_줄이_장식이_아님을_증명() {
        // PortalDataSourceConfig 의 .properties(hibernate.dialect) 한 줄을 뺐을 때를 재현한다.
        // 이 테스트가 없으면 그 줄을 "불필요해 보인다"며 지워도 위 테스트만으로는 잡히지 않는다
        // — 위 테스트가 통과하는 이유가 그 줄 때문임을 여기서 고정한다.
        try (HikariDataSource ds = deadPortalDataSource()) {
            LocalContainerEntityManagerFactoryBean emf = productionBuilder()
                    .dataSource(ds)
                    .packages("kr.co.cudo.authoring")
                    .persistenceUnit("portal-without-dialect")
                    .build();

            assertThatThrownBy(emf::afterPropertiesSet)
                    .as("방언이 없으면 Hibernate 가 JDBC 메타데이터를 읽으려 커넥션을 연다")
                    .rootCause()
                    .hasMessageContaining("Unable to determine Dialect without JDBC metadata");
        }
    }
}

package kr.co.cudo.authoring.support;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 모든 Spring 테스트 컨텍스트에 싱글톤 PostgreSQL Testcontainer 의 접속 정보를 주입한다.
 *
 * <p>{@code META-INF/spring.factories} 에 등록되어 {@code @SpringBootTest} 등 컨텍스트를
 * 띄우는 모든 테스트에 자동 적용된다. 개별 테스트 클래스가 베이스 클래스를 상속하거나
 * {@code @DynamicPropertySource} 를 선언하지 않아도 control/portal 듀얼 데이터소스가
 * 컨테이너를 향하도록 한다.
 *
 * <p>주입 프로퍼티는 {@code spring.datasource.control.*} / {@code spring.datasource.portal.*}
 * 로, application-local.yml(test) 의 PG datasource 자리에 들어간다. 두 데이터소스 모두
 * 동일 컨테이너/DB 를 가리킨다 (현재 portal 전용 리포지토리가 없으므로 안전).
 */
public class PostgresContainerContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                     List<ContextConfigurationAttributes> configAttributes) {
        boolean portalProvisioned = !AnnotatedElementUtils.hasAnnotation(testClass, UnprovisionedPortalReplica.class);
        return new PostgresContainerContextCustomizer(portalProvisioned);
    }

    /**
     * equals/hashCode 를 동일하게 유지하여 Spring 테스트 컨텍스트 캐시가 깨지지 않도록 한다
     * (모든 테스트가 동일 컨테이너를 공유하므로 customizer 도 동등하게 취급).
     *
     * <p>단 {@link UnprovisionedPortalReplica} 테스트는 portal 접속 대상이 다르므로 {@code portalProvisioned}
     * 를 동등성에 포함해 <b>별도 컨텍스트</b>로 캐시된다 — 그러지 않으면 먼저 뜬 컨텍스트가 재사용돼
     * 미프로비저닝 재현이 무산된다.
     */
    static final class PostgresContainerContextCustomizer implements ContextCustomizer {

        private final boolean portalProvisioned;

        PostgresContainerContextCustomizer(boolean portalProvisioned) {
            this.portalProvisioned = portalProvisioned;
        }

        @Override
        public void customizeContext(org.springframework.context.ConfigurableApplicationContext context,
                                     MergedContextConfiguration mergedConfig) {
            String jdbcUrl = PostgresTestContainer.INSTANCE.getJdbcUrl();
            String username = PostgresTestContainer.INSTANCE.getUsername();
            String password = PostgresTestContainer.INSTANCE.getPassword();
            String portalJdbcUrl = portalProvisioned
                    ? jdbcUrl
                    : PostgresTestContainer.unprovisionedPortalJdbcUrl();

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("spring.datasource.control.jdbc-url", jdbcUrl);
            props.put("spring.datasource.control.username", username);
            props.put("spring.datasource.control.password", password);
            props.put("spring.datasource.control.driver-class-name", "org.postgresql.Driver");
            props.put("spring.datasource.portal.jdbc-url", portalJdbcUrl);
            props.put("spring.datasource.portal.username", username);
            props.put("spring.datasource.portal.password", password);
            props.put("spring.datasource.portal.driver-class-name", "org.postgresql.Driver");

            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("testcontainers-postgres", props));
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof PostgresContainerContextCustomizer other
                    && this.portalProvisioned == other.portalProvisioned;
        }

        @Override
        public int hashCode() {
            return Boolean.hashCode(portalProvisioned);
        }
    }
}

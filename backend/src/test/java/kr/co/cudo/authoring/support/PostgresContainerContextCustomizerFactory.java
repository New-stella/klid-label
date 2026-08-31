package kr.co.cudo.authoring.support;

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
 * {@code @DynamicPropertySource} 를 선언하지 않아도 데이터소스가 컨테이너를 향하도록 한다.
 *
 * <p>주입 프로퍼티는 {@code spring.datasource.control.*} 로, application-local.yml(test) 의
 * PG datasource 자리에 들어간다.
 *
 * <p>⚠⚠ <b>여기서 주입한 값은 {@code @TestPropertySource}·{@code @DynamicPropertySource} 를
 * 이긴다</b> — {@code addFirst()} 로 환경 맨 앞에 꽂는데, 컨텍스트 커스터마이저는 그 둘이 처리되는
 * {@code prepareContext} 보다 <b>나중에</b> 실행되기 때문이다. 그래서 어떤 테스트가 데이터소스
 * 주소를 죽은 값으로 덮어써도 <b>조용히 무시된 채 살아 있는 컨테이너를 가리키고 초록으로 통과</b>한다
 * (실측으로 확인됐고, 그 탓에 접속 불가 경로가 구조적으로 검증되지 않았다).
 * 접속 실패를 재현해야 하는 시험은 컨텍스트를 띄우지 말고 <b>운영 빈 조립 코드를 직접 호출</b>하고,
 * 덮어쓰기를 쓰는 시험은 <b>그 값이 실제로 적용됐는지 먼저 단언</b>할 것.
 */
public class PostgresContainerContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                     List<ContextConfigurationAttributes> configAttributes) {
        return new PostgresContainerContextCustomizer();
    }

    /**
     * equals/hashCode 를 동일하게 유지하여 Spring 테스트 컨텍스트 캐시가 깨지지 않도록 한다
     * (모든 테스트가 동일 컨테이너를 공유하므로 customizer 도 동등하게 취급).
     */
    static final class PostgresContainerContextCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(org.springframework.context.ConfigurableApplicationContext context,
                                     MergedContextConfiguration mergedConfig) {
            String jdbcUrl = PostgresTestContainer.INSTANCE.getJdbcUrl();
            String username = PostgresTestContainer.INSTANCE.getUsername();
            String password = PostgresTestContainer.INSTANCE.getPassword();

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("spring.datasource.control.jdbc-url", jdbcUrl);
            props.put("spring.datasource.control.username", username);
            props.put("spring.datasource.control.password", password);
            props.put("spring.datasource.control.driver-class-name", "org.postgresql.Driver");

            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("testcontainers-postgres", props));
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof PostgresContainerContextCustomizer;
        }

        @Override
        public int hashCode() {
            return PostgresContainerContextCustomizer.class.hashCode();
        }
    }
}

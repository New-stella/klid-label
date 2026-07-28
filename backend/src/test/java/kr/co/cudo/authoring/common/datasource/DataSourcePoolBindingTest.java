package kr.co.cudo.authoring.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 듀얼 DataSource(control/portal) 의 <b>HikariCP 풀 설정 바인딩 형식</b> 검증.
 *
 * <p>{@link ControlDataSourceConfig}/{@link PortalDataSourceConfig} 는
 * {@code DataSourceBuilder.create().type(HikariDataSource.class).build()} 로 만든
 * <b>HikariDataSource 인스턴스에 직접</b> {@code @ConfigurationProperties} 를 바인딩한다.
 * 따라서 Spring Boot 오토컨피그의 {@code spring.datasource.hikari.*} 중첩 규약이 적용되지 않고,
 * 풀 속성은 prefix 직하(평면)에 두어야 한다.
 *
 * <p>본 테스트는 그 사실을 <b>실측</b>하고(1·2번), 실제 운영(prd) yml 이 바인딩되는 형식으로
 * 선언돼 있는지(3번)를 회귀 방지로 고정한다. 잘못된 형식이면 조용히 무시되어 운영 풀이
 * 의도한 크기가 아닌 Hikari 기본값(10)으로 동작한다.
 *
 * <p>커넥션을 열지 않으므로 DB/Testcontainers 불요(순수 바인딩 검증).
 */
class DataSourcePoolBindingTest {

    private static final String CONTROL_PREFIX = prefixOf(ControlDataSourceConfig.class, "controlDataSource");
    private static final String PORTAL_PREFIX = prefixOf(PortalDataSourceConfig.class, "portalDataSource");

    @Test
    @DisplayName("hikari_중첩없는_형식이_실제_풀크기에_바인딩된다")
    void flatFormatBindsToPool() {
        // given: prefix 직하(평면)에 선언한 풀 속성
        ConfigurableEnvironment env = environmentOf(Map.of(
                CONTROL_PREFIX + ".maximum-pool-size", "7",
                CONTROL_PREFIX + ".minimum-idle", "3"));

        // when: 실제 설정 클래스가 만드는 DataSource 에 동일 prefix 로 바인딩
        HikariDataSource ds = bind(newControlDataSource(), CONTROL_PREFIX, env);

        // then
        assertThat(ds.getMaximumPoolSize()).isEqualTo(7);
        assertThat(ds.getMinimumIdle()).isEqualTo(3);
    }

    @Test
    @DisplayName("hikari_중첩_형식은_바인딩되지_않는다")
    void nestedHikariFormatIsIgnored() {
        // given: spring.datasource.{control}.hikari.* 중첩 형식 (Boot 오토컨피그 규약)
        ConfigurableEnvironment env = environmentOf(Map.of(
                CONTROL_PREFIX + ".hikari.maximum-pool-size", "7",
                CONTROL_PREFIX + ".hikari.minimum-idle", "3"));

        // when
        HikariDataSource ds = bind(newControlDataSource(), CONTROL_PREFIX, env);

        // then: HikariDataSource 에 hikari 속성이 없어 조용히 무시된다(미설정 상태 유지)
        assertThat(ds.getMaximumPoolSize())
                .as("중첩 형식은 무시되므로 7 이 반영되지 않는다")
                .isNotEqualTo(7);
        assertThat(ds.getMinimumIdle()).isNotEqualTo(3);
    }

    @Test
    @DisplayName("운영_prd_yml의_풀설정이_실제_바인딩되는_형식으로_선언돼있다")
    void prdPoolSettingsActuallyBind() {
        // given: 실제 application-prd.yml (환경변수 placeholder 는 미해석 상태로 두어도 무방 —
        //        커넥션을 열지 않는다)
        ConfigurableEnvironment env = MainResourceYaml.environment("application-prd.yml");

        // when
        HikariDataSource control = bind(newControlDataSource(), CONTROL_PREFIX, env);
        HikariDataSource portal = bind(newPortalDataSource(), PORTAL_PREFIX, env);

        // then: prd 가 의도한 20/5 가 실제 풀 인스턴스에 반영돼야 한다
        assertThat(control.getMaximumPoolSize()).as("control 최대 풀 크기").isEqualTo(20);
        assertThat(control.getMinimumIdle()).as("control 최소 유휴").isEqualTo(5);
        assertThat(portal.getMaximumPoolSize()).as("portal 최대 풀 크기").isEqualTo(20);
        assertThat(portal.getMinimumIdle()).as("portal 최소 유휴").isEqualTo(5);
    }

    private static HikariDataSource newControlDataSource() {
        DataSource ds = new ControlDataSourceConfig().controlDataSource();
        return (HikariDataSource) ds;
    }

    private static HikariDataSource newPortalDataSource() {
        DataSource ds = new PortalDataSourceConfig().portalDataSource();
        return (HikariDataSource) ds;
    }

    /** {@code @ConfigurationProperties} 바인딩(ConfigurationPropertiesBindingPostProcessor 와 동일 동작) 재현. */
    private static HikariDataSource bind(HikariDataSource ds, String prefix, ConfigurableEnvironment env) {
        Binder.get(env).bind(prefix, Bindable.ofInstance(ds));
        return ds;
    }

    private static ConfigurableEnvironment environmentOf(Map<String, String> properties) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", new LinkedHashMap<>(properties)));
        return env;
    }

    /** 설정 클래스의 실제 prefix 를 애너테이션에서 읽어 테스트 드리프트를 방지한다. */
    private static String prefixOf(Class<?> configClass, String beanMethod) {
        try {
            ConfigurationProperties annotation = configClass.getDeclaredMethod(beanMethod)
                    .getAnnotation(ConfigurationProperties.class);
            if (annotation == null) {
                throw new IllegalStateException("@ConfigurationProperties 선언이 없습니다: " + beanMethod);
            }
            return annotation.prefix();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("빈 메서드를 찾을 수 없습니다: " + beanMethod, e);
        }
    }
}

package kr.co.cudo.authoring.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>포털 DB 가 닿지 않아도 서비스가 살아 있는 것으로 보이는가</b>를 고정한다.
 *
 * <p>배경: 포털을 함께 반입하지 않는 배포는 포털 DB 가 <b>존재하지 않는다</b>. Spring Boot 는
 * DataSource 빈마다 상태 기여자를 자동으로 만들므로 포털 데이터소스도 상태 점검에 참여하고,
 * 닿지 않으면 DOWN 을 낸다(둘 다 실측). 그래서 전체 {@code /actuator/health} 는 DOWN(503)이 된다.
 *
 * <p><b>이 시험이 지키는 것은 그 다음</b>이다 — 반입 문서·설치 안내·운영 런북이 노드 생사를
 * 판정하는 프로브는 전체 상태가 아니라 <b>{@code /actuator/health/liveness}</b> 다. 그 그룹에
 * DB 가 섞이면 포털 DB 부재만으로 <b>노드가 죽은 것으로 보여 서비스에서 빠진다</b>. 그 경계를 못박는다.
 *
 * <p>⚠ 이 시험은 포털 복제 축이 철거되면 함께 사라져도 된다. 다만 <b>그때까지는</b> 관제향 배포의
 * 가용성이 여기 걸려 있다.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("포털 DB 부재가 서비스 가용성 판정에 미치는 영향")
class PortalDataSourceHealthContributionTest {

    @Autowired
    private HealthContributorRegistry registry;

    @Autowired
    private HealthEndpointGroups groups;

    private static List<String> flatten(String name, HealthContributor contributor) {
        List<String> names = new ArrayList<>();
        if (contributor instanceof CompositeHealthContributor composite) {
            for (NamedContributor<HealthContributor> child : composite) {
                names.addAll(flatten(name + "/" + child.getName(), child.getContributor()));
            }
        } else {
            names.add(name);
        }
        return names;
    }

    private List<String> allContributorPaths() {
        List<String> all = new ArrayList<>();
        registry.forEach(named -> all.addAll(flatten(named.getName(), named.getContributor())));
        return all;
    }

    @Test
    @Timeout(180)
    @DisplayName("포털_데이터소스가_전체_상태점검에_참여한다_그래서_포털DB가_없으면_전체는_DOWN이다")
    void 포털_데이터소스가_전체_상태점검에_참여한다_그래서_포털DB가_없으면_전체는_DOWN이다() {
        List<String> all = allContributorPaths();
        System.out.println("[health-contributors] " + all);

        assertThat(all)
                .as("포털 데이터소스가 상태 기여자로 등록된다 — 이 사실이 아래 liveness 경계를 필요하게 만든다")
                .contains("db/portalDataSource");
    }

    @Test
    @Timeout(120)
    @DisplayName("닿지_않는_데이터소스의_상태_지표는_DOWN_이다")
    void 닿지_않는_데이터소스의_상태_지표는_DOWN_이다() {
        try (HikariDataSource dead = new HikariDataSource()) {
            dead.setJdbcUrl("jdbc:postgresql://127.0.0.1:59996/klid_portal_absent");
            dead.setUsername("nobody");
            dead.setPassword("nobody");
            dead.setDriverClassName("org.postgresql.Driver");
            dead.setConnectionTimeout(2000);

            DataSourceHealthIndicator indicator = new DataSourceHealthIndicator(dead);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("★liveness_프로브는_DB를_보지_않는다_포털DB가_없어도_노드가_서비스에서_빠지지_않는다")
    void liveness_프로브는_DB를_보지_않는다_포털DB가_없어도_노드가_서비스에서_빠지지_않는다() {
        HealthEndpointGroup liveness = groups.get("liveness");
        assertThat(liveness)
                .as("반입 문서·설치 안내·런북이 노드 생사 판정에 쓰는 그룹이다(/actuator/health/liveness)")
                .isNotNull();

        List<String> inLiveness = allContributorPaths().stream()
                .filter(liveness::isMember)
                .toList();
        System.out.println("[liveness-members] " + inLiveness);

        assertThat(inLiveness)
                .as("liveness 에 DB 가 섞이면 포털 DB 부재만으로 노드가 죽은 것으로 판정된다")
                .noneMatch(name -> name.startsWith("db"));
    }
}

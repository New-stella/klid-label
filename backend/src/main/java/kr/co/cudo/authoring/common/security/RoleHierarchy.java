package kr.co.cudo.authoring.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * 역할 계층 — <b>관리자에서 검수자로 가는 한 단계뿐이다.</b>
 *
 * <p>이 한 줄 덕분에 기존 {@code hasRole('REVIEWER')} 지점을 하나도 바꾸지 않고도 관리자가 검수
 * 업무를 그대로 수행한다. 겸직을 위해 계정을 둘 가질 필요가 없다.
 *
 * <h3>★ 계층을 여기서 선언하지 않는다 — {@link Role#directlyInheritedRoles()} 에서 파생한다</h3>
 * <p>계층 문자열을 여기 손으로 적고 서비스 판정({@link TokenClaims#hasRole(Role)})이 계층을 따로
 * 들면, 한쪽만 고쳤을 때 권한 축과 서비스 축이 조용히 갈린다. 그래서 상속 선언은 {@code Role}
 * 한 곳에만 두고 이 문자열은 그것을 옮겨 적기만 한다. <b>계층을 바꾸려면 {@code Role} 을 고쳐야
 * 한다</b> — 여기를 고쳐도 판정 축은 따라오지 않는다.
 *
 * <p>넓히면 안 되는 이유(작업자 전용 자리 · 채널 격리)는 {@code Role} 의 상속 선언에 적혀 있다.
 *
 * <p>이 계층은 세 곳에서 함께 쓰인다 — ① {@code SecurityConfig} 의 {@code hasRole(...)} 매처
 * (Spring 이 이 빈을 자동으로 적용한다) ② 메서드 보안 {@code @PreAuthorize}(같은 자동 적용)
 * ③ {@code SecurityConfig} 가 직접 조립하는 권한 매니저(그쪽은 <b>수동으로</b> 이 빈을 넘겨야
 * 한다 — 넘기지 않으면 관리자가 {@code /v1/**} 포괄 매처에서 막힌다).
 *
 * @design ADR-055
 * @design AC-125
 */
@Configuration
public class RoleHierarchy {

    /**
     * 계층 표기 — 왼쪽이 오른쪽 권한을 물려받는다. 한 줄뿐인 것이 의도다.
     *
     * <p><b>손으로 적지 않는다.</b> {@code Role} 의 상속 선언을 그대로 옮긴 파생값이며, 순서는
     * enum 선언 순서와 이름순으로 고정해 빌드마다 흔들리지 않게 한다(전이 확장은 Spring 이 한다).
     */
    static final String HIERARCHY = deriveHierarchy();

    private static String deriveHierarchy() {
        return Arrays.stream(Role.values())
                .flatMap(parent -> parent.directlyInheritedRoles().stream()
                        .sorted(Comparator.comparing(Role::name))
                        .map(child -> "ROLE_" + parent.name() + " > ROLE_" + child.name()))
                .collect(Collectors.joining("\n"));
    }

    @Bean
    public org.springframework.security.access.hierarchicalroles.RoleHierarchy authoringRoleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy(HIERARCHY);
    }
}

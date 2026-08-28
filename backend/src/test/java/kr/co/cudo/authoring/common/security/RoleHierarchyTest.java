package kr.co.cudo.authoring.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역할 계층의 <b>모양</b>을 직접 고정한다 (@design AC-125 · @design ADR-055).
 *
 * <h3>왜 인가 시험만으로는 부족한가</h3>
 * <p>"관리자가 포털 전용 창구에서 거부된다" 는 채널 격리가 <b>먼저</b> 막아 주므로, 계층에
 * {@code PORTAL_USER} 를 끼워 넣어도 그 시험은 여전히 통과한다. 즉 그 되돌림을 잡지 못한다.
 * 여기서는 계층이 실제로 도달시키는 권한 집합을 <b>정확히</b> 비교해, 한 줄이라도 더해지면
 * RED 가 되게 한다.
 */
class RoleHierarchyTest {

    private final org.springframework.security.access.hierarchicalroles.RoleHierarchy hierarchy =
            new RoleHierarchy().authoringRoleHierarchy();

    private List<String> reachableFrom(Role role) {
        List<GrantedAuthority> granted = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        return hierarchy.getReachableGrantedAuthorities(granted).stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("★관리자는_검수자까지만_도달한다_작업자_포털회원은_도달하지_않는다")
    void adminReachesReviewerOnly() {
        // 계층에 WORKER 를 끼우면 ROLE_WORKER 가 늘어 RED. PORTAL_USER 도 마찬가지다.
        assertThat(reachableFrom(Role.ADMIN))
                .as("계층은 관리자 → 검수자 한 단계뿐이다")
                .containsExactly("ROLE_ADMIN", "ROLE_REVIEWER");
    }

    @Test
    @DisplayName("검수자는_아무것도_물려받지_않는다")
    void reviewerInheritsNothing() {
        // 검수자가 작업자를 물려받으면 작업자 전용 자리가 사라진다(그것이 계층을 한 단계로 둔 이유).
        assertThat(reachableFrom(Role.REVIEWER)).containsExactly("ROLE_REVIEWER");
    }

    @Test
    @DisplayName("작업자와_포털회원은_계층의_출발점도_도착점도_아니다")
    void workerAndPortalUserAreOutsideHierarchy() {
        assertThat(reachableFrom(Role.WORKER)).containsExactly("ROLE_WORKER");
        assertThat(reachableFrom(Role.PORTAL_USER)).containsExactly("ROLE_PORTAL_USER");
    }

    @Test
    @DisplayName("계층_표기가_비어_있지_않다_구_빈_계층_회귀")
    void hierarchyIsNotEmpty() {
        // 과거 이 빈은 빈 문자열로 선언돼 있어 아무 계층도 없었다. 다시 비우면 관리자가 검수자
        // 권한 지점 전부에서 막히고, 그 순간 이 시험이 RED 가 된다.
        assertThat(RoleHierarchy.HIERARCHY).isNotBlank();
    }

    @Test
    @DisplayName("★계층_표기는_Role의_상속_선언에서_파생된다_손으로_적은_두번째_진실원_금지")
    void hierarchyIsDerivedFromRoleDeclaration() {
        // 계층을 여기 손으로 적고 서비스 판정(TokenClaims.hasRole)이 계층을 따로 들면 한쪽만
        // 고쳤을 때 권한 축과 서비스 축이 조용히 갈린다. 상속 선언은 Role 한 곳뿐이어야 한다.
        String derived = Arrays.stream(Role.values())
                .flatMap(parent -> parent.directlyInheritedRoles().stream()
                        .sorted(Comparator.comparing(Role::name))
                        .map(child -> "ROLE_" + parent.name() + " > ROLE_" + child.name()))
                .collect(Collectors.joining("\n"));

        assertThat(RoleHierarchy.HIERARCHY)
                .as("HIERARCHY 는 Role 의 상속 선언을 옮겨 적은 파생값이다")
                .isEqualTo(derived);
    }

    @Test
    @DisplayName("파생_결과가_현재_계층값과_문자열로_같다_ROLE_ADMIN_gt_ROLE_REVIEWER")
    void derivedHierarchyMatchesCurrentValue() {
        // 파생으로 바꾸면서 값이 달라지면 Spring 매처의 인가 판정이 통째로 흔들린다.
        assertThat(RoleHierarchy.HIERARCHY).isEqualTo("ROLE_ADMIN > ROLE_REVIEWER");
    }

    @Test
    @DisplayName("Role의_상속_선언_자체가_관리자_검수자_한_단계뿐이다")
    void roleDeclarationHasSingleEdge() {
        assertThat(Role.ADMIN.directlyInheritedRoles()).containsExactly(Role.REVIEWER);
        assertThat(Role.REVIEWER.directlyInheritedRoles()).isEmpty();
        assertThat(Role.WORKER.directlyInheritedRoles()).isEmpty();
        assertThat(Role.PORTAL_USER.directlyInheritedRoles()).isEmpty();
    }
}

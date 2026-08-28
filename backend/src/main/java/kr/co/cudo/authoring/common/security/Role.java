package kr.co.cudo.authoring.common.security;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 저작도구 인가 역할. 진실원은 {@code LS_USER_ROLE.ROLE_CD} 이며 인계 토큰의 role 클레임이 아니다.
 *
 * <p><b>선언 순서는 권한이 넓은 쪽부터</b>다 — {@code ADMIN → REVIEWER → WORKER → PORTAL_USER}.
 * 순서 자체가 인가를 결정하지는 않지만(판정은 {@link #INHERITS} 와 그것에서 파생된
 * {@link RoleHierarchy}·매처가 한다) 읽는 사람이 계층을 오해하지 않게 한다.
 *
 * <p><b>{@link #ADMIN} 신설</b> — 관리 권한이 {@link #REVIEWER} 에 통합돼 있던 것을 분리했다.
 * 관리자는 검수자 권한을 <b>계층으로 물려받으므로</b> 기존 검수자 권한 지점을 하나도 바꾸지 않는다.
 * 계층은 관리자 → 검수자 한 단계뿐이다.
 *
 * <h3>★ 계층의 단일 진실원은 {@link #INHERITS} 다</h3>
 * <p>계층은 두 축에서 쓰인다 — ① Spring Security 권한(authority) 검사({@link RoleHierarchy})
 * ② 서비스 안의 역할 판정({@link TokenClaims#hasRole(Role)}). 두 축이 계층을 <b>각자</b> 들고
 * 있으면 한쪽만 고쳤을 때 조용히 갈린다(이 저장소가 반복해서 겪은 「두 번째 진실원」 결함).
 * 그래서 상속 관계를 여기 한 곳에만 선언하고 {@link RoleHierarchy#HIERARCHY} 는 여기서
 * <b>파생</b>시킨다. 계층을 바꾸려면 {@link #INHERITS} 를 고치는 것이 유일한 방법이다.
 *
 * @design ADR-055
 * @design ROLE-004
 * @design AC-125
 */
public enum Role {
    ADMIN,
    REVIEWER,
    WORKER,
    PORTAL_USER;

    /**
     * <b>역할 상속 선언 — 계층의 단일 진실원.</b> 키가 값의 권한을 <b>직접</b> 물려받는다.
     *
     * <p>한 줄뿐인 것이 의도다. 넓히면 인가가 새어 나간다:
     * <ul>
     *   <li><b>{@link #WORKER} 를 값으로 넣지 않는다</b> — 작업자 <b>전용</b>으로 열린 자리가
     *       실재한다(검수 제출 등). 넣으면 관리자·검수자가 그 자리에 흘러든다.</li>
     *   <li><b>{@link #PORTAL_USER} 를 넣지 않는다</b> — 채널이 다르다. 넣으면 채널 격리를 역할
     *       축으로 우회하는 길이 생긴다. 채널 격리는 역할과 <b>별개 축</b>이라 계층으로 뚫려서는
     *       안 된다.</li>
     * </ul>
     *
     * <p>enum 상수는 생성자 인자로 서로를 참조할 수 없어(전방 참조 금지) 정적 맵으로 선언한다.
     */
    private static final Map<Role, Set<Role>> INHERITS = Map.of(ADMIN, Set.of(REVIEWER));

    /**
     * 이 역할이 <b>직접</b> 물려받는 역할들. 계층에 없으면 빈 집합.
     *
     * @return 직접 상위→하위 한 단계 (전이 확장 없음)
     */
    public Set<Role> directlyInheritedRoles() {
        return INHERITS.getOrDefault(this, Set.of());
    }

    /**
     * 이 역할이 물려받는 역할 전부(전이 폐포, <b>자기 자신 제외</b>).
     *
     * <p>현재 계층은 한 단계뿐이라 결과가 {@link #directlyInheritedRoles()} 와 같지만, 단계가
     * 늘어도 판정이 따라오도록 폐포로 계산한다. 순환이 선언돼도 방문 집합이 무한 루프를 막는다.
     *
     * @return 물려받는 역할 집합 (수정 불가)
     */
    public Set<Role> inheritedRoles() {
        Set<Role> reached = EnumSet.noneOf(Role.class);
        Deque<Role> pending = new ArrayDeque<>(directlyInheritedRoles());
        while (!pending.isEmpty()) {
            Role next = pending.poll();
            if (next == this || !reached.add(next)) {
                continue;
            }
            pending.addAll(next.directlyInheritedRoles());
        }
        return Collections.unmodifiableSet(reached);
    }

    /**
     * 이 역할이 {@code required} 역할에게 열린 자리에 들어갈 수 있는가.
     *
     * <p>「같거나 물려받는가」다 — {@code ADMIN.satisfies(REVIEWER)} 는 참이고
     * {@code ADMIN.satisfies(WORKER)} 는 거짓이다(작업자 전용 자리는 관리자에게도 닫혀 있다).
     *
     * @param required 그 자리가 요구하는 역할. null 이면 거짓(fail-closed)
     * @return 자기 자신이거나 계층으로 물려받으면 참
     */
    public boolean satisfies(Role required) {
        if (required == null) {
            return false;
        }
        return required == this || inheritedRoles().contains(required);
    }
}

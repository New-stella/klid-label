package kr.co.cudo.authoring.common.security;

import java.time.Instant;

public record TokenClaims(String sub, Role role, Channel channel, Instant exp) {

    /**
     * null 안전 역할명. 역할 분리 Phase 3 이후 INTERNAL 사용자가 LS_USER_ROLE 미배정이면
     * {@link #role()} 이 null 일 수 있으므로 {@code role().name()} 직접 호출의 NPE 를 막는다.
     *
     * @return 역할 enum 이름, 미배정이면 null
     */
    public String roleName() {
        return role == null ? null : role.name();
    }

    /**
     * <b>계층을 반영한 역할 판정 — 서비스 안에서 역할 enum 을 그대로 동등 비교하던 자리를
     * 대체한다.</b>
     *
     * <p>⚠ 이 javadoc 은 대체 대상이던 비교식을 <b>코드 조각으로 인용하지 않는다</b>. 잔여
     * 동등 비교를 훑는 검사는 주석과 코드를 구분하지 않아, 금지 형태를 주석에 그대로 적으면
     * 이 파일이 그 검사의 위양성이 된다(실제로 한 번 그렇게 잡혔다).
     *
     * <p>Spring 의 {@code RoleHierarchy} 는 권한(authority) 축에만 걸린다. 서비스가 역할을 그대로
     * 동등 비교하면 관리자가 그 지점에서 떨어져, 관리 기능은 쓰되 목록 조회·라벨 접근·검수·배정·
     * 통계에서 거부되어 계층이 반쪽만 성립한다. 그 자리를 이 판정으로 바꾼다.
     *
     * <p>판정은 <b>「같거나 물려받는가」</b>다 — 계층의 진실원은 {@code Role} 의 상속 선언 하나이고
     * {@code RoleHierarchy} 도 같은 선언에서 파생되므로 두 축이 갈리지 않는다.
     * <ul>
     *   <li>{@code hasRole(ADMIN)} — 관리자만 참</li>
     *   <li>{@code hasRole(REVIEWER)} — 관리자·검수자 참 (창구의 「검수자 전용」은 「검수자 이상」)</li>
     *   <li>{@code hasRole(WORKER)} — <b>작업자만</b> 참. 관리자·검수자는 거짓이다 — 작업자 전용
     *       자리가 실재하며 계층은 그 자리를 열지 않는다</li>
     * </ul>
     *
     * <p><b>fail-closed</b> — 역할 미배정(role == null)이거나 요구 역할이 null 이면 거짓이다.
     *
     * <p>⚠ 이 판정을 <b>원본(비-비식별) 프레임 이미지 열람</b>에는 쓰지 않는다. 개인정보 열람은
     * 역할 계층과 별개 축이라 관리자가 물려받지 않는 유일한 예외다.
     *
     * @param required 그 자리가 요구하는 역할
     * @return 요구 역할이거나 계층으로 물려받으면 참
     * @design ADR-055
     * @design ROLE-004
     * @design AC-125
     */
    public boolean hasRole(Role required) {
        return role != null && role.satisfies(required);
    }

    /**
     * {@link #hasRole(Role)} 의 <b>actor null 안전</b> 진입점.
     *
     * <p>인증 컨텍스트가 없는 호출부(actor 가 null 일 수 있는 자리)가 실재한다. 그 자리마다
     * {@code actor != null && ...} 를 흩어 놓으면 검사가 또 갈리므로 여기 한 곳에서 처리한다.
     * 판정은 {@link #hasRole(Role)} 에 그대로 위임하며 <b>복제하지 않는다</b>.
     *
     * @param actor    인증 주체. null 이면 거짓(fail-closed)
     * @param required 그 자리가 요구하는 역할
     * @return actor 가 있고 요구 역할 이상이면 참
     * @design ADR-055
     * @design AC-125
     */
    public static boolean hasRole(TokenClaims actor, Role required) {
        return actor != null && actor.hasRole(required);
    }
}

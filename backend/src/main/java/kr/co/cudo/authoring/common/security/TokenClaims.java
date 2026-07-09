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
}

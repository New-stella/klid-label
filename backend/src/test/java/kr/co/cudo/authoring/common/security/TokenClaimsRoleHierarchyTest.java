package kr.co.cudo.authoring.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계층을 반영한 역할 판정({@link TokenClaims#hasRole}) 을 고정한다
 * (@design ADR-055 · @design ROLE-004 · @design AC-125).
 *
 * <h3>왜 이 시험이 필요한가</h3>
 * <p>Spring 의 {@code RoleHierarchy} 는 권한(authority) 축에만 걸린다. 서비스 안의
 * {@code actor.role() == Role.REVIEWER} 는 계층 밖이라 관리자가 그대로 떨어진다. 이 판정이
 * 계층을 반영하지 않으면(동등 비교로 되돌리면) 아래 「관리자는 검수자 자리에 들어간다」 계열이
 * 즉시 RED 가 된다 — 그것이 이 시험이 지키는 것이다.
 *
 * <p>동시에 계층이 <b>넓어지는</b> 되돌림도 잡는다. 작업자 전용 자리와 포털 회원 자리는 계층이
 * 열지 않아야 하므로, 거기에 관리자·검수자가 흘러들면 「경계」 계열이 RED 가 된다.
 */
class TokenClaimsRoleHierarchyTest {

    private static TokenClaims actor(Role role) {
        return new TokenClaims("1", role, Channel.INTERNAL, Instant.now());
    }

    @Nested
    @DisplayName("관리자는 검수자에게 열린 자리에 그대로 들어간다")
    class AdminInheritsReviewer {

        @Test
        @DisplayName("★관리자는_검수자_이상_판정에_참이다_계층_반영_실증")
        void adminSatisfiesReviewer() {
            // 판정을 동등 비교(role == required)로 되돌리면 여기가 RED 다.
            assertThat(actor(Role.ADMIN).hasRole(Role.REVIEWER)).isTrue();
        }

        @Test
        @DisplayName("관리자는_관리자_자리에도_당연히_참이다")
        void adminSatisfiesAdmin() {
            assertThat(actor(Role.ADMIN).hasRole(Role.ADMIN)).isTrue();
        }

        @Test
        @DisplayName("검수자는_관리자_자리에_들어가지_못한다_계층은_한_방향이다")
        void reviewerDoesNotSatisfyAdmin() {
            assertThat(actor(Role.REVIEWER).hasRole(Role.ADMIN)).isFalse();
        }

        @Test
        @DisplayName("검수자는_검수자_자리에_참이다")
        void reviewerSatisfiesReviewer() {
            assertThat(actor(Role.REVIEWER).hasRole(Role.REVIEWER)).isTrue();
        }
    }

    @Nested
    @DisplayName("계층 경계 — 작업자 전용 자리와 포털 회원은 계층이 열지 않는다")
    class HierarchyBoundary {

        @Test
        @DisplayName("★작업자_전용_자리에_관리자와_검수자는_거짓이다")
        void workerOnlySeatStaysClosed() {
            // 계층에 WORKER 를 끼우면 여기가 RED — 작업자 전용으로 열린 자리(검수 제출 등)에
            // 관리자·검수자가 흘러드는 것을 막는다.
            assertThat(actor(Role.ADMIN).hasRole(Role.WORKER)).isFalse();
            assertThat(actor(Role.REVIEWER).hasRole(Role.WORKER)).isFalse();
        }

        @Test
        @DisplayName("작업자는_작업자_자리에만_참이고_상위_자리엔_거짓이다")
        void workerSatisfiesOnlyItself() {
            TokenClaims worker = actor(Role.WORKER);
            assertThat(worker.hasRole(Role.WORKER)).isTrue();
            assertThat(worker.hasRole(Role.REVIEWER)).isFalse();
            assertThat(worker.hasRole(Role.ADMIN)).isFalse();
        }

        @Test
        @DisplayName("★포털회원은_어느_내부_역할_자리에도_참이_되지_않는다_채널_격리_우회_금지")
        void portalUserNeverReachesInternalRoles() {
            TokenClaims portal = actor(Role.PORTAL_USER);
            assertThat(portal.hasRole(Role.ADMIN)).isFalse();
            assertThat(portal.hasRole(Role.REVIEWER)).isFalse();
            assertThat(portal.hasRole(Role.WORKER)).isFalse();
            assertThat(portal.hasRole(Role.PORTAL_USER)).isTrue();
        }

        @Test
        @DisplayName("내부_역할은_포털회원_자리에_들어가지_못한다")
        void internalRolesDoNotReachPortalSeat() {
            assertThat(actor(Role.ADMIN).hasRole(Role.PORTAL_USER)).isFalse();
            assertThat(actor(Role.REVIEWER).hasRole(Role.PORTAL_USER)).isFalse();
            assertThat(actor(Role.WORKER).hasRole(Role.PORTAL_USER)).isFalse();
        }
    }

    @Nested
    @DisplayName("fail-closed — 없는 값은 거부한다")
    class FailClosed {

        @Test
        @DisplayName("역할_미배정_사용자는_어느_자리에도_거짓이다")
        void nullRoleIsDenied() {
            // 역할 미배정 INTERNAL 사용자가 실재한다(자동 등록 이전 구간).
            TokenClaims noRole = actor(null);
            assertThat(noRole.hasRole(Role.ADMIN)).isFalse();
            assertThat(noRole.hasRole(Role.REVIEWER)).isFalse();
            assertThat(noRole.hasRole(Role.WORKER)).isFalse();
            assertThat(noRole.hasRole(Role.PORTAL_USER)).isFalse();
        }

        @Test
        @DisplayName("요구_역할이_null이면_거짓이다")
        void nullRequiredIsDenied() {
            assertThat(actor(Role.ADMIN).hasRole(null)).isFalse();
        }

        @Test
        @DisplayName("★actor가_null이면_정적_진입점이_거짓을_돌려준다_호출부_null검사_통일")
        void nullActorIsDenied() {
            assertThat(TokenClaims.hasRole(null, Role.REVIEWER)).isFalse();
            assertThat(TokenClaims.hasRole(null, Role.WORKER)).isFalse();
            assertThat(TokenClaims.hasRole(null, null)).isFalse();
        }

        @Test
        @DisplayName("정적_진입점은_인스턴스_판정을_그대로_위임한다_판정_복제_금지")
        void staticEntryPointDelegates() {
            for (Role actorRole : Role.values()) {
                for (Role required : Role.values()) {
                    TokenClaims claims = actor(actorRole);
                    assertThat(TokenClaims.hasRole(claims, required))
                            .as("actor=%s required=%s", actorRole, required)
                            .isEqualTo(claims.hasRole(required));
                }
            }
        }
    }

    @Nested
    @DisplayName("Role 판정기 자체")
    class RoleSatisfies {

        @Test
        @DisplayName("관리자의_물려받는_역할은_검수자_하나뿐이고_자기자신은_포함하지_않는다")
        void adminInheritsReviewerOnly() {
            assertThat(Role.ADMIN.inheritedRoles()).containsExactly(Role.REVIEWER);
            assertThat(Role.REVIEWER.inheritedRoles()).isEmpty();
            assertThat(Role.WORKER.inheritedRoles()).isEmpty();
            assertThat(Role.PORTAL_USER.inheritedRoles()).isEmpty();
        }

        @Test
        @DisplayName("satisfies는_자기자신에_항상_참이다")
        void satisfiesSelf() {
            for (Role role : Role.values()) {
                assertThat(role.satisfies(role)).as("%s", role).isTrue();
            }
        }
    }
}

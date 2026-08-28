package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.common.security.UserRoleResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 이관 도메인 시험 전용 <b>관리자 배우</b> — 관리자 역할 행을 심고 반드시 지운다.
 *
 * <h3>★ 공용 시드에 관리자를 넣지 않는 이유</h3>
 * <p>시드에 넣으면 시스템에 관리자가 <b>항상</b> 있는 셈이 되어 관리자 부트스트랩 창구(관리자가
 * 0명일 때만 열린다)가 영구히 닫히고, 그 창구를 검증하는 시험들이 통째로 깨진다. 그래서 필요한
 * 시험 클래스가 {@code @BeforeEach} 에서 직접 심고 {@code @AfterEach} 에서 지운다.
 *
 * <h3>역할 캐시 무효화가 세트다</h3>
 * <p>역할 판정은 인계 토큰의 role 클레임이 아니라 저작도구가 소유한 역할 저장소를 읽으며, 그 조회는
 * 캐시를 탄다. 행만 넣고 캐시를 비우지 않으면 앞선 시험이 채운 "역할 없음" 스냅샷이 살아남아
 * 관리자 요청이 조용히 거부된다. 지울 때도 같은 이유로 비운다 — 남으면 다음 시험이 있지도 않은
 * 관리자로 통과한다.
 *
 * <h3>사용자번호는 클래스마다 다르게 준다</h3>
 * <p>같은 번호를 여러 클래스가 쓰면 한쪽의 {@code @AfterEach} 가 다른 쪽이 쓰는 중인 행을 지운다.
 *
 * @design ADR-055
 * @design ROLE-004
 */
final class ImportAdminActor {

    private final JdbcTemplate jdbc;
    private final UserRoleResolver roleResolver;
    private final long userNo;

    ImportAdminActor(JdbcTemplate jdbc, UserRoleResolver roleResolver, long userNo) {
        this.jdbc = jdbc;
        this.roleResolver = roleResolver;
        this.userNo = userNo;
    }

    long userNo() {
        return userNo;
    }

    /** 관리자 역할 행을 심는다. 이미 있으면 지우고 다시 넣어 재실행이 안전하다. */
    void grant() {
        clear();
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) "
                + "VALUES (?, 'ADMIN', CURRENT_TIMESTAMP)", userNo);
        roleResolver.evict(userNo);
    }

    /** 심은 것을 되돌린다. 진입 시 자동 등록이 만들 수 있는 사용자 행까지 함께 지운다. */
    void clear() {
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
        roleResolver.evict(userNo);
    }
}

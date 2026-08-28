package kr.co.cudo.authoring.auth;

import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부트스트랩 창구 시험의 <b>전제를 명시적으로 세우는</b> 픽스처 — "시스템에 관리자가 0명이다".
 *
 * <h3>왜 필요한가 (실측 사고)</h3>
 * <p>{@code RoleClaimService} 의 부트스트랩 게이트는 {@code countByRoleCd("ADMIN") > 0} 이라
 * <b>전역 카운트</b>다 — 행의 소유자를 가리지 않는다. 그런데 시험 컨테이너는 싱글톤 PostgreSQL 이고
 * 워커 JVM 하나가 전 시험을 공유한다({@code forkEvery} 없음). 그래서 <b>다른 시험 클래스가 남긴
 * 관리자 행 하나</b>가 이 클래스의 전제를 통째로 무너뜨린다.
 *
 * <p>실제로 그렇게 됐다 — {@code DevSeedRunnerTest} 가 {@code authoring.dev.seed.enabled=true} 로
 * dev 시드를 공유 DB 에 적재하는데, 2026-08-28 에 그 시드에 관리자(9001)가 더해지면서 그 뒤에
 * 실행되는 부트스트랩 시험이 열린 창구 대신 <b>닫힌 창구</b>를 만났다(409 "이미 관리자가 있어…").
 *
 * <h3>왜 전역 DELETE 로 해결하지 않는가</h3>
 * <p>{@code DELETE FROM LS_USER_ROLE} 류는 공유 컨테이너의 다른 시험이 의존하는 역할 시드를 통째로
 * 지운다. 여기서는 <b>ADMIN 행만 스냅샷해 잠시 걷어내고 종료 시 원래대로 되돌린다</b> — 이 클래스가
 * 만든 상태가 아니라 <b>발견한 상태</b>를 복원하므로, 시드가 있든 없든 실행 순서가 어떻든 같다.
 *
 * <p>사용법: {@code @BeforeEach} 에서 {@link #park()}, {@code @AfterEach} 에서 {@link #restore()}.
 * 이 클래스의 시험이 스스로 만든 관리자 행(창이 닫힌 뒤를 재현하는 표본)은 각 시험의 cleanup 이
 * 지운 <b>다음</b>에 복원해야 한다.
 *
 * @design ADR-055
 * @design AC-127
 */
final class AdminBootstrapWindow {

    private final JdbcTemplate jdbc;
    private final UserRoleResolver userRoleResolver;
    /** 걷어낸 관리자 행 원본 — 복원 대상. 비어 있는 것이 정상이며(관리자 0명) 그때는 무동작이다. */
    private List<Map<String, Object>> parked = List.of();

    AdminBootstrapWindow(JdbcTemplate jdbc, UserRoleResolver userRoleResolver) {
        this.jdbc = jdbc;
        this.userRoleResolver = userRoleResolver;
    }

    /** 기존 관리자 행을 걷어내 창을 연다. 창이 실제로 열렸는지 <b>단언</b>한다. */
    void park() {
        parked = new ArrayList<>(jdbc.queryForList(
                "SELECT USER_NO, ROLE_CD, REG_DT, UPD_DT FROM LS_USER_ROLE WHERE ROLE_CD = ?",
                Role.ADMIN.name()));
        if (!parked.isEmpty()) {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE ROLE_CD = ?", Role.ADMIN.name());
            parked.forEach(row -> userRoleResolver.evict(userNoOf(row)));
        }
        // ★전제를 암묵으로 두지 않는다 — 깨졌을 때 "409 가 났다" 가 아니라 여기서 원인이 드러나야 한다.
        assertThat(adminCount())
                .as("부트스트랩 창구 시험의 전제 = 관리자 0명. 공유 컨테이너에 남은 ADMIN 행을 "
                        + "걷어내지 못했다 — 이 상태로는 창구가 닫혀 있어 시험이 무의미하다")
                .isZero();
    }

    /** 걷어냈던 관리자 행을 원래대로 되돌린다. 이 클래스가 만든 행을 지운 <b>뒤</b>에 부른다. */
    void restore() {
        for (Map<String, Object> row : parked) {
            jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT, UPD_DT)"
                            + " VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT (USER_NO) DO UPDATE SET ROLE_CD = EXCLUDED.ROLE_CD,"
                            + " REG_DT = EXCLUDED.REG_DT, UPD_DT = EXCLUDED.UPD_DT",
                    row.get("user_no"), row.get("role_cd"), row.get("reg_dt"), row.get("upd_dt"));
            userRoleResolver.evict(userNoOf(row));
        }
        parked = List.of();
    }

    long adminCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM LS_USER_ROLE WHERE ROLE_CD = ?",
                Long.class, Role.ADMIN.name());
        return n == null ? 0L : n;
    }

    private static long userNoOf(Map<String, Object> row) {
        return ((Number) row.get("user_no")).longValue();
    }
}

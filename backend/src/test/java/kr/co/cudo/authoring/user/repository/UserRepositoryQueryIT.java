package kr.co.cudo.authoring.user.repository;

import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.dto.WorkerWithTaskCount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 마스터 조회 경로 실동작 검증 (V169 이관 후, Testcontainers PostgreSQL).
 *
 * <h3>왜 필요한가</h3>
 * <p>이름 표시는 4개 경로({@code findByUserNo} · {@code findByUserNoIn} ·
 * {@code findAllWorkersWithTaskCount} · {@code searchByKeywordAndRole})로 갈라져 배정·검수·통계·관리 화면에
 * 각각 쓰인다. 테이블·컬럼 개명(특히 {@code USER_EMAIL} → {@code USER_EML_ADDR})에서 <b>한 경로만
 * 어긋나면</b> 그 화면만 이름이 비거나 500 이 나는데, 서비스 단위 테스트는 리포지토리를 목으로
 * 두므로 이를 잡지 못한다. 여기서 <b>같은 사용자를 네 경로로 조회해 이름이 동일함</b>을 고정한다.
 *
 * <p>{@code ddl-auto=validate} 가 이 프로젝트에서 실제로 동작하지 않아 엔티티↔DDL 불일치가 기동에서
 * 잡히지 않는다는 점도 이 테스트가 필요한 이유다.
 */
@SpringBootTest
@ActiveProfiles("local")
class UserRepositoryQueryIT {

    private static final long USER_NO = 969_200_001L;
    private static final String USER_ID = "namecheck1";
    private static final String USER_NM = "이름확인자";
    private static final String EMAIL = "namecheck1@example.com";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        jdbc.update("INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USER_EML_ADDR, USE_YN, REG_DT)"
                        + " VALUES (?, ?, ?, ?, 'Y', CURRENT_TIMESTAMP)",
                USER_NO, USER_ID, USER_NM, EMAIL);
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT)"
                + " VALUES (?, 'WORKER', CURRENT_TIMESTAMP)", USER_NO);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", USER_NO);
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", USER_NO);
    }

    @Test
    @DisplayName("사용자명_표시가_배정_검수_통계_관리_경로에서_동일하다")
    void 사용자명_표시가_배정_검수_통계_관리_경로에서_동일하다() {
        // ① 단건 lookup — 검수 이슈 작성자명·프로필
        LsAcntUser single = userRepository.findByUserNo(USER_NO).orElseThrow();
        assertThat(single.getUserNm()).isEqualTo(USER_NM);
        assertThat(single.getUserId()).isEqualTo(USER_ID);
        assertThat(single.getUserEmlAddr()).isEqualTo(EMAIL);

        // ② batch lookup — 배정·검수·통계·영상목록의 N+1 회피 경로
        List<LsAcntUser> batch = userRepository.findByUserNoIn(List.of(USER_NO));
        assertThat(batch).singleElement()
                .satisfies(u -> assertThat(u.getUserNm()).isEqualTo(USER_NM));

        // ③ 작업자 목록(+활성 태스크 수) — 배정 후보 화면
        assertThat(userRepository.findAllWorkersWithTaskCount())
                .filteredOn(w -> USER_NO == w.userNo())
                .singleElement()
                .satisfies(w -> {
                    assertThat(w.userNm()).isEqualTo(USER_NM);
                    assertThat(w.userId()).isEqualTo(USER_ID);
                    assertThat(w.userEmail()).as("이메일 컬럼 개명 후에도 같은 값이 실려야 한다")
                            .isEqualTo(EMAIL);
                    assertThat(w.activeTaskCount()).isNotNull();
                });

        // ④ 관리 화면 검색 — 이름/아이디/이메일 LIKE 가 모두 같은 행을 찾는다
        for (String keyword : List.of(USER_ID, USER_NM, EMAIL)) {
            assertThat(userRepository.searchByKeywordAndRole(keyword, null, PageRequest.of(0, 20)).getContent())
                    .as("키워드 %s 로 검색되어야 한다", keyword)
                    .extracting(LsAcntUser::getUserNo)
                    .contains(USER_NO);
        }
    }

    @Test
    @DisplayName("비활성_사용자는_작업자_목록에서_제외된다")
    void 비활성_사용자는_작업자_목록에서_제외된다() {
        // USE_YN 읽기 필터 회귀 — 자동등록이 이 값을 되살리지 않는다는 정책의 소비측이다.
        jdbc.update("UPDATE LS_ACNT_USER SET USE_YN = 'N' WHERE USER_NO = ?", USER_NO);

        assertThat(userRepository.findAllWorkersWithTaskCount())
                .extracting(WorkerWithTaskCount::userNo)
                .doesNotContain(USER_NO);
    }

    @Test
    @DisplayName("표시정보가_비어도_조회_경로가_깨지지_않는다")
    void 표시정보가_비어도_조회_경로가_깨지지_않는다() {
        // 자동등록 직후 상태 — 관제가 표시 정보를 안 보내면 userId/이메일은 null, 이름은 빈 문자열.
        //   ★이름이 null 이면 소비측 Collectors.toMap(userNo, userNm) 이 NPE 로 터진다.
        jdbc.update("UPDATE LS_ACNT_USER SET USER_ID = NULL, USER_EML_ADDR = NULL, USER_NM = DEFAULT"
                + " WHERE USER_NO = ?", USER_NO);

        LsAcntUser user = userRepository.findByUserNo(USER_NO).orElseThrow();
        assertThat(user.getUserNm()).as("NOT NULL DEFAULT '' — null 이 아니어야 한다").isEmpty();
        assertThat(user.getUserId()).isNull();

        assertThat(userRepository.findAllWorkersWithTaskCount())
                .filteredOn(w -> USER_NO == w.userNo())
                .singleElement()
                .satisfies(w -> assertThat(w.userNm()).isEmpty());

        // null 컬럼이 LIKE 에 걸리지 않을 뿐, 검색 자체는 예외 없이 동작한다
        assertThat(userRepository.searchByKeywordAndRole(USER_ID, null, PageRequest.of(0, 20)).getContent())
                .extracting(LsAcntUser::getUserNo)
                .doesNotContain(USER_NO);
    }
}

package kr.co.cudo.authoring.user;

import kr.co.cudo.authoring.user.dto.UserSummaryResponse;
import kr.co.cudo.authoring.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 관리 화면의 <b>역할 필터가 서버 전체 기준</b>임을 고정한다 (@design AC-1018, Testcontainers).
 *
 * <h3>★ 왜 2페이지 이상이어야 하는가 — 1페이지 fixture 로는 결함이 재현되지 않는다</h3>
 * <p>구 구현은 {@code searchByKeyword} 로 <b>한 페이지를 먼저 가져온 뒤</b> 그 안에서 역할을 걸렀다.
 * 그래서 한 페이지에 다 들어가는 데이터로는 <b>결과가 정확해 보인다</b> — 결함이 드러나는 조건은
 * "찾는 역할의 사용자가 1페이지 밖에 있을 때" 하나뿐이고, 기존 시험이 전부 1페이지짜리 fixture 라
 * 이 결함이 지금까지 초록으로 통과했다. 그래서 여기서는 <b>페이지 크기보다 많은</b> 사용자를 심고
 * 찾는 대상을 <b>맨 뒤</b>(등록순 정렬 기준)에 둔다.
 *
 * <p>구 동작에서 이 시험은 다음과 같이 깨진다:
 * <ul>
 *   <li>검수자 필터 → 1페이지(작업자 24명)에 없으므로 <b>0건</b>이 나온다(기대 1건)</li>
 *   <li>총건수 → 필터 이전 합계인 <b>26</b>이 나온다(기대 1)</li>
 * </ul>
 *
 * <h3>키워드로 모수를 좁히는 이유</h3>
 * <p>공용 시드에도 사용자·역할 행이 있어 역할만으로 세면 다른 시험의 데이터에 흔들린다. 이 시험
 * 전용 접두어를 검색어로 함께 걸어 <b>총건수를 결정적으로</b> 만든다.
 */
@SpringBootTest
@ActiveProfiles("local")
class UserRoleFilterPagingIT {

    /** 이 시험 전용 사용자번호 대역 — 공용 시드·다른 시험과 겹치지 않는다. */
    private static final long BASE_USER_NO = 969_700_000L;

    /** 이 시험 전용 검색 접두어 — 모수를 이 시험이 심은 행으로 좁힌다. */
    private static final String PREFIX = "rolefilterprobe";

    /** 작업자 24명 + 검수자 1명 + 미배정 1명 = 26명 (페이지 크기 20 보다 많다). */
    private static final int WORKER_COUNT = 24;
    private static final long REVIEWER_NO = BASE_USER_NO + 25;
    private static final long UNASSIGNED_NO = BASE_USER_NO + 26;
    private static final int TOTAL_COUNT = WORKER_COUNT + 2;
    private static final int PAGE_SIZE = 20;

    @Autowired private UserService userService;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    /** 컨트롤러가 고정하는 정렬(등록순)을 그대로 쓴다 — 화면이 이 순서를 전제한다. */
    private static Pageable page(int pageNo) {
        return PageRequest.of(pageNo, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "userNo"));
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();

        for (int i = 1; i <= WORKER_COUNT; i++) {
            insertUser(BASE_USER_NO + i, i);
            insertRole(BASE_USER_NO + i, "WORKER");
        }
        // 검수자는 맨 뒤(등록순) — 1페이지 밖에 있어야 이 시험이 의미를 갖는다.
        insertUser(REVIEWER_NO, 25);
        insertRole(REVIEWER_NO, "REVIEWER");
        // 역할 행이 없는 사용자 — 어떤 역할 필터에도 걸리지 않아야 한다.
        insertUser(UNASSIGNED_NO, 26);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void insertUser(long userNo, int seq) {
        jdbc.update("INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USER_EML_ADDR, USE_YN, REG_DT)"
                        + " VALUES (?, ?, ?, ?, 'Y', CURRENT_TIMESTAMP)",
                userNo, PREFIX + seq, "역할필터대상" + seq, PREFIX + seq + "@example.com");
    }

    private void insertRole(long userNo, String roleCd) {
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, ?, CURRENT_TIMESTAMP)",
                userNo, roleCd);
    }

    private void cleanup() {
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO > ? AND USER_NO <= ?",
                BASE_USER_NO, BASE_USER_NO + 100);
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO > ? AND USER_NO <= ?",
                BASE_USER_NO, BASE_USER_NO + 100);
    }

    @Test
    @DisplayName("★1페이지_밖의_해당_역할_사용자가_필터_1페이지에_나온다")
    void roleFilterReachesUsersBeyondFirstPage() {
        Page<UserSummaryResponse> result = userService.searchUsers(PREFIX, "REVIEWER", page(0));

        // 구 인메모리 필터에서는 1페이지(작업자 24명)에 검수자가 없어 0건이었다.
        assertThat(result.getContent())
                .as("등록순 맨 뒤의 검수자가 필터 결과 1페이지에 나와야 한다")
                .extracting(UserSummaryResponse::userNo)
                .containsExactly(REVIEWER_NO);
    }

    @Test
    @DisplayName("★필터_적용시_총건수가_실제_매칭_수와_같다")
    void totalElementsEqualsActualMatches() {
        Page<UserSummaryResponse> result = userService.searchUsers(PREFIX, "REVIEWER", page(0));

        // 구 구현은 「필터 후 목록 + 필터 이전 합계」라 26 이 나왔다.
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("★작업자_필터는_페이지를_넘겨_전건에_도달한다")
    void workerFilterPagesThroughAllMatches() {
        Page<UserSummaryResponse> first = userService.searchUsers(PREFIX, "WORKER", page(0));
        Page<UserSummaryResponse> second = userService.searchUsers(PREFIX, "WORKER", page(1));

        assertThat(first.getTotalElements()).isEqualTo(WORKER_COUNT);
        assertThat(first.getContent()).hasSize(PAGE_SIZE);
        assertThat(second.getContent()).hasSize(WORKER_COUNT - PAGE_SIZE);
        assertThat(first.getContent()).extracting(UserSummaryResponse::role)
                .containsOnly("WORKER");
        // 두 페이지를 합치면 등록순으로 전건이며 중복이 없다(EXISTS 라 JOIN 중복이 생기지 않는다).
        assertThat(first.getContent().stream().map(UserSummaryResponse::userNo).toList())
                .doesNotContainAnyElementsOf(
                        second.getContent().stream().map(UserSummaryResponse::userNo).toList());
        assertThat(first.getContent().get(0).userNo()).isEqualTo(BASE_USER_NO + 1);
    }

    @Test
    @DisplayName("role_미지정이면_전체가_등록순으로_반환된다 — 기본_동선_무변경")
    void noRoleFilterReturnsEveryoneInRegistrationOrder() {
        Page<UserSummaryResponse> result = userService.searchUsers(PREFIX, null, page(0));

        assertThat(result.getTotalElements()).isEqualTo(TOTAL_COUNT);
        assertThat(result.getContent()).hasSize(PAGE_SIZE);
        assertThat(result.getContent().get(0).userNo()).isEqualTo(BASE_USER_NO + 1);
        assertThat(result.getContent()).extracting(UserSummaryResponse::userNo).isSorted();

        // 빈 문자열도 「필터 없음」이다 — 화면이 '전체' 를 빈 값으로 보낼 수 있다.
        assertThat(userService.searchUsers(PREFIX, "", page(0)).getTotalElements())
                .isEqualTo(TOTAL_COUNT);
    }

    @Test
    @DisplayName("미배정_사용자는_어떤_역할_필터에도_걸리지_않는다")
    void unassignedUserMatchesNoRoleFilter() {
        for (String role : List.of("ADMIN", "REVIEWER", "WORKER", "PORTAL_USER")) {
            Page<UserSummaryResponse> filtered = userService.searchUsers(PREFIX, role, page(0));
            assertThat(filtered.getContent())
                    .as("역할 %s 필터에 미배정 사용자가 섞이면 안 된다", role)
                    .extracting(UserSummaryResponse::userNo)
                    .doesNotContain(UNASSIGNED_NO);
        }
        // 필터가 없을 때는 미배정으로 보인다 — 목록에서 사라지는 것이 아니다.
        Page<UserSummaryResponse> all = userService.searchUsers(PREFIX, null, page(1));
        assertThat(all.getContent())
                .filteredOn(u -> UNASSIGNED_NO == u.userNo())
                .singleElement()
                .satisfies(u -> assertThat(u.role()).isNull());
    }

    @Test
    @DisplayName("해당_역할이_한_명도_없으면_빈_결과에_총건수_0이다")
    void emptyResultHasZeroTotal() {
        Page<UserSummaryResponse> result = userService.searchUsers(PREFIX, "PORTAL_USER", page(0));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }
}

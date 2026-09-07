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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 관리 검색의 <b>매칭 축은 로그인ID·이름 둘뿐</b>이다 — 이메일은 축이 아니다
 * (@design API-001, Testcontainers).
 *
 * <h3>왜 이메일을 뺐는가</h3>
 * <p>상위 시스템이 인계하는 토큰에 이메일이 실려 오지 않아 그 경로로 진입한 사용자는 이메일이
 * <b>영구히 비어 있고</b>, 목록도 이메일 대신 로그인 식별자를 보여준다. 그래서 이메일 매칭은
 * <b>화면이 안내하지 않는 숨은 검색 축</b>이 된다 — 검색 결과가 화면과 어긋나 보인다.
 *
 * <p>⚠ <b>바뀐 것은 매칭 축뿐이다.</b> {@code USER_EML_ADDR} 컬럼과 응답의 이메일 필드는 이미
 * 적재된 값을 표현하기 위해 <b>존치</b>한다 — 아래 마지막 시험이 그 존치를 고정한다. 둘을 함께
 * 지우면 기존 데이터를 표현할 수단이 사라진다.
 *
 * <h3>★ 표본이 이 시험의 절반이다</h3>
 * <p>이메일 지역부(local-part)가 <b>로그인ID·이름 어디에도 나타나지 않는</b> 값이어야 한다.
 * 겹치면 다른 축이 대신 매칭해 "이메일로도 찾힌다" 가 통과해 버려 축소가 검증되지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
class UserSearchKeywordAxisIT {

    /** 이 시험 전용 사용자번호 대역 — 공용 시드·다른 시험과 겹치지 않는다. */
    private static final long BASE_USER_NO = 969_830_000L;
    private static final long USER_NO = BASE_USER_NO + 1;

    private static final String LOGIN_ID = "kwaxisloginid";
    private static final String USER_NM = "키워드축표본";
    /** ★로그인ID·이름 어느 쪽의 부분문자열도 아니다 — 겹치면 이 시험이 무의미해진다. */
    private static final String EMAIL_LOCAL_PART = "zzmailonlyzz";
    private static final String EMAIL = EMAIL_LOCAL_PART + "@example.com";

    @Autowired private UserService userService;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    private static Pageable page() {
        return PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "userNo"));
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        jdbc.update("INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USER_EML_ADDR, USE_YN, REG_DT)"
                        + " VALUES (?, ?, ?, ?, 'Y', CURRENT_TIMESTAMP)",
                USER_NO, LOGIN_ID, USER_NM, EMAIL);
        // 표본 전제 — 이메일 지역부가 다른 두 축에 나타나지 않는다.
        assertThat(LOGIN_ID).doesNotContain(EMAIL_LOCAL_PART);
        assertThat(USER_NM).doesNotContain(EMAIL_LOCAL_PART);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO > ? AND USER_NO <= ?",
                BASE_USER_NO, BASE_USER_NO + 100);
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO > ? AND USER_NO <= ?",
                BASE_USER_NO, BASE_USER_NO + 100);
    }

    @Test
    @DisplayName("★이메일로는_더_이상_매칭되지_않는다_숨은_검색축_제거")
    void emailIsNoLongerAMatchingAxis() {
        Page<UserSummaryResponse> byEmail = userService.searchUsers(EMAIL_LOCAL_PART, null, page());

        assertThat(byEmail.getContent())
                .as("이메일 축을 되살리면 여기서 표본이 잡혀 RED 다")
                .isEmpty();
        assertThat(byEmail.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("로그인ID로는_매칭된다")
    void loginIdStillMatches() {
        assertThat(userService.searchUsers(LOGIN_ID, null, page()).getContent())
                .extracting(UserSummaryResponse::userNo)
                .containsExactly(USER_NO);
    }

    @Test
    @DisplayName("이름으로는_매칭된다")
    void userNameStillMatches() {
        assertThat(userService.searchUsers(USER_NM, null, page()).getContent())
                .extracting(UserSummaryResponse::userNo)
                .containsExactly(USER_NO);
    }

    @Test
    @DisplayName("★응답의_이메일_필드는_존치한다_매칭축만_좁혔지_값을_지우지_않았다")
    void emailFieldRemainsInResponse() {
        UserSummaryResponse row = userService.searchUsers(LOGIN_ID, null, page())
                .getContent().get(0);

        assertThat(row.email()).isEqualTo(EMAIL);
        assertThat(row.userEmail()).isEqualTo(EMAIL);
    }
}

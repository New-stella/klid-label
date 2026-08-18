package kr.co.cudo.authoring.user;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.StubControllers;
import kr.co.cudo.authoring.user.service.LastLoginRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 최종로그인일시({@code LS_ACNT_USER.LAST_LGN_DT}) 기록 통합 검증.
 *
 * <p>세 축을 실제 DB·실제 필터로 고정한다.
 * <ol>
 *   <li><b>스키마</b> — V12 가 표준 물리명·타입·nullable 로 컬럼을 만들었는가</li>
 *   <li><b>throttle</b> — 조건부 UPDATE 의 WHERE 절이 실제로 매 요청 쓰기를 막는가
 *       (단위 테스트는 창 계산만 볼 수 있고 판정은 SQL 이 한다)</li>
 *   <li><b>배선</b> — JWT 필터를 통과한 INTERNAL 요청이 실제로 기록을 남기는가</li>
 * </ol>
 *
 * @design SCREEN-024
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class LastLoginRecordIT {

    /** 다른 픽스처와 겹치지 않는 전용 사용자번호 — {@code test-data.sql} 이 재삽입하는 번호를 피한다. */
    private static final long USER_NO = 990911L;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    private LastLoginRecorder recorder;

    @Autowired
    private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        // 전용 사용자 1명 — 최종로그인일시는 아직 없다(한 번도 접속하지 않은 계정).
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", USER_NO);
        jdbc.update("""
                INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USER_EML_ADDR, USE_YN, REG_DT)
                VALUES (?, 'lastlgn', '최종로그인테스트', 'lastlgn@example.com', 'Y', CURRENT_TIMESTAMP)
                """, USER_NO);
        // 인가 역할 — 없으면 보호 엔드포인트가 fail-closed 403 이 되어 필터 배선 검증이 불가능하다.
        jdbc.update("""
                INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT)
                VALUES (?, 'REVIEWER', CURRENT_TIMESTAMP)
                ON CONFLICT (USER_NO) DO UPDATE SET ROLE_CD = 'REVIEWER'
                """, USER_NO);
    }

    @Test
    @DisplayName("최종로그인일시_컬럼이_표준_물리명과_타입_nullable_로_존재한다")
    void 최종로그인일시_컬럼이_표준_물리명과_타입_nullable_로_존재한다() {
        // when — 대상 스키마는 커넥션 search_path 가 정한다('public' 리터럴 금지).
        Map<String, Object> col = jdbc.queryForMap("""
                SELECT data_type, is_nullable
                  FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name  = 'ls_acnt_user'
                   AND column_name = 'last_lgn_dt'
                """);

        // then — 행안부 공통표준용어 최종로그인일시(LAST_LGN_DT) / 표준도메인 연월일시분초D.
        assertThat(col.get("data_type"))
                .as("레포의 다른 _dt 컬럼과 동일한 타입이어야 한다")
                .isEqualTo("timestamp without time zone");
        assertThat(col.get("is_nullable"))
                .as("한 번도 접속하지 않은 계정은 값이 없는 것이 사실이다 — 기본값을 넣으면 접속을 날조한다")
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("최종로그인일시가_비어있는_계정은_최초_요청에_기록된다")
    void 최종로그인일시가_비어있는_계정은_최초_요청에_기록된다() {
        // given
        assertThat(readLastLoginAt()).isNull();

        // when
        recorder.record(USER_NO);

        // then
        assertThat(readLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("임계시간_이내_재요청은_다시_기록하지_않는다")
    void 임계시간_이내_재요청은_다시_기록하지_않는다() {
        // given — 방금 기록된 상태(throttle 창 안).
        LocalDateTime justNow = LocalDateTime.now().minusSeconds(3);
        writeLastLoginAt(justNow);

        // when
        recorder.record(USER_NO);

        // then — 값이 그대로여야 한다(조건부 UPDATE 가 0행). 문자열이 아니라 값으로 비교한다.
        assertThat(readLastLoginAt())
                .as("throttle 창 안의 재요청이 UPDATE 를 일으키면 요청 수만큼 DB 쓰기가 발생한다")
                .isEqualTo(justNow);
    }

    @Test
    @DisplayName("임계시간이_지난_요청은_다시_기록한다")
    void 임계시간이_지난_요청은_다시_기록한다() {
        // given — 기본 throttle(5분)보다 오래 전에 기록된 상태.
        LocalDateTime stale = LocalDateTime.now().minusHours(2);
        writeLastLoginAt(stale);

        // when
        recorder.record(USER_NO);

        // then
        assertThat(readLastLoginAt()).isAfter(stale);
    }

    @Test
    @DisplayName("유효한_JWT_INTERNAL_요청이_최종로그인일시를_기록한다")
    void 유효한_JWT_INTERNAL_요청이_최종로그인일시를_기록한다() throws Exception {
        // given
        assertThat(readLastLoginAt()).isNull();

        // when
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + internalToken()))
                .andExpect(status().isOk());

        // then — 기록 지점이 실제로 필터에 배선돼 있다.
        assertThat(readLastLoginAt()).isNotNull();
    }

    private LocalDateTime readLastLoginAt() {
        Timestamp ts = jdbc.queryForObject(
                "SELECT LAST_LGN_DT FROM LS_ACNT_USER WHERE USER_NO = ?", Timestamp.class, USER_NO);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private void writeLastLoginAt(LocalDateTime at) {
        jdbc.update("UPDATE LS_ACNT_USER SET LAST_LGN_DT = ? WHERE USER_NO = ?",
                Timestamp.valueOf(at), USER_NO);
    }

    private String internalToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(USER_NO))
                .issuer(issuer)
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key())
                .compact();
    }

    private SecretKey key() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            return Keys.hmacShaKeyFor(padded);
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}

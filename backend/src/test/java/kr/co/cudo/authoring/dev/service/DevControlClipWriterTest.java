package kr.co.cudo.authoring.dev.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DevControlClipWriter} 단위 테스트 — 관제 두 테이블 네이티브 INSERT (Phase 3).
 *
 * <p>검증 축:
 * <ul>
 *   <li>MNG_CLIP_MASTER(16컬럼) + MNG_CLIP_EVNT_LST(12컬럼) 두 INSERT 가 모두 실행된다.</li>
 *   <li><b>CWE-89</b> — SQL 은 정적 상수이고 값은 전부 {@code ?} 파라미터 바인딩이다(문자열 결합 0건).</li>
 *   <li>영상 길이는 관제 실측 단위인 <b>ms</b> 로 바인딩된다(적재가 ÷1000 으로 초 복원).</li>
 *   <li>복합 PK 충돌은 <b>어느 테이블인지 구분된</b> 409 메시지로 변환된다.</li>
 * </ul>
 *
 * <p>Mockito 의 varargs 매처 모호성을 피하기 위해 {@link JdbcTemplate} 을 mock 하지 않고
 * {@link RecordingJdbcTemplate} 로 실제 호출을 기록한다 — SQL 본문과 바인딩 값을 그대로 검증할 수 있다.
 */
class DevControlClipWriterTest {

    /** {@code update(String, Object...)} 호출을 기록하는 테스트용 JdbcTemplate. */
    private static class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> sqls = new ArrayList<>();
        private final List<Object[]> argsList = new ArrayList<>();
        /** 1-based 호출 순번 → 이 순번에서 제약 위반을 던진다 (0 이면 던지지 않음). */
        private final int failAtCall;

        RecordingJdbcTemplate(int failAtCall) {
            this.failAtCall = failAtCall;
        }

        @Override
        public int update(String sql, Object... args) {
            sqls.add(sql);
            argsList.add(args);
            if (failAtCall > 0 && sqls.size() == failAtCall) {
                throw new DuplicateKeyException("duplicate key value violates unique constraint");
            }
            return 1;
        }
    }

    private DevControlClipWriter.ControlClipRow row() {
        return new DevControlClipWriter.ControlClipRow(
                "DEV-evnt-1", "ORIGINAL", "CLP-abc123", "1168000000",
                "sample.mp4", "/nas/data/upload/v2/CLP-abc123.mp4", "mp4",
                137_000, "mediainfo_complete",
                LocalDateTime.of(2024, 5, 1, 12, 0), LocalDateTime.of(2024, 5, 1, 12, 1),
                "Y", "CCTV-001", 4L, "N", 0,
                "EV02000201", LocalDateTime.of(2024, 5, 1, 11, 0), "쓰러짐",
                null, null, "ANONY", null, null, null);
    }

    @Test
    @DisplayName("업로드하면_관제_두_테이블에_행이_생긴다")
    void insertsIntoBothControlTables() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(0);

        new DevControlClipWriter(jdbc).insertClip(row());

        assertThat(jdbc.sqls).hasSize(2);
        assertThat(jdbc.sqls.get(0)).contains("INSERT INTO MNG_CLIP_MASTER");
        assertThat(jdbc.sqls.get(1)).contains("INSERT INTO MNG_CLIP_EVNT_LST");
        // 16컬럼 / 12컬럼 전량 바인딩
        assertThat(jdbc.argsList.get(0)).hasSize(16);
        assertThat(jdbc.argsList.get(1)).hasSize(12);
    }

    @Test
    @DisplayName("관제_INSERT_는_SQL_문자열_결합_없이_파라미터_바인딩만_사용한다")
    void usesParameterBindingOnly() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(0);
        DevControlClipWriter.ControlClipRow malicious = new DevControlClipWriter.ControlClipRow(
                "DEV-evnt-1", "ORIGINAL", "CLP-abc123", "1168000000",
                "'; DROP TABLE MNG_CLIP_MASTER; --", "/nas/x.mp4", "mp4",
                1000, "mediainfo_complete", LocalDateTime.now(), LocalDateTime.now(),
                "Y", "CCTV-001", 4L, "N", 0,
                "EV02000201", LocalDateTime.now(), "' OR 1=1 --",
                null, null, "ANONY", null, null, null);

        new DevControlClipWriter(jdbc).insertClip(malicious);

        // 악성 문자열이 SQL 본문에 섞이지 않고 바인딩 파라미터로만 전달된다 (CWE-89).
        for (String sql : jdbc.sqls) {
            assertThat(sql).doesNotContain("DROP TABLE").doesNotContain("OR 1=1");
            // 정적 SQL — 값 자리는 전부 '?'
            assertThat(sql).contains("?");
        }
        assertThat(jdbc.argsList.get(0)).contains("'; DROP TABLE MNG_CLIP_MASTER; --");
        assertThat(jdbc.argsList.get(1)).contains("' OR 1=1 --");
    }

    @Test
    @DisplayName("영상길이는_ms_로_관제에_바인딩된다")
    void bindsVideoLengthAsMillis() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(0);

        new DevControlClipWriter(jdbc).insertClip(row());

        // MNG_CLIP_MASTER 8번째 컬럼 = VDO_LEN_SEC (실측 단위 ms)
        assertThat(jdbc.argsList.get(0)[7]).isEqualTo(137_000);
    }

    @Test
    @DisplayName("이벤트리스트_INSERT_의_WTHR_CD_는_항상_null_이다")
    void neverWritesWeatherCode() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(0);

        new DevControlClipWriter(jdbc).insertClip(row());

        // MNG_CLIP_EVNT_LST 7번째 컬럼 = WTHR_CD — 날씨는 관제에서 받지 않는다(2026-07-31 확정).
        assertThat(jdbc.argsList.get(1)[6]).isNull();
    }

    @Test
    @DisplayName("LocalDateTime_은_Timestamp_로_바인딩된다")
    void bindsTimestamps() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(0);

        new DevControlClipWriter(jdbc).insertClip(row());

        assertThat(jdbc.argsList.get(0)[9])
                .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2024, 5, 1, 12, 0)));
        assertThat(jdbc.argsList.get(1)[2])
                .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2024, 5, 1, 11, 0)));
    }

    @Test
    @DisplayName("같은_evntId와_clipTypeCd_재업로드시_409를_반환한다")
    void masterPkConflictReturns409() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(1);

        assertThatThrownBy(() -> new DevControlClipWriter(jdbc).insertClip(row()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        // PostgreSQL 은 제약 위반 시 트랜잭션 전체를 abort 하므로 같은 tx 에서 이어 쿼리하지 않는다.
        assertThat(jdbc.sqls).hasSize(1);
    }

    @Test
    @DisplayName("충돌_메시지는_마스터와_이벤트리스트가_서로_다르다")
    void conflictMessagesDiffer() {
        String masterMessage = catchMessage(new DevControlClipWriter(new RecordingJdbcTemplate(1)));
        String evntMessage = catchMessage(new DevControlClipWriter(new RecordingJdbcTemplate(2)));

        assertThat(masterMessage).contains("MNG_CLIP_MASTER");
        assertThat(evntMessage).contains("MNG_CLIP_EVNT_LST");
        assertThat(masterMessage).isNotEqualTo(evntMessage);
    }

    private String catchMessage(DevControlClipWriter target) {
        try {
            target.insertClip(row());
            return "";
        } catch (CustomException e) {
            return e.getMessage();
        }
    }
}

package kr.co.cudo.authoring.dataset.view;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * blocker#2 (V104) — <b>V_COMPLETED_FRAME 프레임 설명(DESCRIPTION) 노출 회귀/기능 통합 테스트</b>.
 *
 * <p>V104 마이그레이션으로 재정의된 View 를 실 DB(PostgreSQL Testcontainer) SELECT 로 검증한다.
 * <ol>
 *   <li>V61 기준 기존 출력 8컬럼(계약)이 재정의 후에도 동일 이름으로 SELECT 가능
 *       (관제 소비자 쿼리 회귀 0).</li>
 *   <li>Phase 1(LS_DATA_SRC.FRM_EXPLN) 값이 DESCRIPTION 으로 노출.</li>
 *   <li>APPROVED 아닌 영상의 프레임은 미노출(WHERE 게이트 보존).</li>
 * </ol>
 *
 * <p>컨테이너는 {@code PostgresContainerContextCustomizerFactory} 가 자동 주입한다.
 * 시드는 공유 컨테이너 오염 방지를 위해 고유 RAW_SN 으로 넣고 {@link #cleanup()} 에서 제거한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class CompletedFrameDescriptionIT {

    /** V61 기준 V_COMPLETED_FRAME 의 기존 출력 컬럼(계약) — 이름·순서 보존 대상. */
    private static final List<String> LEGACY_FRAME_COLUMNS = List.of(
            "SRC_SN", "RAW_SN", "FRAME_NO", "ORIGINAL_PATH", "DEIDENTIFIED_PATH",
            "CAPTURED_AT", "REG_DT", "UPD_DT");

    private final JdbcTemplate jdbc;
    private final List<Long> seededRawSns = new ArrayList<>();

    CompletedFrameDescriptionIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
    }

    /** LS_DATA_RAW + LS_RAW_DATA_STATUS 라이브 소스 시드(상태 코드 파라미터화). */
    private long seedRawAndStatus(String reviewStts) {
        long nano = System.nanoTime();
        Long rawSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_RAW (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                        + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
                        + "DATA_STTS_CD, REG_DT) "
                        + "VALUES (?, ?, 'EVT01', '1111000000', 'PRVC', 'Y', 'Y', ?, ?, 30, 'COMPLETED', ?) "
                        + "RETURNING RAW_SN",
                Long.class,
                "CLIP-" + nano, "CCTV-" + nano, "/nas/raw/" + nano + ".mp4",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
        seededRawSns.add(rawSn);
        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                        + "VALUES (?, ?, ?, 1)",
                rawSn, reviewStts, LocalDateTime.now());
        return rawSn;
    }

    /** LS_DATA_SRC 프레임 1행 시드(설명 포함). */
    private long seedFrame(long rawSn, int frameNo, String description) {
        return jdbc.queryForObject(
                "INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM, "
                        + "DE_IDNTF_SRC_FILE_PATH_NM, SHT_DT, FRM_EXPLN, REG_DT) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING SRC_SN",
                Long.class,
                rawSn, frameNo, "/nas/frames/raw/" + rawSn + "/" + frameNo + ".jpg",
                "/nas/frames/deid/" + rawSn + "/" + frameNo + ".jpg",
                LocalDateTime.of(2026, 1, 15, 22, 0), description, LocalDateTime.now());
    }

    @Test
    @DisplayName("V_COMPLETED_FRAME_기존_8컬럼_보존")
    void completedFrame_preservesLegacyContractColumns() {
        // given — APPROVED 영상 + 프레임 1건
        long rawSn = seedRawAndStatus("APPROVED");
        long srcSn = seedFrame(rawSn, 10, "야간 교차로 보행자 3명");

        // when — 기존 8개 계약 컬럼을 명시적으로 SELECT (하나라도 없으면 SQL 실패 = 회귀)
        String cols = String.join(", ", LEGACY_FRAME_COLUMNS);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT " + cols + " FROM V_COMPLETED_FRAME WHERE SRC_SN = ?", srcSn);

        // then — 8개 컬럼 모두 존재 + 기존 매핑값 유지
        assertThat(row.keySet()).containsAll(
                LEGACY_FRAME_COLUMNS.stream().map(String::toLowerCase).toList());
        assertThat(((Number) row.get("src_sn")).longValue()).isEqualTo(srcSn);
        assertThat(((Number) row.get("raw_sn")).longValue()).isEqualTo(rawSn);
        assertThat(((Number) row.get("frame_no")).intValue()).isEqualTo(10);
        assertThat(row.get("original_path")).isEqualTo("/nas/frames/raw/" + rawSn + "/10.jpg");
        assertThat(row.get("deidentified_path")).isEqualTo("/nas/frames/deid/" + rawSn + "/10.jpg");
        assertThat(row.get("captured_at")).isNotNull();
        assertThat(row.get("reg_dt")).isNotNull();
    }

    @Test
    @DisplayName("V_COMPLETED_FRAME_DESCRIPTION_노출")
    void completedFrame_exposesDescription() {
        // given — FRM_EXPLN 값을 가진 APPROVED 프레임
        long rawSn = seedRawAndStatus("APPROVED");
        long srcSn = seedFrame(rawSn, 5, "우천 야간 교차로에서 우산 든 보행자");

        // when — 신규 DESCRIPTION 컬럼 SELECT
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT DESCRIPTION FROM V_COMPLETED_FRAME WHERE SRC_SN = ?", srcSn);

        // then — FRM_EXPLN 값이 DESCRIPTION 으로 노출
        assertThat(row.get("description")).isEqualTo("우천 야간 교차로에서 우산 든 보행자");
    }

    @Test
    @DisplayName("설명_null이면_DESCRIPTION도_null_노출")
    void completedFrame_exposesNullDescriptionWhenUnset() {
        // given — 설명 미입력(null) 프레임
        long rawSn = seedRawAndStatus("APPROVED");
        long srcSn = seedFrame(rawSn, 7, null);

        // when
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT DESCRIPTION FROM V_COMPLETED_FRAME WHERE SRC_SN = ?", srcSn);

        // then — null 도 그대로 노출(뷰가 프레임 자체를 숨기지 않음)
        assertThat(row.get("description")).isNull();
    }

    @Test
    @DisplayName("승인영상_프레임만_노출_회귀")
    void completedFrame_showsOnlyApprovedVideoFrames() {
        // given — APPROVED 아닌 영상(PENDING)의 프레임
        long rawSn = seedRawAndStatus("PENDING");
        long srcSn = seedFrame(rawSn, 1, "미승인 영상 프레임");

        // when
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM V_COMPLETED_FRAME WHERE SRC_SN = ?", Integer.class, srcSn);

        // then — 미승인 영상 프레임은 뷰에 노출되지 않음(WHERE 게이트 보존)
        assertThat(count).isZero();
    }
}

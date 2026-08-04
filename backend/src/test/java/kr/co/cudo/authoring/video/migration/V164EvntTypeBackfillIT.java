package kr.co.cudo.authoring.video.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V164 이벤트유형 백필 데이터 마이그레이션 실동작 검증 (Testcontainers PostgreSQL).
 *
 * <h3>왜 필요한가</h3>
 * <p>적재 시점 해석(코드 수정)은 <b>신규 영상</b>만 고친다. 이미 적재된 영상은
 * {@code LS_DATA_RAW.EVNT_TYPE_CD} 가 null 인 채로 남아 <b>계속 마킹이 400 으로 막힌다</b>.
 * V164 는 {@code EVNT_ID} 로 해석 가능한 기존 행만 채우는 1회성 데이터 마이그레이션이다.
 *
 * <h3>왜 마이그레이션 SQL 을 다시 실행해서 검증하는가</h3>
 * <p>Flyway 는 컨텍스트 기동 시 이미 V164 를 <b>한 번</b> 적용했고, 그 시점에는 이 테스트의 시드 행이
 * 존재하지 않았다. 그래서 "이 SQL 이 실제로 무엇을 하는가"는 <b>배포되는 파일 자체를 읽어 다시
 * 실행</b>해야만 검증된다(테스트가 SQL 을 복제해 갖고 있으면 파일이 바뀌어도 테스트는 통과한다 —
 * 검증이 아니라 흉내다).
 *
 * <p>재실행이 안전한 근거가 곧 이 마이그레이션의 요구사항이다: <b>멱등</b>(재실행해도 결과 동일) +
 * <b>기존 값 무보존 파괴 금지</b>(이미 값이 있는 행은 덮어쓰지 않는다). 두 성질을 여기서 단언한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class V164EvntTypeBackfillIT {

    /** 배포되는 마이그레이션 파일 — 테스트가 SQL 을 복제하지 않고 <b>원본</b>을 실행한다. */
    private static final String MIGRATION =
            "db/migration/V164__backfill_ls_data_raw_evnt_type_cd_from_ingest.sql";

    private static final String CLIP_PREFIX = "V164-BACKFILL-IT-";
    private static final String EVNT_ID_PREFIX = "V164-BACKFILL-IT-EVT-";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private String runId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        runId = String.valueOf(System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ls_data_ingest WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id LIKE ?", CLIP_PREFIX + "%");
        jdbc.update("DELETE FROM mng_clip_evnt_lst WHERE evnt_id LIKE ?", EVNT_ID_PREFIX + "%");
    }

    @Test
    @DisplayName("EVNT_ID로_해석되는_기존행만_채우고_재실행해도_결과가_같다")
    void backfillsResolvableRowsIdempotently() throws IOException {
        // given — ① 해석 가능(유형 1건) ② 매칭 없음 ③ 이미 값이 있는 행(덮어쓰기 금지 대상)
        long resolvable = seedIngestedVideo("RESOLVABLE", evnt("A"), null);
        seedEventType(evnt("A"), "INTRUSION");

        long unresolvable = seedIngestedVideo("UNRESOLVABLE", evnt("B"), null);

        long alreadySet = seedIngestedVideo("ALREADY", evnt("C"), "LOITERING");
        seedEventType(evnt("C"), "INTRUSION");

        // when — 배포되는 마이그레이션 원본을 실행
        runMigration();

        // then — 해석 가능한 행만 채워진다
        assertThat(evntTypeOf(resolvable)).isEqualTo("INTRUSION");
        // then — 매칭이 없으면 null 그대로(대용값 대입 금지)
        assertThat(evntTypeOf(unresolvable)).isNull();
        // then — ★기존 값은 덮어쓰지 않는다(운영자·다른 경로가 넣은 값을 파괴하지 않는다)
        assertThat(evntTypeOf(alreadySet)).isEqualTo("LOITERING");

        // when — 멱등성: 같은 SQL 을 다시 실행
        runMigration();

        // then — 결과 동일
        assertThat(evntTypeOf(resolvable)).isEqualTo("INTRUSION");
        assertThat(evntTypeOf(unresolvable)).isNull();
        assertThat(evntTypeOf(alreadySet)).isEqualTo("LOITERING");
    }

    @Test
    @DisplayName("한_이벤트에_유형이_둘_이상이면_임의선택하지_않고_비워둔다")
    void skipsAmbiguousEventIds() throws IOException {
        // given — 복합 PK (EVNT_ID, EVNT_TYPE_CD) 라 다중 매칭이 가능하다. 아무거나 고르면
        //   관제 통지 계약·필터·통계가 <틀린 값으로 확정>된다(null 보다 나쁘다).
        long ambiguous = seedIngestedVideo("AMBIGUOUS", evnt("D"), null);
        seedEventType(evnt("D"), "INTRUSION");
        seedEventType(evnt("D"), "FIRE");

        // when
        runMigration();

        // then
        assertThat(evntTypeOf(ambiguous)).isNull();
    }

    // ---------------------------------------------------------------- fixtures

    private String evnt(String suffix) {
        return EVNT_ID_PREFIX + suffix + "-" + runId;
    }

    private void runMigration() throws IOException {
        String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.execute(sql);
    }

    /** 적재 완료 상태(인입 행 DONE + RAW_SN 연결)의 영상 1건을 심고 {@code RAW_SN} 을 돌려준다. */
    private long seedIngestedVideo(String suffix, String evntId, String evntTypeCd) {
        String clipId = CLIP_PREFIX + suffix + "-" + runId;
        jdbc.update("""
                INSERT INTO ls_data_raw
                    (vms_clip_id, vms_cctv_id, prvc_type_cd, prvc_yn, de_ident_yn,
                     raw_file_path_nm, data_stts_cd, reg_dt, evnt_type_cd)
                VALUES (?, 'CCTV-V164-01', 'PRVC', 'N', 'N',
                        '/nas/v164/clip.mp4', 'PENDING', CURRENT_TIMESTAMP, ?)
                """, clipId, evntTypeCd);
        Long rawSn = jdbc.queryForObject(
                "SELECT raw_sn FROM ls_data_raw WHERE vms_clip_id = ?", Long.class, clipId);
        jdbc.update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, proc_stts_cd, raw_sn, evnt_id)
                VALUES (?, 'CCTV-V164-01', 'clip.mp4', '/nas/v164/clip.mp4', 'RELAY',
                        now(), 'DONE', ?, ?)
                """, clipId, rawSn, evntId);
        return rawSn;
    }

    private void seedEventType(String evntId, String evntTypeCd) {
        jdbc.update("INSERT INTO mng_clip_evnt_lst (evnt_id, evnt_type_cd, sht_dt)"
                + " VALUES (?, ?, now())", evntId, evntTypeCd);
    }

    private String evntTypeOf(long rawSn) {
        return jdbc.queryForObject(
                "SELECT evnt_type_cd FROM ls_data_raw WHERE raw_sn = ?", String.class, rawSn);
    }
}

package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관제 학습용 자동 적재 픽업 경로 — 외부 관제 DB 없이 로컬 검증 (Testcontainers PostgreSQL).
 *
 * <p>{@code POST /v1/dev/batch/scan} 가 {@code JOB_DMND_YN='Y'} 시드 클립(MNG_CLIP_MASTER)을
 * 픽업해 {@code LS_DATA_RAW} 로 적재하고 {@code VideoIngestedEvent} 를 발행하는 경로를,
 * dev-seed.sql 자동 적재(local 한정) 없이 테스트가 직접 시드(JdbcTemplate)한 뒤 검증한다.
 *
 * <p>시드는 {@code DEV-CLIP-9*} / {@code DEV-EVT-9*} 접두사로 격리하고 매 테스트 전후로 정리해
 * 다른 통합테스트 상태를 오염시키지 않는다(application-local.yml dev.seed.enabled=false 와 동일 격리).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BatchDevScanIntegrationTest {

    private static final String SEED_CLIP_ID = "DEV-CLIP-9101";
    private static final String SEED_EVNT_ID = "DEV-EVT-9101";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    private JdbcTemplate jdbc;

    /** REVIEWER + INTERNAL 채널 토큰 — /v1/dev/batch/** 는 REVIEWER 인증 필수. */
    private String reviewerToken() {
        return JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        // 픽업 후보 CCTV 마스터(FK 참조 대상) — 기존 시드와 무관한 격리 ID 보장.
        jdbc.update("INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, USE_YN) VALUES (?, ?, 'Y') "
                + "ON CONFLICT (VMS_CCTV_ID) DO NOTHING", "CCTV-001", "CCTV-강남구-001");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        // V146(DB-ISSUE-01) 이후 적재 영상에는 자식 FK 가 붙는다. 적재는 AFTER_COMMIT → @Async 선두
        // 비식별을 띄우므로, 그 비동기 트랜잭션이 LS_DEIDENT_PROC_LOG 를 INSERT 한 채 커밋 전이면 부모
        // 영상 행에 FOR KEY SHARE 가 걸려 삭제가 대기한다(커넥션 기본 lock_timeout 30s → 테스트 실패).
        // 짧은 lock_timeout + 재시도로 비동기 커밋을 기다린 뒤 삭제한다(자식은 CASCADE 동반 삭제).
        RawVideoFixture.deleteRawsWhere(jdbc, "VMS_CLIP_ID LIKE ?", "DEV-CLIP-%");
        jdbc.update("DELETE FROM MNG_CLIP_EVNT_LST WHERE EVNT_ID LIKE 'DEV-EVT-%'");
        jdbc.update("DELETE FROM MNG_CLIP_MASTER WHERE EVNT_ID LIKE 'DEV-EVT-%'");
    }

    /** JOB_DMND_YN='Y' 픽업 후보 클립 1건 + 이벤트리스트 1행을 관제 stub 에 직접 세팅. */
    private void seedTrainingClip() {
        jdbc.update("""
                INSERT INTO MNG_CLIP_MASTER
                    (EVNT_ID, CLIP_TYPE_CD, CLIP_ID, LCLGV_CD, FILE_NM, FILE_PATH, FILE_FMT,
                     VDO_LEN_SEC, CLIP_STTS_CD, CRT_DT, JOB_DMND_YN, VMS_CCTV_ID)
                VALUES (?, 'ORIGINAL', ?, '11110', 'clip-9101.mp4', ?, 'mp4',
                        30000, 'mediainfo_complete', ?, 'Y', 'CCTV-001')
                """,
                SEED_EVNT_ID, SEED_CLIP_ID, "./storage/raw/seed/clip-9101.mp4", LocalDateTime.now());
        jdbc.update("INSERT INTO MNG_CLIP_EVNT_LST (EVNT_ID, EVNT_TYPE_CD, SHT_DT) VALUES (?, 'INTRUSION', ?)",
                SEED_EVNT_ID, LocalDateTime.now());
    }

    private long countSeedRaw() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", Long.class, SEED_CLIP_ID);
        return n == null ? 0L : n;
    }

    @Test
    @DisplayName("scan트리거_호출시_JOB_DMND_Y_시드클립을_픽업해_LS_DATA_RAW에_적재한다")
    void scanIngestsTrainingDesignatedSeedClip() throws Exception {
        // given — 학습용 지정 시드 클립 1건
        seedTrainingClip();

        // when — scan 1회 동기 실행 (REVIEWER 인증)
        mockMvc.perform(post("/v1/dev/batch/scan")
                        .header("Authorization", "Bearer " + reviewerToken()))
                // then — 200 + 적재 건수 >= 1
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // LS_DATA_RAW 에 시드 CLIP_ID 가 적재됨
        assertThat(countSeedRaw()).isEqualTo(1L);
    }

    @Test
    @DisplayName("scan트리거_재호출시_CLIP_ID로_멱등하여_중복적재되지_않는다")
    void scanIsIdempotentByClipId() throws Exception {
        // given
        seedTrainingClip();

        // when — 2회 호출 (REVIEWER 인증)
        mockMvc.perform(post("/v1/dev/batch/scan")
                .header("Authorization", "Bearer " + reviewerToken())).andExpect(status().isOk());
        mockMvc.perform(post("/v1/dev/batch/scan")
                .header("Authorization", "Bearer " + reviewerToken())).andExpect(status().isOk());

        // then — 동일 VMS_CLIP_ID row 1건만 존재 (CLIP_ID 멱등키)
        assertThat(countSeedRaw()).isEqualTo(1L);
    }

    @Test
    @DisplayName("픽업된_클립에_대해_PENDING_상태로_적재되어_VideoIngestedEvent_발행_경로를_탄다")
    void ingestedClipStartsPendingForVideoIngestedEvent() throws Exception {
        // given
        seedTrainingClip();

        // when (REVIEWER 인증)
        mockMvc.perform(post("/v1/dev/batch/scan")
                .header("Authorization", "Bearer " + reviewerToken())).andExpect(status().isOk());

        // then — 적재 직후 DATA_STTS_CD=PENDING (VideoIngestedEvent → 비식별 선두 트리거 시작점)
        String status = jdbc.queryForObject(
                "SELECT DATA_STTS_CD FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", String.class, SEED_CLIP_ID);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("픽업_대상이_없으면_0건_200으로_응답한다")
    void scanWithNoCandidatesReturnsZero() throws Exception {
        // given — 시드 클립 없음 (cleanup 만 수행됨)

        // when / then — 0건도 200 + 0 (REVIEWER 인증)
        mockMvc.perform(post("/v1/dev/batch/scan")
                        .header("Authorization", "Bearer " + reviewerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    @DisplayName("미인증_요청은_401_또는_403으로_거부된다")
    void scanWithoutAuthIsRejected() throws Exception {
        // given — 토큰 미첨부
        // when / then — Authorization 헤더 없이 호출 시 인증 거부 (permitAll 제거 → REVIEWER 강제)
        mockMvc.perform(post("/v1/dev/batch/scan"))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    assertThat(sc).isIn(401, 403);
                });
    }

    @Test
    @DisplayName("WORKER_토큰은_403으로_거부된다")
    void scanWithWorkerTokenIsForbidden() throws Exception {
        // given — REVIEWER 가 아닌 WORKER 토큰
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        // when / then — REVIEWER 미보유 → 403
        mockMvc.perform(post("/v1/dev/batch/scan")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER_인증시_미픽업_scan은_정상_200으로_호출된다")
    void scanWithReviewerAuthSucceeds() throws Exception {
        // given — 시드 클립 없음
        // when / then — REVIEWER 인증 → 정상 200 + 0건
        mockMvc.perform(post("/v1/dev/batch/scan")
                        .header("Authorization", "Bearer " + reviewerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(0));
    }
}

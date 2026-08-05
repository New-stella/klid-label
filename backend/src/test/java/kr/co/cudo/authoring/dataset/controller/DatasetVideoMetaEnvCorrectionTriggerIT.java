package kr.co.cudo.authoring.dataset.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 촬영환경 정정 백필 <b>수동 트리거 API</b> 실 DB 통합 테스트 — 로컬(local 프로파일) 검증 경로가
 * 실제로 동작함을 고정한다.
 *
 * <p>기동 실행기({@code DatasetVideoMetaBackfillRunner})는 {@code @Profile("!local")} 이라 로컬에서는
 * 백필이 돌지 않는다. 이 API 가 유일한 로컬 정정 수단이므로 <b>mock 없이</b> 실제 서비스·리포지토리·
 * 재동결 트랜잭션을 태워 ①dry-run 이 정정하지 않는지 ②실행이 실제로 정정하는지 ③재호출이 0건인지를 본다.
 *
 * <p>시드 형태·판별식 근거는 {@code DatasetVideoMetaEnvCorrectionIT} 와 동일하다(라이브 수동값 없음 +
 * 활성 스냅샷에 파생값 존재 = 폐기된 파생 폴백 산물).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DatasetVideoMetaEnvCorrectionTriggerIT {

    private static final String TARGETS_URL = "/v1/dev/dataset-video-meta/shooting-env-correction-targets";
    private static final String CORRECTIONS_URL = "/v1/dev/dataset-video-meta/shooting-env-corrections";

    /** 레거시 동결 행의 승인 시각 — 정정(재동결)이 이 값을 보존해야 한다. */
    private static final LocalDateTime ORIGINAL_APPROVED_AT = LocalDateTime.of(2026, 3, 1, 10, 0);

    @Autowired private MockMvc mockMvc;
    @Autowired private LsDatasetVideoMetaRepository metaRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private final JdbcTemplate jdbc;

    private final List<Long> seededRawSns = new ArrayList<>();
    private final List<String> seededEvntCds = new ArrayList<>();

    private String reviewerToken;

    DatasetVideoMetaEnvCorrectionTriggerIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_MON_NOTI_ACML WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_META_REPL_OUTBOX WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATASET_VIDEO_META WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_META WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_INGEST WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
        for (String evntCd : seededEvntCds) {
            jdbc.update("DELETE FROM LS_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", evntCd);
        }
    }

    @Test
    @DisplayName("대상조회는_대상건수를_세지만_동결값을_정정하지_않는다")
    void 대상조회는_정정하지_않는다() throws Exception {
        // given — 파생값으로 동결된 레거시 활성 스냅샷 1건
        long rawSn = seedApprovedVideo();
        seedLegacySnapshot(rawSn, "NGT", "SUMMER");

        // when & then — 건수는 잡히지만
        mockMvc.perform(get(TARGETS_URL).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetCount",
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // and — 스냅샷은 손대지 않는다(재동결 행 추가 없음 + 파생값 그대로)
        assertThat(metaRepository.findByRawSn(rawSn)).hasSize(1);
        LsDatasetVideoMeta active = activeSnapshot(rawSn);
        assertThat(active.getDayNgtCd()).isEqualTo("NGT");
        assertThat(active.getSesnCd()).isEqualTo("SUMMER");
    }

    @Test
    @DisplayName("실행API를_호출하면_레거시_파생동결값이_null로_재동결된다")
    void 실행API는_실제로_정정한다() throws Exception {
        // given
        long rawSn = seedApprovedVideo();
        seedLegacySnapshot(rawSn, "NGT", "SUMMER");

        // when
        mockMvc.perform(post(CORRECTIONS_URL).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corrected",
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // then — 새 활성 스냅샷은 두 필드가 null(미상)이고 승인 시각은 보존, 오염 행은 이력으로 남는다
        LsDatasetVideoMeta active = activeSnapshot(rawSn);
        assertThat(active.getDayNgtCd()).isNull();
        assertThat(active.getSesnCd()).isNull();
        assertThat(active.getRvwCmplDt()).isEqualTo(ORIGINAL_APPROVED_AT);
        assertThat(metaRepository.findByRawSn(rawSn)).hasSize(2);
    }

    @Test
    @DisplayName("실행API_두번째_호출은_0건이고_스냅샷을_더_쌓지_않는다")
    void 실행API는_멱등하다() throws Exception {
        // given
        long rawSn = seedApprovedVideo();
        seedLegacySnapshot(rawSn, "NGT", "SUMMER");
        mockMvc.perform(post(CORRECTIONS_URL).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        // when — 같은 API 재호출
        mockMvc.perform(post(CORRECTIONS_URL).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corrected").value(0))
                .andExpect(jsonPath("$.data.remaining").value(0))
                .andExpect(jsonPath("$.data.completed").value(true));

        // then — 정정된 영상은 판별식에서 빠져 재동결이 반복되지 않는다
        assertThat(metaRepository.findByRawSn(rawSn)).hasSize(2);
        assertThat(activeSnapshot(rawSn).getDayNgtCd()).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // 시드 헬퍼 (DatasetVideoMetaEnvCorrectionIT 와 동일 형태 — 수동값 미입력 APPROVED 영상)
    // ---------------------------------------------------------------------------------------

    private LsDatasetVideoMeta activeSnapshot(long rawSn) {
        List<LsDatasetVideoMeta> active =
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES);
        assertThat(active).hasSize(1);
        return active.get(0);
    }

    private long seedApprovedVideo() {
        long nano = System.nanoTime();
        String clipId = "CLIP-" + nano;
        String cctvId = "CCTV-" + nano;
        String lclgvCd = "LG-" + (nano % 100000);
        String evntCd = "EV-" + (nano % 100000);
        seededEvntCds.add(evntCd);

        Long rawSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_RAW (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                        + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
                        + "ORGNL_RAW_SN, DATA_STTS_CD, DAY_NGT_CD, SESN_CD, WTHR_NM, REG_DT) "
                        + "VALUES (?, ?, ?, ?, 'PRVC', 'Y', 'Y', ?, ?, 30, NULL, 'COMPLETED', NULL, NULL, NULL, ?) "
                        + "RETURNING RAW_SN",
                Long.class,
                clipId, cctvId, evntCd, lclgvCd, "/nas/raw/" + nano + ".mp4",
                // 촬영일시 22:00 — 구 파생 규칙이었다면 NGT 로 채워졌을 시각(정정 후에도 null 이어야 한다).
                LocalDateTime.of(2026, 7, 15, 22, 0), LocalDateTime.now());
        seededRawSns.add(rawSn);

        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                + "VALUES (?, 'APPROVED', ?, 2)", rawSn, ORIGINAL_APPROVED_AT);

        // 관제 인입 평면값 시드 — CCTV명·좌표·파일형식의 유일한 조달처(V167 — 구 MNG_* 마스터 제거).
        //   ★조인 축이 VMS_CCTV_ID/LCLGV_CD 가 아니라 RAW_SN 이다(IngestSourceLink).
        //   지자체명(LCLGV_NM)은 넣되 동결 스냅샷의 sidoNm/sggNm 은 상수 null 이다 — 인입은 지역명을
        //   1필드로만 주고 그 입도가 계약으로 확정되지 않아 시도 전용 필드에 넣지 않는다.
        seedIngestFlatValues(rawSn, cctvId);
        jdbc.update("INSERT INTO LS_EVNT_TYPE (EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, CLCT_YN) "
                + "VALUES (?, '보행자 감지', 'A', 'Y')", evntCd);

        jdbc.update("INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, RTRY_NMTM, REG_DT) "
                + "VALUES (?, 'video.resolution', '1920x1080', 0, ?)", rawSn, LocalDateTime.now());
        return rawSn;
    }

    /** 배포 이전에 동결된 <b>레거시 활성 스냅샷</b>(파생 폴백 시절 산물) 직접 적재. */
    private void seedLegacySnapshot(long rawSn, String dayNgtCd, String sesnCd) {
        jdbc.update("INSERT INTO LS_DATASET_VIDEO_META (RAW_SN, SNPSHT_HASH, ACTIVE_YN, "
                        + "DAY_NGT_CD, SESN_CD, WTHR_NM, RVW_CMPL_DT, REG_DT) "
                        + "VALUES (?, ?, 'Y', ?, ?, NULL, ?, ?)",
                rawSn, "legacy-trigger-" + rawSn, dayNgtCd, sesnCd,
                ORIGINAL_APPROVED_AT, LocalDateTime.now());
    }

    /**
     * 관제 인입 평면값({@code LS_DATA_INGEST}) 시드 — 동결 소스가 조인해 읽는 CCTV명·좌표·파일형식.
     *
     * <p>구 시드는 {@code MNG_RESOURCE_CCTV}(VMS_CCTV_ID 축) + {@code MNG_EX_LOCAL_GOV}(LCLGV_CD 축)
     * 두 마스터였다. V167 로 두 테이블이 제거되면서 조달처가 인입 평면값 하나로 합쳐졌고,
     * <b>조인 축도 영상(RAW_SN)</b> 으로 바뀌었다.
     */
    private void seedIngestFlatValues(long rawSn, String cctvId) {
        jdbc.update("INSERT INTO LS_DATA_INGEST "
                        + "(RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE, "
                        + " RCPTN_DT, PRCS_STTS_CD, CCTV_NM, WGS84_LAT, WGS84_LOT, FILE_FMT, LCLGV_NM) "
                        + "VALUES (?, ?, ?, 'clip.mp4', '/nas/raw/clip.mp4', 'ORIGINAL', "
                        + "        CURRENT_TIMESTAMP, 'DONE', ?, ?, ?, 'mp4', ?)",
                rawSn, "ING-" + rawSn, cctvId, "교차로 CCTV",
                37.5665000, 126.9780000, "서울특별시 중구");
    }

}

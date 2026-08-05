package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.dataset.dto.EnvironmentMetaUpdateRequest;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A/★ 결함 회귀 방어 — 승인된 영상의 <b>촬영환경 수정 → 스냅샷 재동결</b> 전체 경로를 실 DB(PostgreSQL
 * Testcontainer)로 통과시킨다(재동결 경로 IT 0건이던 허점 보강).
 *
 * <p>검증 축:
 * <ol>
 *   <li><b>WTHR_NM 갱신</b>: 승인 후 촬영환경을 정정하면 활성 동결 스냅샷의 WTHR_NM 이 최신 수동값으로 갱신된다
 *       (같은 트랜잭션에서 flush 된 dirty 값을 native materialize 소스 조회가 관측하는지 함께 검증).</li>
 *   <li><b>RVW_CMPL_DT 불변(A)</b>: 재동결은 새 active 행을 append 하지만 검수 완료 일시는 <b>최초 승인 시각</b>을
 *       그대로 보존한다(편집 시각 now() 로 덮지 않음 — TASK_COMPLETED "검수 완료 일시" 계약).</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class EnvironmentMetaReFreezeIT {

    /** 최초 검수 완료 시각 — 재동결이 이 값을 보존해야 한다. */
    private static final LocalDateTime ORIGINAL_APPROVED_AT = LocalDateTime.of(2026, 3, 1, 10, 0);

    @Autowired private EnvironmentMetaService environmentMetaService;
    @Autowired private DatasetVideoMetaSnapshotService snapshotService;
    @Autowired private LsDatasetVideoMetaRepository metaRepository;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final List<Long> seededRawSns = new ArrayList<>();
    private final List<String> seededEvntCds = new ArrayList<>();

    private final TokenClaims reviewer =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                    java.time.Instant.now().plusSeconds(600));

    EnvironmentMetaReFreezeIT(@Qualifier("controlDataSource") DataSource dataSource,
                              @Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
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

    /** materialize 소스(LS_DATA_RAW + MNG_* + LS_DATA_META video.*) + APPROVED 상태 시드. */
    private long seedApprovedSource() {
        long nano = System.nanoTime();
        String clipId = "CLIP-" + nano;
        String cctvId = "CCTV-" + nano;
        String lclgvCd = "LG-" + (nano % 100000);
        String evntCd = "EV-" + (nano % 100000);
        seededEvntCds.add(evntCd);

        Long rawSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_RAW (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                        + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
                        + "ORGNL_RAW_SN, DATA_STTS_CD, REG_DT) "
                        + "VALUES (?, ?, ?, ?, 'PRVC', 'Y', 'Y', ?, ?, 30, NULL, 'APPROVED', ?) "
                        + "RETURNING RAW_SN",
                Long.class,
                clipId, cctvId, evntCd, lclgvCd, "/nas/raw/" + nano + ".mp4",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
        seededRawSns.add(rawSn);

        // 관제 인입 평면값 시드 — CCTV명·좌표·파일형식의 유일한 조달처(V167 — 구 MNG_* 마스터 제거).
        //   ★조인 축이 VMS_CCTV_ID/LCLGV_CD 가 아니라 RAW_SN 이다(IngestSourceLink).
        //   지자체명(LCLGV_NM)은 넣되 동결 스냅샷의 sidoNm/sggNm 은 상수 null 이다 — 인입은 지역명을
        //   1필드로만 주고 그 입도가 계약으로 확정되지 않아 시도 전용 필드에 넣지 않는다.
        seedIngestFlatValues(rawSn, cctvId);
        jdbc.update("INSERT INTO LS_EVNT_TYPE (EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, CLCT_YN) "
                + "VALUES (?, '보행자', 'A', 'Y')", evntCd);

        jdbc.update("INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, RTRY_NMTM, REG_DT) "
                + "VALUES (?, 'video.resolution', '1920x1080', 0, ?)", rawSn, LocalDateTime.now());

        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, STP_CYCL, IGI_CYCL, UPD_DT, VER) "
                + "VALUES (?, 'APPROVED', 0, 0, ?, 0)", rawSn, LocalDateTime.now());
        return rawSn;
    }

    private LsDatasetVideoMeta activeSnapshot(long rawSn) {
        return txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0);
    }

    @Test
    @DisplayName("승인된_영상_촬영환경_수정시_스냅샷_WTHR_NM은_갱신되고_RVW_CMPL_DT는_불변이다")
    void 승인후_촬영환경_수정시_WTHR갱신_RVW보존() {
        // given — APPROVED 영상을 최초 승인 시각(ORIGINAL_APPROVED_AT)으로 동결(수동값 미입력이라 WTHR_NM=null)
        long rawSn = seedApprovedSource();
        txTemplate.executeWithoutResult(s -> snapshotService.materialize(rawSn, ORIGINAL_APPROVED_AT));
        LsDatasetVideoMeta before = activeSnapshot(rawSn);
        assertThat(before.getWthrNm()).as("승인 시점엔 날씨 미입력").isNull();
        assertThat(before.getRvwCmplDt()).isEqualTo(ORIGINAL_APPROVED_AT);

        // when — 승인 후 촬영환경을 '눈'/야간/겨울로 정정(같은 트랜잭션 내 dirty 값을 native 가 읽어야 함)
        txTemplate.executeWithoutResult(s ->
                environmentMetaService.update(rawSn,
                        new EnvironmentMetaUpdateRequest("눈", "NGT", "WINTER"), reviewer));

        // then — 활성 스냅샷 WTHR_NM 이 최신 수동값으로 갱신되고, 검수 완료 일시는 최초 승인 시각 그대로 보존
        LsDatasetVideoMeta after = activeSnapshot(rawSn);
        assertThat(after.getWthrNm()).isEqualTo("눈");
        assertThat(after.getDayNgtCd()).isEqualTo("NGT");
        assertThat(after.getSesnCd()).isEqualTo("WINTER");
        assertThat(after.getRvwCmplDt())
                .as("재동결이 검수 완료 일시를 편집 시각(now)으로 덮으면 안 됨(A)")
                .isEqualTo(ORIGINAL_APPROVED_AT);

        // 활성 스냅샷은 여전히 정확히 1건(불변식 유지)
        List<LsDatasetVideoMeta> active = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES));
        assertThat(active).hasSize(1);
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

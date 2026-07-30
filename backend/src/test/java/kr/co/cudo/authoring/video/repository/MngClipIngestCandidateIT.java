package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.video.entity.MngClipEvntLst;
import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-ISSUE-04 — 학습용 클립 적재 후보 조회의 <b>미적재 필터 + tick 상한</b> 실동작 검증
 * (Testcontainers PostgreSQL, 실 SQL 실행).
 *
 * <p>구 구현은 {@code JOB_DMND_YN='Y'} 클립을 <b>상한 없이 전량</b> 조회했고 이미 적재된 클립을
 * 제외하지도 않아, 매 tick 전량 SELECT 후 전량 skip 이 관제 공유 DB(MNG_*)에 반복됐다
 * (실측 로그 {@code scanned=3 ingested=0}). 본 테스트가 고정하는 것:
 * <ol>
 *   <li>{@code NOT EXISTS (LS_DATA_RAW)} — 이미 적재된 CLIP_ID 는 후보에서 빠진다(제거하면 실패).</li>
 *   <li>{@link org.springframework.data.domain.Pageable} — 요청 크기를 넘는 후보는 반환되지 않는다
 *       (상한을 없애면 실패).</li>
 *   <li>정렬 고정(PK 오름차순) — 상한이 걸려도 잔여분이 굶지 않도록 tick 간 순서가 결정적이다.</li>
 *   <li>이벤트리스트 IN 조회 1회가 클립당 개별 조회와 동일한 행(EVNT_ID 당 첫 행)을 준다.</li>
 * </ol>
 *
 * <p>시드는 {@code FKIT-*} 접두로 격리하고 매 테스트 전후 정리해 다른 통합테스트를 오염시키지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
class MngClipIngestCandidateIT {

    private static final String CCTV_ID = "CCTV-001";

    @Autowired
    private MngClipMasterRepository clipMasterRepository;

    @Autowired
    private MngClipEvntLstRepository clipEvntLstRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        jdbc.update("INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, USE_YN) VALUES (?, ?, 'Y') "
                + "ON CONFLICT (VMS_CCTV_ID) DO NOTHING", CCTV_ID, "CCTV-테스트-001");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'FKIT-CLIP-%'");
        jdbc.update("DELETE FROM MNG_CLIP_EVNT_LST WHERE EVNT_ID LIKE 'FKIT-EVT-%'");
        jdbc.update("DELETE FROM MNG_CLIP_MASTER WHERE EVNT_ID LIKE 'FKIT-EVT-%'");
    }

    /** JOB_DMND_YN='Y' + FILE_PATH 존재 후보 클립 1건 시드. */
    private void seedClip(String evntId, String clipId) {
        jdbc.update("""
                INSERT INTO MNG_CLIP_MASTER
                    (EVNT_ID, CLIP_TYPE_CD, CLIP_ID, LCLGV_CD, FILE_NM, FILE_PATH, FILE_FMT,
                     VDO_LEN_SEC, CLIP_STTS_CD, CRT_DT, JOB_DMND_YN, VMS_CCTV_ID)
                VALUES (?, 'ORIGINAL', ?, '11110', 'c.mp4', ?, 'mp4',
                        30000, 'mediainfo_complete', ?, 'Y', ?)
                """, evntId, clipId, "./storage/raw/seed/" + clipId + ".mp4", LocalDateTime.now(), CCTV_ID);
    }

    /** 이미 적재된 상태를 만든다 — LS_DATA_RAW.VMS_CLIP_ID 가 멱등키. */
    private void seedIngested(String clipId) {
        jdbc.update("""
                INSERT INTO LS_DATA_RAW
                    (VMS_CLIP_ID, VMS_CCTV_ID, PRVC_TYPE_CD, RAW_FILE_PATH_NM, DATA_STTS_CD)
                VALUES (?, ?, 'ANONY', ?, 'PENDING')
                """, clipId, CCTV_ID, "./storage/raw/seed/" + clipId + ".mp4");
    }

    private List<MngClipMaster> candidates(int limit) {
        return clipMasterRepository.findIngestCandidatesByJobDmndYn("Y", PageRequest.of(0, limit));
    }

    @Test
    @DisplayName("이미_적재된_클립은_후보에서_제외된다")
    void alreadyIngestedClipIsExcludedFromCandidates() {
        // given — 후보 2건 중 1건은 이미 LS_DATA_RAW 에 적재됨
        seedClip("FKIT-EVT-1", "FKIT-CLIP-1");
        seedClip("FKIT-EVT-2", "FKIT-CLIP-2");
        seedIngested("FKIT-CLIP-1");

        // when
        List<MngClipMaster> found = candidates(100);

        // then — 미적재 클립만 후보에 남는다(NOT EXISTS 제거 시 2건이 되어 실패)
        assertThat(found).extracting(MngClipMaster::getClipId).contains("FKIT-CLIP-2");
        assertThat(found).extracting(MngClipMaster::getClipId).doesNotContain("FKIT-CLIP-1");
    }

    @Test
    @DisplayName("전량_적재된_뒤에는_후보가_0건이라_매_tick_전량조회가_반복되지_않는다")
    void fullyIngestedSeedYieldsNoCandidates() {
        // given — 시드 3건 전량 적재 완료 (실측 재현 상황: scanned=3 ingested=0 반복)
        for (int i = 1; i <= 3; i++) {
            seedClip("FKIT-EVT-" + i, "FKIT-CLIP-" + i);
            seedIngested("FKIT-CLIP-" + i);
        }

        // when
        List<MngClipMaster> found = candidates(100);

        // then — 시드 클립이 후보 목록에 하나도 나타나지 않는다
        assertThat(found).extracting(MngClipMaster::getClipId)
                .doesNotContain("FKIT-CLIP-1", "FKIT-CLIP-2", "FKIT-CLIP-3");
    }

    @Test
    @DisplayName("후보가_상한을_넘으면_상한_건수만_조회된다")
    void candidatesAreCappedByPageableLimit() {
        // given — 후보 5건
        for (int i = 1; i <= 5; i++) {
            seedClip("FKIT-EVT-" + i, "FKIT-CLIP-" + i);
        }

        // when — 상한 2건으로 조회
        List<MngClipMaster> found = candidates(2);

        // then — Pageable 제거 시 5건(또는 그 이상)이 되어 실패
        assertThat(found).hasSize(2);
    }

    @Test
    @DisplayName("상한_조회는_PK_오름차순으로_결정적이라_잔여분이_다음_tick으로_이월된다")
    void cappedScanIsDeterministicallyOrdered() {
        // given
        for (int i = 1; i <= 3; i++) {
            seedClip("FKIT-EVT-" + i, "FKIT-CLIP-" + i);
        }

        // when — 같은 조건으로 두 번 조회
        List<String> first = candidates(3).stream().map(MngClipMaster::getEvntId).toList();
        List<String> second = candidates(3).stream().map(MngClipMaster::getEvntId).toList();

        // then — 순서가 흔들리지 않는다(정렬 미고정이면 상한 하에서 특정 클립이 영원히 굶을 수 있다)
        assertThat(first).isEqualTo(second);
        assertThat(first).isSorted();
    }

    @Test
    @DisplayName("이벤트리스트_IN조회는_후보_EVNT_ID들을_한번에_돌려준다")
    void eventListInQueryReturnsAllRequestedRows() {
        // given
        seedClip("FKIT-EVT-1", "FKIT-CLIP-1");
        seedClip("FKIT-EVT-2", "FKIT-CLIP-2");
        jdbc.update("INSERT INTO MNG_CLIP_EVNT_LST (EVNT_ID, EVNT_TYPE_CD, SHT_DT) VALUES (?, ?, ?)",
                "FKIT-EVT-1", "INTRUSION", LocalDateTime.now());
        jdbc.update("INSERT INTO MNG_CLIP_EVNT_LST (EVNT_ID, EVNT_TYPE_CD, SHT_DT) VALUES (?, ?, ?)",
                "FKIT-EVT-2", "FIRE", LocalDateTime.now());

        // when — 클립당 개별 조회 대신 IN 조회 1회
        List<MngClipEvntLst> rows = clipEvntLstRepository
                .findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc(List.of("FKIT-EVT-1", "FKIT-EVT-2"));

        // then
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(MngClipEvntLst::getEvntId)
                .containsExactly("FKIT-EVT-1", "FKIT-EVT-2");
    }

    @Test
    @DisplayName("EVNT_ID당_다행이면_IN조회_첫행이_findFirstByEvntId와_같다")
    void inQueryFirstRowMatchesFindFirstSemantics() {
        // given — 복합 PK (EVNT_ID, EVNT_TYPE_CD) 상 같은 EVNT_ID 에 2행
        seedClip("FKIT-EVT-1", "FKIT-CLIP-1");
        jdbc.update("INSERT INTO MNG_CLIP_EVNT_LST (EVNT_ID, EVNT_TYPE_CD, SHT_DT) VALUES (?, ?, ?)",
                "FKIT-EVT-1", "ZZZ_LAST", LocalDateTime.now());
        jdbc.update("INSERT INTO MNG_CLIP_EVNT_LST (EVNT_ID, EVNT_TYPE_CD, SHT_DT) VALUES (?, ?, ?)",
                "FKIT-EVT-1", "AAA_FIRST", LocalDateTime.now());

        // when
        List<MngClipEvntLst> rows = clipEvntLstRepository
                .findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc(List.of("FKIT-EVT-1"));

        // then — 정렬 첫 행이 결정적으로 뽑힌다(스캔 서비스가 putIfAbsent 로 이 행을 채택)
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getEvntTypeCd()).isEqualTo("AAA_FIRST");
    }

    @Test
    @DisplayName("tick_상한_기본값은_전량조회가_아닌_유한값이다")
    void tickLimitIsFinite() {
        // given / when / then — 상한 상수가 실제로 유한(전량 조회 금지 규칙 준수)
        assertThat(TrainingVideoIngestService.INGEST_SCAN_LIMIT).isPositive();
        assertThat(TrainingVideoIngestService.INGEST_SCAN_LIMIT).isLessThanOrEqualTo(1000);
    }
}

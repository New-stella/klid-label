package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-5 — 백필 <b>대상 발견</b>이 라벨 축이 아니라 <b>파생 축</b>으로 동작하는지 실 DB(PostgreSQL
 * Testcontainer)에서 검증한다.
 *
 * <p>구 구현은 {@code LS_DATA_AUG → LS_DATA_AUG_LBL_MAP → LS_DATA_LBL → LS_DATA_SRC → LS_DATA_RAW}
 * 전부 INNER JOIN 이라 ①부모 라벨이 0건이라 매핑 행 자체가 없는 파생 ②라벨 삭제로 매핑이 dangling 이 된
 * 파생(해당 FK 없음)을 구조적으로 누락했다. 이 테스트는 라벨/라벨매핑을 <b>한 건도 넣지 않고</b>
 * 대상이 잡히는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ResolutionBackfillTargetDiscoveryIT {

    private static final long PARENT = 990401L;
    private static final long DERIVATIVE_NO_LABEL = 990402L;
    private static final long DERIVATIVE_DANGLING = 990403L;
    private static final long AUGMENT_DERIVATIVE = 990404L;

    private final JdbcTemplate jdbc;
    private final List<Long> seededRawSns = new ArrayList<>();
    private final List<Long> seededAugSns = new ArrayList<>();

    @Autowired VideoRepository videoRepository;

    ResolutionBackfillTargetDiscoveryIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        for (Long augSn : seededAugSns) {
            jdbc.update("DELETE FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", augSn);
        }
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
    }

    private void insertRaw(long rawSn, String vmsClipId, Long orgnlRawSn) {
        jdbc.update("INSERT INTO LS_DATA_RAW (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                        + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, DATA_STTS_CD, ORGNL_RAW_SN, REG_DT) "
                        + "VALUES (?, ?, 'CCTV', 'EVT', '11680', 'PRVC', 'Y', 'Y', ?, 'COMPLETED', ?, CURRENT_TIMESTAMP)",
                rawSn, vmsClipId, "/x/" + rawSn + ".mp4", orgnlRawSn);
        seededRawSns.add(rawSn);
    }

    private long insertParentFrame() {
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, 0, '/x/p0.jpg')", PARENT);
        return jdbc.queryForObject(
                "SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = ? AND FRM_NO = 0", Long.class, PARENT);
    }

    private void insertAug(long parentFrameSrcSn, String augTypeCd, String status) {
        jdbc.update("INSERT INTO LS_DATA_AUG (SRC_SN, AUG_TYPE_CD, AUG_PROC_STTS_CD, RTRY_NMTM, REG_DT) "
                + "VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP)", parentFrameSrcSn, augTypeCd, status);
        Long augSn = jdbc.queryForObject(
                "SELECT DATA_AUG_SN FROM LS_DATA_AUG WHERE SRC_SN = ? AND AUG_TYPE_CD = ?",
                Long.class, parentFrameSrcSn, augTypeCd);
        seededAugSns.add(augSn);
    }

    private List<Long> discoveredRawSns() {
        List<Long> out = new ArrayList<>();
        for (Object[] row : videoRepository.findResolutionDerivativeTargets()) {
            out.add(((Number) row[0]).longValue());
        }
        return out;
    }

    /** 발견 행의 프리셋(augTypeCd) — 없으면 null. */
    private String discoveredPreset(long rawSn) {
        for (Object[] row : videoRepository.findResolutionDerivativeTargets()) {
            if (((Number) row[0]).longValue() == rawSn) {
                return (String) row[2];
            }
        }
        return null;
    }

    @Test
    @DisplayName("부모_라벨이_0건인_파생도_백필_대상에_포함된다")
    void derivativeWithoutAnyLabelIsStillDiscovered() {
        // given — 부모 + 프레임 1건 + ACCEPTED RESL 예약. 라벨/라벨매핑은 <한 건도 없다>.
        insertRaw(PARENT, "CLIP-" + PARENT, null);
        long parentFrame = insertParentFrame();
        insertAug(parentFrame, "RESL_720P", "ACCEPTED");
        insertRaw(DERIVATIVE_NO_LABEL, "CLIP-" + PARENT + "_RESL_RESL_720P_1700000000000", PARENT);

        // when / then — 라벨 축 조인이었다면 0행이라 영구 누락됐다.
        assertThat(discoveredRawSns()).contains(DERIVATIVE_NO_LABEL);
    }

    @Test
    @DisplayName("라벨매핑이_끊긴_파생도_백필_대상에_포함된다")
    void derivativeWithDanglingLabelMapIsStillDiscovered() {
        // given — 작업자 저장(full-replace)·비식별 신고로 복사 라벨이 전량 삭제된 상태를 모사한다:
        //         LS_DATA_AUG_LBL_MAP 행이 없거나 dangling 이어도 파생 축은 영향받지 않는다.
        insertRaw(PARENT, "CLIP-" + PARENT, null);
        long parentFrame = insertParentFrame();
        insertAug(parentFrame, "RESL_480P", "ACCEPTED");
        insertRaw(DERIVATIVE_DANGLING, "CLIP-" + PARENT + "_RESL_RESL_480P_1700000000001", PARENT);

        assertThat(discoveredRawSns()).contains(DERIVATIVE_DANGLING);
    }

    @Test
    @DisplayName("PENDING_예약_파생은_프리셋이_확정되지_않고_증강_파생은_대상에서_제외된다")
    void pendingReservationHasNoPresetAndAugmentDerivativeIsExcluded() {
        insertRaw(PARENT, "CLIP-" + PARENT, null);
        long parentFrame = insertParentFrame();
        // PENDING(생성 중) 예약 — ACCEPTED 가 아니므로 프리셋으로 짝지어지지 않는다.
        insertAug(parentFrame, "RESL_1080P", "PENDING");
        insertRaw(DERIVATIVE_NO_LABEL, "CLIP-" + PARENT + "_RESL_RESL_1080P_1700000000002", PARENT);
        // 외부 증강(WINTER) 파생 — 해상도 파생이 아니다(_RESL_ 마커 없음).
        insertAug(parentFrame, "WINTER", "ACCEPTED");
        insertRaw(AUGMENT_DERIVATIVE, "CLIP-" + PARENT + "_WINTER_1700000000003", PARENT);

        List<Long> discovered = discoveredRawSns();
        // A-4 — 파생 축 발견이므로 예약행 상태와 무관하게 파생 RAW 자체는 보인다. 단 프리셋은 미확정이다
        //       (호출측이 unresolvedPresetRawSns 로 보고 → 영상 파일은 손대지 않는다).
        assertThat(discovered).contains(DERIVATIVE_NO_LABEL);
        assertThat(discoveredPreset(DERIVATIVE_NO_LABEL)).isNull();
        assertThat(discovered).doesNotContain(AUGMENT_DERIVATIVE);
    }

    // ---------------------------------------------------------------------
    // A-4 — LS_DATA_AUG 행이 아예 없는 파생(확정 실패 시 예약행 삭제)도 발견돼야 한다
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("A4_대응_LS_DATA_AUG_행이_없는_파생도_발견되고_프리셋은_null로_드러난다")
    void derivativeWithoutAnyAugRowIsStillDiscovered() {
        // given — 파생 RAW 만 남고 예약행은 삭제된 상태(확정 실패 정책의 실제 결과, 실측 12건).
        insertRaw(PARENT, "CLIP-" + PARENT, null);
        insertParentFrame();
        insertRaw(DERIVATIVE_NO_LABEL, "CLIP-" + PARENT + "_RESL_RESL_720P_1700000000004", PARENT);

        // when / then — AUG INNER JOIN 이던 구 구현은 이 파생을 영구 누락했다.
        assertThat(discoveredRawSns()).contains(DERIVATIVE_NO_LABEL);
        assertThat(discoveredPreset(DERIVATIVE_NO_LABEL)).isNull();
    }

    // ---------------------------------------------------------------------
    // A-5 — 프리셋 짝짓기는 VMS_CLIP_ID 이중 접두 드리프트(E-ISSUE-25)에 의존하지 않는다
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("A5_현행_이중접두_클립ID에서_프리셋이_짝지어진다")
    void matchesPresetWithCurrentDoublePrefixClipId() {
        insertRaw(PARENT, "CLIP-" + PARENT, null);
        long parentFrame = insertParentFrame();
        insertAug(parentFrame, "RESL_720P", "ACCEPTED");
        // 현행(드리프트) 규약: {부모}_RESL_{RESL_720P}_{ts}
        insertRaw(DERIVATIVE_NO_LABEL, "CLIP-" + PARENT + "_RESL_RESL_720P_1700000000005", PARENT);

        assertThat(discoveredPreset(DERIVATIVE_NO_LABEL)).isEqualTo("RESL_720P");
    }

    @Test
    @DisplayName("A5_드리프트를_고친_단일접두_클립ID에서도_프리셋이_짝지어진다")
    void matchesPresetWithFixedSinglePrefixClipId() {
        insertRaw(PARENT, "CLIP-" + PARENT, null);
        long parentFrame = insertParentFrame();
        insertAug(parentFrame, "RESL_480P", "ACCEPTED");
        // E-ISSUE-25 를 정공법으로 고친 뒤의 규약: {부모}_{RESL_480P}_{ts} (이중 접두 없음)
        insertRaw(DERIVATIVE_DANGLING, "CLIP-" + PARENT + "_RESL_480P_1700000000006", PARENT);

        // 구 패턴('%_RESL_' || AUG_TYPE_CD || '_%')은 이 형태에서 0행이 되어 백필이 "대상 없음"으로
        // 성공 보고했다 — 되돌리면 이 단언이 실패한다.
        assertThat(discoveredRawSns()).contains(DERIVATIVE_DANGLING);
        assertThat(discoveredPreset(DERIVATIVE_DANGLING)).isEqualTo("RESL_480P");
    }

    // ---------------------------------------------------------------------
    // A-3 — 감사 입력 필터가 뷰 게이트와 동치(빈 문자열 = 결측 = 정상 통과)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("A3_빈문자열_비식별경로는_감사입력에서_제외돼_뷰게이트와_같은_결측취급이_된다")
    void blankDeidPathIsExcludedFromAuditInputLikeViewGate() {
        // given — 빈 문자열 / 공백 / 정상 경로 3종
        insertRaw(PARENT, "CLIP-" + PARENT, null);
        insertFrameWithDeid(10, "");
        insertFrameWithDeid(11, "   ");
        insertFrameWithDeid(12, "/x/deid12.jpg");
        Long cursor = jdbc.queryForObject(
                "SELECT MIN(SRC_SN) - 1 FROM LS_DATA_SRC WHERE RAW_SN = ?", Long.class, PARENT);

        // when
        List<Object[]> rows = videoRepository.findDeidFramePathsAfter(cursor, 500);

        // then — 빈 문자열/공백은 <결측>이라 판정 대상이 아니다(뷰 게이트의 TRIM<>'' 와 동일 취급).
        //        되돌리면 BLANK 가 위반으로 집계돼 SQL 근사 vs 코드 판정 비동치가 입력 단계에 되살아난다.
        assertThat(rows).noneMatch(r -> r[2] == null || ((String) r[2]).isBlank());
        assertThat(rows).anyMatch(r -> "/x/deid12.jpg".equals(r[2]));
    }

    private void insertFrameWithDeid(int frameNo, String deidPath) {
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM, DE_IDNTF_SRC_FILE_PATH_NM) "
                + "VALUES (?, ?, ?, ?)", PARENT, frameNo, "/x/p" + frameNo + ".jpg", deidPath);
    }

    @Test
    @DisplayName("A5_프리셋_코드가_다르면_교차매칭되지_않는다")
    void doesNotCrossMatchOtherPresets() {
        insertRaw(PARENT, "CLIP-" + PARENT, null);
        long parentFrame = insertParentFrame();
        insertAug(parentFrame, "RESL_1080P", "ACCEPTED");
        insertAug(parentFrame, "RESL_480P", "ACCEPTED");
        insertRaw(DERIVATIVE_NO_LABEL, "CLIP-" + PARENT + "_RESL_RESL_480P_1700000000007", PARENT);

        assertThat(discoveredPreset(DERIVATIVE_NO_LABEL)).isEqualTo("RESL_480P");
    }
}

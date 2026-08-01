package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.config.AugmentDiscardProperties;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.repository.LsDataAugDscdRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.augment.service.AugmentDiscardPurgeSweeper;
import kr.co.cudo.authoring.augment.service.AugmentDiscardPurgeTxService;
import kr.co.cudo.authoring.augment.service.AugmentDiscardService;
import kr.co.cudo.authoring.augment.service.AugmentReviewService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 7 — 증강 파생영상 <b>폐기(소프트 삭제) → 유예 → 실삭제 → 복구</b> 통합 테스트
 * (실 DB, PostgreSQL Testcontainer).
 *
 * <p>이 Phase 는 데이터를 <b>영구 삭제</b>하는 코드다. 그래서 "지워지는가" 보다 <b>"지워지면 안 되는 것이
 * 지워지지 않는가"</b> 를 먼저 고정한다 — 원본 영상(C1)·검수 승인분(H8)·복구된 건(H7)·매핑 없는
 * 그랜드퍼더링(확정 설계 3).
 *
 * <p>시간 경과는 <b>폐기 표식 시각({@code DSCD_DT}) 백데이팅</b>으로 재현한다. 구 방식(미래 cutoff 주입)은
 * FIX-1 클램프 이후 통하지 않는다 — 그게 클램프의 목적이다(유예는 넓힐 수만 있고 좁힐 수 없다). 그래서
 * 테스트도 운영과 <b>같은 cutoff</b>({@code now - graceDays})로 돌고, 대신 표식이 실제로 늙는다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AugmentDiscardPurgeIT {

    private static final TokenClaims REVIEWER =
            new TokenClaims("rv-discard", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));

    @Autowired AugmentDiscardService discardService;
    @Autowired AugmentDiscardPurgeSweeper sweeper;
    @Autowired AugmentDiscardPurgeTxService purgeTxService;
    @Autowired AugmentReviewService reviewService;
    @Autowired LsDataAugDscdRepository discardRepository;
    @Autowired LsDataAugRvwRepository reviewRepository;
    @Autowired kr.co.cudo.authoring.augment.repository.LsDataAugRepository augRepository;
    @Autowired AugmentDiscardProperties discardProperties;
    @Autowired PlatformTransactionManager controlTransactionManager;

    private final JdbcTemplate jdbc;
    private final List<Long> rawSns = new ArrayList<>();

    /**
     * 시드가 만든 행 중 <b>{@code LS_DATA_RAW} 삭제로 정리되지 않는 것</b>의 PK.
     *
     * <p>V146 이 붙인 RAW CASCADE 는 {@code LS_DATA_SRC} 까지만 닿는다. 라벨({@code LS_DATA_LBL})은
     * {@code SRC_SN} 에 <b>FK 가 없어</b> 프레임이 지워져도 남고, 라벨 속성값·라벨 마스터·속성 정의도
     * 마찬가지다. 그래서 이 시드는 <b>테스트 클래스 경계를 넘어</b> 누적됐고, 뒤따르는
     * {@code label}·{@code stats}·{@code portal} 테스트가 그 테이블을 비우거나 개수를 단언할 때
     * FK 위반으로 무더기 실패했다(FULL 회귀 실패 69건 = {@code fk_ls_data_lbl_attr_lbl} 45 +
     * {@code fk_ls_data_lbl_attr_attr} 13 + {@code fk_ls_label_attr_label} 11).
     *
     * <p>세그먼트(패키지별 {@code --tests}) 실행에서는 <b>피해자가 같은 실행에 없어</b> 드러나지 않는다 —
     * 그래서 PK 를 모아 {@code @AfterEach} 에서 <b>자기 시드만</b> 되돌린다. 범위를 넓혀
     * ({@code DELETE FROM LS_LABEL} 같은) 전량 삭제를 하면 반대 방향 오염이 된다.
     */
    private final List<Long> srcSns = new ArrayList<>();
    private final List<Long> dataLblSns = new ArrayList<>();
    private final List<Long> labelMasterIds = new ArrayList<>();
    private final List<Long> dataAugSns = new ArrayList<>();

    /**
     * 테스트가 <b>실 파일시스템</b>({@code backend/storage/deidentified})에 심은 시드 경로.
     *
     * <p>이 IT 는 @TempDir 이 아니라 프로젝트 작업 디렉터리 하위를 쓰므로 잔재가 <b>다음 실행까지
     * 남는다</b>. 특히 심링크는 재실행 시 {@code createSymbolicLink} 가
     * {@code FileAlreadyExistsException} 으로 <b>항상 실패</b>하게 만든다(Testcontainer 가 매번 새로
     * 뜨면서 시퀀스가 리셋돼 같은 RAW_SN → 같은 경로가 다시 쓰인다). 정리 코드를 테스트 본문 끝에 두면
     * <b>단언이 하나라도 실패한 순간 환경이 영구 오염</b>되므로 등록 → {@code @AfterEach} 일괄 삭제로
     * 옮긴다(FIX-D).
     */
    private final List<Path> seededPaths = new ArrayList<>();

    AugmentDiscardPurgeIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ────────────────────────────────────────────────────────────────────
    // 시드
    // ────────────────────────────────────────────────────────────────────

    /** 부모(원본) + 파생 + 프레임/라벨/속성값/라벨맵/이력 + 증강 행(생성 성공). */
    private record Seed(long parentRawSn, long parentSrcSn, long derivativeRawSn,
                        long derivativeSrcSn, long dataAugSn, long lblSn) { }

    private Seed seed(String tag) {
        return seed(tag, true);
    }

    /**
     * @param mapped false 면 {@code NEW_RAW_SN} 을 채우지 않는다(그랜드퍼더링 재현).
     */
    private Seed seed(String tag, boolean mapped) {
        String uniq = tag + "-" + UUID.randomUUID();
        long parent = insertRaw("CLIP-P-" + uniq, null, "/nas/original/" + uniq + ".mp4");
        long parentSrc = insertFrame(parent, 0, "/nas/original/frames/p-" + uniq + ".jpg");
        long derivative = insertRaw("CLIP-D-" + uniq, parent, "/deid/videos/augment/x.mp4");
        long derivativeSrc = insertFrame(derivative, 0, "/deid/frames/deid/d-" + uniq + ".jpg");

        long lblSn = insertLabel(derivativeSrc, "car-" + tag);
        long attrId = insertLabelMasterAttr(uniq);
        jdbc.update("INSERT INTO LS_DATA_LBL_ATTR_VAL (LBL_SN, ATRB_ID, ATRB_VL, REG_DT) "
                + "VALUES (?, ?, 'v', CURRENT_TIMESTAMP)", lblSn, attrId);
        jdbc.update("INSERT INTO LS_DATA_LBL_HSTRY (SRC_SN, REG_DT, ADD_CNT, MDFCN_CNT, DEL_CNT) "
                + "VALUES (?, CURRENT_TIMESTAMP, 1, 0, 0)", derivativeSrc);
        // 파생 프레임 1장마다 실제로 쌓이는 생성 이력(AugmentExtractPersist 의 recordCreated).
        // FK 도 RAW_SN 컬럼도 없어 어떤 자동 경로로도 정리되지 않는다 — 명시 삭제 대상(FIX-2).
        jdbc.update("INSERT INTO LS_DATA_SRC_HSTRY (SRC_SN, CHG_TYPE_CD, CHG_DT) "
                + "VALUES (?, 'CREATE', CURRENT_TIMESTAMP)", derivativeSrc);

        jdbc.update("INSERT INTO LS_DATA_AUG (SRC_SN, AUG_TYPE_CD, AUG_PROC_STTS_CD, REG_DT, RTRY_NMTM, NEW_RAW_SN) "
                        + "VALUES (?, 'WINTER', 'ACCEPTED', CURRENT_TIMESTAMP, 0, ?)",
                parentSrc, mapped ? derivative : null);
        long dataAugSn = jdbc.queryForObject(
                "SELECT MAX(DATA_AUG_SN) FROM LS_DATA_AUG WHERE SRC_SN = ?", Long.class, parentSrc);
        dataAugSns.add(dataAugSn);
        jdbc.update("INSERT INTO LS_DATA_AUG_LBL_MAP (DATA_AUG_SN, DATA_LBL_SN, COORD_RECALC_YN, REG_DT) "
                + "VALUES (?, ?, 'N', CURRENT_TIMESTAMP)", dataAugSn, lblSn);

        return new Seed(parent, parentSrc, derivative, derivativeSrc, dataAugSn, lblSn);
    }

    private long insertRaw(String clipId, Long orgnlRawSn, String filePath) {
        jdbc.update("INSERT INTO LS_DATA_RAW (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, PRVC_TYPE_CD, "
                        + "PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, DATA_STTS_CD, ORGNL_RAW_SN, REG_DT) "
                        + "VALUES (?, 'CCTV', 'EVT', '11680', 'PRVC', 'Y', 'Y', ?, 'COMPLETED', ?, CURRENT_TIMESTAMP)",
                clipId, filePath, orgnlRawSn);
        Long rawSn = jdbc.queryForObject("SELECT RAW_SN FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", Long.class, clipId);
        rawSns.add(rawSn);
        return rawSn;
    }

    private long insertFrame(long rawSn, int frameNo, String path) {
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM, DE_IDNTF_SRC_FILE_PATH_NM) "
                + "VALUES (?, ?, ?, ?)", rawSn, frameNo, path, path);
        Long srcSn = jdbc.queryForObject("SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = ? AND FRM_NO = ?",
                Long.class, rawSn, frameNo);
        srcSns.add(srcSn);
        return srcSn;
    }

    private long insertLabel(long srcSn, String name) {
        jdbc.update("INSERT INTO LS_DATA_LBL (SRC_SN, LBL_TYPE_CD, LBL_NM, POINT_CN, REG_DT) "
                + "VALUES (?, 'BBOX', ?, '[]', CURRENT_TIMESTAMP)", srcSn, name);
        Long lblSn = jdbc.queryForObject(
                "SELECT MAX(LBL_SN) FROM LS_DATA_LBL WHERE SRC_SN = ?", Long.class, srcSn);
        dataLblSns.add(lblSn);
        return lblSn;
    }

    /** 라벨 마스터 + 속성 정의(LS_LABEL_ATTR) — 속성값 FK 를 만족시키기 위한 최소 시드. */
    private long insertLabelMasterAttr(String uniq) {
        jdbc.update("INSERT INTO LS_LABEL (LBL_NM, COLR_VL, LBL_TYPE_CD, USE_YN, SORT_SEQ, REG_DT) "
                + "VALUES (?, '#fff', 'BBOX', 'Y', 1, CURRENT_TIMESTAMP)", "M-" + uniq);
        Long labelId = jdbc.queryForObject("SELECT LBL_ID FROM LS_LABEL WHERE LBL_NM = ?", Long.class, "M-" + uniq);
        labelMasterIds.add(labelId);
        jdbc.update("INSERT INTO LS_LABEL_ATTR (LBL_ID, ATRB_NM, INPUT_TYPE_CD, MUTABLE_YN, SORT_SEQ, USE_YN, REG_DT) "
                + "VALUES (?, 'color', 'TEXT', 'Y', 1, 'Y', CURRENT_TIMESTAMP)", labelId);
        return jdbc.queryForObject("SELECT MAX(ATRB_ID) FROM LS_LABEL_ATTR WHERE LBL_ID = ?", Long.class, labelId);
    }

    private void approve(long rawSn) {
        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, STP_CYCL, IGI_CYCL, UPD_DT, VER) "
                + "VALUES (?, 'APPROVED', 0, 0, CURRENT_TIMESTAMP, 0)", rawSn);
    }

    /**
     * 운영 스윕이 만드는 것과 <b>같은</b> cutoff 요청값. 진입부 클램프가 {@code now - graceDays} 로
     * 되돌리므로 어떤 값을 넣어도 유예는 줄어들지 않는다(FIX-1) — 테스트도 그 규칙 아래서 돈다.
     */
    private static LocalDateTime sweepCutoff() {
        return LocalDateTime.now();
    }

    /**
     * 클램프를 거치지 않는 <b>SQL 직접 호출</b>용 cutoff — 최종 DELETE 문장 자체가 다른 조건
     * (원본 여부·승인·표식 상태)으로 거부하는지 확인할 때만 쓴다.
     */
    private static LocalDateTime rawSqlCutoff() {
        return LocalDateTime.now().plusMinutes(5);
    }

    /**
     * 유예 경과 재현 — 폐기 표식 시각을 하드 컷오프 <b>이전</b>으로 되돌린다.
     *
     * <p>구 테스트는 미래 cutoff 를 주입해 "유예가 지난 것처럼" 만들었는데, 그 경로는 FIX-1 클램프가
     * 정확히 막는 경로다(운영에서 그것이 곧 "반려 즉시 영구 삭제" 였다). 그래서 시간 축을 표식 쪽으로
     * 옮긴다.
     */
    private void expireGrace(long dataAugSn) {
        jdbc.update("UPDATE LS_DATA_AUG_DSCD SET DSCD_DT = ? "
                        + "WHERE DATA_AUG_SN = ? AND RSTR_DT IS NULL AND DEL_DT IS NULL",
                java.sql.Timestamp.valueOf(
                        LocalDateTime.now().minusDays(discardProperties.graceDays() + 1L)),
                dataAugSn);
    }

    /** 표식 행을 직접 심은 경우(원본 겨냥 등)의 백데이팅 — 비석 PK 기준. */
    private void expireGraceByDscdSn(long dscdSn) {
        jdbc.update("UPDATE LS_DATA_AUG_DSCD SET DSCD_DT = ? WHERE DATA_AUG_DSCD_SN = ?",
                java.sql.Timestamp.valueOf(
                        LocalDateTime.now().minusDays(discardProperties.graceDays() + 1L)),
                dscdSn);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private boolean rawExists(long rawSn) {
        return count("SELECT COUNT(1) FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn) > 0;
    }

    /**
     * 남은 시드 정리(테스트가 지우지 못한 것만) — <b>단언이 모두 평가된 뒤</b>에 돈다. 그래서 "고아 없이
     * 지웠다" 를 확인하는 테스트({@code deletesLabelGraphWithoutOrphans})의 검증력을 건드리지 않는다.
     * 이미 실삭제된 시드에 대해서는 전부 0건 DELETE 다(멱등).
     *
     * <p>삭제 순서는 <b>FK 역방향</b>이다 — {@code LS_DATA_LBL_ATTR_VAL} 은
     * {@code LS_DATA_LBL}({@code fk_ls_data_lbl_attr_lbl}) 과 {@code LS_LABEL_ATTR}
     * ({@code fk_ls_data_lbl_attr_attr}) 을, {@code LS_LABEL_ATTR} 은
     * {@code LS_LABEL}({@code fk_ls_label_attr_label}) 을 참조하므로 값 → 라벨/속성정의 → 마스터 순으로
     * 내려가야 한다.
     *
     * <p><b>범위는 자기 시드 PK 로 한정</b>한다. 편하다고 전량 삭제({@code DELETE FROM LS_LABEL})로
     * 바꾸면 이번 사고의 방향만 뒤집힐 뿐이다 — 다른 테스트의 라벨 마스터 전제를 지운다.
     */
    @AfterEach
    void cleanup() {
        cleanupSeededFiles();

        // 증강 축 — LS_DATA_AUG 는 SRC_SN 에 FK 가 없어 RAW CASCADE 로 정리되지 않는다. 구 정리는
        // NEW_RAW_SN 일치분만 지워, 매핑 없는 그랜드퍼더링 시드(seed(tag, false))가 그대로 남았다.
        for (Long dataAugSn : dataAugSns) {
            jdbc.update("DELETE FROM LS_DATA_AUG_DSCD WHERE DATA_AUG_SN = ?", dataAugSn);
            jdbc.update("DELETE FROM LS_DATA_AUG_LBL_MAP WHERE DATA_AUG_SN = ?", dataAugSn);
            jdbc.update("DELETE FROM LS_DATA_AUG_RVW WHERE DATA_AUG_SN = ?", dataAugSn);
            jdbc.update("DELETE FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", dataAugSn);
        }
        // 라벨 축 — 속성값을 먼저 지워야 라벨과 속성 정의를 지울 수 있다.
        for (Long lblSn : dataLblSns) {
            jdbc.update("DELETE FROM LS_DATA_LBL_ATTR_VAL WHERE LBL_SN = ?", lblSn);
            jdbc.update("DELETE FROM LS_DATA_LBL WHERE LBL_SN = ?", lblSn);
        }
        // 프레임 이력 축 — SRC_SN 에 FK 도, RAW_SN 컬럼도 없어 어떤 자동 경로로도 정리되지 않는다.
        for (Long srcSn : srcSns) {
            jdbc.update("DELETE FROM LS_DATA_LBL_HSTRY WHERE SRC_SN = ?", srcSn);
            jdbc.update("DELETE FROM LS_DATA_SRC_HSTRY WHERE SRC_SN = ?", srcSn);
        }
        // 라벨 마스터 축 — 속성 정의가 마스터를 참조하므로 정의 → 마스터 순.
        for (Long labelId : labelMasterIds) {
            jdbc.update("DELETE FROM LS_LABEL_ATTR WHERE LBL_ID = ?", labelId);
            jdbc.update("DELETE FROM LS_LABEL WHERE LBL_ID = ?", labelId);
        }
        // 영상 축 — FK CASCADE(V146)가 프레임·상태·검수 등 자식을 정리한다.
        // 비석(LS_DATA_AUG_DSCD)은 FK 가 없어 아무것도 지워주지 않으므로 명시 정리한다 — 남기면
        // 다음 테스트의 스윕이 남의 표식을 집어 "삭제 0건" 단언이 비결정적으로 깨진다.
        for (Long rawSn : rawSns) {
            jdbc.update("DELETE FROM LS_DATA_AUG_DSCD WHERE NEW_RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_AUG WHERE NEW_RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }

        dataAugSns.clear();
        dataLblSns.clear();
        srcSns.clear();
        labelMasterIds.clear();
        rawSns.clear();
    }

    // ────────────────────────────────────────────────────────────────────
    // C1 · C2 — 원본은 어떤 경로로도 지워지지 않는다
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("원본영상은_어떤_조건에서도_삭제_대상이_아니다")
    void originalVideoIsNeverPurged() {
        // given: 원본 영상에 (강제로) 유예 경과 폐기 표식을 심는다 — 배치가 집더라도 지워지면 안 된다.
        Seed s = seed("C1");
        jdbc.update("INSERT INTO LS_DATA_AUG_DSCD (DATA_AUG_SN, NEW_RAW_SN, ORGNL_RAW_SN, DSCD_DT, REG_DT) "
                        + "VALUES (?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                s.dataAugSn(), s.parentRawSn());
        Long dscdSn = jdbc.queryForObject(
                "SELECT MAX(DATA_AUG_DSCD_SN) FROM LS_DATA_AUG_DSCD WHERE NEW_RAW_SN = ?",
                Long.class, s.parentRawSn());
        expireGraceByDscdSn(dscdSn);

        // when: 클레임까지 통과시키고 집행한다
        assertThat(purgeTxService.claim(dscdSn, sweepCutoff(), LocalDateTime.now())).isTrue();
        AugmentDiscardPurgeTxService.Outcome outcome = purgeTxService.purge(dscdSn, sweepCutoff());

        // then: 거부 + 원본 생존(자식 프레임/라벨도 그대로)
        assertThat(outcome.result()).isEqualTo(AugmentDiscardPurgeTxService.Result.REFUSED);
        assertThat(rawExists(s.parentRawSn())).isTrue();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_SRC WHERE RAW_SN = ?", s.parentRawSn())).isEqualTo(1);
    }

    @Test
    @DisplayName("최종_삭제_SQL_은_원본_rawSn_을_받아도_0건이다")
    void deleteStatementItselfRefusesOriginal() {
        // given: 원본을 겨눈 표식(위 테스트와 같은 조건)
        Seed s = seed("C1SQL");
        jdbc.update("INSERT INTO LS_DATA_AUG_DSCD (DATA_AUG_SN, NEW_RAW_SN, ORGNL_RAW_SN, DSCD_DT, REG_DT) "
                        + "VALUES (?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                s.dataAugSn(), s.parentRawSn());
        Long dscdSn = jdbc.queryForObject(
                "SELECT MAX(DATA_AUG_DSCD_SN) FROM LS_DATA_AUG_DSCD WHERE NEW_RAW_SN = ?",
                Long.class, s.parentRawSn());

        // when: 서비스 가드를 건너뛰고 <SQL 을 직접> 호출한다(리팩터링·신규 호출처 재현)
        TransactionTemplate tx = new TransactionTemplate(controlTransactionManager);
        int deleted = tx.execute(status -> discardRepository.deleteDiscardedDerivativeRaw(
                s.parentRawSn(), dscdSn, rawSqlCutoff()));

        // then: SQL 자체가 ORGNL_RAW_SN IS NOT NULL 을 요구하므로 0건
        assertThat(deleted).isZero();
        assertThat(rawExists(s.parentRawSn())).isTrue();
    }

    @Test
    @DisplayName("원본영상에는_폐기표식을_기록할_수_없다")
    void cannotMarkOriginalAsDiscarded() {
        // given: 증강 행이 <원본> 을 파생으로 가리키는 정합 이상 상태
        Seed s = seed("C2");
        jdbc.update("UPDATE LS_DATA_AUG SET NEW_RAW_SN = ? WHERE DATA_AUG_SN = ?",
                s.parentRawSn(), s.dataAugSn());

        // when / then: 표식 기록이 거부된다(배치 큐에 원본이 들어가지 못한다)
        assertThatThrownBy(() -> reviewService.reject(s.dataAugSn(), "원본 오연결", REVIEWER))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("원본 영상은 폐기 대상이 아닙니다");
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG_DSCD WHERE DATA_AUG_SN = ?", s.dataAugSn()))
                .isZero();
    }

    // ────────────────────────────────────────────────────────────────────
    // H8 · 유예 · 실삭제
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("승인된_파생은_삭제_대상이_아니다")
    void approvedDerivativeIsNeverPurged() {
        // given: 반려로 표식이 찍힌 뒤 그 파생이 검수 승인(APPROVED)된 모순 상태
        Seed s = seed("H8");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        approve(s.derivativeRawSn());
        expireGrace(s.dataAugSn());
        Long dscdSn = openDiscardSn(s.dataAugSn());

        // when
        assertThat(purgeTxService.claim(dscdSn, sweepCutoff(), LocalDateTime.now())).isTrue();
        AugmentDiscardPurgeTxService.Outcome outcome = purgeTxService.purge(dscdSn, sweepCutoff());

        // then: 관제 접근 보장 — 삭제 거부
        assertThat(outcome.result()).isEqualTo(AugmentDiscardPurgeTxService.Result.REFUSED);
        assertThat(rawExists(s.derivativeRawSn())).isTrue();
    }

    @Test
    @DisplayName("유예_경과_전에는_실삭제되지_않는다")
    void withinGracePeriodNothingIsDeleted() {
        // given: 방금 반려된 파생(백데이팅 없음 = 유예 중)
        Seed s = seed("GRACE");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);

        // when: 운영과 같은 cutoff 로 스윕
        int purged = sweeper.purgeExpired(sweepCutoff());

        // then
        assertThat(purged).isZero();
        assertThat(rawExists(s.derivativeRawSn())).isTrue();
        assertThat(discardRepository.findByDataAugSnAndRstrDtIsNullAndDelDtIsNull(s.dataAugSn()))
                .isPresent();
    }

    /**
     * ★ FIX-1 회귀 — <b>유예를 좁히는 cutoff 를 넘겨도 유예가 줄어들지 않는다.</b>
     *
     * <p>최종 DELETE 의 다른 두 조건은 SQL 리터럴인데 유예만 파라미터였다. 즉 "즉시 폐기" 운영 버튼이나
     * dev 트리거가 {@code purgeExpired(now())} 를 부르는 순간 <b>반려 직후 DB 행과 NAS 파일이 영구
     * 삭제</b>되고 클레임·최종 DELETE 어느 가드도 걸리지 않았다(셋 다 같은 cutoff 를 믿는다).
     */
    @Test
    @DisplayName("유예를_좁히는_cutoff_를_넘겨도_유예_미경과분은_삭제되지_않는다")
    void narrowedCutoffCannotShortenGrace() {
        // given: 방금 반려된(유예가 한참 남은) 파생
        Seed s = seed("CLAMP");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        Long dscdSn = openDiscardSn(s.dataAugSn());

        // when: "즉시 폐기" 를 노린 호출 — 지금 시각, 심지어 먼 미래 시각까지 넣어본다
        assertThat(sweeper.purgeExpired(LocalDateTime.now())).isZero();
        assertThat(sweeper.purgeExpired(LocalDateTime.now().plusYears(1))).isZero();
        // 클레임 진입부도 같은 클램프를 통과한다(집행 자격 자체가 서지 않는다)
        assertThat(purgeTxService.claim(dscdSn, LocalDateTime.now().plusYears(1), LocalDateTime.now()))
                .isFalse();

        // then: 아무것도 지워지지 않고 표식도 열린 채다(복구 가능)
        assertThat(rawExists(s.derivativeRawSn())).isTrue();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_LBL WHERE SRC_SN = ?", s.derivativeSrcSn()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", s.dataAugSn()))
                .isEqualTo(1);
        assertThat(discardRepository.findById(dscdSn).orElseThrow().isOpen()).isTrue();
    }

    @Test
    @DisplayName("유예_경과_후_배치가_DB행과_파일을_삭제한다")
    void purgesAfterGracePeriod() throws IOException {
        // given: 반려된 파생 + 실제 산출물 파일(비식별 저장소 서브트리)
        Seed s = seed("PURGE");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        Path video = writeDerivativeArtifacts(s.parentRawSn(), s.derivativeRawSn());
        expireGrace(s.dataAugSn());

        // when
        int purged = sweeper.purgeExpired(sweepCutoff());

        // then: DB 행 + 파일 모두 사라지고, 비석에 삭제 시각이 남는다
        assertThat(purged).isPositive();
        assertThat(rawExists(s.derivativeRawSn())).isFalse();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", s.dataAugSn())).isZero();
        assertThat(Files.exists(video)).isFalse();
        assertThat(Files.exists(deidBase().resolve("frames/deid/" + s.derivativeRawSn()))).isFalse();

        var tomb = discardRepository.findFirstByDataAugSnOrderByDataAugDscdSnDesc(s.dataAugSn()).orElseThrow();
        assertThat(tomb.getDelDt()).isNotNull();
        assertThat(tomb.getFileDelDt()).isNotNull();
        assertThat(tomb.getVdoFilePath()).isNotBlank();
    }

    /**
     * 파생 삭제 후 <b>고아 전수 확인</b>. 여기 없는 테이블이 하나라도 있으면 "FK 없는 테이블 → 조용히
     * 고아" 라는 이 도메인의 실패 클래스가 그대로 재현된다.
     *
     * <p>{@code LS_DATA_SRC_HSTRY} 가 특히 위험하다 — {@code SRC_SN} 에 FK 가 없고 {@code RAW_SN} 컬럼도
     * 없어 <b>V146 의 RAW CASCADE 대상에도 들어가지 않는다</b>. 증강 파생은 프레임 1장마다 이 이력을
     * 남기므로 누락 시 프레임 수만큼 고아가 쌓인다(FIX-2).
     */
    @Test
    @DisplayName("라벨_속성_라벨맵_프레임이력까지_고아없이_삭제된다")
    void deletesLabelGraphWithoutOrphans() {
        // given: 라벨 + 속성값 + 라벨맵 + 라벨이력 + 프레임 생성이력이 채워진 파생
        Seed s = seed("ORPHAN");
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_LBL_ATTR_VAL WHERE LBL_SN = ?", s.lblSn())).isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_SRC_HSTRY WHERE SRC_SN = ?", s.derivativeSrcSn()))
                .isEqualTo(1);
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        expireGrace(s.dataAugSn());

        // when
        assertThat(sweeper.purgeExpired(sweepCutoff())).isPositive();

        // then: FK 가 없어 CASCADE 로 정리되지 않는 테이블 전부 0건
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_LBL_ATTR_VAL WHERE LBL_SN = ?", s.lblSn())).isZero();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_LBL WHERE SRC_SN = ?", s.derivativeSrcSn())).isZero();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG_LBL_MAP WHERE DATA_AUG_SN = ?", s.dataAugSn())).isZero();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_LBL_HSTRY WHERE SRC_SN = ?", s.derivativeSrcSn())).isZero();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_SRC_HSTRY WHERE SRC_SN = ?", s.derivativeSrcSn())).isZero();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_SRC WHERE RAW_SN = ?", s.derivativeRawSn())).isZero();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG_RVW WHERE DATA_AUG_SN = ?", s.dataAugSn())).isZero();
        // 라벨 마스터의 속성 <정의> 는 절대 삭제 대상이 아니다(이름이 비슷한 다른 테이블).
        assertThat(count("SELECT COUNT(1) FROM LS_LABEL_ATTR")).isPositive();
    }

    @Test
    @DisplayName("매핑없는_그랜드퍼더링_증강은_실삭제되지_않는다")
    void grandfatheredAugmentIsNeverPurged() {
        // given: NEW_RAW_SN 이 없는(백필하지 않은) 구 증강
        Seed s = seed("GF", false);
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);

        // then: 표식 자체가 생기지 않는다 → 스윕이 아무것도 지우지 않는다
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG_DSCD WHERE DATA_AUG_SN = ?", s.dataAugSn()))
                .isZero();
        assertThat(sweeper.purgeExpired(sweepCutoff())).isZero();
        assertThat(rawExists(s.derivativeRawSn())).isTrue();
    }

    /**
     * ★ FIX-6 — 증강 행 삭제가 0건이면 <b>불변식 위반</b>이므로 전체를 되돌린다.
     *
     * <p>증강 행 DELETE 는 {@code DATA_AUG_SN} + {@code NEW_RAW_SN} 동시 일치를 요구하는데 RAW DELETE 는
     * {@code dataAugSn} 과 무관하다. 둘이 드리프트로 어긋나면 <b>RAW 는 지워지고 그 RAW 를 가리키는 증강
     * 행만 (FK 가 없어) 조용히 남는다.</b>
     */
    @Test
    @DisplayName("증강행_삭제가_0건이면_불변식_위반으로_전체_롤백된다")
    void zeroAugmentDeleteAbortsPurge() {
        // given: 유예가 지난 표식 + 증강 행의 NEW_RAW_SN 매핑이 어긋난 상태(드리프트 재현)
        Seed s = seed("INVARIANT");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        expireGrace(s.dataAugSn());
        Long dscdSn = openDiscardSn(s.dataAugSn());
        jdbc.update("UPDATE LS_DATA_AUG SET NEW_RAW_SN = NULL WHERE DATA_AUG_SN = ?", s.dataAugSn());
        assertThat(purgeTxService.claim(dscdSn, sweepCutoff(), LocalDateTime.now())).isTrue();

        // when
        assertThatThrownBy(() -> purgeTxService.purge(dscdSn, sweepCutoff()))
                .isInstanceOf(AugmentDiscardPurgeTxService.DiscardPurgeAbortedException.class)
                .matches(e -> ((AugmentDiscardPurgeTxService.DiscardPurgeAbortedException) e)
                        .isInvariantViolation(), "불변식 위반으로 분류돼야 한다");

        // then: 파생과 자식 행이 모두 살아있다(반쪽 삭제 금지)
        assertThat(rawExists(s.derivativeRawSn())).isTrue();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_LBL WHERE SRC_SN = ?", s.derivativeSrcSn())).isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_SRC_HSTRY WHERE SRC_SN = ?", s.derivativeSrcSn()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", s.dataAugSn())).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────────
    // H7 — 클레임 · 복구 레이스
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("배치_중복_실행시_한_번만_삭제된다")
    void concurrentClaimAllowsSingleExecutor() throws Exception {
        Seed s = seed("RACE");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        expireGrace(s.dataAugSn());
        Long dscdSn = openDiscardSn(s.dataAugSn());

        LocalDateTime cutoff = sweepCutoff();
        LocalDateTime staleCutoff = LocalDateTime.now();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return purgeTxService.claim(dscdSn, cutoff, staleCutoff);
                }));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> f : results) {
                if (Boolean.TRUE.equals(f.get(20, TimeUnit.SECONDS))) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("클레임_이후_복구되면_삭제되지_않는다")
    void restoreAfterClaimBlocksDeletion() {
        Seed s = seed("RESTORE-RACE");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        expireGrace(s.dataAugSn());
        Long dscdSn = openDiscardSn(s.dataAugSn());

        // given: 배치가 클레임을 잡은 상태
        assertThat(purgeTxService.claim(dscdSn, sweepCutoff(), LocalDateTime.now())).isTrue();
        // when: 그 사이 사용자가 복구(별도 트랜잭션)
        discardService.restore(s.dataAugSn(), "오조작 복구", REVIEWER);

        // then: 집행이 대상 아님으로 종결되고 아무것도 지워지지 않는다
        AugmentDiscardPurgeTxService.Outcome outcome = purgeTxService.purge(dscdSn, sweepCutoff());
        assertThat(outcome.result()).isEqualTo(AugmentDiscardPurgeTxService.Result.SKIPPED);
        assertThat(rawExists(s.derivativeRawSn())).isTrue();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_LBL WHERE SRC_SN = ?", s.derivativeSrcSn())).isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", s.dataAugSn())).isEqualTo(1);

        // and: 서비스 가드를 건너뛰어도 <최종 DELETE 문장> 자체가 0건이다(단일 방어선 금지).
        TransactionTemplate tx = new TransactionTemplate(controlTransactionManager);
        int deleted = tx.execute(status -> discardRepository.deleteDiscardedDerivativeRaw(
                s.derivativeRawSn(), dscdSn, rawSqlCutoff()));
        assertThat(deleted).isZero();
        assertThat(rawExists(s.derivativeRawSn())).isTrue();
    }

    @Test
    @DisplayName("최종_DELETE_가_0건이면_앞_단계_삭제까지_롤백된다")
    void abortRollsBackChildDeletes() {
        // given: 유예 경과 표식으로 클레임까지 얻는다
        Seed s = seed("ABORT");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        expireGrace(s.dataAugSn());
        Long dscdSn = openDiscardSn(s.dataAugSn());
        assertThat(purgeTxService.claim(dscdSn, sweepCutoff(), LocalDateTime.now())).isTrue();

        // when: 클레임 이후 표식이 다시 유예 안으로 들어간다(최종 DELETE 가 조건을 재평가해 0건이 되는
        //       경로를 결정적으로 재현 — 운영에서는 복구가 표식을 닫는 레이스가 같은 경로를 탄다).
        //       cutoff 주입으로는 재현할 수 없다 — FIX-1 클램프가 유예 축소를 막기 때문이다.
        jdbc.update("UPDATE LS_DATA_AUG_DSCD SET DSCD_DT = CURRENT_TIMESTAMP WHERE DATA_AUG_DSCD_SN = ?",
                dscdSn);
        assertThatThrownBy(() -> purgeTxService.purge(dscdSn, sweepCutoff()))
                .isInstanceOf(AugmentDiscardPurgeTxService.DiscardPurgeAbortedException.class);

        // then: 앞 단계(라벨·속성값·라벨맵·증강행) 삭제가 전부 되돌아간다 — 반쪽 삭제 금지
        assertThat(rawExists(s.derivativeRawSn())).isTrue();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_LBL WHERE SRC_SN = ?", s.derivativeSrcSn())).isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_LBL_ATTR_VAL WHERE LBL_SN = ?", s.lblSn())).isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG_LBL_MAP WHERE DATA_AUG_SN = ?", s.dataAugSn())).isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", s.dataAugSn())).isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG_RVW WHERE DATA_AUG_SN = ?", s.dataAugSn())).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────────
    // 복구 (확정 설계 2)
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("유예_내에는_복구할_수_있고_다시_결정할_수_있다")
    void restoredAugmentCanBeDecidedAgain() {
        Seed s = seed("REDECIDE");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        assertThat(reviewRepository.findLatestByDataAugSn(s.dataAugSn()).orElseThrow().getRvwSttsCd())
                .isEqualTo(LsDataAugRvw.STTS_REJECTED);

        // when: 복구 → 검수 재오픈
        discardService.restore(s.dataAugSn(), "잘못 눌렀음", REVIEWER);

        // then: PENDING 으로 되돌아가 다시 채택할 수 있고, 채택하면 등재 게이트가 열린다
        assertThat(reviewRepository.findLatestByDataAugSn(s.dataAugSn()).orElseThrow().getRvwSttsCd())
                .isEqualTo(LsDataAugRvw.STTS_PENDING);
        reviewService.accept(s.dataAugSn(), REVIEWER);
        assertThat(reviewRepository.findLatestByDataAugSn(s.dataAugSn()).orElseThrow().getRvwSttsCd())
                .isEqualTo(LsDataAugRvw.STTS_ACCEPTED);
        // 검수 행은 1건을 유지한다 — 새 행이 쌓이면 EXISTS(ACCEPTED) 게이트가 과거 결정에 오염된다.
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG_RVW WHERE DATA_AUG_SN = ?", s.dataAugSn()))
                .isEqualTo(1);
        // 복구했으므로 실삭제 대상이 아니다.
        assertThat(sweeper.purgeExpired(sweepCutoff())).isZero();
        assertThat(rawExists(s.derivativeRawSn())).isTrue();
    }

    /**
     * ★ FIX-7 — <b>표식이 찍히지 않은 반려도 되돌릴 수 있다.</b>
     *
     * <p>매핑 없는 그랜드퍼더링({@code NEW_RAW_SN == null})은 <b>실삭제 대상 제외</b>가 확정 정책이라
     * 표식을 만들지 않는다. 그런데 복구가 표식을 전제하면 <b>그 반려만 영영 되돌릴 수 없다</b>(검수는
     * REJECTED 로 굳고 재결정은 409). 실삭제 제외는 그대로 두고 복구 경로만 연다.
     */
    @Test
    @DisplayName("표식없는_그랜드퍼더링_반려도_복구할_수_있다")
    void rejectWithoutDiscardMarkIsStillRestorable() {
        // given: NEW_RAW_SN 이 없어 표식이 만들어지지 않은 반려
        Seed s = seed("GF-RESTORE", false);
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG_DSCD WHERE DATA_AUG_SN = ?", s.dataAugSn()))
                .isZero();

        // when
        discardService.restore(s.dataAugSn(), "잘못 눌렀음", REVIEWER);

        // then: 검수가 재오픈되어 다시 결정할 수 있다(표식은 여전히 만들지 않는다)
        assertThat(reviewRepository.findLatestByDataAugSn(s.dataAugSn()).orElseThrow().getRvwSttsCd())
                .isEqualTo(LsDataAugRvw.STTS_PENDING);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG_DSCD WHERE DATA_AUG_SN = ?", s.dataAugSn()))
                .isZero();
        assertThat(rawExists(s.derivativeRawSn())).isTrue();
    }

    /**
     * ★ FIX-4 — 표식 기록은 <b>반려 트랜잭션 안에서만</b> 성립한다.
     *
     * <p>{@code AugmentDiscardService} 는 클래스가 {@code readOnly = true} 라, 트랜잭션 밖에서 호출되면
     * 읽기 전용 트랜잭션이 새로 열려 <b>표식 INSERT 가 조용히 유실</b>된다("반려는 됐는데 표식만 없는"
     * 무증상 상태). {@code MANDATORY} 로 그 오배선을 호출 즉시 실패로 드러낸다.
     */
    @Test
    @DisplayName("표식_기록은_트랜잭션_밖에서_호출되면_즉시_실패한다")
    void markDiscardedRequiresExistingTransaction() {
        Seed s = seed("MANDATORY");
        var aug = augRepository.findById(s.dataAugSn()).orElseThrow();

        assertThatThrownBy(() -> discardService.markDiscarded(aug, "반려", REVIEWER.sub()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG_DSCD WHERE DATA_AUG_SN = ?", s.dataAugSn()))
                .isZero();
    }

    @Test
    @DisplayName("반려되지_않은_증강의_복구는_404")
    void restoringNonRejectedAugmentIsNotFound() {
        Seed s = seed("NO-REJECT");

        assertThatThrownBy(() -> discardService.restore(s.dataAugSn(), "복구", REVIEWER))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("복구할 폐기 이력이 없습니다");
    }

    @Test
    @DisplayName("복구는_되돌린_이력을_남긴다")
    void restoreLeavesAuditTrail() {
        Seed s = seed("AUDIT");
        reviewService.reject(s.dataAugSn(), "화질 불량", REVIEWER);

        discardService.restore(s.dataAugSn(), "오조작이었음", REVIEWER);

        var tomb = discardRepository.findFirstByDataAugSnOrderByDataAugDscdSnDesc(s.dataAugSn()).orElseThrow();
        assertThat(tomb.getRstrDt()).isNotNull();                 // 언제
        assertThat(tomb.getMdfcnId()).isEqualTo(REVIEWER.sub());  // 누가
        assertThat(tomb.getRstrRsn()).isEqualTo("오조작이었음");     // 왜
        assertThat(tomb.getDscdRsn()).isEqualTo("화질 불량");        // 원래 반려 사유 스냅샷
    }

    /**
     * ★ FIX-8 — 비석에 <b>무엇을</b> 버렸는지가 남는다. 중복 증강 요청이 허용된 뒤로 같은 (영상 × 종류)
     * 파생이 여러 건 공존하며 결과물을 구분하는 유일한 축이 {@code PROMPT_CN} 이다(구속 정책). 증강 행은
     * 실삭제로 사라지므로 이 값이 비석에 없으면 감사의 앞 절반이 사라진다.
     */
    @Test
    @DisplayName("비석에_증강종류와_프롬프트가_스냅샷된다")
    void tombstoneSnapshotsAugmentTypeAndPrompt() {
        Seed s = seed("PROMPT");
        jdbc.update("UPDATE LS_DATA_AUG SET PROMPT_CN = ? WHERE DATA_AUG_SN = ?",
                "눈 내리는 새벽 도로", s.dataAugSn());
        reviewService.reject(s.dataAugSn(), "화질 불량", REVIEWER);
        expireGrace(s.dataAugSn());

        // 실삭제로 LS_DATA_AUG 행이 사라져도 비석에는 남아야 한다
        assertThat(sweeper.purgeExpired(sweepCutoff())).isPositive();
        assertThat(count("SELECT COUNT(1) FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", s.dataAugSn())).isZero();

        var tomb = discardRepository.findFirstByDataAugSnOrderByDataAugDscdSnDesc(s.dataAugSn()).orElseThrow();
        assertThat(tomb.getAugTypeCd()).isEqualTo("WINTER");
        assertThat(tomb.getPromptCn()).isEqualTo("눈 내리는 새벽 도로");
        assertThat(tomb.getDscdRsn()).isEqualTo("화질 불량");
    }

    @Test
    @DisplayName("삭제된_뒤에는_복구할_수_없다")
    void cannotRestoreAfterPurge() {
        Seed s = seed("TOOLATE");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        expireGrace(s.dataAugSn());
        assertThat(sweeper.purgeExpired(sweepCutoff())).isPositive();

        assertThatThrownBy(() -> discardService.restore(s.dataAugSn(), "되돌리고 싶다", REVIEWER))
                .isInstanceOf(CustomException.class);
    }

    // ────────────────────────────────────────────────────────────────────
    // FIX-3 — 파일 정리 재시도는 반드시 수렴한다
    // ────────────────────────────────────────────────────────────────────

    /**
     * ★ FIX-3 — <b>자동 정리가 불가능한 비석은 재시도 큐를 영구 점유하지 않는다.</b>
     *
     * <p>파생 프레임 트리에 심링크가 있으면 우리는 <b>의도적으로</b> 지우지 않는다(원본 보호). 그런데 그
     * 상태를 "다음 tick 재시도" 로만 두면 정리는 <b>영원히</b> 완료되지 않고, 재시도 큐는
     * {@code DEL_DT} 오름차순이라 그 비석이 <b>항상 앞자리를 점유</b>한다 — batch-size 만큼 쌓이면 이후
     * 비석의 파일 정리가 전면 정지한다(head-of-line blocking).
     */
    @Test
    @DisplayName("자동정리_불가_비석은_재시도큐에서_빠지고_사람개입_대상으로_종결된다")
    void unresolvableFileCleanupLeavesRetryQueue() throws IOException {
        // given: 정상 산출물(비디오 + 프레임)을 갖춘 파생 — 비디오·프레임 경로는 <정상 규약>이라
        //        정리에 성공한다. 미완료 사유를 <심링크 하나>로 한정하기 위한 조건이다
        //        (경로가 규약 밖이면 그것만으로 UNRESOLVABLE 이 되어 테스트가 심링크를 검증하지 못한다).
        Seed s = seed("HOL");
        reviewService.reject(s.dataAugSn(), "반려", REVIEWER);
        Path video = writeDerivativeArtifacts(s.parentRawSn(), s.derivativeRawSn());
        Path frames = seedFile(deidBase().resolve("frames/deid/" + s.derivativeRawSn()));
        Path linkTarget = seedFile(deidBase().resolve("frames/raw/" + s.parentRawSn() + "/frame-0.jpg"));
        Files.createDirectories(linkTarget.getParent());
        Files.writeString(linkTarget, "original-pixels");
        Path link = seedFile(frames.resolve("link.jpg"));
        Files.deleteIfExists(link); // 과거 실행의 잔재가 있어도 이 테스트는 돌아야 한다
        Files.createSymbolicLink(link, linkTarget);
        expireGrace(s.dataAugSn());
        Long dscdSn = openDiscardSn(s.dataAugSn());

        // when: 실삭제(DB 삭제 후 파일 정리 시도)
        assertThat(sweeper.purgeExpired(sweepCutoff())).isPositive();

        // then: 지울 수 있는 것은 지워졌고(비디오), 심링크와 그 대상은 온전하다
        assertThat(Files.exists(video)).isFalse();
        assertThat(Files.exists(linkTarget)).isTrue();
        assertThat(Files.readString(linkTarget)).isEqualTo("original-pixels");
        var tomb = discardRepository.findById(dscdSn).orElseThrow();
        assertThat(tomb.getFileDelDt()).as("파일이 남았으므로 정리 완료로 표시하면 안 된다").isNull();
        assertThat(tomb.isFileCleanupAbandoned()).isTrue();
        assertThat(tomb.getFileDelFailRsn()).isNotBlank();
        assertThat(discardRepository.findFileCleanupPending(100)).doesNotContain(dscdSn);

        // and: 재시도 축을 돌려도 이 비석은 다시 집히지 않는다(무한 재시도 없음)
        int attemptsBefore = tomb.getFileDelRtryNmtm();
        sweeper.retryFileCleanup();
        var after = discardRepository.findById(dscdSn).orElseThrow();
        assertThat(after.getFileDelRtryNmtm()).isEqualTo(attemptsBefore);
        assertThat(discardRepository.findFileCleanupPending(100)).doesNotContain(dscdSn);
        // 시드(심링크·프레임 디렉터리) 정리는 @AfterEach 가 책임진다 — 위 단언이 실패해도 실행된다(FIX-D).
    }

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    /** 파일시스템 시드 등록 — 삭제는 {@code @AfterEach} 가 책임진다(테스트 실패와 무관하게 실행). */
    private Path seedFile(Path path) {
        seededPaths.add(path);
        return path;
    }

    /**
     * 등록된 시드 경로를 <b>깊은 것부터</b> 지운다. 심링크는 링크 자체만 지워지고(대상 미추적)
     * 이미 지워진 경로는 무시된다. 정리 실패가 테스트 결과를 바꾸지 않도록 예외를 삼킨다.
     */
    private void cleanupSeededFiles() {
        seededPaths.stream()
                .sorted((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException | RuntimeException ignored) {
                        // 비어있지 않은 디렉터리 등 — 다음 실행을 막지 않는 잔재는 무시한다.
                    }
                });
        seededPaths.clear();
    }

    private Long openDiscardSn(long dataAugSn) {
        return discardRepository.findByDataAugSnAndRstrDtIsNullAndDelDtIsNull(dataAugSn)
                .orElseThrow().getDataAugDscdSn();
    }

    private Path deidBase() {
        return Path.of(System.getProperty("user.dir"), "storage", "deidentified")
                .toAbsolutePath().normalize();
    }

    /**
     * 파생 산출물(비디오 + 프레임)을 실제 파일로 만든다 — 삭제 검증은 목이 아니라 실 파일시스템으로 한다.
     * 경로 규약은 운영 코드({@code StorageSubtreePolicy})와 동일하며, DB 의 파생 비디오 경로도 함께 맞춘다.
     */
    private Path writeDerivativeArtifacts(long parentRawSn, long derivativeRawSn) throws IOException {
        Path base = deidBase();
        Path video = seedFile(
                base.resolve("videos/augment/" + parentRawSn + "/" + derivativeRawSn + "/WINTER.mp4"));
        Files.createDirectories(video.getParent());
        seedFile(video.getParent());
        Files.writeString(video, "video");
        Path frames = seedFile(base.resolve("frames/deid/" + derivativeRawSn));
        Files.createDirectories(frames);
        Files.writeString(seedFile(frames.resolve("frame-0.jpg")), "frame");
        jdbc.update("UPDATE LS_DATA_RAW SET RAW_FILE_PATH_NM = ? WHERE RAW_SN = ?",
                video.toString(), derivativeRawSn);
        return video;
    }
}

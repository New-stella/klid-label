package kr.co.cudo.authoring.dev;

import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * dev-seed 재적재가 <b>사용자 작업 데이터를 파괴하지 않음</b>을 강제하는 회귀 가드.
 *
 * <h2>재현한 사고 (로컬, 프레임 23건 전량 소실)</h2>
 * 구 시드는 "선(先)DELETE 후 INSERT" 로 멱등을 흉내냈다.
 * <ol>
 *   <li>{@code DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%'} 가 V146 의 자식 FK
 *       ({@code ON DELETE CASCADE})를 타고 그 영상의 프레임({@code LS_DATA_SRC})까지 지웠다.</li>
 *   <li>이어지는 {@code LS_LABEL} INSERT 가 {@code UK_LS_LABEL_DTCT_TYPE} 위반으로 죽었다 —
 *       사용자가 라벨 관리에서 라벨명을 바꿔 <b>COCO 매핑만 겹치는</b> 상태였기 때문이다.</li>
 *   <li>{@code DevSeedRunner} 는 fail-soft 라 부팅을 계속했고, 트랜잭션이 없어 <b>DELETE 는 커밋,
 *       복구 INSERT 는 미실행</b>으로 끝났다. 재기동할 때마다 작업 데이터가 사라졌다.</li>
 * </ol>
 *
 * <p>따라서 세 축을 모두 고정한다 — ①비파괴(존재하면 skip) ②원자성(실패 시 전량 롤백)
 * ③{@code LS_LABEL} 충돌 안전. 어느 하나만 되돌려도 사고가 재발한다.
 *
 * <p>컨텍스트 설정은 {@link DevSeedRunnerTest} 와 동일하게 맞춰 캐시된 컨텍스트를 공유한다.
 * 시드/작업 데이터는 <b>커밋</b>되므로(별도 트랜잭션의 시드가 봐야 한다) 매 테스트 전후로 직접 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.dev.seed.enabled=true")
class DevSeedNonDestructiveIT {

    /** 구 시드의 파괴 대상 네임스페이스({@code DEV-CLIP-%}) 안에 있는 <b>테스트 전용</b> 클립 ID. */
    private static final String SEED_CLIP_ID = "DEV-CLIP-9901";

    /** "사용자가 라벨 관리 화면에서 시드 라벨 이름을 바꿨다" 를 모사할 때 쓰는 이름. */
    private static final String RENAMED_PERSON_LABEL = "사람(비파괴테스트)";

    @Autowired
    private DevSeedRunner devSeedRunner;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    /** 테스트가 직접 만든 라벨 마스터 행 — 정리 대상. */
    private final List<Long> createdLabelIds = new ArrayList<>();

    /** 이름을 바꾼 기존 활성 라벨 — 원래 이름으로 복원한다. */
    private Long renamedLabelId;
    private String originalLabelName;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanupWorkData();
        // 영상 행이 참조하는 CCTV 마스터 보장(시드가 넣지만 순서에 의존하지 않는다).
        jdbc.update("INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, USE_YN) VALUES (?, ?, 'Y') "
                + "ON CONFLICT (VMS_CCTV_ID) DO NOTHING", "CCTV-001", "CCTV-강남구-001");
    }

    @AfterEach
    void tearDown() {
        cleanupWorkData();
        // 라벨 정리는 <생성분 삭제 → 이름 복원> 순. 반대로 하면 이름/매핑 유니크가 잠시 겹친다.
        for (Long lblId : createdLabelIds) {
            jdbc.update("DELETE FROM LS_LABEL WHERE LBL_ID = ?", lblId);
        }
        createdLabelIds.clear();
        if (renamedLabelId != null) {
            jdbc.update("UPDATE LS_LABEL SET LBL_NM = ? WHERE LBL_ID = ?", originalLabelName, renamedLabelId);
            renamedLabelId = null;
            originalLabelName = null;
        }
    }

    // ------------------------------------------------------------------ 회귀 가드

    @Test
    @DisplayName("시드_재실행이_시드영상_위의_사용자_작업데이터_프레임과_라벨을_지우지_않는다")
    void seedRerunPreservesUserWorkData() {
        // given — 시드 소유 네임스페이스(DEV-CLIP-%)의 영상 위에 사용자가 프레임·라벨을 쌓아둔 상태
        long rawSn = insertSeedOwnedVideo();
        long srcSn = insertFrame(rawSn, 0);
        long lblSn = insertFrameLabel(srcSn);

        // when — 재기동과 동일하게 시드를 다시 적재
        devSeedRunner.applySeed();

        // then — 영상·프레임·라벨이 모두 그대로다 (구 코드: CASCADE 로 영상·프레임 소실)
        assertThat(countRaw(rawSn)).isEqualTo(1L);
        assertThat(countFrame(srcSn)).isEqualTo(1L);
        assertThat(countFrameLabel(lblSn)).isEqualTo(1L);
    }

    @Test
    @DisplayName("시드_적재가_실패하는_상황에서도_작업데이터가_사라지지_않는다_사고재현")
    void seedFailureDoesNotDestroyUserWorkData() {
        // given — 사고 당시 상태: 사용자가 시드 라벨명을 바꿔 COCO 매핑만 겹친다(구 시드는 여기서 죽었다)
        renameActivePersonLabel();
        long rawSn = insertSeedOwnedVideo();
        long srcSn = insertFrame(rawSn, 0);

        // when — 부팅 경로 그대로(fail-soft: 예외를 삼키고 부팅 계속)
        devSeedRunner.run();

        // then — 영상·프레임이 살아 있다
        //   구 코드: DELETE 커밋 → LS_LABEL INSERT 실패 → 복구 미실행 = 소실
        assertThat(countRaw(rawSn)).isEqualTo(1L);
        assertThat(countFrame(srcSn)).isEqualTo(1L);
    }

    @Test
    @DisplayName("시드_스크립트가_도중_실패하면_앞서_실행된_DELETE가_롤백된다")
    void seedScriptRunsInSingleTransaction() {
        // given — 삭제 대상 영상 1건 + "삭제 후 반드시 실패하는" 스크립트
        long rawSn = insertSeedOwnedVideo();
        ByteArrayResource failingScript = new ByteArrayResource(
                ("DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID = '" + SEED_CLIP_ID + "';\n"
                        + "SELECT 1/0;\n").getBytes(StandardCharsets.UTF_8));

        // when — 스크립트 실행은 실패한다
        assertThatThrownBy(() -> devSeedRunner.applyScript(failingScript))
                .isInstanceOf(RuntimeException.class);

        // then — DELETE 가 롤백되어 영상이 그대로 남는다 (부분 적용 없음)
        assertThat(countRaw(rawSn)).isEqualTo(1L);
    }

    @Test
    @DisplayName("사용자가_라벨명을_바꿔_COCO매핑만_겹쳐도_시드_재실행이_실패하지_않는다")
    void seedRerunSucceedsWhenUserRenamedMappedLabel() {
        // given — 활성 라벨 1건이 COCO 'person' 매핑을 점유하되 이름은 시드와 다르다
        renameActivePersonLabel();

        // when / then — 시드 재실행이 UK_LS_LABEL_DTCT_TYPE 위반으로 죽지 않는다
        assertThatCode(() -> devSeedRunner.applySeed()).doesNotThrowAnyException();

        // then — 제약(1 COCO 클래스 = 1 활성 라벨)은 그대로이고, 사용자 라벨이 매핑을 유지한다
        assertThat(activeLabelNamesMappedToPerson()).containsExactly(RENAMED_PERSON_LABEL);
    }

    @Test
    @DisplayName("시드와_이름이_같고_매핑이_비어있는_라벨이_있어도_시드_재실행이_실패하지_않는다")
    void seedRerunSucceedsWhenNameMatchesButMappingIsTaken() {
        // given — 사용자가 라벨을 재구성해 "이름만 person" 인 미매핑 라벨과
        //   "매핑만 person" 인 다른 라벨이 <서로 다른 행>으로 공존한다.
        //   → 시드 §6-1 의 매핑 백필 UPDATE 가 UK_LS_LABEL_DTCT_TYPE 를 위반할 수 있는 상태.
        renameActivePersonLabel();
        createdLabelIds.add(insertLabelMaster("person", null));

        // when / then — 시드 재실행이 죽지 않는다
        assertThatCode(() -> devSeedRunner.applySeed()).doesNotThrowAnyException();

        // then — 매핑은 여전히 사용자 라벨 1건만 보유한다
        assertThat(activeLabelNamesMappedToPerson()).containsExactly(RENAMED_PERSON_LABEL);
    }

    // -------------------------------------------------------------------- 픽스처

    /** 구 시드의 파괴 대상({@code VMS_CLIP_ID LIKE 'DEV-CLIP-%'})에 속하는 영상 1건. */
    private long insertSeedOwnedVideo() {
        Long rawSn = jdbc.queryForObject("""
                INSERT INTO LS_DATA_RAW
                    (VMS_CLIP_ID, VMS_CCTV_ID, PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN,
                     RAW_FILE_PATH_NM, DATA_STTS_CD, REG_DT)
                VALUES (?, 'CCTV-001', 'ANONY', 'N', 'N', ?, 'PENDING', CURRENT_TIMESTAMP)
                RETURNING RAW_SN
                """, Long.class, SEED_CLIP_ID, "./storage/raw/seed/dev-seed-guard.mp4");
        assertThat(rawSn).isNotNull();
        return rawSn;
    }

    private long insertFrame(long rawSn, int frameNo) {
        Long srcSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, ?, ?) RETURNING SRC_SN",
                Long.class, rawSn, frameNo, "./storage/raw/seed/dev-seed-guard-0.jpg");
        assertThat(srcSn).isNotNull();
        return srcSn;
    }

    private long insertFrameLabel(long srcSn) {
        Long lblSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_LBL (SRC_SN, LBL_TYPE_CD, LBL_NM, POINT_CN, REG_DT) "
                        + "VALUES (?, 'BBOX', ?, '[]', CURRENT_TIMESTAMP) RETURNING LBL_SN",
                Long.class, srcSn, "guard-label");
        assertThat(lblSn).isNotNull();
        return lblSn;
    }

    private long insertLabelMaster(String name, String dtctTypeCd) {
        Long lblId = jdbc.queryForObject(
                "INSERT INTO LS_LABEL (LBL_NM, COLR_VL, LBL_TYPE_CD, SORT_SEQ, USE_YN, REG_DT, DTCT_TYPE_CD) "
                        + "VALUES (?, '#123456', 'BBOX', 99, 'Y', CURRENT_TIMESTAMP, ?) RETURNING LBL_ID",
                Long.class, name, dtctTypeCd);
        assertThat(lblId).isNotNull();
        return lblId;
    }

    /**
     * COCO {@code person} 매핑을 점유한 활성 라벨의 이름을 사용자 지정 이름으로 바꾼다
     * (없으면 그 상태의 라벨을 새로 만든다). 원래 이름은 {@code @AfterEach} 에서 복원한다.
     */
    private void renameActivePersonLabel() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT LBL_ID, LBL_NM FROM LS_LABEL WHERE USE_YN = 'Y' AND DTCT_TYPE_CD = 'person'");
        if (rows.isEmpty()) {
            createdLabelIds.add(insertLabelMaster(RENAMED_PERSON_LABEL, "person"));
            return;
        }
        renamedLabelId = ((Number) rows.get(0).get("lbl_id")).longValue();
        originalLabelName = (String) rows.get(0).get("lbl_nm");
        jdbc.update("UPDATE LS_LABEL SET LBL_NM = ? WHERE LBL_ID = ?", RENAMED_PERSON_LABEL, renamedLabelId);
    }

    private List<String> activeLabelNamesMappedToPerson() {
        return jdbc.queryForList(
                "SELECT LBL_NM FROM LS_LABEL WHERE USE_YN = 'Y' AND DTCT_TYPE_CD = 'person'", String.class);
    }

    // -------------------------------------------------------------------- 정리

    private void cleanupWorkData() {
        // LS_DATA_LBL 은 LS_DATA_SRC 로의 FK 가 없어 CASCADE 로 정리되지 않는다 — 먼저 지운다.
        jdbc.update("""
                DELETE FROM LS_DATA_LBL WHERE SRC_SN IN (
                    SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN IN (
                        SELECT RAW_SN FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?))
                """, SEED_CLIP_ID);
        RawVideoFixture.deleteRawsWhere(jdbc, "VMS_CLIP_ID = ?", SEED_CLIP_ID);
    }

    private long countRaw(long rawSn) {
        return count("SELECT COUNT(*) FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
    }

    private long countFrame(long srcSn) {
        return count("SELECT COUNT(*) FROM LS_DATA_SRC WHERE SRC_SN = ?", srcSn);
    }

    private long countFrameLabel(long lblSn) {
        return count("SELECT COUNT(*) FROM LS_DATA_LBL WHERE LBL_SN = ?", lblSn);
    }

    private long count(String sql, Object arg) {
        Long n = jdbc.queryForObject(sql, Long.class, arg);
        return n == null ? 0L : n;
    }
}

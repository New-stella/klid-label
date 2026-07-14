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
 * Phase 2 (키포인트) — <b>V_COMPLETED_LABEL 이 SKELETON 삼중값을 pass-through 함을 실증</b>.
 *
 * <p>뷰 정의는 {@code lbl.POINT_CN AS POINTS_JSON} 로 POINT_CN 원문을 그대로 노출하므로
 * SKELETON 라벨의 17×[x,y,v] 삼중값이 변형 없이 POINTS_JSON 으로 조회돼야 한다(뷰 DDL 무변경).
 * COCO-pose JSON 파일 조립은 외부 데이터마트 위임 — 저작도구 책임은 이 View 노출까지.
 *
 * <p>APPROVED 게이트(WHERE LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED') 보존도 함께 검증한다.
 * 시드는 공유 컨테이너 오염 방지를 위해 고유 RAW_SN 으로 넣고 {@link #cleanup()} 에서 제거한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class CompletedLabelSkeletonIT {

    /** COCO-native 17×[x,y,v] 삼중값(간략화된 대표값). */
    private static final String SKELETON_POINTS_JSON =
            "[[10.0,20.0,2],[11.0,21.0,2],[12.0,22.0,1],[13.0,23.0,0],[14.0,24.0,2],"
                    + "[15.0,25.0,2],[16.0,26.0,2],[17.0,27.0,1],[18.0,28.0,2],[19.0,29.0,2],"
                    + "[20.0,30.0,2],[21.0,31.0,2],[22.0,32.0,2],[23.0,33.0,1],[24.0,34.0,2],"
                    + "[25.0,35.0,2],[26.0,36.0,2]]";

    private final JdbcTemplate jdbc;
    private final List<Long> seededRawSns = new ArrayList<>();

    CompletedLabelSkeletonIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_DATA_LBL WHERE SRC_SN IN "
                    + "(SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = ?)", rawSn);
            jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
    }

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

    private long seedFrame(long rawSn, int frameNo) {
        return jdbc.queryForObject(
                "INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM, "
                        + "DE_IDNTF_SRC_FILE_PATH_NM, SHT_DT, REG_DT) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING SRC_SN",
                Long.class,
                rawSn, frameNo, "/nas/frames/raw/" + rawSn + "/" + frameNo + ".jpg",
                "/nas/frames/deid/" + rawSn + "/" + frameNo + ".jpg",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
    }

    private long seedSkeletonLabel(long srcSn, String pointsJson) {
        return jdbc.queryForObject(
                "INSERT INTO LS_DATA_LBL (SRC_SN, LBL_TYPE_CD, LBL_NM, POINT_CN, REG_DT) "
                        + "VALUES (?, 'SKELETON', 'human-pose', ?, ?) RETURNING LBL_SN",
                Long.class,
                srcSn, pointsJson, LocalDateTime.now());
    }

    @Test
    @DisplayName("V_COMPLETED_LABEL_SKELETON행_삼중값_노출")
    void completedLabel_exposesSkeletonTripleValues() {
        // given — APPROVED 영상 + 프레임 + SKELETON 라벨(삼중값)
        long rawSn = seedRawAndStatus("APPROVED");
        long srcSn = seedFrame(rawSn, 10);
        long lblSn = seedSkeletonLabel(srcSn, SKELETON_POINTS_JSON);

        // when — 뷰에서 SKELETON 행 조회
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT LBL_TYPE_CD, POINTS_JSON FROM V_COMPLETED_LABEL WHERE LBL_SN = ?", lblSn);

        // then — 타입 SKELETON + POINTS_JSON 이 POINT_CN 원문 삼중값 그대로(pass-through)
        assertThat(row.get("lbl_type_cd")).isEqualTo("SKELETON");
        assertThat(String.valueOf(row.get("points_json"))).isEqualTo(SKELETON_POINTS_JSON);
    }

    @Test
    @DisplayName("미승인영상_SKELETON라벨은_뷰_미노출_APPROVED게이트_보존")
    void completedLabel_skeletonHiddenWhenNotApproved() {
        // given — PENDING(미승인) 영상의 SKELETON 라벨
        long rawSn = seedRawAndStatus("PENDING");
        long srcSn = seedFrame(rawSn, 1);
        long lblSn = seedSkeletonLabel(srcSn, SKELETON_POINTS_JSON);

        // when
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM V_COMPLETED_LABEL WHERE LBL_SN = ?", Integer.class, lblSn);

        // then — 미승인 영상 라벨은 노출되지 않음(WHERE 게이트 보존)
        assertThat(count).isZero();
    }
}

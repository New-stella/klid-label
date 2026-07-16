package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트랙 보간 대상 조회({@link LsDataLblRepository#findAutoBboxWithTrackId})가
 * BBOX 뿐 아니라 POLYGON 도 포함하는지 검증 (R1 폴리곤 트랙 보간).
 *
 * <p>과거 {@code LBL_TYPE_CD='BBOX'} 하드코딩 → {@code IN ('BBOX','POLYGON')} 로 확장.
 * SEGMENT/SKELETON 은 여전히 제외되어야 한다.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDataLblRepositoryPolygonTrackTest {

    @Autowired
    private LsDataLblRepository lblRepository;
    @Autowired
    private LsDataSrcRepository srcRepository;
    @Autowired
    private LsDataLblAiInfoRepository aiInfoRepository;

    private Long persistAutoLabel(Long rawSn, long frameNo, String type, String trackId, String pointsJson) {
        LsDataSrc src = srcRepository.saveAndFlush(
                LsDataSrc.create(rawSn, frameNo, "raw/" + rawSn + "/" + frameNo + ".png", null));
        LsDataLbl lbl = LsDataLbl.createManual(src.getSrcSn(), type, null, "person", pointsJson, null);
        lbl = lblRepository.saveAndFlush(lbl);
        // 자동 라벨로 인식되도록 AUTO_LBL_YN='Y' AI_INFO 부여 (create 가 'Y' 강제). LBL_SRC_CD 는 NOT NULL.
        aiInfoRepository.saveAndFlush(LsDataLblAiInfo.create(
                lbl.getLblSn(), rawSn, src.getSrcSn(), LsDataLblAiInfo.SRC_YOLO, BigDecimal.valueOf(0.9), "batch"));
        // trackId 는 createManual 이 세팅하지 않으므로 별도 부여.
        setTrackId(lbl, trackId);
        lblRepository.saveAndFlush(lbl);
        return lbl.getLblSn();
    }

    private static void setTrackId(LsDataLbl lbl, String trackId) {
        try {
            var f = LsDataLbl.class.getDeclaredField("trackId");
            f.setAccessible(true);
            f.set(lbl, trackId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("findAutoBboxWithTrackId_POLYGON도_조회됨")
    void polygonIncludedInInterpolationCandidates() {
        Long rawSn = 990_401L;
        Long bboxSn = persistAutoLabel(rawSn, 0, "BBOX", "t-b", "[[0,0],[10,10]]");
        Long polySn = persistAutoLabel(rawSn, 1, "POLYGON", "t-p", "[[0,0],[10,0],[5,10]]");
        // 보간 대상 외 — SEGMENT 는 조회되면 안 됨
        Long segSn = persistAutoLabel(rawSn, 2, "SEGMENT", "t-s", "[[0,0],[10,0],[5,10]]");

        List<LsDataLbl> found = lblRepository.findAutoBboxWithTrackId(rawSn);

        assertThat(found).extracting(LsDataLbl::getLblSn)
                .contains(bboxSn, polySn)
                .doesNotContain(segSn);
        assertThat(found).extracting(LsDataLbl::getLblTypeCd)
                .contains("BBOX", "POLYGON");
    }

    @Test
    @DisplayName("findInterpolatedLblSnsByRawSn_보간생성row만_조회")
    void findsOnlyInterpolatedRows() {
        Long rawSn = 990_402L;
        // detection 라벨 (lblSrcCd 미지정) + 보간 라벨 (lblSrcCd='INTERPOLATE')
        LsDataSrc src = srcRepository.saveAndFlush(LsDataSrc.create(rawSn, 0, "raw/x/0.png", null));
        LsDataLbl detected = lblRepository.saveAndFlush(
                LsDataLbl.createAutoBbox(src.getSrcSn(), null, "car", "[[0,0],[10,10]]", BigDecimal.valueOf(0.9), "d1"));
        aiInfoRepository.saveAndFlush(LsDataLblAiInfo.create(
                detected.getLblSn(), rawSn, src.getSrcSn(), LsDataLblAiInfo.SRC_YOLO, BigDecimal.valueOf(0.9), "batch"));

        LsDataLbl interp = lblRepository.saveAndFlush(
                LsDataLbl.createAutoInterpolatedBbox(src.getSrcSn(), null, "car", "[[1,1],[11,11]]", BigDecimal.ZERO, "d1"));
        aiInfoRepository.saveAndFlush(LsDataLblAiInfo.create(
                interp.getLblSn(), rawSn, src.getSrcSn(), LsDataLblAiInfo.SRC_INTERPOLATE, BigDecimal.ZERO, "batch"));

        List<Long> stale = lblRepository.findInterpolatedLblSnsByRawSn(rawSn);

        assertThat(stale).contains(interp.getLblSn()).doesNotContain(detected.getLblSn());
    }
}

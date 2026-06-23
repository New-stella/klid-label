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
 * Bug 1 — findAutoLabelInfoByRawSn 가 LS_DATA_LBL + LS_DATA_LBL_AI_INFO 를 단일 LEFT JOIN 으로
 * 조회해 auto/manual 구분과 신뢰도를 정확히 투영하는지 DB 라운드트립으로 검증한다.
 *
 * <p>회귀의 핵심: LsDataLbl.autoLblYn/confScore 는 @Transient 라 DB 조회 시 항상 null 이므로,
 * 본체만 읽으면 모두 manual·null 이 된다. AI_INFO 조인이 실제 값을 가져와야 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDataLblRepositoryAutoLabelInfoTest {

    @Autowired
    private LsDataLblRepository lblRepository;

    @Autowired
    private LsDataSrcRepository srcRepository;

    @Autowired
    private LsDataLblAiInfoRepository aiInfoRepository;

    @Test
    @DisplayName("AI_INFO_auto_라벨은_autoLblYn_Y_와_confScore_를_수동라벨은_null_을_투영한다")
    void joinProjectsAutoAndManualCorrectly() {
        // given — 동일 영상(rawSn)에 프레임 1개, 그 위에 auto 라벨 1건 + manual 라벨 1건
        LsDataSrc src = srcRepository.saveAndFlush(
                LsDataSrc.create(995_001L, 0, "raw/frame0.jpg", null));

        LsDataLbl autoLbl = lblRepository.saveAndFlush(LsDataLbl.createAutoBbox(
                src.getSrcSn(), null, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.9), "track-1"));
        LsDataLbl manualLbl = lblRepository.saveAndFlush(LsDataLbl.createManual(
                src.getSrcSn(), LsDataLbl.TYPE_BBOX, null, "car", "[[3,3],[4,4]]", 7L));

        // auto 라벨에만 AI_INFO(auto_lbl_yn='Y', conf_score) 적재
        aiInfoRepository.saveAndFlush(LsDataLblAiInfo.create(
                autoLbl.getLblSn(), 995_001L, src.getSrcSn(),
                LsDataLblAiInfo.SRC_YOLO, new BigDecimal("0.90000"), "test"));

        // when
        List<AutoLabelInfoProjection> rows = lblRepository.findAutoLabelInfoByRawSn(995_001L);

        // then — 라벨당 1행, auto 는 'Y'+conf, manual 은 null
        assertThat(rows).hasSize(2);

        AutoLabelInfoProjection autoRow = rows.stream()
                .filter(r -> r.getLblSn().equals(autoLbl.getLblSn())).findFirst().orElseThrow();
        assertThat(autoRow.getAutoLblYn()).isEqualTo("Y");
        assertThat(autoRow.getConfScore()).isNotNull();
        assertThat(autoRow.getConfScore()).isEqualByComparingTo("0.9");

        AutoLabelInfoProjection manualRow = rows.stream()
                .filter(r -> r.getLblSn().equals(manualLbl.getLblSn())).findFirst().orElseThrow();
        assertThat(manualRow.getAutoLblYn()).isNull();
        assertThat(manualRow.getConfScore()).isNull();
    }

    @Test
    @DisplayName("AI_INFO_가_2건이면_MAX가_아니라_최신행(mdfcnDt기준)_의_confScore_를_라벨당1건으로_투영한다")
    void picksLatestAiInfoNotMax() {
        // given — 한 라벨에 auto AI_INFO 2건:
        //   ① 먼저 conf=0.90 적재(mdfcnDt 없음)
        //   ② 이후 더 최근에 conf=0.50, auto_lbl_yn='Y' 로 갱신(mdfcnDt 설정)
        // MAX(conf)=0.90 이지만 최신 행은 0.50 이므로 0.50 이 나와야 한다.
        LsDataSrc src = srcRepository.saveAndFlush(
                LsDataSrc.create(995_002L, 0, "raw/frame0.jpg", null));

        LsDataLbl autoLbl = lblRepository.saveAndFlush(LsDataLbl.createAutoBbox(
                src.getSrcSn(), null, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.9), "track-1"));

        // ① 먼저 적재된 행 (더 높은 신뢰도, mdfcnDt 없음)
        aiInfoRepository.saveAndFlush(LsDataLblAiInfo.create(
                autoLbl.getLblSn(), 995_002L, src.getSrcSn(),
                LsDataLblAiInfo.SRC_YOLO, new BigDecimal("0.90000"), "test"));

        // ② 이후 더 최근에 적재된 행 (더 낮은 신뢰도, mdfcnDt 설정 → 최신)
        LsDataLblAiInfo newer = LsDataLblAiInfo.create(
                autoLbl.getLblSn(), 995_002L, src.getSrcSn(),
                LsDataLblAiInfo.SRC_VLM, new BigDecimal("0.90000"), "test");
        newer.updateConfidence(new BigDecimal("0.50000"), "vlm");
        aiInfoRepository.saveAndFlush(newer);

        // when
        List<AutoLabelInfoProjection> rows = lblRepository.findAutoLabelInfoByRawSn(995_002L);

        // then — 라벨은 1건만, 신뢰도는 MAX(0.90)가 아닌 최신(0.50)
        assertThat(rows).hasSize(1);
        AutoLabelInfoProjection row = rows.get(0);
        assertThat(row.getLblSn()).isEqualTo(autoLbl.getLblSn());
        assertThat(row.getAutoLblYn()).isEqualTo("Y");
        assertThat(row.getConfScore()).isEqualByComparingTo("0.50000");
    }
}

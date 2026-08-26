package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug 1 — findAutoLabelInfoByRawSn 가 auto/manual 구분과 신뢰도를 정확히 투영하는지 DB
 * 라운드트립으로 검증한다. V6 흡수 이후 두 값은 LS_DATA_LBL <b>본체 컬럼</b>이라 조인이 없다.
 *
 * <p><b>V6 이전 회귀의 핵심이었던 것</b>: 두 값이 별도 테이블에 있고 엔티티 필드가 @Transient 라
 * 본체만 읽으면 전부 manual·null 이 됐다(그래서 조인이 실제 값을 가져와야 했다). 흡수로 그 함정은
 * 사라졌고, 지금 이 테스트가 고정하는 것은 <b>투영 계약</b>이다 — 자동이 아닌 라벨은 두 값을 null 로
 * 내보내야 한다(그 매핑이 바뀌면 화면의 auto/manual 표시가 뒤집힌다).
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
    private LsLabelRepository labelRepository;


    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("AI_INFO_auto_라벨은_autoLblYn_Y_와_confScore_를_수동라벨은_null_을_투영한다")
    void joinProjectsAutoAndManualCorrectly() {
        // given — 부모 영상(V146 FK) + 동일 영상(rawSn)에 프레임 1개, 그 위에 auto 라벨 1건 + manual 라벨 1건
        RawVideoFixture.seedRaw(jdbcTemplate, 995_001L);
        LsDataSrc src = srcRepository.saveAndFlush(
                LsDataSrc.create(995_001L, 0, "raw/frame0.jpg", null));

        LsDataLbl autoLbl = lblRepository.saveAndFlush(LsDataLbl.createAutoBbox(
                src.getSrcSn(), null, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.9), "track-1"));
        LsDataLbl manualLbl = lblRepository.saveAndFlush(LsDataLbl.createManual(
                src.getSrcSn(), LsDataLbl.TYPE_BBOX, null, "car", "[[3,3],[4,4]]", 7L));

        // auto 라벨에만 AI_INFO(auto_lbl_yn='Y', conf_score) 적재
        // V6 — 생산이력이 라벨 행의 컬럼이라 AI 정보 행 대신 그 라벨에 직접 부여한다.
        autoLbl.applyAiSource(LsDataLbl.SRC_YOLO, new BigDecimal("0.90000"));
        lblRepository.saveAndFlush(autoLbl);

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
    @DisplayName("투영이_LBL_ID_를_실어_돌려준다_마스터_미연결은_null_이다")
    void projectsLabelIdForMasterLookup() {
        // [design: API-044] 오토라벨 응답의 표시명·표시색은 이 키로 라벨 마스터를 배치 조회해 조달한다.
        //   투영에서 이 값이 빠지면 조회 자체가 불가능해 라벨명이 저장 원문(COCO 영문)으로만 나간다.
        RawVideoFixture.seedRaw(jdbcTemplate, 995_003L);
        LsDataSrc src = srcRepository.saveAndFlush(
                LsDataSrc.create(995_003L, 0, "raw/frame0.jpg", null));

        // 활성 라벨 마스터 1건을 만들어 그 PK 를 라벨에 연결한다(FK 위반 없이 실제 값으로 왕복).
        LsLabel master = labelRepository.saveAndFlush(
                LsLabel.create("검증용라벨-995003", "#E11D48", "BBOX", 0, "tester"));

        LsDataLbl linked = lblRepository.saveAndFlush(LsDataLbl.createAutoBbox(
                src.getSrcSn(), master.getLabelId(), "person", "[[1,1],[2,2]]",
                BigDecimal.valueOf(0.9), "track-1"));
        LsDataLbl unlinked = lblRepository.saveAndFlush(LsDataLbl.createAutoBbox(
                src.getSrcSn(), null, "bicycle", "[[3,3],[4,4]]",
                BigDecimal.valueOf(0.7), "track-2"));

        // when
        List<AutoLabelInfoProjection> rows = lblRepository.findAutoLabelInfoByRawSn(995_003L);

        // then — 연결 라벨은 실제 마스터 PK, 미연결 라벨은 null
        assertThat(rows).hasSize(2);
        AutoLabelInfoProjection linkedRow = rows.stream()
                .filter(r -> r.getLblSn().equals(linked.getLblSn())).findFirst().orElseThrow();
        assertThat(linkedRow.getLabelId()).isEqualTo(master.getLabelId());

        AutoLabelInfoProjection unlinkedRow = rows.stream()
                .filter(r -> r.getLblSn().equals(unlinked.getLblSn())).findFirst().orElseThrow();
        assertThat(unlinkedRow.getLabelId()).isNull();

        // 기존 계약(auto 축)이 이 추가로 흔들리지 않는지 함께 고정
        assertThat(linkedRow.getLabelNm()).isEqualTo("person");
        assertThat(linkedRow.getAutoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("VLM_이_신뢰도를_갱신하면_투영이_갱신된_값을_돌려준다")
    void projectsUpdatedConfidence() {
        // V6 — 구 케이스는 "한 라벨에 AI_INFO 가 2건일 때 MAX 가 아니라 최신 행을 고르는가"였다.
        //   흡수로 한 라벨 = 값 1개가 되어 <b>고를 대상 자체가 없어졌다</b>(그 선택은 마이그레이션이
        //   1회 수행해 결과를 고정했다 — V6 헤더 「결정적 규칙」). 그래서 이 케이스는 폐기하지 않고
        //   "갱신된 신뢰도가 그대로 투영되는가"로 축을 옮긴다 — 화면이 최신 값을 봐야 한다는 원래
        //   요구는 그대로이기 때문이다.
        RawVideoFixture.seedRaw(jdbcTemplate, 995_002L);
        LsDataSrc src = srcRepository.saveAndFlush(
                LsDataSrc.create(995_002L, 0, "raw/frame0.jpg", null));

        LsDataLbl autoLbl = lblRepository.saveAndFlush(LsDataLbl.createAutoBbox(
                src.getSrcSn(), null, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.9), "track-1"));
        autoLbl.applyAiSource(LsDataLbl.SRC_YOLO, new BigDecimal("0.90000"));
        lblRepository.saveAndFlush(autoLbl);

        // VLM 객체 검증 등으로 신뢰도만 갱신.
        autoLbl.updateConfScore(new BigDecimal("0.50000"));
        lblRepository.saveAndFlush(autoLbl);

        // when
        List<AutoLabelInfoProjection> rows = lblRepository.findAutoLabelInfoByRawSn(995_002L);

        // then — 라벨은 1건만, 신뢰도는 갱신값(0.50).
        assertThat(rows).hasSize(1);
        AutoLabelInfoProjection row = rows.get(0);
        assertThat(row.getLblSn()).isEqualTo(autoLbl.getLblSn());
        assertThat(row.getAutoLblYn()).isEqualTo("Y");
        assertThat(row.getConfScore()).isEqualByComparingTo("0.50000");
    }
}

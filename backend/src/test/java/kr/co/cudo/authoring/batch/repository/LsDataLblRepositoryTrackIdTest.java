package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
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
 * Phase 3: V18 마이그레이션으로 추가된 LS_DATA_LBL.TRACK_ID 컬럼 저장/조회 검증.
 *
 * <p>인덱스(IDX_LS_DATA_LBL_TRACK_ID) 동작은 단위 테스트 범위 외 — DDL validation 으로 충분.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDataLblRepositoryTrackIdTest {

    @Autowired
    private LsDataLblRepository repository;

    @Test
    @DisplayName("TRACK_ID_컬럼_에_String_저장_+_조회_정합")
    void persistsAndReadsTrackId() {
        LsDataLbl saved = repository.saveAndFlush(LsDataLbl.createAutoBbox(
                999_001L, null, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.9), "track-42"));

        LsDataLbl found = repository.findById(saved.getLblSn()).orElseThrow();

        assertThat(found.getTrackId()).isEqualTo("track-42");
    }

    @Test
    @DisplayName("TRACK_ID_null_허용_(레거시_수동_라벨_호환)")
    void nullTrackIdAllowed() {
        LsDataLbl saved = repository.saveAndFlush(LsDataLbl.createAutoBbox(
                999_002L, null, "car", "[]", BigDecimal.valueOf(0.7), null));

        LsDataLbl found = repository.findById(saved.getLblSn()).orElseThrow();

        assertThat(found.getTrackId()).isNull();
    }

    @Test
    @DisplayName("동일_TRACK_ID_여러_프레임_저장_가능_(트래커_연속성)")
    void sameTrackIdAcrossFrames() {
        LsDataLbl a = repository.saveAndFlush(LsDataLbl.createAutoBbox(
                999_010L, null, "person", "[]", BigDecimal.valueOf(0.9), "track-1"));
        LsDataLbl b = repository.saveAndFlush(LsDataLbl.createAutoBbox(
                999_011L, null, "person", "[]", BigDecimal.valueOf(0.92), "track-1"));

        List<LsDataLbl> labels = List.of(
                repository.findById(a.getLblSn()).orElseThrow(),
                repository.findById(b.getLblSn()).orElseThrow()
        );

        assertThat(labels).extracting(LsDataLbl::getTrackId)
                .containsExactly("track-1", "track-1");
    }

    // --- Phase 3: V20 LBL_SRC_CD 컬럼 + createAutoInterpolatedBbox 라운드트립 ---

    @Test
    @DisplayName("LBL_SRC_CD_INTERPOLATE_저장_+_조회_정합")
    void persistsAndReadsLblSrcCdInterpolated() {
        LsDataLbl saved = repository.saveAndFlush(LsDataLbl.createAutoInterpolatedBbox(
                999_020L, null, "person", "[0.0,0.0,10.0,10.0]", BigDecimal.ZERO, "track-100"));

        LsDataLbl found = repository.findById(saved.getLblSn()).orElseThrow();

        // ⚠ V6 — 구 기대값 "INTERPOLATED" 는 @Transient 시절의 <b>메모리 값</b>이었다(같은 트랜잭션의
        //   findById 가 1차 캐시에서 같은 인스턴스를 돌려줘 통과했을 뿐, 그 값이 DB 에 있던 적은 없다).
        //   흡수로 실 컬럼이 되면서 적재값 "INTERPOLATE" 로 통일했다 — 되돌리지 말 것.
        assertThat(found.getLblSrcCd()).isEqualTo(LsDataLbl.SRC_INTERPOLATE);
        assertThat(found.getAutoLblYn()).isEqualTo("Y");
        assertThat(found.getLblTypeCd()).isEqualTo("BBOX");
        assertThat(found.getTrackId()).isEqualTo("track-100");
    }

    @Test
    @DisplayName("Phase3_LBL_SRC_CD_기본_NULL_(detection_라벨)")
    void detectedLabelLblSrcCdNull() {
        LsDataLbl saved = repository.saveAndFlush(LsDataLbl.createAutoBbox(
                999_021L, null, "car", "[]", BigDecimal.valueOf(0.85), "track-200"));

        LsDataLbl found = repository.findById(saved.getLblSn()).orElseThrow();

        assertThat(found.getLblSrcCd()).isNull();
    }
}

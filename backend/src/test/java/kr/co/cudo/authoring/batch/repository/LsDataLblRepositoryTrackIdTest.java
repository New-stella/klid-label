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
                999_001L, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.9), "track-42"));

        LsDataLbl found = repository.findById(saved.getLblSn()).orElseThrow();

        assertThat(found.getTrackId()).isEqualTo("track-42");
    }

    @Test
    @DisplayName("TRACK_ID_null_허용_(레거시_수동_라벨_호환)")
    void nullTrackIdAllowed() {
        LsDataLbl saved = repository.saveAndFlush(LsDataLbl.createAutoBbox(
                999_002L, "car", "[]", BigDecimal.valueOf(0.7)));

        LsDataLbl found = repository.findById(saved.getLblSn()).orElseThrow();

        assertThat(found.getTrackId()).isNull();
    }

    @Test
    @DisplayName("동일_TRACK_ID_여러_프레임_저장_가능_(트래커_연속성)")
    void sameTrackIdAcrossFrames() {
        LsDataLbl a = repository.saveAndFlush(LsDataLbl.createAutoBbox(
                999_010L, "person", "[]", BigDecimal.valueOf(0.9), "track-1"));
        LsDataLbl b = repository.saveAndFlush(LsDataLbl.createAutoBbox(
                999_011L, "person", "[]", BigDecimal.valueOf(0.92), "track-1"));

        List<LsDataLbl> labels = List.of(
                repository.findById(a.getLblSn()).orElseThrow(),
                repository.findById(b.getLblSn()).orElseThrow()
        );

        assertThat(labels).extracting(LsDataLbl::getTrackId)
                .containsExactly("track-1", "track-1");
    }
}

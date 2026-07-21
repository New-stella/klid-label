package kr.co.cudo.authoring.version.repository;

import kr.co.cudo.authoring.version.entity.LabelChange;
import kr.co.cudo.authoring.version.entity.LabelSnapshot;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V114 저장이벤트 재구조화 — 실제 Flyway 스키마(Testcontainers PostgreSQL) 위에서
 * LS_DATA_LBL_HSTRY 저장 이벤트 행을 insert/조회하여 ddl-auto=validate 정합(컬럼·타입·nullable)을 확인한다.
 *
 * <p>V114 이전이면 신 컬럼(ADD_CNT/MDFCN_CNT/DEL_CNT/CHG_DTL_CN) 부재로 FAIL(RED),
 * V114 적용 후 PASS(GREEN). "라벨 1건=1행"(구 LBL_SN/CHG_KIND_CD)에서 "저장 이벤트=1행"으로 전환됐다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDataLblHstryRepositoryTest {

    @Autowired
    private LsDataLblHstryRepository repository;

    private LabelChange deleted(long lblSn) {
        return LabelChange.deleted(lblSn, "person",
                new LabelSnapshot("BBOX", null, "person", "[[1,1],[2,2]]"));
    }

    @Test
    @DisplayName("저장이벤트가_LS_DATA_LBL_HSTRY에_저장되고_PK와_건수집계가_보존된다")
    void saveAssignsIdentityPkAndCounts() {
        // given — 프레임 40 삭제 이벤트(라벨 1건)
        LsDataLblHstry h = LsDataLblHstry.recordSaveEvent(40L, "100", List.of(deleted(401L)));

        // when — 실제 PostgreSQL 테이블에 INSERT (신 컬럼 부재면 여기서 FAIL)
        LsDataLblHstry saved = repository.saveAndFlush(h);

        // then — IDENTITY PK 채번 + 매핑 컬럼 보존
        assertThat(saved.getLblHstrySn()).isNotNull();
        assertThat(saved.getSrcSn()).isEqualTo(40L);
        assertThat(saved.getDelCnt()).isEqualTo(1);
        assertThat(saved.getAddCnt()).isEqualTo(0);
        assertThat(saved.getMdfcnCnt()).isEqualTo(0);
        assertThat(saved.getRegId()).isEqualTo("100");
        assertThat(saved.getRegDt()).isNotNull();
        assertThat(saved.getChgDtlCn()).contains("DELETED");
    }

    @Test
    @DisplayName("프레임_단위_저장이벤트를_최신순으로_조회한다")
    void findBySrcSnOrderedByRegDtDesc() {
        // given — 동일 프레임(SRC_SN=50)에 저장 이벤트 2건
        repository.saveAll(List.of(
                LsDataLblHstry.recordSaveEvent(50L, null, List.of(deleted(501L))),
                LsDataLblHstry.recordSaveEvent(50L, null, List.of(deleted(502L)))));
        repository.flush();

        // when
        List<LsDataLblHstry> found = repository.findBySrcSnOrderByRegDtDesc(50L);

        // then — 해당 프레임 이벤트만 2건
        assertThat(found).hasSize(2);
        assertThat(found).allSatisfy(h -> assertThat(h.getSrcSn()).isEqualTo(50L));
    }

    @Test
    @DisplayName("REG_ID는_NULL_허용_신고경로_이벤트도_저장된다")
    void regIdNullable() {
        // given — regId 없이(신고 경로) 저장 이벤트
        LsDataLblHstry h = LsDataLblHstry.recordSaveEvent(60L, null, List.of(deleted(601L)));

        // when / then — REG_ID NULL 허용 정합 확인
        LsDataLblHstry saved = repository.saveAndFlush(h);
        assertThat(saved.getLblHstrySn()).isNotNull();
        assertThat(saved.getRegId()).isNull();
    }
}

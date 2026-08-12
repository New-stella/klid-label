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

    /**
     * 건수를 세는 이 테스트 전용 프레임 번호.
     *
     * <p>★ 구 값 {@code 50L} 폐기 — 이 클래스는 자기 INSERT 를 롤백하지만, <b>같은 컨테이너를 공유하는
     * 다른 테스트</b>(트랜잭션 없이 커밋하는 통합 테스트)가 만드는 프레임은 IDENTITY 채번이라 실행
     * 조합에 따라 {@code SRC_SN} 이 50 에 도달하고, 그 프레임의 라벨 저장 이력이 커밋되면 여기서
     * 3건이 조회돼 <b>이 테스트만 실패</b>했다(단독 실행은 통과 — 전형적 순서 의존). 실제로 다른
     * 작업에서 테스트 1개가 추가되자 채번이 밀려 이 충돌이 발생했다.
     *
     * <p>{@code LS_DATA_LBL_HSTRY.SRC_SN} 에는 FK 가 없으므로 실재하지 않는 큰 값을 써도 되며,
     * IDENTITY 가 닿지 않는 대역이라 조합과 무관하게 항상 이 테스트의 행만 조회된다.
     */
    private static final long ISOLATED_SRC_SN = 9_000_000_050L;

    @Test
    @DisplayName("프레임_단위_저장이벤트를_최신순으로_조회한다")
    void findBySrcSnOrderedByRegDtDesc() {
        // given — 동일 프레임에 저장 이벤트 2건 (다른 테스트와 겹치지 않는 전용 SRC_SN)
        repository.saveAll(List.of(
                LsDataLblHstry.recordSaveEvent(ISOLATED_SRC_SN, null, List.of(deleted(501L))),
                LsDataLblHstry.recordSaveEvent(ISOLATED_SRC_SN, null, List.of(deleted(502L)))));
        repository.flush();

        // when
        List<LsDataLblHstry> found = repository.findBySrcSnOrderByRegDtDesc(ISOLATED_SRC_SN);

        // then — 해당 프레임 이벤트만 2건
        assertThat(found).hasSize(2);
        assertThat(found).allSatisfy(h -> assertThat(h.getSrcSn()).isEqualTo(ISOLATED_SRC_SN));
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

package kr.co.cudo.authoring.version.repository;

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
 * V58 마이그레이션 RED 재현 — LS_DATA_LBL_HSTRY 테이블 부재 시 비식별 신고 500 회귀 가드.
 *
 * <p>배경: 커밋 fef2a7d 가 {@link LsDataLblHstry} 엔티티 + Repository + 삭제 이력 기록 로직을
 * 추가했으나 CREATE TABLE DDL 을 누락하여 실 DB(POST /v1/labels/{srcSn}/deident-report) 가
 * "relation \"ls_data_lbl_hstry\" does not exist" 로 500 을 던졌다.
 *
 * <p>이 테스트는 <b>실제 Flyway 마이그레이션 스키마(Testcontainers PostgreSQL)</b> 위에서
 * 엔티티를 insert/조회한다. V58 이전이면 테이블 부재로 FAIL(RED), V58 적용 후 PASS(GREEN).
 * 기존 DeidentReportServiceTest 는 Repository 를 mock 하여 실 테이블을 한 번도 건드리지 않았기에
 * 이 갭을 잡지 못했다 — 본 테스트가 그 갭을 메운다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDataLblHstryRepositoryTest {

    @Autowired
    private LsDataLblHstryRepository repository;

    @Test
    @DisplayName("삭제_이력이_LS_DATA_LBL_HSTRY에_저장되고_PK가_채번된다")
    void saveAssignsIdentityPk() {
        // given — 라벨 삭제 이력 (엔티티 정적 팩토리: 매핑 컬럼만 채움)
        LsDataLblHstry h = LsDataLblHstry.recordDeletion(401L, 40L);

        // when — 실제 PostgreSQL 테이블에 INSERT (테이블 부재면 여기서 FAIL)
        LsDataLblHstry saved = repository.saveAndFlush(h);

        // then — IDENTITY PK 채번 + 매핑 컬럼 보존
        assertThat(saved.getLblHstrySn()).isNotNull();
        assertThat(saved.getLblSn()).isEqualTo(401L);
        assertThat(saved.getSrcSn()).isEqualTo(40L);
        assertThat(saved.getRegisteredAt()).isNotNull();
    }

    @Test
    @DisplayName("프레임_단위_삭제_이력을_최신순으로_조회한다")
    void findBySrcSnOrderedByRegisteredAtDesc() {
        // given — 동일 프레임(SRC_SN=50)에 라벨 삭제 이력 2건 (saveAll — 신고 경로와 동일)
        repository.saveAll(List.of(
                LsDataLblHstry.recordDeletion(501L, 50L),
                LsDataLblHstry.recordDeletion(502L, 50L)));
        repository.flush();

        // when
        List<LsDataLblHstry> found = repository.findBySrcSnOrderByRegisteredAtDesc(50L);

        // then — 해당 프레임 이력만 2건, 다른 프레임(40L)은 미포함
        assertThat(found).hasSize(2);
        assertThat(found).extracting(LsDataLblHstry::getLblSn)
                .containsExactlyInAnyOrder(501L, 502L);
        assertThat(found).allSatisfy(h -> assertThat(h.getSrcSn()).isEqualTo(50L));
    }

    @Test
    @DisplayName("LBL_SN은_NULL_허용_SRC_SN_REGISTERED_AT만_필수")
    void lblSnNullable() {
        // given — LBL_SN 없이 SRC_SN 만 가진 이력 (엔티티 매핑상 LBL_SN nullable)
        LsDataLblHstry h = LsDataLblHstry.recordDeletion(null, 60L);

        // when / then — NOT NULL 위반 없이 저장 (LBL_SN 컬럼 NULL 허용 정합 확인)
        LsDataLblHstry saved = repository.saveAndFlush(h);
        assertThat(saved.getLblHstrySn()).isNotNull();
        assertThat(saved.getLblSn()).isNull();
    }
}

package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V83 용어 표준 rename 정합 — LS_DEIDENT_PROC_LOG 의 KPST/폴링 4컬럼이 새 물리명
 * (REQ_KND_CD / POLL_ATMPT_CNT / DE_IDNTF_PJT_ID / DE_IDNTF_DATST_ID) 로 rename 된 뒤에도
 * 엔티티가 실 PostgreSQL 스키마(Testcontainers + Flyway)와 ddl-auto=validate 로 일치하고,
 * 저장·조회가 정상 동작하는지 DB 라운드트립으로 검증한다.
 *
 * <p>Java 필드명(kpstPrjId/kpstDatasetId/pollAttemptCnt/reqKindCd)·getter·JSON 계약은 불변이며,
 * 물리 컬럼명만 표준 약어로 바뀐다. V83 rename 과 엔티티 @Column(name) 중 한쪽만 반영되면
 * validate 가 컨텍스트 로드에서 실패한다(RED). 양쪽 정합 시 GREEN.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDeidentProcLogGlossaryRenameTest {

    @Autowired
    private LsDeidentProcLogRepository repository;

    @Test
    @DisplayName("rename된_4컬럼_KPST_폴링_재비식별_필드가_저장_조회_정상")
    void persistsRenamedColumns() {
        // given — KPST 위탁 + 폴링 진행 + 재비식별 표시로 4개 필드를 모두 채운 로그
        LsDeidentProcLog log = LsDeidentProcLog.request(
                980_001L, "req-rename-" + System.nanoTime(), "/var/raw/rename.mp4", "tester");
        log.markKpstSubmitted(7001L, 8001L); // kpstPrjId=DE_IDNTF_PJT_ID, kpstDatasetId=DE_IDNTF_DATST_ID
        log.markPolling();                    // pollAttemptCnt=POLL_ATMPT_CNT +1
        log.markRedeident();                  // reqKindCd=REQ_KND_CD=REDEIDENT

        // when — 실제 PostgreSQL 테이블에 INSERT 후 재조회 (물리 컬럼 부재/불일치면 여기서 FAIL)
        LsDeidentProcLog saved = repository.saveAndFlush(log);
        repository.flush();
        LsDeidentProcLog found = repository.findById(saved.getProcLogSn()).orElseThrow();

        // then — 4개 컬럼값이 새 물리명 컬럼에 무손실 보존 (Java 필드/계약은 불변)
        assertThat(found.getKpstPrjId()).isEqualTo(7001L);
        assertThat(found.getKpstDatasetId()).isEqualTo(8001L);
        assertThat(found.getPollAttemptCnt()).isEqualTo(1);
        assertThat(found.getReqKindCd()).isEqualTo(LsDeidentProcLog.REQ_KIND_REDEIDENT);
        assertThat(found.isRedeident()).isTrue();
    }
}

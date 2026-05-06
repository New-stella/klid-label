package kr.co.cudo.authoring.export.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 10 — 프로젝트 단위 ACCEPTED(=APPROVED) 라벨 대상 RAW_SN 조회.
 *
 * <p>LS_PJT_DATA_STTS 의 EmbeddedId 와 별도 EntityManager 쿼리를 사용 (Repository 별도 메서드 추가 회피).
 */
@Component
public class ApprovedFrameLookup {

    @PersistenceContext(unitName = "control")
    private EntityManager em;

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<Long> findApprovedRawSns(Long pjtId) {
        return em.createQuery(
                        "select s.id.rawDataId from LsPjtDataStts s "
                                + "where s.id.pjtId = :pjtId and s.dataSttsCd = :status",
                        Long.class)
                .setParameter("pjtId", pjtId)
                .setParameter("status", LsPjtDataStts.STTS_APPROVED)
                .getResultList();
    }
}

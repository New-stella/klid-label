package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * {@code OTSD_JOB_ID} 소유자 조회 — <b>독립 트랜잭션</b>(REQUIRES_NEW) 전용 (E-ISSUE-05).
 *
 * <h3>왜 별도 트랜잭션이어야 하는가 (PostgreSQL 25P02)</h3>
 * <p>{@code uk_aug_external_job_id} UNIQUE 위반이 나면 PostgreSQL 은 그 트랜잭션을
 * <b>abort 상태</b>로 만들고, 이후 같은 트랜잭션의 <b>모든</b> 쿼리를
 * {@code current transaction is aborted}(25P02)로 거부한다. 그래서 위반 직후 "누가 이 job_id 를
 * 선점했는가" 를 <b>같은 트랜잭션에서 재조회하면 그 조회마저 실패</b>한다 — 구 구현이 500 을 내던
 * 원인이 정확히 이것이었다.
 *
 * <p>본 컴포넌트는 {@link Propagation#REQUIRES_NEW} 로 <b>새 커넥션</b>에서 읽으므로 abort 된
 * 트랜잭션과 무관하게 소유자를 판별할 수 있다. 잠금 없는 단순 SELECT 라 호출자가 잡고 있는
 * {@code FOR UPDATE} 행 잠금과도 경합하지 않는다(PostgreSQL MVCC).
 *
 * <p><b>주의</b>: 위반이 발생한 트랜잭션은 이미 rollback-only 다. 여기서 소유자를 알아내도 그
 * 트랜잭션을 성공(200)으로 되돌릴 수는 없다 — 이 조회의 목적은 <b>정확한 종결 코드/사유 판별</b>이며,
 * "재수신 = 200" 흡수는 위반이 나기 <b>전</b>의 선점 검사와 non-PENDING 멱등 앵커가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class AugmentJobIdOwnerLookup {

    private final LsDataAugRepository augRepository;

    /**
     * 해당 {@code otsd_job_id} 를 보유한 증강 행의 PK 를 독립 트랜잭션에서 조회한다.
     *
     * @return 소유자 {@code dataAugSn} (없거나 job_id 가 비어 있으면 {@link Optional#empty()})
     */
    @Transactional(value = "controlTransactionManager",
            propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Long> findOwnerDataAugSn(String externalJobId) {
        if (externalJobId == null || externalJobId.isBlank()) {
            return Optional.empty();
        }
        return augRepository.findByExternalJobId(externalJobId).map(LsDataAug::getDataAugSn);
    }
}

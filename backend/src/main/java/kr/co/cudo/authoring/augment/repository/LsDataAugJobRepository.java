package kr.co.cudo.authoring.augment.repository;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 증강 외부 위탁 작업 리포지토리 (LS_DATA_AUG_JOB, V140).
 *
 * <p>웹훅 수신부(A2)가 {@code job_id}/{@code request_id} 로 역조회할 수 있도록 두 축 모두 노출한다.
 */
@ControlRepo
public interface LsDataAugJobRepository extends JpaRepository<LsDataAugJob, Long> {

    List<LsDataAugJob> findByDataAugSnOrderByJobSeqAsc(Long dataAugSn);

    Optional<LsDataAugJob> findByIdempotencyKey(String idempotencyKey);

    Optional<LsDataAugJob> findByExternalJobId(String externalJobId);
}

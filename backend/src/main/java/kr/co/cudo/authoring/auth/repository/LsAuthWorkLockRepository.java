package kr.co.cudo.authoring.auth.repository;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@ControlRepo
public interface LsAuthWorkLockRepository extends JpaRepository<LsAuthWorkLock, Long> {

    boolean existsByLockTargetCdAndDataRawSnAndLockSttsCd(String lockTargetCd, Long dataRawSn, String lockSttsCd);

    List<LsAuthWorkLock> findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
            String lockTargetCd, Long dataRawSn, String lockSttsCd);
}

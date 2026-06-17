package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

@ControlRepo
public interface LsBatchProcLogRepository extends JpaRepository<LsBatchProcLog, Long> {

    Optional<LsBatchProcLog> findTopByDataRawSnOrderByRegDtDesc(Long dataRawSn);
}

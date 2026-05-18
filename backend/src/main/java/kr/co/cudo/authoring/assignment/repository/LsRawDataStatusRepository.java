package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface LsRawDataStatusRepository extends JpaRepository<LsRawDataStatus, Long> {
}

package kr.co.cudo.authoring.assignment.repository;

import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface LsPjtDataSttsRepository extends JpaRepository<LsPjtDataStts, LsPjtDataStts.Pk> {
}

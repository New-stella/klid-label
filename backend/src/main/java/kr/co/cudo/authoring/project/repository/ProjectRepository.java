package kr.co.cudo.authoring.project.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.project.entity.LsPjt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface ProjectRepository extends JpaRepository<LsPjt, Long> {

    Page<LsPjt> findByUseYn(String useYn, Pageable pageable);
}

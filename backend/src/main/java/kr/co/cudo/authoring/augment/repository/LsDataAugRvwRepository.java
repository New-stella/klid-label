package kr.co.cudo.authoring.augment.repository;

import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

@ControlRepo
public interface LsDataAugRvwRepository extends JpaRepository<LsDataAugRvw, Long> {

    Optional<LsDataAugRvw> findFirstByDataAugSnOrderByRegDtDesc(Long dataAugSn);
}

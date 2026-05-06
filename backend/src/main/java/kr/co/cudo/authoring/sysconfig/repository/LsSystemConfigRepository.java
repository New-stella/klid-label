package kr.co.cudo.authoring.sysconfig.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.sysconfig.entity.LsSystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

@ControlRepo
public interface LsSystemConfigRepository extends JpaRepository<LsSystemConfig, String> {

    Optional<LsSystemConfig> findByConfigKey(String configKey);
}

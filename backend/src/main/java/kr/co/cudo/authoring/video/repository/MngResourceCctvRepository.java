package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.MngResourceCctv;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface MngResourceCctvRepository extends JpaRepository<MngResourceCctv, String> {
}

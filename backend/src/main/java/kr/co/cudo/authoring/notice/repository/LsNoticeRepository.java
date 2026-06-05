package kr.co.cudo.authoring.notice.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.notice.entity.LsNotice;
import org.springframework.data.jpa.repository.JpaRepository;

@ControlRepo
public interface LsNoticeRepository extends JpaRepository<LsNotice, Long> {
}

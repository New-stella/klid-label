package kr.co.cudo.authoring.version.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataLblHstryRepository extends JpaRepository<LsDataLblHstry, Long> {

    /** 프레임 단위 버전 목록 — 최신순. */
    List<LsDataLblHstry> findBySrcSnOrderByRegisteredAtDesc(Long srcSn);

    /** Gitea 커밋 해시로 단건 조회 (diff/rollback 시점에 srcSn 식별). */
    Optional<LsDataLblHstry> findByGiteaCmtHash(String giteaCmtHash);
}

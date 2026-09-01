package kr.co.cudo.authoring.aiserver.repository;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 노드 x 용도별 부하 저장소 (Control 데이터소스). [@design ADR-057]
 *
 * <p>비즈니스 로직 금지 — 조회/저장만 둔다. 「어느 노드가 한가한가」의 판정은 여기가 아니라
 * 선택기(Phase 3)의 몫이다.
 */
@ControlRepo
public interface LsAiSrvrUsgRepository extends JpaRepository<LsAiSrvrUsg, LsAiSrvrUsg.Key> {

    /** 한 노드의 용도별 부하 전부 — 용도가 둘이라 목록이 짧다(상한을 따로 두지 않는다). */
    List<LsAiSrvrUsg> findBySrvrId(String srvrId);
}

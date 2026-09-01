package kr.co.cudo.authoring.aiserver.repository;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
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

    /**
     * 여러 노드의 <b>한 용도</b> 부하 — 선택기가 평활 표본이 없을 때 떨어지는 폴백 조회.
     *
     * <p>노드마다 따로 묻지 않는 이유는 선택 1회가 노드 수만큼의 DB 왕복이 되기 때문이다. 노드 수는
     * 원장 크기(운영자가 등록한 장비 수)라 목록이 짧아 상한을 따로 두지 않는다.
     *
     * <p><b>행이 없는 노드는 결과에 없다</b> — 아직 한 번도 관측되지 않은 노드다. 호출측이 그 사실을
     * 「모름」으로 다룰지 0으로 다룰지 정한다.
     */
    List<LsAiSrvrUsg> findBySrvrIdInAndUsgTypeCd(Collection<String> srvrIds, AiSrvrUsageType usgTypeCd);

    /**
     * 여러 노드의 <b>전 용도</b> 부하 — 관리 목록 화면이 한 번에 읽는다. [@design API-226]
     *
     * <p>노드마다 {@link #findBySrvrId(String)} 를 부르면 목록 한 번이 노드 수만큼의 DB 왕복이 된다.
     * 용도가 둘뿐이라 결과 크기는 노드 수의 두 배를 넘지 않아 상한을 따로 두지 않는다.
     *
     * <p><b>행이 없는 노드는 결과에 없다</b> — 아직 한 번도 관측되지 않은 노드다(부하 0이 아니라
     * 「모름」이다). 호출측이 그 사실을 어떻게 보일지 정한다.
     */
    List<LsAiSrvrUsg> findBySrvrIdIn(Collection<String> srvrIds);
}

package kr.co.cudo.authoring.eventtype.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.MngExEvntType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 관제 이벤트 타입 마스터(MNG_EX_EVNT_TYPE) 조회 전용 리포지토리.
 *
 * <p>공유 DB(관제) READ 만 수행한다 — {@link ControlRepo} 로 controlEntityManager/controlTransactionManager
 * 에 바인딩된다. MNG_* 테이블은 관제팀 소유이므로 조회만 노출하며 쓰기 메서드를 두지 않는다.
 * 파생 쿼리(메서드 이름) 이므로 SQL Injection 위험이 없고 파라미터는 자동 바인딩된다.
 */
@ControlRepo
public interface MngExEvntTypeRepository extends JpaRepository<MngExEvntType, String> {

    /**
     * 수집 여부(CLCT_YN) 로 이벤트 타입을 조회한다.
     *
     * @param clctYn 수집 여부 ('Y' = 수집 대상)
     */
    List<MngExEvntType> findByClctYn(String clctYn);
}

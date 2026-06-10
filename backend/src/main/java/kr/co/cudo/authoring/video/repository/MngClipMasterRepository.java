package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.MngClipMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 관제서버 클립 마스터(MNG_CLIP_MASTER) 조회 전용 리포지토리.
 *
 * <p>공유 DB(관제) READ 만 수행한다 — {@link ControlRepo} 로 controlEntityManager/controlTransactionManager
 * 에 바인딩된다. MNG_* 테이블은 관제팀 소유이므로 본 리포지토리는 조회만 노출하며 쓰기 메서드를 두지 않는다.
 */
@ControlRepo
public interface MngClipMasterRepository extends JpaRepository<MngClipMaster, Long> {

    /**
     * 작업수요(학습용 지정) 플래그가 설정된 클립 조회.
     *
     * <p>{@code JOB_DMND_YN} 컬럼 동등 비교 — 파라미터 바인딩만 사용(SQL Injection 방어, CWE-89).
     * 호출 측에서 {@code "Y"} 상수로 호출하므로 {@code 'N'}/null 클립은 자동 제외된다.
     */
    List<MngClipMaster> findByJobDmndYn(String jobDmndYn);
}

package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.entity.MngClipMasterId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 관제서버 클립 마스터(MNG_CLIP_MASTER) 조회 전용 리포지토리.
 *
 * <p>공유 DB(관제) READ 만 수행한다 — {@link ControlRepo} 로 controlEntityManager/controlTransactionManager
 * 에 바인딩된다. MNG_* 테이블은 관제팀 소유이므로 본 리포지토리는 조회만 노출하며 쓰기 메서드를 두지 않는다.
 * PK 는 복합키 {@link MngClipMasterId}(EVNT_ID, CLIP_TYPE_CD).
 */
@ControlRepo
public interface MngClipMasterRepository extends JpaRepository<MngClipMaster, MngClipMasterId> {

    /**
     * 학습용 지정(작업 요청) + 파일경로 존재 클립 조회 — 적재 후보.
     *
     * <p>{@code JOB_DMND_YN} 동등 비교 + {@code FILE_PATH} 비공백 필터를 JPQL 파라미터 바인딩으로만
     * 구성한다(SQL Injection 방어, CWE-89). 호출 측에서 {@code "Y"} 상수로 호출하므로 {@code 'N'}/null
     * 클립과 파일경로 없는(적재 불가) 클립은 자동 제외된다. {@code TRIM} 으로 공백만 있는 경로도 배제한다.
     */
    @Query("""
            SELECT c FROM MngClipMaster c
            WHERE c.jobDmndYn = :jobDmndYn
              AND c.filePath IS NOT NULL
              AND TRIM(c.filePath) <> ''
            """)
    List<MngClipMaster> findIngestCandidatesByJobDmndYn(@Param("jobDmndYn") String jobDmndYn);
}

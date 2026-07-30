package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.entity.MngClipMasterId;
import org.springframework.data.domain.Pageable;
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
     * 학습용 지정(작업 요청) + 파일경로 존재 + <b>미적재</b> 클립 조회 — 적재 후보.
     *
     * <p>{@code JOB_DMND_YN} 동등 비교 + {@code FILE_PATH} 비공백 필터를 JPQL 파라미터 바인딩으로만
     * 구성한다(SQL Injection 방어, CWE-89). 호출 측에서 {@code "Y"} 상수로 호출하므로 {@code 'N'}/null
     * 클립과 파일경로 없는(적재 불가) 클립은 자동 제외된다. {@code TRIM} 으로 공백만 있는 경로도 배제한다.
     *
     * <p><b>B-ISSUE-04 — 미적재 필터 + 상한</b>: 본 쿼리는 60초 주기 스캔 잡이 매 tick 실행하며,
     * <b>관제서버와 공유하는 DB(MNG_*)</b> 를 친다. 구 구현은 미적재 필터도 상한도 없어 학습용 지정
     * 클립이 누적될수록 매 tick 전량 SELECT 후 전량 skip 을 반복했다(실측: {@code scanned=3 ingested=0}
     * 무한 반복). 다음 두 가지로 좁힌다.
     * <ol>
     *   <li>{@code NOT EXISTS (LS_DATA_RAW)} — 이미 적재된 CLIP_ID 는 후보에서 제외한다. {@code LsDataRaw}
     *       는 본 리포지토리와 <b>같은 EntityManager</b>({@code @ControlRepo}) 에 매핑돼 있어 단일 SQL 로
     *       상관 서브쿼리가 성립한다(크로스 데이터소스 아님).</li>
     *   <li>{@link Pageable} — tick 당 처리 상한. 잔여분은 다음 tick 이 이어서 처리한다(의도된 이월).</li>
     * </ol>
     *
     * <p><b>정렬 고정</b>: 상한을 두면 "어느 N 건" 인지가 결정돼야 잔여분이 굶지 않는다. 복합 PK
     * (EVNT_ID, CLIP_TYPE_CD) 오름차순으로 고정해 tick 간 순서가 흔들리지 않게 한다.
     *
     * <p><b>멱등 가드는 그대로 유지한다</b> — 본 필터는 1차 필터일 뿐이며, 2노드 Active-Active 에서
     * 조회~적재 사이 경합이 존재하므로 {@code TrainingVideoIngestTx} 의 이중 멱등(사전 조회 skip +
     * UK 위반 catch-skip)을 대체하지 않는다.
     */
    @Query("""
            SELECT c FROM MngClipMaster c
            WHERE c.jobDmndYn = :jobDmndYn
              AND c.filePath IS NOT NULL
              AND TRIM(c.filePath) <> ''
              AND NOT EXISTS (SELECT 1 FROM LsDataRaw r WHERE r.vmsClipId = c.clipId)
            ORDER BY c.evntId ASC, c.clipTypeCd ASC
            """)
    List<MngClipMaster> findIngestCandidatesByJobDmndYn(@Param("jobDmndYn") String jobDmndYn,
                                                        Pageable pageable);
}

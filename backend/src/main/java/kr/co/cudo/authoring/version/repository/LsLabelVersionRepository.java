package kr.co.cudo.authoring.version.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsLabelVersionRepository extends JpaRepository<LsLabelVersion, Long> {

    List<LsLabelVersion> findByDataSrcSnOrderByRegDtDesc(Long dataSrcSn);

    List<LsLabelVersion> findByDataRawSnAndDataSrcSnAndActiveYn(
            Long dataRawSn, Long dataSrcSn, String activeYn);

    /**
     * HIGH 시나리오 (동시 저장/롤백 Race) 방어 — 같은 (rawSn, srcSn) 의 ACTIVE 버전을
     * 비관적 쓰기 잠금으로 조회한다. 동시 commit/rollback 트랜잭션을 직렬화하여
     * versionNo 충돌·active 중복을 차단한다 (CWE-362).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from LsLabelVersion v "
            + "where v.dataRawSn = :rawSn and v.dataSrcSn = :srcSn and v.activeYn = :activeYn")
    List<LsLabelVersion> findActiveForUpdate(@Param("rawSn") Long rawSn,
                                             @Param("srcSn") Long srcSn,
                                             @Param("activeYn") String activeYn);

    /**
     * 버전 해시로 단건 조회.
     * VERSION_HASH 는 (DATA_SRC_SN, VERSION_HASH) 복합 UNIQUE 이므로 srcSn 과 함께 조회한다.
     */
    Optional<LsLabelVersion> findByDataSrcSnAndVersionHash(Long dataSrcSn, String versionHash);

    /**
     * 버전 해시로 전역 조회 (diff/rollback 진입점 — path/param 으로 해시만 받을 때).
     * (DATA_SRC_SN, VERSION_HASH) 복합 UNIQUE 이므로 서로 다른 프레임이 동일 해시를 가지면
     * 다건이 반환될 수 있다. 호출부에서 srcSn 일치/접근권한을 추가 검증한다.
     */
    List<LsLabelVersion> findByVersionHash(String versionHash);

    int countByDataRawSnAndDataSrcSn(Long dataRawSn, Long dataSrcSn);

    // Phase 7 — rawSn 단위 활용 (영상 전체 버전 트래킹)
    Optional<LsLabelVersion> findByDataRawSnAndActiveYn(Long dataRawSn, String activeYn);

    Page<LsLabelVersion> findAllByDataRawSnOrderByVersionNoDesc(Long dataRawSn, Pageable pageable);

    Optional<LsLabelVersion> findFirstByDataRawSnOrderByVersionNoDesc(Long dataRawSn);
}

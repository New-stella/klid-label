package kr.co.cudo.authoring.version.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.version.dto.SnapshotVersionRef;
import kr.co.cudo.authoring.version.dto.VideoVersionItem;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 프레임의 스냅샷 건수.
     *
     * <p>구 {@code VER_NO} 채번({@code count + 1})의 원천이었으나 V180 의 의미 재정의로 그 용처는
     * 사라졌다(현재 프로덕션 호출부 없음 — 테스트만 사용). <b>삭제하지 않고 남긴다</b>: 산출 버전
     * 번호 채번을 붙이는 후속 단계에서 판정 기준을 다시 검토해야 하고, 건수 자체는 채번과 무관하게
     * 유효한 조회다.
     */
    int countByDataRawSnAndDataSrcSn(Long dataRawSn, Long dataSrcSn);

    /**
     * VER_NO 실채번 — 이 영상의 <b>ACTIVE 이면서 아직 번호가 없는</b> 스냅샷에 산출 버전 번호를 찍는다.
     *
     * <p>판정·근거는 {@code OutputVersionStamper} 가 단독 보유한다(여기서 규칙을 복제하지 않는다).
     * {@code clearAutomatically} 미지정 — 같은 트랜잭션에서 진행 중인 다른 엔티티 dirty-update
     * (산출 원장 SUCCEEDED 전이)를 유실시키지 않기 위함이다. 파라미터 바인딩만 사용(CWE-89).
     *
     * @return 실제로 번호가 찍힌 스냅샷 수 (0 이어도 정상 — 이번 회차에 내용이 바뀐 프레임이 없음)
     * @design D5
     * @req R6
     */
    @Modifying
    @Query("update LsLabelVersion v set v.versionNo = :verNo "
            + "where v.dataRawSn = :rawSn and v.activeYn = :activeYn and v.versionNo is null")
    int stampOutputVersionNo(@Param("rawSn") Long rawSn,
                             @Param("verNo") Integer verNo,
                             @Param("activeYn") String activeYn);

    /**
     * 「시작 버전 선택」 대조 — 그 영상에 <b>실재하는</b> 산출 버전 번호인지.
     *
     * <p>요청받은 번호를 그대로 믿고 스냅샷을 열면 다른 영상/없는 회차를 끌어와 데이터가 오염된다
     * (CWE-639). 조회 전에 이 대조를 통과해야 한다.
     */
    boolean existsByDataRawSnAndVersionNo(Long dataRawSn, Integer versionNo);

    // ★ 구 {@code findNumberedRefsUpTo}(VER_NO <= N 중 최대) 는 <b>제거됐다</b> (V183).
    //   되돌릴 대상 판정의 단일 원천은 회차↔스냅샷 매핑({@code LsOutputVerSnpshRepository.findRefsUpTo})
    //   이다. 번호는 값이 하나뿐이라 "한 스냅샷이 여러 회차의 내용"(1:N)을 담지 못해, 롤백 후 재산출한
    //   회차를 고르면 <b>그 회차에 존재한 적 없는 비활성 스냅샷</b>으로 되돌리는 조용한 오복원이 났다.
    //   같은 판정을 여기서 다시 유도하는 쿼리를 <b>부활시키지 말 것</b> — 두 번째 진실원이 된다.

    /**
     * 영상 단위 산출 버전 목록(내림차순) — 「시작 버전 선택」 화면의 선택지.
     *
     * <p>번호가 찍힌 스냅샷이 있는 회차만 나온다(사유는 {@code VideoVersionItem} 주석 참조).
     */
    @Query("select new kr.co.cudo.authoring.version.dto.VideoVersionItem("
            + "v.versionNo, count(v), max(v.regDt)) "
            + "from LsLabelVersion v "
            + "where v.dataRawSn = :rawSn and v.versionNo is not null "
            + "group by v.versionNo order by v.versionNo desc")
    List<VideoVersionItem> findVideoVersions(@Param("rawSn") Long rawSn);

    // Phase 7 — rawSn 단위 활용 (영상 전체 버전 트래킹)
    Optional<LsLabelVersion> findByDataRawSnAndActiveYn(Long dataRawSn, String activeYn);

    Page<LsLabelVersion> findAllByDataRawSnOrderByVersionNoDesc(Long dataRawSn, Pageable pageable);

    Optional<LsLabelVersion> findFirstByDataRawSnOrderByVersionNoDesc(Long dataRawSn);
}

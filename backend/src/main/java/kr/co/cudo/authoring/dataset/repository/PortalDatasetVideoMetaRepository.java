package kr.co.cudo.authoring.dataset.repository;

import kr.co.cudo.authoring.common.datasource.PortalRepo;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 포털 DB(물리 분리) 복제본 {@code LS_DATASET_VIDEO_META} 저장소.
 *
 * <p>{@link PortalRepo} 로 표시되어 {@code portalEntityManagerFactory}/{@code portalTransactionManager}
 * 에 바인딩된다(control 원본과 동일 엔티티/DDL, 별도 물리 DB). 워커({@link kr.co.cudo.authoring.dataset.worker.MetaReplicationWorker})
 * 만 이 복제본에 write 하며 포털 사용자는 read-only 로 소비한다.
 *
 * <p>멱등성: {@code upsertSnapshot} 은 PostgreSQL {@code ON CONFLICT (RAW_SN, SNPSHT_HASH) DO NOTHING}
 * 으로 at-least-once 재복제(중복 outbox)를 무해하게 흡수한다. 활성 1건 불변식은 control 과 동일하게
 * deactivate-then-insert(+activateByHash) 순서로 유지한다. 모든 값은 파라미터/SpEL 프로퍼티 바인딩이라
 * 문자열 결합이 없다(CWE-89).
 */
@PortalRepo
public interface PortalDatasetVideoMetaRepository extends JpaRepository<LsDatasetVideoMeta, Long> {

    List<LsDatasetVideoMeta> findByRawSnAndActiveYn(Long rawSn, String activeYn);

    // 가용성 probe 는 이 리포지토리(JPA/트랜잭션 경유)에 두지 않는다 — 트랜잭션 안에서 42P01 을 삼키면
    // rollback-only 마킹 때문에 커밋에서 UnexpectedRollbackException 이 터져 graceful skip 이 깨진다.
    // PortalMetaReplicaWriter.isReplicaAvailable() 이 포털 DataSource 직결로 수행한다.

    /**
     * control {@code LsDatasetVideoMetaRepository.insertSnapshotIfAbsent} 와 동일한 멱등 upsert(포털 EMF).
     *
     * <h3>★INSERT 컬럼 집합은 control 과 <b>완전히 같아야 한다</b> (@design INT-009)</h3>
     * 복제본은 원본 동결 메타와 동형 스키마를 유지하며, 복제는 원본 행의 <b>전 컬럼을 그대로</b> 옮긴다 —
     * 일부만 골라 싣지 않는다. 이 동형성은 <b>DDL 만으로 성립하지 않는다</b>: 복제를 수행하는 이 SQL 이
     * 같은 컬럼 집합을 실어야 비로소 성립한다. 실사고 — {@code EVNT_ANNO_CN}(event_annotation 동결 payload)이
     * control 에는 있고 여기에만 없어, 복제본 DDL 에 컬럼이 존재함에도 값이 <b>영구히 NULL</b> 이었다
     * (포털 채널이 read-only 로 소비하는 값이라 그대로 결손). 컬럼 <b>수</b>를 세는 검사로는 잡히지 않는다 —
     * DDL 은 맞고 이 SQL 만 틀렸기 때문. 회귀 가드: {@code PortalMetaReplicaColumnParityGuardTest}.
     *
     * <h3>⚠ dialect 결합 지점</h3>
     * {@code CAST(... AS jsonb)} 와 {@code ON CONFLICT ... DO NOTHING} 은 <b>PostgreSQL 문법</b>이다.
     * 포털 DB 를 다른 RDB 로 바꾸는 검토가 있으며, 그때 이 두 구문이 함께 달라진다. 지금은 control 과
     * 동형을 맞추는 것이 우선이라 PostgreSQL 기준으로 유지한다(선제 대응하지 않는다).
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "INSERT INTO LS_DATASET_VIDEO_META ("
            + "RAW_SN, SNPSHT_HASH, ACTIVE_YN, "
            + "ORGNL_RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
            + "LCLGV_CD, PRVC_YN, PRVC_TYPE_CD, DE_IDENT_YN, AI_CRT_YN, EVNT_TYPE_CD, "
            + "CCTV_NM, WGS84_LAT, WGS84_LOT, SIDO_NM, SGG_NM, FILE_FMT, EVNT_NM, "
            + "VDO_CDC, FPS, BIT_RT, ASPRT_RT, RESL, VDO_WDTH, VDO_HGT, FILE_SZ, "
            + "DAY_NGT_CD, SESN_CD, WTHR_NM, EVNT_ANNO_CN, "
            + "RVW_CMPL_DT, REG_DT, REG_ID"
            + ") VALUES ("
            + ":#{#m.rawSn}, :#{#m.snpshtHash}, :#{#m.activeYn}, "
            + ":#{#m.orgnlRawSn}, :#{#m.vmsClipId}, :#{#m.vmsCctvId}, :#{#m.rawFilePathNm}, :#{#m.shtDt}, :#{#m.vdoLenSec}, "
            + ":#{#m.lclgvCd}, :#{#m.prvcYn}, :#{#m.prvcTypeCd}, :#{#m.deIdentYn}, :#{#m.aiCrtYn}, :#{#m.evntTypeCd}, "
            + ":#{#m.cctvNm}, :#{#m.wgs84Lat}, :#{#m.wgs84Lot}, :#{#m.sidoNm}, :#{#m.sggNm}, :#{#m.fileFmt}, :#{#m.evntNm}, "
            + ":#{#m.vdoCdc}, :#{#m.fps}, :#{#m.bitRt}, :#{#m.asprtRt}, :#{#m.resl}, :#{#m.vdoWdth}, :#{#m.vdoHgt}, :#{#m.fileSz}, "
            + ":#{#m.dayNgtCd}, :#{#m.sesnCd}, :#{#m.wthrNm}, CAST(:#{#m.evntAnnoCn} AS jsonb), "
            + ":#{#m.rvwCmplDt}, :#{#m.regDt}, :#{#m.regId}"
            + ") ON CONFLICT (RAW_SN, SNPSHT_HASH) DO NOTHING",
            nativeQuery = true)
    int upsertSnapshot(@Param("m") LsDatasetVideoMeta m);

    /** 같은 RAW_SN 의 기존 활성('Y')을 비활성('N')으로 — keepHash 제외(deactivate-then-insert 선두). */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "UPDATE LS_DATASET_VIDEO_META SET ACTIVE_YN = 'N' "
            + "WHERE RAW_SN = :rawSn AND SNPSHT_HASH <> :keepHash AND ACTIVE_YN = 'Y'",
            nativeQuery = true)
    int deactivatePrevious(@Param("rawSn") Long rawSn, @Param("keepHash") String keepHash);

    /** 동일 해시 재복제로 대상 행이 이미 'N' 이면 명시 재활성 → 활성 0건 방지(활성 1건 불변식). */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "UPDATE LS_DATASET_VIDEO_META SET ACTIVE_YN = 'Y' "
            + "WHERE RAW_SN = :rawSn AND SNPSHT_HASH = :hash AND ACTIVE_YN = 'N'",
            nativeQuery = true)
    int activateByHash(@Param("rawSn") Long rawSn, @Param("hash") String hash);
}

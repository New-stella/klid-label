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

    /**
     * 포털 복제본 테이블 존재/접근 가능 여부 probe.
     *
     * <p>복제본 테이블이 아직 프로비저닝되지 않은 포털 DB 에서 워커가 매 outbox 를 실패시켜
     * 불필요한 재시도/dead-letter 로 몰지 않도록, 워커가 tick 진입 전에 이 probe 로 가용성을 확인한다.
     * 테이블이 없으면 예외가 발생하고 워커가 graceful skip 한다(중단 아님).
     */
    @Query(value = "SELECT 1 FROM LS_DATASET_VIDEO_META LIMIT 1", nativeQuery = true)
    Integer probeReplicaTable();

    /** control {@code LsDatasetVideoMetaRepository.upsertSnapshot} 와 동일한 멱등 upsert(포털 EMF). */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "INSERT INTO LS_DATASET_VIDEO_META ("
            + "RAW_SN, SNPSHT_HASH, ACTIVE_YN, "
            + "ORGNL_RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
            + "LCLGV_CD, PRVC_YN, PRVC_TYPE_CD, DE_IDENT_YN, AI_CRT_YN, EVNT_TYPE_CD, "
            + "CCTV_NM, WGS84_LAT, WGS84_LOT, SIDO_NM, SGG_NM, FILE_FMT, EVNT_NM, "
            + "VDO_CDC, FPS, BIT_RT, ASPRT_RT, RESL, VDO_WDTH, VDO_HGT, FILE_SZ, "
            + "DAY_NGT_CD, SESN_CD, WTHR_NM, "
            + "RVW_CMPL_DT, REG_DT, REG_ID"
            + ") VALUES ("
            + ":#{#m.rawSn}, :#{#m.snpshtHash}, :#{#m.activeYn}, "
            + ":#{#m.orgnlRawSn}, :#{#m.vmsClipId}, :#{#m.vmsCctvId}, :#{#m.rawFilePathNm}, :#{#m.shtDt}, :#{#m.vdoLenSec}, "
            + ":#{#m.lclgvCd}, :#{#m.prvcYn}, :#{#m.prvcTypeCd}, :#{#m.deIdentYn}, :#{#m.aiCrtYn}, :#{#m.evntTypeCd}, "
            + ":#{#m.cctvNm}, :#{#m.wgs84Lat}, :#{#m.wgs84Lot}, :#{#m.sidoNm}, :#{#m.sggNm}, :#{#m.fileFmt}, :#{#m.evntNm}, "
            + ":#{#m.vdoCdc}, :#{#m.fps}, :#{#m.bitRt}, :#{#m.asprtRt}, :#{#m.resl}, :#{#m.vdoWdth}, :#{#m.vdoHgt}, :#{#m.fileSz}, "
            + ":#{#m.dayNgtCd}, :#{#m.sesnCd}, :#{#m.wthrNm}, "
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

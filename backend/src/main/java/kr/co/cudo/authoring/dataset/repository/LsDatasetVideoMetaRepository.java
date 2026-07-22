package kr.co.cudo.authoring.dataset.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@ControlRepo
public interface LsDatasetVideoMetaRepository extends JpaRepository<LsDatasetVideoMeta, Long> {

    List<LsDatasetVideoMeta> findByRawSn(Long rawSn);

    List<LsDatasetVideoMeta> findByRawSnAndActiveYn(Long rawSn, String activeYn);

    /**
     * 동결 스냅샷 원자 upsert — PostgreSQL {@code ON CONFLICT (RAW_SN, SNPSHT_HASH) DO NOTHING}.
     *
     * <p>동일 페이로드 재승인 시 UK 충돌을 DB 가 원자적으로 흡수하므로, 동시 실행(CWE-362) race 가
     * 발생해도 중복 행 없이 멱등하게 동작한다(예외 없음). 반환값은 실제 삽입 행수 —
     * 신규 삽입 1, 충돌(멱등 스킵) 0. 모든 값은 SpEL 엔티티 프로퍼티 바인딩({@code :#{#m.xxx}}) 으로
     * 파라미터화되어 문자열 결합이 없다(CWE-89). INSERT ... VALUES 컨텍스트라 null 파라미터도
     * 대상 컬럼 타입으로 PG 가 추론한다.
     *
     * <p>{@code flushAutomatically=true} 로 native 실행 전 대기 중 변경을 flush 해 DB 일관성을 맞춘다.
     * {@code clearAutomatically=false} — 이 native 쿼리는 {@code LS_DATASET_VIDEO_META} 행만 건드리고
     * 그 행을 managed 엔티티로 로드하지 않으므로 1차 캐시 stale 위험이 없다. 반면 PC 전체 clear 는 승인
     * 트랜잭션의 다른 managed 엔티티({@code LsRawDataStatus} 등)를 detach 시키는 footgun 이라 하지 않는다.
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

    /**
     * 같은 RAW_SN 의 기존 활성 스냅샷을 비활성화 — {@code keepHash} 를 제외한 ACTIVE_YN='Y' → 'N'.
     *
     * <p>신규 스냅샷을 append 한 뒤 호출하여 최신 1건만 활성으로 유지한다(append-only 이력).
     * 반환값은 비활성 전환된 행수. 모든 값은 파라미터 바인딩(CWE-89). {@code clearAutomatically=false}
     * — 대상 행을 managed 엔티티로 다루지 않아 PC clear 가 불필요하고(footgun 회피), flush 만 강제한다.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "UPDATE LS_DATASET_VIDEO_META SET ACTIVE_YN = 'N' "
            + "WHERE RAW_SN = :rawSn AND SNPSHT_HASH <> :keepHash AND ACTIVE_YN = 'Y'",
            nativeQuery = true)
    int deactivatePrevious(@Param("rawSn") Long rawSn, @Param("keepHash") String keepHash);

    /**
     * 같은 RAW_SN 의 비활성 스냅샷 중 지정 해시 행을 다시 활성화한다 — 재승인이 <b>과거와 동일한</b>
     * 페이로드(같은 해시)로 이루어져 그 행이 이미 존재(ACTIVE_YN='N')하는 경우, {@code upsertSnapshot}
     * 의 {@code ON CONFLICT DO NOTHING} 은 재삽입/재활성을 하지 않으므로 이 메서드로 명시적으로 되살린다.
     *
     * <p>materialize 순서(deactivatePrevious → upsertSnapshot → activateByHash)의 마지막 단계로 호출해
     * "활성 스냅샷 정확히 1건" 불변식을 A→B→A 재승인 엣지에서도 보장한다(활성 0건 방지). 이미 'Y' 면
     * 0행(무영향). 다른 활성 행은 앞선 deactivatePrevious 가 'N' 으로 내려 부분 유니크 인덱스 위반이 없다.
     * {@code clearAutomatically=false} — 대상 행을 managed 엔티티로 다루지 않아 PC clear 가 불필요하다.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "UPDATE LS_DATASET_VIDEO_META SET ACTIVE_YN = 'Y' "
            + "WHERE RAW_SN = :rawSn AND SNPSHT_HASH = :hash AND ACTIVE_YN = 'N'",
            nativeQuery = true)
    int activateByHash(@Param("rawSn") Long rawSn, @Param("hash") String hash);

    /**
     * RAW_SN 단위 PostgreSQL 트랜잭션 advisory 락 획득 — 같은 영상 동시 승인(materialize)을 직렬화한다.
     *
     * <p>{@code pg_advisory_xact_lock} 은 현재 트랜잭션 종료 시 자동 해제되므로 별도 unlock 이 불필요하다.
     * deactivate-then-insert 구간을 이 락으로 감싸 "다른 해시 동시 승인 시 활성 0/2건"(CWE-362) 을 막고,
     * 부분 유니크 인덱스(V99)와 함께 활성 1건 불변식을 이중 방어한다. void 컬럼 매핑을 피하려고
     * 서브쿼리를 감싸 상수 1 을 반환한다.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:rawSn)) AS lock_acquired",
            nativeQuery = true)
    Integer acquireRawLock(@Param("rawSn") Long rawSn);

    /**
     * 백필 대상 조회 — 현재 라이브 상태가 APPROVED 이면서 활성 스냅샷({@code ACTIVE_YN='Y'})이 아직
     * 없는 영상들을 반환한다. {@code NOT EXISTS} 가드로 이미 동결된 영상은 제외되어 <b>멱등</b>하다
     * (백필 재실행 시 신규 대상 0건). 각 행에 과거 APPROVED 전이 시각({@code UPD_DT})을 함께 실어
     * 소급 {@code RVW_CMPL_DT} 로 쓴다. 파라미터가 없어 SQL Injection 표면이 없다(CWE-89).
     */
    @Query(value = """
            SELECT s.RAW_DATA_ID AS "rawSn", s.UPD_DT AS "approvedAt"
              FROM LS_RAW_DATA_STATUS s
             WHERE s.DATA_STTS_CD = 'APPROVED'
               AND NOT EXISTS (
                   SELECT 1 FROM LS_DATASET_VIDEO_META m
                    WHERE m.RAW_SN = s.RAW_DATA_ID AND m.ACTIVE_YN = 'Y'
               )
             ORDER BY s.RAW_DATA_ID
            """, nativeQuery = true)
    List<BackfillTargetRow> findApprovedWithoutActiveSnapshot();
}

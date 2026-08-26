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

    /**
     * 그 영상에 <b>승인 동결 스냅샷이 한 번이라도</b> 만들어졌는지 (P2b — "한번이라도 검수 완료" 판정).
     *
     * <h3>왜 이 테이블이 1순위 근거인가</h3>
     * {@code ReviewService.approve()} 가 <b>라벨 개수·재승인 여부와 무관하게 항상</b>
     * {@code DatasetVideoMetaSnapshotService.materialize(rawSn)} 를 호출하고, 이 테이블은
     * <b>append-only</b> 다 — 행을 지우지 않고 {@code ACTIVE_YN} 만 토글한다. 따라서 행이 하나라도
     * 있으면 "그 영상은 승인된 적이 있다"가 성립한다.
     *
     * <p><b>{@code ACTIVE_YN} 을 보지 않는다</b>: 활성 여부는 "지금 유효한 동결본"이라는 다른 축이고,
     * 여기서 알고 싶은 것은 <b>이력</b>이다.
     *
     * <p>⚠ 이 조회만으로는 부족하다 — 이 테이블은 V97 신설이라 그 이전 승인 + 백필 이전에 재제출된
     * 영상은 행이 0건일 수 있다(false negative = 게이트가 열린다). 그래서 판정은
     * {@code ReviewApprovalGate.hasEverApproved} 가 <b>승인 감사 로그와 OR</b> 로 조합한다.
     */
    boolean existsByRawSn(Long rawSn);

    List<LsDatasetVideoMeta> findByRawSnAndActiveYn(Long rawSn, String activeYn);

    /**
     * 동결 스냅샷 멱등 upsert — <b>rawSn advisory 락으로 직렬화한 뒤</b> PostgreSQL
     * {@code ON CONFLICT (RAW_SN, SNPSHT_HASH) DO NOTHING} 으로 삽입한다.
     * 반환값은 실제 삽입 행수 — 신규 삽입 1, 동일 {@code (RAW_SN, SNPSHT_HASH)} 존재(멱등 스킵) 0.
     *
     * <h3>왜 락이 필요한가 — {@code ON CONFLICT} 만으로는 "예외 없음"이 성립하지 않는다</h3>
     * 이 테이블에는 유니크 제약이 <b>둘</b>인데, {@code ON CONFLICT} 는 <b>명시한 중재 인덱스 하나만</b>
     * 흡수하고 나머지 제약 위반은 그대로 예외로 올린다:
     * <ul>
     *   <li>{@code uk_ls_dataset_video_meta UNIQUE (RAW_SN, SNPSHT_HASH)} — 중재 대상</li>
     *   <li>{@code uk_ls_dataset_video_meta_raw_active UNIQUE (RAW_SN) WHERE ACTIVE_YN='Y'}
     *       — <b>중재되지 않는다</b></li>
     * </ul>
     * 같은 {@code (RAW_SN, SNPSHT_HASH)} 를 <b>활성으로</b> 동시에 넣으면 두 제약이 동시에 걸린다.
     * 앞선 트랜잭션이 아직 커밋 전이라 중재 인덱스 사전검사를 양쪽이 모두 통과하면, 뒤진 쪽은
     * 중재되지 않는 부분 유니크 인덱스에서 {@code duplicate key} 로 죽는다. 반대로 커밋된 행이 이미
     * 보이면 사전검사가 흡수한다 — 즉 <b>경합 창은 "아직 커밋된 행이 없는 순간"에만 열린다.</b>
     * 그래서 부하에 따라 흔들렸고 오래 플레이크로 오인됐다(실측: 콜드 스타트 동시 8세션 × 150회에서
     * 락 없이 61회 실패, 락 적용 후 0회).
     *
     * <p>{@code pg_advisory_xact_lock(rawSn)} 로 같은 영상의 동시 실행을 직렬화하면 뒤진 트랜잭션은
     * 앞선 쪽이 <b>커밋한 뒤에</b> 진입하므로 사전검사가 커밋된 행을 보고 흡수한다 → 0행, 예외 없음.
     * 락은 트랜잭션 종료 시 자동 해제되고 <b>같은 트랜잭션의 재획득은 블록하지 않으므로</b>, 이미
     * {@link #acquireRawLock(Long)} 을 잡고 들어오는 호출자
     * ({@code DatasetVideoMetaSnapshotService.materialize} · {@code DatasetVideoMetaEnvCorrectionTx})
     * 에게는 무해한 재진입이며 잠금 순서(advisory → 행)도 그대로다.
     *
     * <h3>★중재 인덱스를 바꾸지 말 것 — 두 대안은 모두 의미를 깨뜨린다(실측 확인)</h3>
     * <ul>
     *   <li>중재 <b>생략</b>({@code ON CONFLICT DO NOTHING})은 모든 제약을 흡수해 경합은 사라지지만,
     *       "같은 RAW_SN 의 <b>다른</b> 해시를 기존 활성이 남은 채 삽입"이 <b>조용히 0행</b>이 되어
     *       <b>새 동결본이 유실</b>된다. 그 조합은 지금처럼 예외로 즉시 드러나야 한다
     *       ({@code deactivatePrevious} 누락 fail-fast — {@code partialUniqueIndex_blocksTwoActiveRows}).</li>
     *   <li>중재를 <b>부분 유니크로 교체</b>하면 A→B→A 재승인(대상 행이 이미 {@code ACTIVE_YN='N'} 으로
     *       존재)에서 {@code (RAW_SN, SNPSHT_HASH)} 위반이 중재되지 않아 <b>예외</b>가 난다.</li>
     * </ul>
     *
     * @param m 동결 스냅샷 값(비영속)
     * @return 실제 삽입 행수 — 신규 1, 멱등 스킵 0
     */
    default int upsertSnapshot(LsDatasetVideoMeta m) {
        // 직렬화 먼저 — 아래 INSERT 의 ON CONFLICT 는 부분 유니크 인덱스를 중재하지 못한다.
        acquireRawLock(m.getRawSn());
        return insertSnapshotIfAbsent(m);
    }

    /**
     * {@link #upsertSnapshot(LsDatasetVideoMeta)} 의 SQL 단계 — <b>직접 호출하지 말 것.</b>
     * 직렬화 없이 부르면 위에 적은 경합 창이 그대로 열린다(중재되지 않는 부분 유니크 인덱스 위반).
     *
     * <p>모든 값은 SpEL 엔티티 프로퍼티 바인딩({@code :#{#m.xxx}}) 으로 파라미터화되어 문자열 결합이
     * 없다(CWE-89). {@code INSERT ... VALUES} 컨텍스트라 null 파라미터도 대상 컬럼 타입으로 PG 가
     * 추론한다 — {@code INSERT ... SELECT} 로 바꾸면 이 추론이 깨지므로 형태를 유지한다.
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
    int insertSnapshotIfAbsent(@Param("m") LsDatasetVideoMeta m);

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

    /**
     * event_annotation 지연 동결 <b>치유 대상</b> 조회(배치) — 이미 검수 승인(APPROVED)됐고 활성 스냅샷은
     * 존재하지만 그 스냅샷의 {@code EVNT_ANNO_CN} 이 아직 {@code NULL} 로 동결돼, event_annotation 이 export
     * 에서 영구 누락되는 영상을 반환한다(HIGH — 사용자 지목 rawSn 24 상황).
     *
     * <p>대상 조건(모두 충족):
     * <ol>
     *   <li>{@code LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'} — 이미 검수 완료.</li>
     *   <li>{@code LS_EVNT_ANNO} 존재 — 동결할 event_annotation 이 있음.</li>
     *   <li>활성 스냅샷({@code ACTIVE_YN='Y'})의 {@code EVNT_ANNO_CN IS NULL} — 아직 미동결(이미 채워졌으면
     *       멱등 skip 되어 대상에서 빠진다).</li>
     *   <li>최신 검토(RVW_SN DESC)가 {@code REJECTED} 가 아님 — 명시 반려 메타는 치유 제외(반려 존중).</li>
     * </ol>
     *
     * <p>기존 {@link #findApprovedWithoutActiveSnapshot()} 는 {@code NOT EXISTS ACTIVE} 가드 때문에 활성
     * 스냅샷이 <b>이미 있는</b> rawSn 24 를 제외한다 — 그래서 별도 치유 쿼리가 필요하다. 치유가 성공하면
     * {@code EVNT_ANNO_CN} 이 채워져 다음 배치에서 자동으로 대상에서 빠지므로 {@code LIMIT} 배치 반복이
     * 무한 루프 없이 수렴한다(대량 영상 대비 페이징). 최신 검토 선택은 {@code LS_EVNT_ANNO_REVIEW} 결정적
     * 정렬과 동일 기준이다. 모든 값은 파라미터 바인딩(CWE-89).
     */
    @Query(value = """
            SELECT s.RAW_DATA_ID AS "rawSn", NULL AS "approvedAt"
              FROM LS_RAW_DATA_STATUS s
              JOIN LS_EVNT_ANNO a ON a.RAW_SN = s.RAW_DATA_ID
              JOIN LS_DATASET_VIDEO_META m
                ON m.RAW_SN = s.RAW_DATA_ID AND m.ACTIVE_YN = 'Y' AND m.EVNT_ANNO_CN IS NULL
             WHERE s.DATA_STTS_CD = 'APPROVED'
               AND COALESCE((SELECT r.RVW_STTS_CD FROM LS_EVNT_ANNO_REVIEW r
                              WHERE r.EVNT_ANNO_SN = a.EVNT_ANNO_SN
                              ORDER BY r.RVW_SN DESC
                              LIMIT 1), 'NONE') <> 'REJECTED'
             ORDER BY s.RAW_DATA_ID
             LIMIT :batchSize
            """, nativeQuery = true)
    List<BackfillTargetRow> findEventAnnoHealTargets(@Param("batchSize") int batchSize);

    /**
     * 촬영환경 <b>레거시 파생 동결값 정정 대상</b> 판별식(M-1, Phase 10B) — 공통 WHERE 절.
     *
     * <p>대상 = 라이브 {@code LS_DATA_RAW} 의 수동값이 <b>없는데</b> 활성 스냅샷에는 값이 <b>있는</b> 행.
     * E-ISSUE-42 로 동결 경로의 파생 폴백을 제거한 뒤에는 수동 입력이 유일한 원천이므로,
     * "수동 원천 없음 + 동결값 있음" 조합이 성립할 경로는 <b>폐기된 파생 폴백뿐</b>이다 —
     * 즉 그 값이 파생값(추정)임이 결정적으로 증명된다.
     *
     * <p>범위 제한(넓히면 사용자 입력을 지운다):
     * <ul>
     *   <li><b>날씨({@code WTHR_NM})는 대상이 아니다</b> — 애초에 파생 원천이 없어 non-null 이면 수동값이다.</li>
     *   <li><b>반대 방향(raw non-null + 스냅샷 null)도 대상이 아니다</b> — 승인 이후 수동 입력이 추가된
     *       정상 케이스이며 동결은 승인 시점 스냅샷이라 그대로 둔다.</li>
     *   <li>{@code APPROVED} 만 대상 — 관제/데이터마트에 노출되는 상태이며, 미승인 영상은 다음 승인의
     *       {@code materialize} 가 라이브 수동값(=null)으로 자연히 정정한다.</li>
     * </ul>
     *
     * <p>공백은 미입력으로 정규화({@code NULLIF(TRIM(..),'')})해 {@code materialize} 의 blank→null 규칙과
     * 판정을 일치시킨다. 정정 후에는 스냅샷 값이 null 이 되어 이 판별식에서 빠지므로 <b>멱등</b>하다.
     */
    String ENV_CORRECTION_PREDICATE = """
              FROM LS_RAW_DATA_STATUS s
              JOIN LS_DATA_RAW r ON r.RAW_SN = s.RAW_DATA_ID
              JOIN LS_DATASET_VIDEO_META m ON m.RAW_SN = s.RAW_DATA_ID AND m.ACTIVE_YN = 'Y'
             WHERE s.DATA_STTS_CD = 'APPROVED'
               AND ((NULLIF(TRIM(r.DAY_NGT_CD), '') IS NULL AND NULLIF(TRIM(m.DAY_NGT_CD), '') IS NOT NULL)
                 OR (NULLIF(TRIM(r.SESN_CD), '')    IS NULL AND NULLIF(TRIM(m.SESN_CD), '')    IS NOT NULL))
            """;

    /**
     * 정정 대상 <b>총 건수</b>(dry-run 성격) — 실제 정정 전에 규모를 로그로 알려 운영이 폭주 여부를
     * 판단할 수 있게 한다. 파라미터가 없어 SQL Injection 표면이 없다(CWE-89).
     *
     * @see #ENV_CORRECTION_PREDICATE
     */
    @Query(value = "SELECT COUNT(*) " + ENV_CORRECTION_PREDICATE, nativeQuery = true)
    long countShootingEnvCorrectionTargets();

    /**
     * 정정 대상 1페이지 조회({@code LIMIT} 배치 — 무제한 조회 금지).
     *
     * <p>{@code approvedAt} 은 여기서 채우지 않고({@code NULL}) 정정 트랜잭션 안에서 활성 스냅샷의
     * {@code RVW_CMPL_DT} 를 advisory 락 아래 다시 읽는다 — 조회~정정 사이에 재동결이 끼어들어도
     * 승계할 승인 시각이 stale 해지지 않게 하기 위함이다(선례: {@code frozenReviewCompletedAt}).
     *
     * @see #ENV_CORRECTION_PREDICATE
     */
    @Query(value = "SELECT s.RAW_DATA_ID AS \"rawSn\", NULL AS \"approvedAt\" "
            + ENV_CORRECTION_PREDICATE
            + " ORDER BY s.RAW_DATA_ID LIMIT :batchSize", nativeQuery = true)
    List<BackfillTargetRow> findShootingEnvCorrectionTargets(@Param("batchSize") int batchSize);
}

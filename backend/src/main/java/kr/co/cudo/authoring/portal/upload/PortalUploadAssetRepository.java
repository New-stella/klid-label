package kr.co.cudo.authoring.portal.upload;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 포털 업로드 자산이 <b>공용 원장에서 어떻게 읽히고 어떻게 전이하는가</b>의 단일 지점 (ADR-058 흡수).
 *
 * <p>흡수 전에는 전용 표 한 벌이라 파생 쿼리로 끝났다. 지금은 영상 원장 + 메타 원장 + 프레임 원장을
 * 조립해야 하고, 무엇보다 <b>상태가 키·값에 살면서 「행이 없음」이 하나의 값</b>이 됐다. 그 두 가지가
 * 이 클래스가 손으로 쓴 SQL 을 갖는 이유다.
 *
 * <h2>★ 상태 부재 = 업로드됨 — 전이 SQL 의 <b>문장 종류</b>가 여기서 갈린다</h2>
 * <ul>
 *   <li><b>업로드됨에서 출발하는 전이</b>는 단순 UPDATE 로 표현할 수 없다. 대상 행이 아예 없을 수
 *       있기 때문이다 — 그래서 <b>삽입 겸 조건부 갱신</b>({@code ON CONFLICT ... DO UPDATE ... WHERE})
 *       을 쓴다. 없으면 넣고(= 업로드됨에서 출발한 것), 있으면 값이 맞을 때만 고친다.</li>
 *   <li><b>후처리 중에서 출발하는 전이</b>는 반대로 <b>단순 UPDATE 여야 한다</b>. 삽입 겸 갱신으로
 *       쓰면 상태 행이 없는 자산(= 업로드됨)이 후처리를 건너뛰고 그 상태로 점프한다.</li>
 * </ul>
 * <p>두 형태를 「일관성」을 이유로 하나로 합치지 말 것 — 합치는 순간 한쪽이 반드시 틀린다.
 *
 * <h2>★★ 보존기간 만료 자동 삭제 — 판별자 셋을 <b>실행문마다</b> 건다</h2>
 * <p>흡수 전에는 전용 표가 울타리였다. 이제 삭제 대상은 <b>출처 판별자 + 소유자 보유 + 보존기간 경과</b>
 * 셋을 동시에 충족해야 하며 <b>하나만 빠져도 관제 영상을 지운다</b>. 그래서
 * <ul>
 *   <li>조건을 후보 조회에만 두지 않고 <b>최종 삭제 실행문 자체에</b> 박는다(파생영상 폐기 삭제와
 *       같은 원칙). 조회~삭제 사이에 조건이 풀릴 수 있고, 그 창에서 지우면 비가역이다.</li>
 *   <li><b>영상 식별자만 받아 지우는 창구를 두지 않는다</b> — 삭제 메서드는 축과 커트라인(또는
 *       소유자)을 함께 받고, 그 인자가 곧 조건이다.</li>
 * </ul>
 *
 * <h2>연쇄 삭제 — 부모 외래키가 없어 조용히 고아가 남는다</h2>
 * <p>영상 행을 지우면 프레임({@code LS_DATA_SRC})·메타({@code LS_DATA_META})는 외래키 연쇄로 정리되지만
 * <b>라벨·라벨 속성값·라벨 이력·증강·증강라벨매핑은 그렇지 않다</b>. 특히 라벨 속성값의 외래키는
 * 연쇄가 아니어서 <b>라벨을 먼저 지우면 외래키 위반으로 실패</b>한다(실측). 순서를 지킨다:
 * <pre>
 *   라벨 속성값 → 라벨 → 라벨 이력 → 증강라벨매핑 → 증강 → 영상
 * </pre>
 *
 * @design ADR-058
 * @design ERD-028
 * @design DFEAT-055
 */
@Repository
// 리포지토리가 스스로 트랜잭션 경계를 갖는다 — Spring Data 리포지토리와 같은 규약이다.
//   여기 문장들은 <네이티브 갱신·삭제>라 활성 트랜잭션 없이는 실행 자체가 거부된다. 호출부가
//   트랜잭션을 갖고 있으면 그대로 합류하고(REQUIRED), 없으면 이 경계가 만든다 — 그래야 정리 잡·
//   시험처럼 서비스 트랜잭션 밖에서 부르는 자리가 조용히 깨지지 않는다.
@Transactional("controlTransactionManager")
public class PortalUploadAssetRepository {

    /**
     * 상태 축 — 「행이 없으면 업로드됨」을 SQL 로 옮긴 것.
     *
     * <p>조인 별칭이 아니라 <b>상관 스칼라 서브쿼리</b>다. 그래야 SELECT·EXISTS·DELETE 어디에서나
     * <b>같은 술어</b>를 쓸 수 있다 — 조인 형태로 두면 DELETE 에서 쓸 수 없어 조회와 삭제가 서로
     * 다른 술어를 갖게 되고, 그 순간 한쪽만 고치는 사고가 열린다.
     */
    private static final String STATUS_EXPR =
            "COALESCE((SELECT m.meta_vl FROM ls_data_meta m"
                    + " WHERE m.raw_sn = r.raw_sn AND m.meta_key = :statusKey), '"
                    + PortalUploadLedger.STATUS_UPLOADED + "')";

    /**
     * 상태 마지막 변경 시각 — 방치 판정과 보존기간(실패 축)의 기준점.
     *
     * <p>상태 행이 없으면(= 업로드됨) 영상 행의 시각으로 떨어진다. 그러지 않으면 상태를 기록하기 전
     * 자산이 <b>기준점 없음</b>이 되어, NULL 비교가 거짓이라 방치 스윕에서 영영 빠진다.
     */
    private static final String STATUS_CHANGED_EXPR =
            "COALESCE((SELECT COALESCE(m.mdfcn_dt, m.reg_dt) FROM ls_data_meta m"
                    + " WHERE m.raw_sn = r.raw_sn AND m.meta_key = :statusKey), r.mdfcn_dt, r.reg_dt)";

    /** 자산 종류 SQL 식 — 판정 규칙은 {@link PortalUploadLedger#assetTypeOf(String)} 과 같아야 한다. */
    private static final String ASSET_TYPE_EXPR =
            "(CASE WHEN lower(mi.meta_vl) LIKE 'image/%' THEN '" + PortalUploadLedger.TYPE_IMAGE
                    + "' ELSE '" + PortalUploadLedger.TYPE_VIDEO + "' END)";

    /**
     * 정렬 allowlist — <b>논리 키 → SQL 식</b>. 외부 키는 컨트롤러가 이미 걸렀고, 여기 표에 없는 키는
     * 조용히 무시한다(미지의 문자열이 SQL 로 흘러갈 통로를 남기지 않는다 — CWE-89).
     */
    private static final Map<String, String> SORT_EXPRESSIONS = Map.of(
            "regDt", "r.reg_dt",
            "uldSn", "r.raw_sn",
            "uldSttsCd", "COALESCE(st.meta_vl, '" + PortalUploadLedger.STATUS_UPLOADED + "')",
            "uldTypeCd", ASSET_TYPE_EXPR,
            "fileSz", "(CASE WHEN fs.meta_vl ~ '^[0-9]+$' THEN CAST(fs.meta_vl AS bigint) END)");

    /** 자산 조립 SELECT — <b>컬럼 순서가 곧 매핑 규약</b>이다({@link #toAsset}). */
    private static final String SELECT_ASSET = """
            SELECT r.raw_sn, r.portal_user_no, r.raw_file_path_nm,
                   r.vdo_len_sec, r.vdo_len_ms, r.reg_dt,
                   COALESCE(st.meta_vl, '""" + PortalUploadLedger.STATUS_UPLOADED + "')"
            + ", COALESCE(st.mdfcn_dt, st.reg_dt, r.mdfcn_dt, r.reg_dt)"
            + ", fr.meta_vl, nm.meta_vl, mi.meta_vl, fp.meta_vl, fs.meta_vl"
            + ", (SELECT count(*) FROM ls_data_src s WHERE s.raw_sn = r.raw_sn) ";

    /** 자산 조회 공통 FROM/JOIN — 메타 여섯 키를 각각 LEFT JOIN 으로 붙인다(조회는 값이 다 필요하다). */
    private static final String FROM_ASSET = """
              FROM ls_data_raw r
              LEFT JOIN ls_data_meta st ON st.raw_sn = r.raw_sn AND st.meta_key = :statusKey
              LEFT JOIN ls_data_meta fr ON fr.raw_sn = r.raw_sn AND fr.meta_key = :failKey
              LEFT JOIN ls_data_meta nm ON nm.raw_sn = r.raw_sn AND nm.meta_key = :nameKey
              LEFT JOIN ls_data_meta mi ON mi.raw_sn = r.raw_sn AND mi.meta_key = :mimeKey
              LEFT JOIN ls_data_meta fp ON fp.raw_sn = r.raw_sn AND fp.meta_key = :fpsKey
              LEFT JOIN ls_data_meta fs ON fs.raw_sn = r.raw_sn AND fs.meta_key = :sizeKey
            """;

    /**
     * 포털 자산 판별 — <b>출처 판별자 + 소유자 보유</b> 두 축을 함께 건다. 「소유자가 비어 있으면
     * 통과」 같은 완화를 두면 관제 영상이 포털 채널로 샌다.
     */
    private static final String PORTAL_SCOPE =
            " WHERE r.src_type = :srcType AND r.portal_user_no IS NOT NULL";

    private static final String OWNER_SCOPE = PORTAL_SCOPE + " AND r.portal_user_no = :owner";

    @PersistenceContext(unitName = "control")
    private EntityManager em;

    // ==================================================================
    // 조회
    // ==================================================================

    /** 소유자 자산 단건 — 소유자 불일치·부재는 모두 {@code empty}(응답 통일은 호출부 몫). */
    public Optional<PortalUploadAsset> findByOwner(Long uldSn, String portalUserNo) {
        Query q = em.createNativeQuery(SELECT_ASSET + FROM_ASSET + OWNER_SCOPE + " AND r.raw_sn = :rawSn");
        bindMetaKeys(q);
        q.setParameter("srcType", PortalUploadLedger.SRC_TYPE);
        q.setParameter("owner", portalUserNo);
        q.setParameter("rawSn", uldSn);
        List<?> rows = q.getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(toAsset((Object[]) rows.get(0)));
    }

    /** 소유권 미검증 단건 — 내부 파이프라인(프레임 추출 러너) 전용. 사용자 요청 진입점에서 쓰지 말 것. */
    public Optional<PortalUploadAsset> findPortalAsset(Long uldSn) {
        Query q = em.createNativeQuery(SELECT_ASSET + FROM_ASSET + PORTAL_SCOPE + " AND r.raw_sn = :rawSn");
        bindMetaKeys(q);
        q.setParameter("srcType", PortalUploadLedger.SRC_TYPE);
        q.setParameter("rawSn", uldSn);
        List<?> rows = q.getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(toAsset((Object[]) rows.get(0)));
    }

    /**
     * 소유자 자산 목록(페이징) + 선택적 자산 종류 필터.
     *
     * @param assetTypeCd {@code null} 이면 전체. 자산 종류는 <b>보관하지 않으므로</b> 매체 유형에서
     *                    판정한 식으로 거른다 — 읽기·정렬·필터가 모두 같은 식을 쓴다
     */
    public Page<PortalUploadAsset> findPageByOwner(String portalUserNo, String assetTypeCd, Pageable pageable) {
        String typeFilter = assetTypeCd == null ? "" : " AND " + ASSET_TYPE_EXPR + " = :assetType";
        String sql = SELECT_ASSET + FROM_ASSET + OWNER_SCOPE + typeFilter + orderBy(pageable.getSort());

        Query q = em.createNativeQuery(sql);
        bindMetaKeys(q);
        q.setParameter("srcType", PortalUploadLedger.SRC_TYPE);
        q.setParameter("owner", portalUserNo);
        if (assetTypeCd != null) {
            q.setParameter("assetType", assetTypeCd);
        }
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        List<PortalUploadAsset> content = new ArrayList<>();
        for (Object row : q.getResultList()) {
            content.add(toAsset((Object[]) row));
        }

        Query countQuery = em.createNativeQuery("SELECT count(*)" + FROM_ASSET + OWNER_SCOPE + typeFilter);
        bindMetaKeys(countQuery);
        countQuery.setParameter("srcType", PortalUploadLedger.SRC_TYPE);
        countQuery.setParameter("owner", portalUserNo);
        if (assetTypeCd != null) {
            countQuery.setParameter("assetType", assetTypeCd);
        }
        long total = ((Number) countQuery.getSingleResult()).longValue();
        return new PageImpl<>(content, pageable, total);
    }

    /**
     * 자산별 <b>라벨 마지막 저장일</b> 집계 — 보존기간(READY 축) 기준점 한쪽. @design DFEAT-055
     *
     * <p>목록 한 페이지의 자산 집합을 단일 집계 1회로 모은다(자산마다 조회하면 N+1). 라벨이 없는
     * 자산은 <b>키 자체가 없다</b>. 소유자 스코프를 조건에 강제한다 — 결과는 같지만 빼면 그 보장이
     * 조회 순서에 의존한다.
     */
    @SuppressWarnings("unchecked")
    public Map<Long, LocalDateTime> findLastLabelSavedAt(String portalUserNo, List<Long> uldSns) {
        Map<Long, LocalDateTime> result = new LinkedHashMap<>();
        if (uldSns == null || uldSns.isEmpty()) {
            return result;
        }
        Query q = em.createNativeQuery("""
                SELECT s.raw_sn, max(l.reg_dt)
                  FROM ls_data_lbl l
                  JOIN ls_data_src s ON s.src_sn = l.src_sn
                  JOIN ls_data_raw r ON r.raw_sn = s.raw_sn
                 WHERE r.src_type = :srcType
                   AND r.portal_user_no = :owner
                   AND s.raw_sn IN (:rawSns)
                 GROUP BY s.raw_sn
                """);
        q.setParameter("srcType", PortalUploadLedger.SRC_TYPE);
        q.setParameter("owner", portalUserNo);
        q.setParameter("rawSns", uldSns);
        for (Object row : (List<Object>) q.getResultList()) {
            Object[] cols = (Object[]) row;
            if (cols[0] == null || cols[1] == null) {
                continue;
            }
            result.put(((Number) cols[0]).longValue(), toDateTime(cols[1]));
        }
        return result;
    }

    /**
     * 자산별 <b>표시용 원본 파일명</b> 일괄 조회 — 목록 한 페이지를 단일 쿼리 1회로 모은다.
     *
     * <p>소유자 스코프를 조건에 강제한다. 보관값이 없는 자산은 <b>키 자체가 없다</b>(빈 문자열을
     * 지어내지 않는다 — 「모른다」와 「빈 이름」은 다르다).
     */
    @SuppressWarnings("unchecked")
    public Map<Long, String> findOriginalFileNames(String portalUserNo, List<Long> rawSns) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (rawSns == null || rawSns.isEmpty()) {
            return result;
        }
        Query q = em.createNativeQuery("""
                SELECT r.raw_sn, m.meta_vl
                  FROM ls_data_raw r
                  JOIN ls_data_meta m ON m.raw_sn = r.raw_sn AND m.meta_key = :nameKey
                 WHERE r.src_type = :srcType
                   AND r.portal_user_no = :owner
                   AND r.raw_sn IN (:rawSns)
                """);
        q.setParameter("nameKey", PortalUploadLedger.KEY_ORIGINAL_FILENAME);
        q.setParameter("srcType", PortalUploadLedger.SRC_TYPE);
        q.setParameter("owner", portalUserNo);
        q.setParameter("rawSns", rawSns);
        for (Object row : (List<Object>) q.getResultList()) {
            Object[] cols = (Object[]) row;
            if (cols[0] == null || cols[1] == null) {
                continue;
            }
            result.put(((Number) cols[0]).longValue(), (String) cols[1]);
        }
        return result;
    }

    /**
     * 자산별 <b>등록 일시</b> 일괄 조회 — 증강 결과물이 <b>언제 도착했는가</b>의 조달처.
     *
     * <p>★ 소유자 스코프가 곧 <b>결과물 노출 가드</b>다. 여기 없는 식별자는 그 사용자가 열 수 없는
     * 자산이므로, 조회 결과에 실리지 않는 결과물 식별자를 응답에 담지 않는다(CWE-639). 결과물이
     * 포털 계보에 앉지 않은 동안에는 이 조회가 비어 있고, 그것이 fail-closed 방향이다.
     */
    @SuppressWarnings("unchecked")
    public Map<Long, LocalDateTime> findRegDtByOwner(String portalUserNo, List<Long> rawSns) {
        Map<Long, LocalDateTime> result = new LinkedHashMap<>();
        if (rawSns == null || rawSns.isEmpty()) {
            return result;
        }
        Query q = em.createNativeQuery("""
                SELECT r.raw_sn, r.reg_dt
                  FROM ls_data_raw r
                 WHERE r.src_type = :srcType
                   AND r.portal_user_no = :owner
                   AND r.raw_sn IN (:rawSns)
                """);
        q.setParameter("srcType", PortalUploadLedger.SRC_TYPE);
        q.setParameter("owner", portalUserNo);
        q.setParameter("rawSns", rawSns);
        for (Object row : (List<Object>) q.getResultList()) {
            Object[] cols = (Object[]) row;
            if (cols[0] == null) {
                continue;
            }
            LocalDateTime regDt = toDateTime(cols[1]);
            if (regDt != null) {
                result.put(((Number) cols[0]).longValue(), regDt);
            }
        }
        return result;
    }

    // ==================================================================
    // 적재
    // ==================================================================

    /**
     * 자산 적재 — 영상 원장 행 + 클립 식별자 확정 + 상태·메타 기록을 <b>한 트랜잭션</b>으로 끝낸다.
     *
     * <p>상태({@link PortalUploadLedger#STATUS_UPLOADED})를 <b>명시적으로 기록</b>한다. 부재를
     * 업로드됨으로 읽는 규칙은 그 기록이 아직 없는 좁은 틈을 위한 방어이지 기록을 생략해도 된다는
     * 뜻이 아니다 — 생략하면 방치 판정의 기준점이 영상 등록 시각에 고정돼 갱신되지 않는다.
     *
     * @return 적재된 자산 식별자({@code RAW_SN})
     */
    public Long insertUploaded(String portalUserNo, String filePathNm, String orgnlFileNm,
                               String mimeTypeNm, Long fileSz) {
        LsDataRaw raw = LsDataRaw.createPortalUpload(portalUserNo, filePathNm);
        em.persist(raw);
        // 클립 식별자는 PK 가 확정된 뒤에만 만들 수 있다 — 같은 트랜잭션에서 곧바로 확정한다.
        em.flush();
        raw.assignPortalClipId();
        em.flush();

        Long rawSn = raw.getRawSn();
        upsertMeta(rawSn, PortalUploadLedger.KEY_UPLOAD_STATUS, PortalUploadLedger.STATUS_UPLOADED);
        upsertMeta(rawSn, PortalUploadLedger.KEY_ORIGINAL_FILENAME, orgnlFileNm);
        upsertMeta(rawSn, PortalUploadLedger.KEY_MIME, mimeTypeNm);
        upsertMeta(rawSn, PortalUploadLedger.KEY_FILESIZE, fileSz == null ? null : String.valueOf(fileSz));
        return rawSn;
    }

    /**
     * 메타 한 칸을 원자적으로 기록한다({@code ON CONFLICT}). 값이 {@code null} 이면 아무것도 하지
     * 않는다 — 「모른다」는 빈 칸으로 두는 편이 지어낸 값보다 낫다.
     */
    public void upsertMeta(Long rawSn, String metaKey, String metaVl) {
        if (metaVl == null) {
            return;
        }
        em.createNativeQuery("""
                INSERT INTO ls_data_meta (raw_sn, meta_key, meta_vl, reg_dt, mdfcn_dt)
                VALUES (:rawSn, :metaKey, :metaVl, now(), now())
                ON CONFLICT (raw_sn, meta_key)
                DO UPDATE SET meta_vl = EXCLUDED.meta_vl, mdfcn_dt = now()
                """)
                .setParameter("rawSn", rawSn)
                .setParameter("metaKey", metaKey)
                .setParameter("metaVl", metaVl)
                .executeUpdate();
    }

    /** 메타 한 칸을 지운다 — 「값이 없다」를 빈 문자열이 아니라 행 부재로 표현한다. */
    public void deleteMeta(Long rawSn, String metaKey) {
        em.createNativeQuery("DELETE FROM ls_data_meta WHERE raw_sn = :rawSn AND meta_key = :metaKey")
                .setParameter("rawSn", rawSn)
                .setParameter("metaKey", metaKey)
                .executeUpdate();
    }

    /** 영상 길이 확정 — 초는 반올림 정수, 밀리초 정밀도는 별도 칸이 보존한다(ERD-028). */
    public void applyVideoDuration(Long rawSn, Double durationSeconds) {
        if (durationSeconds == null || durationSeconds <= 0d || !Double.isFinite(durationSeconds)) {
            return;
        }
        em.createNativeQuery("""
                UPDATE ls_data_raw
                   SET vdo_len_sec = :sec, vdo_len_ms = :ms, mdfcn_dt = now()
                 WHERE raw_sn = :rawSn AND src_type = :srcType
                """)
                .setParameter("sec", (int) Math.round(durationSeconds))
                .setParameter("ms", Math.round(durationSeconds * 1000d))
                .setParameter("rawSn", rawSn)
                .setParameter("srcType", PortalUploadLedger.SRC_TYPE)
                .executeUpdate();
    }

    // ==================================================================
    // 원자 전이
    // ==================================================================

    /**
     * <b>업로드됨 → 후처리 중</b> 원자 전이(러너 진입 시점).
     *
     * <p>상태 행이 <b>없어도</b> 성립해야 한다(부재 = 업로드됨) — 그래서 삽입 겸 조건부 갱신이다.
     * 이미 후처리 중이거나 완료·실패면 갱신 조건에 걸려 0행이 되고 러너는 즉시 중단한다.
     *
     * @return 전이한 행 수(1 이면 이 호출이 처리 책임, 0 이면 다른 상태이거나 포털 자산이 아니다)
     */
    public int transitionToProcessing(Long uldSn) {
        return em.createNativeQuery("""
                INSERT INTO ls_data_meta (raw_sn, meta_key, meta_vl, reg_dt, mdfcn_dt)
                SELECT r.raw_sn, :statusKey, :toStatus, now(), now()
                  FROM ls_data_raw r
                 WHERE r.raw_sn = :rawSn
                   AND r.src_type = :srcType
                   AND r.portal_user_no IS NOT NULL
                ON CONFLICT (raw_sn, meta_key)
                DO UPDATE SET meta_vl = EXCLUDED.meta_vl, mdfcn_dt = now()
                 WHERE ls_data_meta.meta_vl = :fromStatus
                """)
                .setParameter("statusKey", PortalUploadLedger.KEY_UPLOAD_STATUS)
                .setParameter("toStatus", PortalUploadLedger.STATUS_PROCESSING)
                .setParameter("fromStatus", PortalUploadLedger.STATUS_UPLOADED)
                .setParameter("srcType", PortalUploadLedger.SRC_TYPE)
                .setParameter("rawSn", uldSn)
                .executeUpdate();
    }

    /**
     * <b>후처리 중 → 라벨링 가능</b> 원자 전이(추출 완료 시점).
     *
     * <p>여기는 <b>단순 UPDATE 여야 한다</b> — 삽입 겸 갱신으로 쓰면 상태 행이 없는 자산(= 업로드됨)이
     * 후처리를 건너뛰고 완료로 점프한다. 스윕이 이미 실패로 마감한 자산을 되살리는 것도 막는다.
     *
     * @return 전이한 행 수(0 이면 이미 실패 처리됐거나 삭제됐다 — 되살리지 않는다)
     */
    public int transitionToReady(Long uldSn) {
        return em.createNativeQuery("""
                UPDATE ls_data_meta
                   SET meta_vl = :toStatus, mdfcn_dt = now()
                 WHERE raw_sn = :rawSn AND meta_key = :statusKey AND meta_vl = :fromStatus
                """)
                .setParameter("statusKey", PortalUploadLedger.KEY_UPLOAD_STATUS)
                .setParameter("toStatus", PortalUploadLedger.STATUS_READY)
                .setParameter("fromStatus", PortalUploadLedger.STATUS_PROCESSING)
                .setParameter("rawSn", uldSn)
                .executeUpdate();
    }

    /**
     * <b>후처리 중 → 처리 실패</b> 조건부 전이(러너 실패 경로). 단순 UPDATE 인 이유는
     * {@link #transitionToReady} 와 같다 — 상태 행이 없는 자산을 실패로 만들지 않는다.
     *
     * @return 전이한 행 수(0 이면 이미 완료·실패·삭제 — 덮지 않는다)
     */
    public int failFromProcessing(Long uldSn) {
        return em.createNativeQuery("""
                UPDATE ls_data_meta
                   SET meta_vl = :toStatus, mdfcn_dt = now()
                 WHERE raw_sn = :rawSn AND meta_key = :statusKey AND meta_vl = :fromStatus
                """)
                .setParameter("statusKey", PortalUploadLedger.KEY_UPLOAD_STATUS)
                .setParameter("toStatus", PortalUploadLedger.STATUS_FAILED)
                .setParameter("fromStatus", PortalUploadLedger.STATUS_PROCESSING)
                .setParameter("rawSn", uldSn)
                .executeUpdate();
    }

    /**
     * <b>방치 판정 → 처리 실패</b> 조건부 전이(스윕 경로). 출발 상태는 <b>후처리 중</b> 하나다.
     *
     * <h3>★ 삽입 겸 갱신이 아니라 단순 UPDATE 다 (2026-09-02 순서 반전)</h3>
     * <p>구 형태는 「상태 행 부재(= 업로드됨)」도 출발점으로 삼아 삽입 겸 갱신이었다. 이제 업로드됨은
     * <b>마킹 대기</b>라 방치가 아니므로 출발점에서 뺐고, 그러면 남는 출발점이 실재하는 행 하나뿐이라
     * 단순 UPDATE 로 충분하다. 삽입 겸 갱신을 남겨 두면 상태 행이 없는 정상 대기 자산까지 실패로
     * 만들 수 있다.
     *
     * <p>라벨링 가능·이미 실패인 자산은 갱신 조건에 걸려 0행이 된다(역방향 전이 차단).
     *
     * @return 전이한 행 수(2노드 동시 실행에서 한쪽만 1)
     * @design DFEAT-055
     */
    public int failStuck(Long uldSn) {
        return em.createNativeQuery("""
                UPDATE ls_data_meta
                   SET meta_vl = :toStatus, mdfcn_dt = now()
                 WHERE raw_sn = :rawSn AND meta_key = :statusKey AND meta_vl = :fromStatus
                """)
                .setParameter("statusKey", PortalUploadLedger.KEY_UPLOAD_STATUS)
                .setParameter("toStatus", PortalUploadLedger.STATUS_FAILED)
                .setParameter("fromStatus", PortalUploadLedger.STATUS_PROCESSING)
                .setParameter("rawSn", uldSn)
                .executeUpdate();
    }

    /**
     * 후처리 진행 중 하트비트 — 상태 행의 변경 시각만 민다.
     *
     * <p>방치 판정 축이 <b>무갱신 경과 시간</b>이라, 오래 걸리는 정상 추출이 방치로 오판되지 않게
     * 러너가 주기적으로 부른다. 0행이면 삭제·전이된 것이므로 러너의 중단 신호이기도 하다.
     *
     * @return 여전히 후처리 중이면 1, 아니면 0
     */
    public int touchProcessing(Long uldSn) {
        return em.createNativeQuery("""
                UPDATE ls_data_meta
                   SET mdfcn_dt = now()
                 WHERE raw_sn = :rawSn AND meta_key = :statusKey AND meta_vl = :status
                """)
                .setParameter("statusKey", PortalUploadLedger.KEY_UPLOAD_STATUS)
                .setParameter("status", PortalUploadLedger.STATUS_PROCESSING)
                .setParameter("rawSn", uldSn)
                .executeUpdate();
    }

    /**
     * 방치 후보 — 커트라인 이전부터 상태 갱신이 멈춘 <b>후처리 중</b> 자산.
     *
     * <h3>★ 「업로드됨」은 더 이상 방치 후보가 아니다 (2026-09-02 순서 반전)</h3>
     * <p>그 상태의 뜻이 <b>「추출 대기」에서 「마킹 대기」로</b> 바뀌었다. 추출 대기는 서버가 곧
     * 처리할 상태라 오래 머무르면 방치가 맞지만, 마킹 대기는 <b>사람이 화면에 들어와 지점을 고를
     * 때까지</b>의 상태라 며칠이 걸려도 정상이다. 그대로 두면 올려 둔 영상이 커트라인(기본 30분)마다
     * 실패로 마감되고 실패 보존기간 뒤 <b>비가역 삭제</b>된다 — 사용자가 아무것도 잘못하지 않았는데
     * 데이터가 사라진다.
     *
     * <p>그래서 후보를 후처리 중 하나로 좁힌다. 후처리 중은 러너가 하트비트로 갱신 시각을 밀어내므로
     * 「무갱신 경과」 판정이 여전히 성립한다.
     *
     * <p>★ 그때 남았던 공백 — <b>마킹하지 않은 자산은 어느 스윕에도 걸리지 않아 파일째 영구히
     * 남는다</b> — 은 2026-09-05 확정으로 <b>보존기간 축</b>이 닫았다({@link RetentionAxis#UPLOADED}).
     * 방치 전이가 아니라 <b>일 단위 정상 만료</b>로 닫은 것이라 <b>이 메서드의 후보는 여전히
     * 후처리 중 하나</b>다 — 두 경로를 합치지 말 것(타이머 길이도 종착점도 다르다).
     *
     * @design DFEAT-055
     * @design AC-1070
     * @design API-140
     */
    @SuppressWarnings("unchecked")
    public List<Long> findStuck(LocalDateTime cutoff) {
        Query q = em.createNativeQuery("SELECT r.raw_sn FROM ls_data_raw r"
                + PORTAL_SCOPE
                + " AND " + STATUS_EXPR + " = :status"
                + " AND " + STATUS_CHANGED_EXPR + " < :cutoff"
                + " ORDER BY r.raw_sn");
        q.setParameter("statusKey", PortalUploadLedger.KEY_UPLOAD_STATUS);
        q.setParameter("srcType", PortalUploadLedger.SRC_TYPE);
        q.setParameter("status", PortalUploadLedger.STATUS_PROCESSING);
        q.setParameter("cutoff", cutoff);
        return ((List<Object>) q.getResultList()).stream().map(v -> ((Number) v).longValue()).toList();
    }

    // ==================================================================
    // 보존기간 만료 자동 삭제
    // ==================================================================

    /**
     * 만료 후보 조회. @design DFEAT-055, AC-1070, AC-036, AC-037
     *
     * <p>축별로 <b>기준점이 다르다</b>(설정 키는 마킹 대기·준비 완료가 같다). 후보 조회와 삭제
     * 실행문이 {@link #expiryPredicate} 하나를 공유한다 — 한쪽만 고치면 조회는 잡는데 삭제는
     * 못 하거나(고착) 그 반대(과삭제)가 된다.
     *
     * <p><b>{@code PROCESSING} 만</b> 어느 축에도 없어 구조적으로 후보가 될 수 없다(AC-1070).
     */
    @SuppressWarnings("unchecked")
    public List<Long> findExpired(RetentionAxis axis, LocalDateTime cutoff) {
        Query q = em.createNativeQuery(
                "SELECT r.raw_sn FROM ls_data_raw r WHERE" + expiryPredicate(axis) + " ORDER BY r.raw_sn");
        bindRetentionParams(q, axis, cutoff);
        return ((List<Object>) q.getResultList()).stream().map(v -> ((Number) v).longValue()).toList();
    }

    /** 자산 1건의 삭제 대상 파일 경로(프레임 + 원본, 중복 제거·순서 보존). */
    @SuppressWarnings("unchecked")
    public List<String> findFilePaths(Long uldSn) {
        List<String> paths = new ArrayList<>();
        Query frames = em.createNativeQuery(
                "SELECT s.src_file_path_nm FROM ls_data_src s WHERE s.raw_sn = :rawSn ORDER BY s.frm_no");
        frames.setParameter("rawSn", uldSn);
        collectPaths((List<Object>) frames.getResultList(), paths);

        Query original = em.createNativeQuery(
                "SELECT r.raw_file_path_nm FROM ls_data_raw r WHERE r.raw_sn = :rawSn");
        original.setParameter("rawSn", uldSn);
        collectPaths((List<Object>) original.getResultList(), paths);
        return paths;
    }

    private static void collectPaths(List<Object> rows, List<String> into) {
        for (Object v : rows) {
            if (v == null) {
                continue;
            }
            String path = String.valueOf(v);
            if (!path.isBlank() && !into.contains(path)) {
                into.add(path);
            }
        }
    }

    /**
     * 만료 자산 1건을 <b>조건부</b> 삭제한다 — 잡이 파일을 먼저 지운 뒤에만 부른다.
     * @design AC-036, AC-037, DFEAT-055
     *
     * <p>★ 후보 조회에서 이미 판정했더라도 <b>모든 실행문에 판별자 셋을 다시 건다</b>. 조회~삭제
     * 사이에 라벨이 새로 저장되면 더는 만료가 아니고, 그 창에서 지우면 방금 한 작업이 비가역으로
     * 사라진다. 무엇보다 <b>출처·소유자를 빠뜨리면 관제 영상을 지운다</b>.
     *
     * @return 영상 행 삭제 수(0 이면 타 노드 선점 또는 조건 해제 — 이 노드는 영상을 지우지 않았다)
     */
    public int deleteExpired(RetentionAxis axis, Long uldSn, LocalDateTime cutoff) {
        String guard = " EXISTS (SELECT 1 FROM ls_data_raw r WHERE" + expiryPredicate(axis)
                + " AND r.raw_sn = :rawSn)";
        for (String sql : childDeleteStatements(guard)) {
            bindRetentionParams(em.createNativeQuery(sql), axis, cutoff)
                    .setParameter("rawSn", uldSn)
                    .executeUpdate();
        }
        Query q = em.createNativeQuery(
                "DELETE FROM ls_data_raw r WHERE" + expiryPredicate(axis) + " AND r.raw_sn = :rawSn");
        return bindRetentionParams(q, axis, cutoff).setParameter("rawSn", uldSn).executeUpdate();
    }

    /**
     * 사용자 삭제 — 만료가 아니라 <b>본인이 지운다</b>. 소유자 일치를 실행문마다 건다.
     *
     * <p>연쇄 순서는 {@link #deleteExpired} 와 <b>같은 표</b>를 쓴다. 다른 것은 조건이 「만료」가
     * 아니라 「소유자 일치」라는 점뿐이며, 순서표가 두 벌이 되면 한쪽만 고쳐져 조용히 고아가 남는다.
     *
     * @return 영상 행 삭제 수(0 이면 이미 지워졌거나 본인 자산이 아니다)
     */
    public int deleteOwned(Long uldSn, String portalUserNo) {
        String guard = " EXISTS (SELECT 1 FROM ls_data_raw r WHERE r.raw_sn = :rawSn"
                + " AND r.src_type = :srcType AND r.portal_user_no = :owner)";
        for (String sql : childDeleteStatements(guard)) {
            em.createNativeQuery(sql)
                    .setParameter("rawSn", uldSn)
                    .setParameter("srcType", PortalUploadLedger.SRC_TYPE)
                    .setParameter("owner", portalUserNo)
                    .executeUpdate();
        }
        return em.createNativeQuery("""
                DELETE FROM ls_data_raw r
                 WHERE r.raw_sn = :rawSn AND r.src_type = :srcType AND r.portal_user_no = :owner
                """)
                .setParameter("rawSn", uldSn)
                .setParameter("srcType", PortalUploadLedger.SRC_TYPE)
                .setParameter("owner", portalUserNo)
                .executeUpdate();
    }

    /**
     * 연쇄 삭제 <b>순서표</b> — 삭제 경로 둘이 이 하나를 공유한다.
     *
     * <p>여기 있는 표들은 부모 외래키가 없거나 연쇄가 아니라 <b>스스로 지우지 않으면 조용히 고아가
     * 된다</b>(오류로 드러나지 않아 더 위험하다). 라벨 속성값이 맨 앞인 이유는 그 외래키가 연쇄가
     * 아니어서 라벨을 먼저 지우면 <b>외래키 위반으로 실패</b>하기 때문이다.
     */
    private static List<String> childDeleteStatements(String guard) {
        return List.of(
                // ① 라벨 속성값 — 라벨보다 먼저(연쇄 아닌 외래키).
                "DELETE FROM ls_data_lbl_attr_val v"
                        + " WHERE v.lbl_sn IN (SELECT l.lbl_sn FROM ls_data_lbl l"
                        + " JOIN ls_data_src s ON s.src_sn = l.src_sn WHERE s.raw_sn = :rawSn)"
                        + " AND" + guard,
                // ② 라벨 — 프레임을 참조하지만 외래키가 없어 영상 삭제로 정리되지 않는다.
                "DELETE FROM ls_data_lbl l USING ls_data_src s"
                        + " WHERE s.src_sn = l.src_sn AND s.raw_sn = :rawSn AND" + guard,
                // ③ 라벨 이력 — 같은 이유로 외래키가 없다.
                "DELETE FROM ls_data_lbl_hstry h USING ls_data_src s"
                        + " WHERE s.src_sn = h.src_sn AND s.raw_sn = :rawSn AND" + guard,
                // ④ 증강 라벨 매핑 — 증강 행보다 먼저(증강 식별자로 잇는다).
                "DELETE FROM ls_data_aug_lbl_map m"
                        + " WHERE m.data_aug_sn IN (SELECT a.data_aug_sn FROM ls_data_aug a"
                        + " JOIN ls_data_src s ON s.src_sn = a.src_sn WHERE s.raw_sn = :rawSn)"
                        + " AND" + guard,
                // ⑤ 증강 — 이 표에는 외래키가 아예 없다.
                "DELETE FROM ls_data_aug a USING ls_data_src s"
                        + " WHERE s.src_sn = a.src_sn AND s.raw_sn = :rawSn AND" + guard);
    }

    /** 자산 행이 아직 남아 있는가 — 0행 삭제의 원인(타 노드 선점 / 조건 해제) 구분용. */
    public boolean exists(Long uldSn) {
        Number n = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM ls_data_raw r WHERE r.raw_sn = :rawSn AND r.src_type = :srcType")
                .setParameter("rawSn", uldSn)
                .setParameter("srcType", PortalUploadLedger.SRC_TYPE)
                .getSingleResult();
        return n.longValue() > 0;
    }

    // ==================================================================
    // 내부
    // ==================================================================

    /**
     * 만료 판정 술어 — <b>출처 판별자 + 소유자 보유 + 상태 + 보존기간 경과</b>를 한 덩어리로 묶는다.
     * 후보 조회와 삭제 실행문이 이 하나를 공유하므로 두 곳의 기준이 갈릴 수 없다.
     */
    private static String expiryPredicate(RetentionAxis axis) {
        String base = " r.src_type = :srcType"
                + " AND r.portal_user_no IS NOT NULL"
                + " AND " + STATUS_EXPR + " = :status";
        return switch (axis) {
            // 기준점 = 등록일. ★ READY 축의 「커트라인 이후 저장된 라벨이 없다」 조건을 <넣지 않는다>
            //   — 마킹 대기 자산에는 프레임이 없어 라벨이 존재할 수 없으므로 그 조건은 항상 참이고,
            //   달아 두면 이 축이 READY 축과 같아 보여 기산점 차이가 지워진다(AC-1070).
            //
            // ★★ 이 축만 판별자가 <두 겹>이다 — 다른 축과 비대칭이니 base 를 절대 우회하지 말 것.
            //   STATUS_EXPR 의 COALESCE 기본값이 하필 'UPLOADED' 라, 상태 행이 아예 없는 관제 영상은
            //   이 축의 상태 조건을 <그대로 통과>한다. 다른 축(READY·FAILED)에서는 그 기본값이
            //   리터럴과 달라 상태 조건이 세 번째 판별자로 작동하지만 여기서는 작동하지 않는다.
            //   그래서 관제 영상을 막는 것은 src_type 과 portal_user_no <둘뿐>이고, 둘 중 하나만
            //   빠져도 곧바로 관제 영상이 지워진다(비가역). 회귀 가드는 PortalRetentionSweepIT 의
            //   「출처 판별자만…」·「소유자 보유만…」 두 시험이며 판별자마다 하나씩 짝지어 둔다.
            case UPLOADED -> base + " AND r.reg_dt < :cutoff";
            // 기준점 = 등록일과 라벨 마지막 저장일 중 늦은 쪽. 「등록일이 커트라인 이전」과
            // 「커트라인 이후에 저장된 라벨이 없다」의 곱으로 표현한다 — 작업 중이면 라벨 저장이
            // 기준점을 계속 밀어내므로 자동으로 후보에서 빠진다.
            case READY -> base + " AND r.reg_dt < :cutoff"
                    + " AND NOT EXISTS (SELECT 1 FROM ls_data_lbl l"
                    + " JOIN ls_data_src s2 ON s2.src_sn = l.src_sn"
                    + " WHERE s2.raw_sn = r.raw_sn AND l.reg_dt >= :cutoff)";
            // 기준점 = 실패 전이 시각. READY 축과 <다른 설정 키>이며 독립 판정된다.
            case FAILED -> base + " AND " + STATUS_CHANGED_EXPR + " < :cutoff";
        };
    }

    private Query bindRetentionParams(Query q, RetentionAxis axis, LocalDateTime cutoff) {
        return q.setParameter("statusKey", PortalUploadLedger.KEY_UPLOAD_STATUS)
                .setParameter("srcType", PortalUploadLedger.SRC_TYPE)
                .setParameter("status", axis.status())
                .setParameter("cutoff", cutoff);
    }

    private String orderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            // 기존 동작 보존 — 지정이 없으면 최신순.
            return " ORDER BY r.reg_dt DESC, r.raw_sn DESC";
        }
        StringBuilder sb = new StringBuilder();
        for (Sort.Order order : sort) {
            String expr = SORT_EXPRESSIONS.get(order.getProperty());
            if (expr == null) {
                continue;
            }
            sb.append(sb.length() == 0 ? " ORDER BY " : ", ")
                    .append(expr)
                    .append(order.isAscending() ? " ASC" : " DESC");
        }
        if (sb.length() == 0) {
            return " ORDER BY r.reg_dt DESC, r.raw_sn DESC";
        }
        // 동값 페이지 경계가 흔들리지 않게 PK 를 마지막 키로 붙인다.
        return sb.append(", r.raw_sn DESC").toString();
    }

    private void bindMetaKeys(Query q) {
        q.setParameter("statusKey", PortalUploadLedger.KEY_UPLOAD_STATUS);
        q.setParameter("failKey", PortalUploadLedger.KEY_FAIL_REASON);
        q.setParameter("nameKey", PortalUploadLedger.KEY_ORIGINAL_FILENAME);
        q.setParameter("mimeKey", PortalUploadLedger.KEY_MIME);
        q.setParameter("fpsKey", PortalUploadLedger.KEY_FPS);
        q.setParameter("sizeKey", PortalUploadLedger.KEY_FILESIZE);
    }

    /** SELECT 컬럼 순서 ↔ 읽기 모델 매핑. {@link #SELECT_ASSET} 을 고치면 여기도 함께 고친다. */
    private static PortalUploadAsset toAsset(Object[] c) {
        String mime = (String) c[10];
        Double fileSize = parseNumeric((String) c[12]);
        return new PortalUploadAsset(
                toLong(c[0]),
                (String) c[1],
                PortalUploadLedger.assetTypeOf(mime),
                (String) c[9],
                (String) c[2],
                fileSize == null ? null : fileSize.longValue(),
                mime,
                PortalUploadLedger.statusOrUploaded((String) c[6]),
                durationSeconds(c[3], c[4]),
                parseNumeric((String) c[11]),
                toInt(c[13]),
                (String) c[8],
                toDateTime(c[5]),
                toDateTime(c[7]));
    }

    /** 초는 정수 컬럼이라 소수가 잘린다 — 밀리초 칸이 있으면 그쪽이 정본이다. */
    private static Double durationSeconds(Object secObj, Object msObj) {
        Long ms = toLong(msObj);
        if (ms != null && ms > 0) {
            return ms / 1000.0d;
        }
        Integer sec = toInt(secObj);
        return sec == null ? null : sec.doubleValue();
    }

    /** 메타는 문자열이라 숫자가 아닐 수 있다 — 파싱 실패는 값 없음으로 읽는다(조회를 깨지 않는다). */
    private static Double parseNumeric(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static Integer toInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static LocalDateTime toDateTime(Object v) {
        if (v instanceof LocalDateTime dt) {
            return dt;
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    /**
     * 보존기간 축 — 기준점이 서로 다르며 <b>독립 판정</b>된다. @design AC-1070, AC-037
     *
     * <p><b>{@code PROCESSING} 만 여기에 없다</b> — 프레임 추출 러너와 경쟁하면 파일과 원장이
     * 어긋나기 때문이며, 그 상태는 <b>방치 판정 → 실패 전이</b>가 따로 회수한다.
     *
     * <p>★ <b>마킹 대기({@code UPLOADED})는 2026-09-05 확정으로 후보가 됐다</b>(AC-1070). 그전에는
     * 그 상태가 후보 상태 어느 쪽으로도 스스로 전이하지 않아 <b>자동 삭제 경로가 아예 없었고</b>,
     * 사람이 마킹하지 않으면 파일째 영구히 남았다. ⚠ 이것을 방치 판정과 혼동하지 말 것 — 2026-09-02
     * 에 닫은 것은 「분 단위 방치 타이머가 마킹 대기를 <b>실패로 마감</b>하던 것」이고 이것은
     * 「일 단위 보존기간으로 <b>정상 만료</b>시키는 것」이다.
     *
     * <p>설정 키는 마킹 대기·준비 완료가 <b>같고</b>({@code portal.upload.retention-days}) 처리 실패만
     * 다르다. 같은 설정을 쓰는 두 축을 <b>기산점</b>이 가른다.
     */
    public enum RetentionAxis {
        /**
         * 마킹 대기 자산 — 기준점 = 등록일. 프레임이 없어 라벨이 기준점을 밀어낼 수 없다.
         *
         * <p>⚠ <b>이 축만 관제 영상 차단이 두 겹이다</b>({@code src_type}·{@code portal_user_no}).
         * 상태 축의 「부재 = 업로드됨」 기본값이 이 축의 리터럴과 같아 상태 조건이 관제 행을 걸러
         * 주지 못하기 때문이다 — 상세는 {@code expiryPredicate} 의 {@code case UPLOADED} 주석.
         */
        UPLOADED(PortalUploadLedger.STATUS_UPLOADED),
        /** 정상 처리 자산 — 기준점 = 등록일·라벨 최종 저장일 중 늦은 쪽. */
        READY(PortalUploadLedger.STATUS_READY),
        /** 처리 실패 자산 — 기준점 = 실패 전이 시각. */
        FAILED(PortalUploadLedger.STATUS_FAILED);

        private final String status;

        RetentionAxis(String status) {
            this.status = status;
        }

        public String status() {
            return status;
        }
    }
}

package kr.co.cudo.authoring.portal.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import kr.co.cudo.authoring.portal.dto.PortalWorkAssetSource;
import kr.co.cudo.authoring.portal.service.PortalMetaKeyPolicy;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「내 작업」 목록의 <b>합집합 한 질의</b>. @design API-225, DFEAT-055, SCREEN-028, UC-024
 *
 * <h2>★★ 왜 한 질의여야 하는가 — 두 축을 메모리에서 합치면 페이징이 깨진다</h2>
 * <p>이 목록은 성질이 다른 두 축을 한 목록으로 정렬해 내린다. 축마다 {@code N} 쪽씩 받아 애플리케이션에서
 * 합치면 <b>2쪽의 첫 행이 1쪽의 마지막 행보다 최신</b>일 수 있어 정렬이 페이지 경계에서 무너지고,
 * 총 건수도 두 축의 합과 맞지 않는다. 오류가 나지 않아 한 쪽만 보는 시험은 전부 통과한다.
 * ⇒ 합집합을 <b>한 질의로 만들어 거기서</b> 정렬·페이징하고, 개수도 <b>같은 합집합</b>에서 센다.
 *
 * <h2>모집단은 두 축이고 <b>싣는 기준이 서로 다르다</b></h2>
 * <table>
 *   <caption>축별 등재 기준</caption>
 *   <tr><th>축</th><th>기준</th></tr>
 *   <tr><td>{@link PortalWorkAssetSource#PORTAL_UPLOAD}</td>
 *       <td>본인 자산이므로 <b>저작 여부와 무관하게 전부</b> — 저작물 0건도, 프레임 0건(마킹 전)도 싣는다</td></tr>
 *   <tr><td>{@link PortalWorkAssetSource#DATAMART}</td>
 *       <td>세 저작물(저장 라벨 · 메타 오버레이 · 이벤트 어노테이션 오버레이) 중 <b>어느 하나라도</b>
 *           보유한 영상만</td></tr>
 * </table>
 * <p>★ 데이터마트 축을 <b>저장 라벨만으로 산정하지 않는다</b> — 라벨 없이 메타만 고친 영상은 목록에
 * 뜨지 않는데 보존 규칙의 삭제 대상은 되어, 사용자가 자기 작업물이 사라지는 것을 <b>볼 수조차 없다</b>.
 * <p>★ 저작물이 하나도 없는 데이터마트 영상은 <b>행이 되지 않는다</b> — 그것을 실으면 저작도구가
 * 포털 소유인 데이터마트 카탈로그를 대신 그리는 것이 된다.
 *
 * <h2>★★ 「저작물」의 뜻은 두 축이 같다 — 저장되는 <b>자리만</b> 다르다</h2>
 * <p>저작 편집은 <b>저장 라벨 · 포털 메타 편집 · 이벤트 어노테이션</b> 셋이며, 데이터마트축은
 * 오버레이에 업로드축은 <b>자기 자산 원장</b>에 쌓인다(가려야 할 남의 원본이 없다). 그래서
 * {@code last_saved_at} 을 업로드축에서 <b>저장 라벨만</b> 세면, 라벨 없이 촬영환경·프레임 설명·
 * 개인정보 판정만 고친 자산이 「저작 이력 없음」으로 읽혀 <b>내려받기가 통째로 막힌다</b> —
 * 데이터마트축에서 이미 고친 결함의 <b>거울상</b>이다.
 * <p>★ 반대 방향도 똑같이 위험하다 — 같은 원장에 <b>사람이 남기지 않은 것</b>(자동으로 읽어 넣은
 * 기술메타 · 업로드 처리 상태 · 실패 사유)이 함께 앉아 있어, 전부 세면 <b>업로드 직후 아무것도 하지
 * 않은 자산이 「저장한 행」으로 뒤집힌다</b>. 열쇠 갈림의 소유자는 {@link PortalMetaKeyPolicy} 이며
 * 여기서 접두를 복제하지 않는다({@link #findAuthoredMetaKeys}).
 * <p>★ 영상 축 컬럼 편집은 <b>자기 시각을 갖지 않아</b> 원장 행의 변경 시각을 빌려 쓰는데, 그 시각은
 * 자동 갱신(영상 길이 확정 등)과 <b>공유</b>된다. 그래서 시각만 보지 않고 <b>그 칸에 값이 실제로 들어
 * 있을 때만</b> 센다.
 * <p>⚠⚠ <b>그 칸은 촬영환경 셋뿐이다</b>({@code 날씨·주야·계절}). 영상 축 개인정보 판정 셋은
 * <b>적재 시점에 기본값이 실제 값으로 채워지므로</b>(익명 Y / 가명 N / 개인정보 N) 「값이 있다」가
 * 「사람이 고쳤다」를 뜻하지 않는다 — 함께 세면 <b>업로드 직후 자산이 전부 「저장한 행」으로
 * 뒤집힌다</b>(실측으로 확인했고, 그 픽스처가 회귀 시험으로 서 있다). 기본값과 값을 비교해 판정하는
 * 것도 안 된다 — 사용자가 같은 값을 명시적으로 고른 경우와 구분되지 않는다.
 * <p>⚠ 그래서 <b>영상 축 개인정보 판정만</b> 고친 자산은 이 값에 잡히지 않는다 — 인지·수용한 공백이다.
 * <b>프레임 축</b> 개인정보 판정은 반대로 잡힌다(프레임 행의 변경 시각은 적재 시점에 비어 있다).
 *
 * <h2>★★★ 이 값은 <b>보존기간 기산 입력이 아니다</b></h2>
 * <p>여기서 넓힌 것은 <b>표시·정렬·내려받기 판정</b> 축뿐이다. 업로드 채널의 보존기간 기산은 다른
 * 기능이 소유하고 <b>「데이터마트는 최초, 업로드는 늦은 쪽 — 통일하지 말 것」</b>이 확정돼 있다.
 * 두 값이 <b>다를 수 있는 것이 정상</b>이며 합치면 확정되지 않은 사양으로 사용자 데이터를 비가역
 * 삭제하게 된다(DFEAT-055).
 *
 * <h2>★ 두 축은 겹치지 않는다 — 겹치면 같은 영상이 두 행이 된다</h2>
 * <p>오버레이 세 표는 데이터마트 자산에만 쌓이고 본인 업로드 자산의 저작은 그 자산의 원장에 그대로
 * 쌓인다({@code PortalWorkTargetResolver}). 그럼에도 데이터마트 축에서 포털 업로드 자산을 <b>명시적으로
 * 배제</b>하는 것은, 그 불변식이 깨졌을 때 목록이 조용히 중복 행을 내는 대신 <b>배제로 막기</b> 위해서다.
 *
 * <h2>정렬 — 표시하는 값과 정렬하는 값이 다르다(의도)</h2>
 * <p>{@code lastSavedAt} 은 <b>실제 저작 편집이 있었을 때만</b> 값이 있다(아직 아무것도 저장하지 않은
 * 업로드 행은 {@code null}). 대체값 없이 그 값으로 정렬하면 <b>그 행이 실제 시간축과 무관한 자리로
 * 튄다</b> — PostgreSQL 은 내림차순에서 {@code NULLS FIRST} 가 기본이라 <b>맨 앞</b>으로 오고,
 * {@code NULLS LAST} 를 명시하면 반대로 <b>맨 뒤</b>로 간다. 어느 쪽이든 「그 행이 놓여야 할 자리」가
 * 아니라서, 목록이 길어지면 사용자가 자기 자산을 시간 순서로 찾지 못한다.
 * <p>⚠ <b>{@code NULLS LAST} 를 명시하는 것은 해결이 아니다</b> — 자리를 앞에서 뒤로 옮길 뿐이고,
 * 뒤로 보내면 방금 올린 자산이 마지막 페이지로 가라앉아 더 나쁘다. 필요한 것은 <b>대체값</b>이다.
 * 그래서 정렬 키는 {@code COALESCE(마지막 저장, 등록일)} 이고, 표시값은 <b>거짓말하지 않도록</b>
 * {@code null} 그대로 내린다.
 * <p>동값에서 페이지 경계가 흔들리지 않게 <b>영상 식별자</b>를 마지막 정렬 키로 붙인다.
 *
 * <h2>개수 질의는 목록 질의와 <b>같은 본문</b>을 쓴다</h2>
 * <p>둘을 따로 쓰면 술어가 갈려 「목록에는 있는데 개수에는 없다」가 조용히 생긴다(이 저장소의 실사고).
 * 그래서 본문 정의({@link #WORK_ROWS})를 한 벌로 두고 바깥 SELECT 만 바꾼다 — 개수 질의가 쓰지 않는
 * 상관 서브쿼리까지 함께 도는 비용은 <b>인지·수용</b>한다(대상이 한 사용자의 자산으로 한정된다).
 */
@Repository
public class PortalUserWorkListRepository {

    /**
     * 데이터마트 축 후보 — 세 저작물을 통틀어 <b>마지막 저장 시각</b>. @design DFEAT-055
     *
     * <p>여기서 <b>최초</b> 저장 시각(만료 기산점)을 함께 구하지 않는다 — 그 판정은
     * {@code PortalUserWorkRepository#findEarliestAuthoredAtByVideo} 가 이미 소유하고 있고, 여기서
     * 다시 유도하면 <b>같은 축의 네 번째 사본</b>이 되어 한쪽만 바뀌는 순간 화면이 고지한 만료일과
     * 실제 삭제일이 갈린다.
     *
     * <p>마지막 저장은 {@code MDFCN_DT} 로 읽는다 — 메타·어노테이션 오버레이는 같은 키를 <b>덮어쓰므로</b>
     * 등록일만 보면 두 번째 저장 이후가 보이지 않는다.
     */
    private static final String DATAMART_WORK = """
            dm AS (
                SELECT t.src_raw_sn AS raw_sn, max(t.mdfcn_dt) AS last_saved_at
                  FROM (
                        SELECT l.src_raw_sn, l.mdfcn_dt FROM ls_portal_user_label l
                         WHERE l.portal_user_no = :owner
                        UNION ALL
                        SELECT m.src_raw_sn, m.mdfcn_dt FROM ls_portal_user_meta m
                         WHERE m.portal_user_no = :owner
                        UNION ALL
                        SELECT a.src_raw_sn, a.mdfcn_dt FROM ls_portal_user_evnt_anno a
                         WHERE a.portal_user_no = :owner
                       ) t
                 GROUP BY t.src_raw_sn
            )
            """;

    /** 축 이름 SQL 리터럴 — <b>열거에서 유도</b>한다. 손으로 적으면 열거를 고칠 때 한쪽만 바뀐다. */
    private static final String UPLOAD_AXIS = "'" + PortalWorkAssetSource.PORTAL_UPLOAD.name() + "'";
    private static final String DATAMART_AXIS = "'" + PortalWorkAssetSource.DATAMART.name() + "'";

    /**
     * 두 축의 합집합 본문 — 목록 질의와 개수 질의가 <b>이것 하나</b>를 공유한다.
     *
     * <p>업로드 축의 소유 판정은 <b>출처 판별자와 소유자를 각각</b> 건다. 「소유자가 비어 있으면 통과」
     * 같은 완화를 두면 관제 영상이 포털 채널로 샌다.
     */
    private static final String WORK_ROWS = "work_rows AS (\n"
            + """
                SELECT r.raw_sn                                                  AS raw_sn,
            """
            + "           " + UPLOAD_AXIS + " AS asset_source,\n"
            + """
                       GREATEST(
                         (SELECT max(l.reg_dt) FROM ls_data_lbl l
                            JOIN ls_data_src s ON s.src_sn = l.src_sn
                           WHERE s.raw_sn = r.raw_sn AND l.reg_user_no = :owner),
                         (SELECT max(m.mdfcn_dt) FROM ls_data_meta m
                           WHERE m.raw_sn = r.raw_sn AND m.meta_key IN (:authoredKeys)),
                         (SELECT max(ea.mdfcn_dt) FROM ls_evnt_anno ea
                           WHERE ea.raw_sn = r.raw_sn),
                         (SELECT max(s3.upd_dt) FROM ls_data_src s3
                           WHERE s3.raw_sn = r.raw_sn),
                         (CASE WHEN r.wthr_nm IS NOT NULL OR r.day_ngt_cd IS NOT NULL
                                 OR r.sesn_cd IS NOT NULL
                               THEN r.mdfcn_dt END))                              AS last_saved_at,
                       r.reg_dt                                                  AS activity_at,
                       (SELECT count(*) FROM ls_data_lbl l
                          JOIN ls_data_src s ON s.src_sn = l.src_sn
                         WHERE s.raw_sn = r.raw_sn AND l.reg_user_no = :owner)    AS label_cnt,
                       r.vms_clip_id                                             AS video_name,
                       COALESCE(
                         (SELECT l.src_sn FROM ls_data_lbl l
                            JOIN ls_data_src s ON s.src_sn = l.src_sn
                           WHERE s.raw_sn = r.raw_sn AND l.reg_user_no = :owner
                           ORDER BY l.reg_dt DESC, l.lbl_sn DESC LIMIT 1),
                         (SELECT s.src_sn FROM ls_data_src s
                           WHERE s.raw_sn = r.raw_sn
                           ORDER BY s.frm_no ASC, s.src_sn ASC LIMIT 1))          AS entry_src_sn
                  FROM ls_data_raw r
                 WHERE r.src_type = :srcType
                   AND r.portal_user_no IS NOT NULL
                   AND r.portal_user_no = :owner
                UNION ALL
                SELECT r.raw_sn,
            """
            + "           " + DATAMART_AXIS + ",\n"
            + """
                       dm.last_saved_at,
                       dm.last_saved_at,
                       (SELECT count(*) FROM ls_portal_user_label ul
                         WHERE ul.portal_user_no = :owner AND ul.src_raw_sn = r.raw_sn),
                       r.vms_clip_id,
                       COALESCE(
                         (SELECT ul.src_data_src_sn FROM ls_portal_user_label ul
                           WHERE ul.portal_user_no = :owner AND ul.src_raw_sn = r.raw_sn
                           ORDER BY ul.mdfcn_dt DESC, ul.user_lbl_sn DESC LIMIT 1),
                         (SELECT s.src_sn FROM ls_data_src s
                           WHERE s.raw_sn = r.raw_sn
                           ORDER BY s.frm_no ASC, s.src_sn ASC LIMIT 1))
                  FROM dm
                  JOIN ls_data_raw r ON r.raw_sn = dm.raw_sn
                 WHERE NOT (r.src_type = :srcType AND r.portal_user_no IS NOT NULL)
            )
            """;

    /** 합집합 정의(개수·목록 공용). */
    private static final String WITH_CLAUSE = "WITH " + DATAMART_WORK + ", " + WORK_ROWS;

    private static final String SELECT_PAGE = WITH_CLAUSE + """
            SELECT raw_sn, asset_source, last_saved_at, label_cnt, video_name, entry_src_sn
              FROM work_rows
            """;

    private static final String SELECT_COUNT = WITH_CLAUSE + " SELECT count(*) FROM work_rows";

    /**
     * 정렬 allowlist — <b>논리 키 → SQL 식</b>. 컨트롤러가 미등록 키를 이미 400 으로 거르고, 여기 표에
     * 없는 키는 조용히 무시한다(미지의 문자열이 SQL 로 흘러갈 통로를 남기지 않는다 — CWE-89).
     */
    private static final Map<String, String> SORT_EXPRESSIONS = Map.of(
            "lastSavedAt", "COALESCE(last_saved_at, activity_at)",
            "rawSn", "raw_sn");

    /** 기본 정렬 — 마지막 저장 시각 내림차순(API-225). 아직 저장이 없는 업로드 행은 등록일로 대신한다. */
    private static final String DEFAULT_ORDER = " ORDER BY COALESCE(last_saved_at, activity_at) DESC, raw_sn DESC";

    /**
     * 저작 열쇠가 하나도 없을 때 바인딩하는 <b>어떤 행에도 걸리지 않는 값</b>.
     *
     * <p>목록 질의와 개수 질의가 <b>같은 문자열</b>이어야 술어가 갈리지 않으므로(이 클래스의 존재
     * 이유 중 하나), 빈 목록일 때 SQL 을 다시 조립하는 대신 값으로 처리한다. 어느 적재 통로도 이
     * 이름을 쓰지 않는다.
     */
    private static final String NO_AUTHORED_META_KEY = "__no_authored_meta_key__";

    /**
     * 그 사용자의 포털 자산에 실제로 앉아 있는 메타 열쇠 — <b>저작 편집인 것만</b> 골라 낸다.
     * @design API-225
     *
     * <p>★ <b>전 열쇠를 세면 정반대로 깨진다</b> — 같은 원장에 우리가 자동으로 읽어 넣은 기술메타
     * ({@code video.*})와 업로드 처리 상태·실패 사유({@code portal.*})가 함께 앉아 있어, 그것까지
     * 세면 <b>업로드 직후 아무것도 하지 않은 자산이 「저장한 행」으로 뒤집힌다</b>. 그러면
     * 「업로드 자산은 저작물이 없어도 실린다」가 무의미해지고 내려받기 판정도 함께 틀어진다.
     *
     * <p>★ 그 갈림의 <b>소유자는 포털 메타 창구</b>({@link PortalMetaKeyPolicy})다. 여기서 접두를
     * 다시 적으면 두 번째 진실원이 되어, 소유자가 분류를 늘리는 날 이 목록만 조용히 뒤처진다.
     * 그래서 <b>실재하는 열쇠를 먼저 뽑아 그 판정기에 물어보고</b> 통과한 것만 질의에 바인딩한다 —
     * 판정 자체를 SQL 로 옮기지 않는다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<String> findAuthoredMetaKeys(String portalUserNo) {
        Query q = em.createNativeQuery("""
                SELECT DISTINCT m.meta_key
                  FROM ls_data_meta m
                  JOIN ls_data_raw r ON r.raw_sn = m.raw_sn
                 WHERE r.src_type = :srcType
                   AND r.portal_user_no IS NOT NULL
                   AND r.portal_user_no = :owner
                """);
        q.setParameter("srcType", PortalUploadLedger.SRC_TYPE);
        q.setParameter("owner", portalUserNo);
        List<String> keys = new ArrayList<>();
        for (Object row : q.getResultList()) {
            String key = (String) row;
            if (key != null && PortalMetaKeyPolicy.isEditable(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    @PersistenceContext(unitName = "control")
    private EntityManager em;

    /**
     * 「내 작업」 한 페이지. <b>본인 데이터만</b> — 토큰 주체가 격리 키다.
     *
     * @param portalUserNo 소유자 식별자. 비면 빈 페이지(값을 지어내지 않는다)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Page<UserWorkRow> findPage(String portalUserNo, Pageable pageable) {
        if (portalUserNo == null || portalUserNo.isBlank()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        // 목록과 개수가 <같은 열쇠 집합>을 봐야 술어가 갈리지 않는다 — 한 번 뽑아 둘 다에 넘긴다.
        List<String> authoredKeys = findAuthoredMetaKeys(portalUserNo);

        Query q = bind(em.createNativeQuery(SELECT_PAGE + orderBy(pageable.getSort())),
                portalUserNo, authoredKeys);
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        List<UserWorkRow> content = new ArrayList<>();
        for (Object row : q.getResultList()) {
            content.add(toRow((Object[]) row));
        }

        long total = ((Number) bind(em.createNativeQuery(SELECT_COUNT), portalUserNo, authoredKeys)
                .getSingleResult()).longValue();
        return new PageImpl<>(content, pageable, total);
    }

    private Query bind(Query q, String portalUserNo, List<String> authoredKeys) {
        q.setParameter("owner", portalUserNo);
        q.setParameter("srcType", PortalUploadLedger.SRC_TYPE);
        q.setParameter("authoredKeys",
                authoredKeys.isEmpty() ? List.of(NO_AUTHORED_META_KEY) : authoredKeys);
        return q;
    }

    private String orderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return DEFAULT_ORDER;
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
            return DEFAULT_ORDER;
        }
        // 동값 페이지 경계가 흔들리지 않게 식별자를 마지막 키로 붙인다.
        return sb.append(", raw_sn DESC").toString();
    }

    private static UserWorkRow toRow(Object[] c) {
        return new UserWorkRow(
                toLong(c[0]),
                PortalWorkAssetSource.valueOf((String) c[1]),
                toDateTime(c[2]),
                c[3] instanceof Number n ? n.intValue() : 0,
                (String) c[4],
                toLong(c[5]));
    }

    private static Long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
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
     * 합집합 한 행 — <b>만료 예정일은 여기 없다</b>.
     *
     * <p>만료는 축마다 규칙이 다르고 조회 시점 설정으로 매번 재계산하는 파생값이라,
     * {@code PortalRetentionPolicy} 가 서비스 단계에서 채운다. 리포지토리가 계산하면 그 판정이
     * SQL 로 굳어 설정 변경이 반영되지 않는다.
     *
     * @param rawSn       영상 식별자
     * @param assetSource 자산 출처 — 만료 규칙과 이어서 작업 동선이 이 값으로 갈린다
     * @param lastSavedAt 마지막 저장 시각. 아직 아무 저작물도 없는 업로드 행은 {@code null}
     * @param labelCount  본인 저장 라벨 건수(0 이 정상으로 존재한다)
     * @param videoName   영상 식별 이름. 업로드 축은 서비스가 원본 파일명으로 바꿔 단다
     * @param entrySrcSn  이어서 작업이 여는 프레임. 저장 라벨이 없으면 첫 프레임, 프레임 0건이면 {@code null}
     */
    public record UserWorkRow(Long rawSn,
                              PortalWorkAssetSource assetSource,
                              LocalDateTime lastSavedAt,
                              int labelCount,
                              String videoName,
                              Long entrySrcSn) {

        public boolean isUpload() {
            return assetSource == PortalWorkAssetSource.PORTAL_UPLOAD;
        }
    }
}

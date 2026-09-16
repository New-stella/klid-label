package kr.co.cudo.authoring.portal.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import kr.co.cudo.authoring.portal.service.PortalDatasetLedger;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 포털 데이터셋 영상 목록의 조회 — <b>출처 PORTAL_DATASET 이고 영상 메타의 데이터셋 번호가 같은 영상</b>.
 * @design API-253, ADR-068
 *
 * <h3>대상 판정은 두 조건을 함께 건다</h3>
 * <p>출처 판별자와 데이터셋 번호 메타를 <b>각각</b> 건다. 메타만 보면 같은 키를 가진 다른 출처 영상이
 * 섞일 수 있고, 출처만 보면 다른 데이터셋 영상이 섞인다. 출처 값은 <b>상수를 바인딩</b>한다(판별자 리터럴을
 * 영상 원장 밖에 적지 않는다).
 *
 * <h3>개수와 목록은 같은 본문을 쓴다</h3>
 * <p>술어를 따로 적으면 「목록에는 있는데 개수에는 없다」가 조용히 생긴다.
 *
 * <p>정렬은 영상 이름(원본 파일명, 없으면 클립 식별자) 오름차순이고, 동값에서 페이지 경계가 흔들리지 않게
 * 영상 식별자를 마지막 키로 붙인다.
 */
@Repository
public class PortalDatasetVideoRepository {

    /** 목록·개수 공용 본문. */
    private static final String FROM_WHERE = """
              FROM ls_data_raw r
              JOIN ls_data_meta d
                ON d.raw_sn = r.raw_sn AND d.meta_key = :datasetKey AND d.meta_vl = :datasetId
              LEFT JOIN ls_data_meta fn
                ON fn.raw_sn = r.raw_sn AND fn.meta_key = :filenameKey
             WHERE r.src_type = :srcType
            """;

    private static final String VIDEO_NAME = "COALESCE(NULLIF(btrim(fn.meta_vl), ''), r.vms_clip_id)";

    private static final String SELECT_PAGE = "SELECT r.raw_sn, " + VIDEO_NAME + " AS video_name\n"
            + FROM_WHERE
            + " ORDER BY video_name ASC, r.raw_sn ASC";

    private static final String SELECT_COUNT = "SELECT count(*)\n" + FROM_WHERE;

    @PersistenceContext(unitName = "control")
    private EntityManager em;

    /**
     * 데이터셋 영상 한 페이지 — 영상 식별자와 표시 이름만. 집계는 호출부가 페이지 단위로 일괄 조회한다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Page<VideoRow> findPage(long datasetId, Pageable pageable) {
        Query q = bind(em.createNativeQuery(SELECT_PAGE), datasetId);
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        List<VideoRow> content = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] c = (Object[]) row;
            content.add(new VideoRow(((Number) c[0]).longValue(), (String) c[1]));
        }
        long total = ((Number) bind(em.createNativeQuery(SELECT_COUNT), datasetId).getSingleResult()).longValue();
        return new PageImpl<>(content, pageable, total);
    }

    /**
     * 영상별 <b>원본 라벨</b> 수 — 등록 때 배포본에서 앉힌 라벨이며 사용자가 저장한 라벨이 아니다.
     * 페이지 한 장에 대해 1회.
     *
     * @return 영상 식별자 → 라벨 수. 라벨이 없는 영상은 결과에 없다(호출부가 0 으로 읽는다)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Map<Long, Long> countLabelsByVideos(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Map.of();
        }
        Query q = em.createNativeQuery("""
                SELECT s.raw_sn, count(l.lbl_sn)
                  FROM ls_data_src s
                  JOIN ls_data_lbl l ON l.src_sn = s.src_sn
                 WHERE s.raw_sn IN (:rawSns)
                 GROUP BY s.raw_sn
                """);
        q.setParameter("rawSns", rawSns);
        Map<Long, Long> result = new HashMap<>();
        for (Object row : q.getResultList()) {
            Object[] c = (Object[]) row;
            result.put(((Number) c[0]).longValue(), ((Number) c[1]).longValue());
        }
        return result;
    }

    private static Query bind(Query q, long datasetId) {
        q.setParameter("datasetKey", PortalDatasetLedger.KEY_DATASET_ID);
        q.setParameter("datasetId", Long.toString(datasetId));
        q.setParameter("filenameKey", PortalDatasetLedger.KEY_ORIGINAL_FILENAME);
        q.setParameter("srcType", LsDataRaw.SRC_TYPE_PORTAL_DATASET);
        return q;
    }

    /** 목록 한 행 — 영상 식별자와 표시 이름. */
    public record VideoRow(long rawSn, String videoName) {
    }
}

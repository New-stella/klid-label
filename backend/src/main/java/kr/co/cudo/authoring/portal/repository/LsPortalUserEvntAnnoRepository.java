package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUserEvntAnno;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 포털 사용자 이벤트 어노테이션 오버레이 저장소 — 모든 진입이 <b>소유자 스코프</b>다 (IDOR / CWE-639).
 *
 * @design ERD-018
 * @design API-237
 */
@ControlRepo
public interface LsPortalUserEvntAnnoRepository extends JpaRepository<LsPortalUserEvntAnno, Long> {

    /** 적재 키 (포털사용자, 영상) 로 최대 1건. */
    Optional<LsPortalUserEvntAnno> findByPortalUserNoAndSrcRawSn(String portalUserNo, Long srcRawSn);

    /**
     * 원자 upsert — 영상당 한 벌이라 다시 저장하면 덮어쓴다.
     *
     * <p>{@code jsonb} 캐스팅을 SQL 에서 명시한다. 바인딩은 문자열이고 캐스팅은 DB 가 하므로,
     * 유효하지 않은 JSON 은 여기서 걸린다 — 그러나 <b>그것에 기대지 않고</b> 창구가 먼저 400 으로
     * 거부한다(DB 오류는 500 이 되어 사용자에게 원인이 전달되지 않는다).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO ls_portal_user_evnt_anno
                   (portal_user_no, src_raw_sn, anno_cn, reg_dt, mdfcn_dt)
            VALUES (:portalUserNo, :srcRawSn, CAST(:annoCn AS jsonb), now(), now())
            ON CONFLICT (portal_user_no, src_raw_sn)
            DO UPDATE SET anno_cn = EXCLUDED.anno_cn, mdfcn_dt = now()
            """, nativeQuery = true)
    void upsertAnnotation(@Param("portalUserNo") String portalUserNo,
                          @Param("srcRawSn") Long srcRawSn,
                          @Param("annoCn") String annoCn);

    /**
     * 본인 오버레이 한 벌을 지운다 — <b>「행이 없다」가 「원본을 그대로 쓴다」는 뜻</b>이라
     * 지우는 것이 곧 원본으로 되돌리는 것이다. @design API-237, AC-1068
     *
     * <p>이 창구가 있는 이유는 <b>무변경 저장이 오버레이 행을 만들지 않게</b> 하기 위함이다.
     * 받은 본문이 원본의 현재 본문과 같으면 만들지 않고 이미 있으면 지운다. 빈 본문으로 덮어쓰는
     * 방식은 쓸 수 없다 — 그러면 「사용자가 비웠다」와 「원본을 그대로 쓴다」가 구분되지 않는다.
     *
     * <p>행이 남으면 그 영상이 <b>저작물을 보유한 행</b>이 되어 본인 작업 목록에 등재되고
     * 보존기간 기산점까지 선다 — 열어 보기만 한 영상에 만료 시계가 도는 것은 틀린 동작이다.
     *
     * <p>소유자 스코프를 문장에 강제한다(IDOR / CWE-639).
     *
     * @return 지워진 행 수(0 이면 원래 없었다)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM ls_portal_user_evnt_anno
             WHERE portal_user_no = :portalUserNo
               AND src_raw_sn = :srcRawSn
            """, nativeQuery = true)
    int deleteOverlay(@Param("portalUserNo") String portalUserNo,
                      @Param("srcRawSn") Long srcRawSn);
}

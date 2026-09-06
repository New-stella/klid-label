package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsPortalUserMeta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 포털 사용자 메타 오버레이 저장소 — 모든 진입이 <b>소유자 스코프</b>다 (IDOR / CWE-639).
 *
 * <p>포털 식별자는 추측 가능하므로 소유자 미검증 조회 메서드를 두지 않는다. 조회·저장 어느 쪽도
 * {@code portalUserNo} 없이는 부를 수 없게 시그니처로 강제한다.
 *
 * <h3>★ upsert 가 두 개인 이유 — NULL 은 서로 다른 값이다</h3>
 * <p>적재 키는 (포털사용자, 영상, 프레임, 메타키) 하나이지만, 프레임이 비는 영상 축 행에서는
 * 기본 유일 인덱스가 성립하지 않는다(PostgreSQL 은 NULL 을 서로 다른 값으로 본다). 그래서 스키마가
 * 조건부 유일 인덱스 두 벌로 나뉘고, {@code ON CONFLICT} 의 대상 추론도 그 둘을 각각 가리켜야 한다.
 * <b>하나로 합치면 영상 축 저장이 덮어쓰지 않고 행을 계속 쌓는다</b> — 오류 없이 조용히 중복이 된다.
 *
 * @design ERD-018
 * @design API-235
 */
@ControlRepo
public interface LsPortalUserMetaRepository extends JpaRepository<LsPortalUserMeta, Long> {

    /** 영상 축 오버레이(프레임 참조 없음) — 소유자 스코프. */
    List<LsPortalUserMeta> findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(
            String portalUserNo, Long srcRawSn);

    /** 프레임 축 오버레이 — 소유자 스코프. */
    List<LsPortalUserMeta> findByPortalUserNoAndSrcDataSrcSn(String portalUserNo, Long srcDataSrcSn);

    /**
     * 영상 축 메타 원자 upsert. 프레임 참조를 <b>비워</b> 적재한다 — 채우면 없는 프레임을 가리킨다.
     *
     * <p>대상 추론에 조건부 인덱스({@code uk_lpum_key_video})의 술어를 그대로 적어야 그 인덱스가
     * 선택된다. 값은 전부 파라미터 바인딩이다(문자열 결합 없음 — CWE-89).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO ls_portal_user_meta
                   (portal_user_no, src_raw_sn, src_data_src_sn, meta_key, meta_vl, reg_dt, mdfcn_dt)
            VALUES (:portalUserNo, :srcRawSn, NULL, :metaKey, :metaVl, now(), now())
            ON CONFLICT (portal_user_no, src_raw_sn, meta_key) WHERE src_data_src_sn IS NULL
            DO UPDATE SET meta_vl = EXCLUDED.meta_vl, mdfcn_dt = now()
            """, nativeQuery = true)
    void upsertVideoScoped(@Param("portalUserNo") String portalUserNo,
                           @Param("srcRawSn") Long srcRawSn,
                           @Param("metaKey") String metaKey,
                           @Param("metaVl") String metaVl);

    /** 프레임 축 메타 원자 upsert. 대상 추론은 조건부 인덱스 {@code uk_lpum_key} 다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO ls_portal_user_meta
                   (portal_user_no, src_raw_sn, src_data_src_sn, meta_key, meta_vl, reg_dt, mdfcn_dt)
            VALUES (:portalUserNo, :srcRawSn, :srcDataSrcSn, :metaKey, :metaVl, now(), now())
            ON CONFLICT (portal_user_no, src_raw_sn, src_data_src_sn, meta_key)
                    WHERE src_data_src_sn IS NOT NULL
            DO UPDATE SET meta_vl = EXCLUDED.meta_vl, mdfcn_dt = now()
            """, nativeQuery = true)
    void upsertFrameScoped(@Param("portalUserNo") String portalUserNo,
                           @Param("srcRawSn") Long srcRawSn,
                           @Param("srcDataSrcSn") Long srcDataSrcSn,
                           @Param("metaKey") String metaKey,
                           @Param("metaVl") String metaVl);

    /**
     * 영상 축 오버레이 한 칸을 지운다 — <b>「행이 없다」가 「원본 값을 그대로 쓴다」는 뜻</b>이라
     * 지우는 것이 곧 원본으로 되돌리는 것이다.
     *
     * <p>이 창구가 있는 이유는 <b>자동 계산값의 승격 방어</b>다. 받은 값이 원본의 현재 유효값과
     * 같으면 오버레이를 만들지 않고, 이미 있으면 지운다. 빈 값으로 덮어쓰는 방식은 쓸 수 없다 —
     * 그러면 「사용자가 값을 비웠다」와 「원본을 그대로 쓴다」가 구분되지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM ls_portal_user_meta
             WHERE portal_user_no = :portalUserNo
               AND src_raw_sn = :srcRawSn
               AND src_data_src_sn IS NULL
               AND meta_key = :metaKey
            """, nativeQuery = true)
    int deleteVideoScoped(@Param("portalUserNo") String portalUserNo,
                          @Param("srcRawSn") Long srcRawSn,
                          @Param("metaKey") String metaKey);

    /** 프레임 축 오버레이 한 칸을 지운다 — 근거는 {@link #deleteVideoScoped} 와 같다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM ls_portal_user_meta
             WHERE portal_user_no = :portalUserNo
               AND src_raw_sn = :srcRawSn
               AND src_data_src_sn = :srcDataSrcSn
               AND meta_key = :metaKey
            """, nativeQuery = true)
    int deleteFrameScoped(@Param("portalUserNo") String portalUserNo,
                          @Param("srcRawSn") Long srcRawSn,
                          @Param("srcDataSrcSn") Long srcDataSrcSn,
                          @Param("metaKey") String metaKey);
}

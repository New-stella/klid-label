package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 영상 1건의 <b>관제 인입 평면값</b>(CCTV 명·지자체명·이벤트 분류/카테고리 코드·검증이벤트유형·
 * 원천 개인정보 3필드) 단건 조회 리포지토리.
 *
 * <p>구 조달처였던 관제 공유 마스터({@code MNG_RESOURCE_CCTV}·{@code MNG_EX_LOCAL_GOV})가 제거되면서
 * 신설됐다. 연결 규칙(파생영상 {@code ORGNL_RAW_SN} 1단계 폴백 · 개인정보 3필드 예외 · LATERAL 단건
 * 보장)은 전부 {@link IngestSourceLink} 한 곳에서 온다 — 여기에 복제하지 말 것.
 *
 * <p>조회 전용이라 매핑 엔티티가 없지만 Spring Data 계약상 도메인 타입이 필요하여
 * {@link LsDataRaw} 를 재사용한다({@code DatasetMetaSourceRepository} 와 동일 관례).
 *
 * <p>보안: {@code rawSn} 은 파라미터 바인딩만 사용한다(문자열 결합 없음 — CWE-89).
 */
@ControlRepo
public interface IngestSourceRepository extends JpaRepository<LsDataRaw, Long> {

    /**
     * 영상 1건의 인입 평면값. 영상 행이 없으면 null, 인입 행이 없으면 전 필드 null 인 행을 돌려준다
     * (영상 유무와 인입 유무를 호출부가 구분할 수 있게 <b>행 자체는 만든다</b>).
     *
     * @param rawSn 영상 PK(원본·파생 모두 허용)
     */
    @Query(value = """
            SELECT
              i.CCTV_NM       AS "cctvNm",
              i.LCLGV_NM      AS "lclgvNm",
              i.EVNT_CLSF_CD  AS "evntClsfCd",
              i.EVNT_CTGRY_CD AS "evntCtgryCd",
              i.EVNT_ID       AS "evntId",
              i.VRFC_EVNT_TYPE_CD AS "vrfcEvntTypeCd",
            """
            + IngestSourceLink.SQL_SOURCE_PRIVACY_COLUMNS
            + """
            FROM LS_DATA_RAW r
            """
            + IngestSourceLink.SQL_LATERAL_JOIN
            + """
            WHERE r.RAW_SN = :rawSn
            """, nativeQuery = true)
    IngestSourceRow findSourceMeta(@Param("rawSn") Long rawSn);
}

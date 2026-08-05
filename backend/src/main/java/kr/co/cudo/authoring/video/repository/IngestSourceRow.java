package kr.co.cudo.authoring.video.repository;

/**
 * {@link IngestSourceRepository#findSourceMeta} 결과 행 — 영상 1건의 <b>관제 인입 평면값</b> 프로젝션.
 *
 * <p>getter 이름은 native 쿼리의 큰따옴표 별칭과 정확히 일치한다.
 *
 * <p>{@code src*} 접두가 붙은 개인정보 3필드는 <b>원천(비식별 전) 축</b>이며 파생영상에서는 항상
 * {@code null} 이다(결손이 아니라 정상 — {@link IngestSourceLink} javadoc 참조). 파생의 개인정보
 * 판정은 <b>비식별 축</b>이고 그 값은 {@code LS_DATA_RAW} 자기 행에 있다.
 *
 * <p><b>인터페이스 프로젝션</b>이라 getter 추가는 기존 소비자에 무영향이다(호출하지 않으면 그만).
 * 새 getter 를 추가할 때는 native 쿼리 SELECT 절에 같은 이름의 큰따옴표 별칭을 반드시 함께 넣는다 —
 * 별칭이 없으면 런타임에 매핑 실패로 터진다.
 */
public interface IngestSourceRow {

    /** 관제가 보낸 CCTV 명. 미송신이면 null(호출부가 {@code VMS_CCTV_ID} 로 폴백). */
    String getCctvNm();

    /** 관제가 보낸 지방자치단체명 — 단일 필드다. 시도/시군구로 쪼개지 않는다. */
    String getLclgvNm();

    /**
     * 관제가 보낸 이벤트 <b>분류</b> 코드({@code LS_DATA_INGEST.EVNT_CLSF_CD}). 미송신이면 null.
     *
     * <p>완료 통지의 {@code evnt_cls_cd}(관제 계약 키 — 우리 컬럼명 {@code CLSF} 와 철자가 다르다)와
     * {@code V_COMPLETED_VIDEO.EVNT_CLSF_CD} 의 조달처다. dev 실측 40행 전량 NULL 이므로 null 이
     * <b>정상 경로</b>다 — 상수로 채우지 않는다(D-ISSUE-41).
     */
    String getEvntClsfCd();

    /** 관제가 보낸 이벤트 <b>카테고리</b> 코드({@code LS_DATA_INGEST.EVNT_CTGRY_CD}). 미송신이면 null. */
    String getEvntCtgryCd();

    /** 원천 익명정보 포함여부(Y/N). 파생영상은 null. */
    String getSrcAnonyInclYn();

    /** 원천 가명정보 포함여부(Y/N). 파생영상은 null. */
    String getSrcPsdoInclYn();

    /** 원천 개인정보 포함여부(Y/N). 파생영상은 null. */
    String getSrcPrvcInclYn();
}

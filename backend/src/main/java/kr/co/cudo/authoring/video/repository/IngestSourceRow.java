package kr.co.cudo.authoring.video.repository;

/**
 * {@link IngestSourceRepository#findSourceMeta} 결과 행 — 영상 1건의 <b>관제 인입 평면값</b> 프로젝션.
 *
 * <p>getter 이름은 native 쿼리의 큰따옴표 별칭과 정확히 일치한다.
 *
 * <p>{@code src*} 접두가 붙은 개인정보 3필드는 <b>원천(비식별 전) 축</b>이며 파생영상에서는 항상
 * {@code null} 이다(결손이 아니라 정상 — {@link IngestSourceLink} javadoc 참조). 파생의 개인정보
 * 판정은 <b>비식별 축</b>이고 그 값은 {@code LS_DATA_RAW} 자기 행에 있다.
 */
public interface IngestSourceRow {

    /** 관제가 보낸 CCTV 명. 미송신이면 null(호출부가 {@code VMS_CCTV_ID} 로 폴백). */
    String getCctvNm();

    /** 관제가 보낸 지역(지자체)명 — 단일 필드다. 시도/시군구로 쪼개지 않는다. */
    String getRgnNm();

    /** 원천 익명정보 포함여부(Y/N). 파생영상은 null. */
    String getSrcAnonyInclYn();

    /** 원천 가명정보 포함여부(Y/N). 파생영상은 null. */
    String getSrcPsdoInclYn();

    /** 원천 개인정보 포함여부(Y/N). 파생영상은 null. */
    String getSrcPrvcInclYn();
}

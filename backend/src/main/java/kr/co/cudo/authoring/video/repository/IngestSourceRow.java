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

    /**
     * 관제가 보낸 이벤트 <b>식별자</b>({@code LS_DATA_INGEST.EVNT_ID}, 예 {@code ABA_0001}). 미송신이면 null.
     *
     * <p>학습데이터 export JSON 의 {@code video.event_id} 조달처다(R9-c). 이벤트 <b>유형</b>
     * 코드({@code EVNT_TYPE_CD})와 <b>축이 다른 값</b>이라 서로 대체하지 않는다 — 미송신이면 null 이
     * 정상이며 유형코드로 폴백하지 않는다.
     */
    String getEvntId();

    /**
     * 관제가 보낸 <b>검증이벤트유형</b>({@code LS_DATA_INGEST.VRFC_EVNT_TYPE_CD}, V176). 미송신이면
     * null (@req R5).
     *
     * <p>외부 VLM 검증 API 요청의 {@code event_type} 조달처이며 허용값은
     * {@code LsDataIngest.VRFC_EVNT_TYPES} 프리셋 7종이다(허용목록이 아니라 그 밖의 값도 온다).
     * 이벤트 <b>유형</b>코드({@code EVNT_TYPE_CD},
     * 예 {@code EV01000101})와 <b>축이 다른 값</b>이라 서로 대체하지 않는다 — 미송신이면 null 이
     * 정상이며 유형코드에서 유도하지 않는다(그 유도표가 곧 이 설계가 피하려던 자체 매핑표다).
     *
     * <p><b>파생영상도 부모 인입값을 그대로 물려받는다</b> — 폴백 예외는 개인정보 3필드뿐이다.
     * 이 값은 개인정보 <b>판정</b>이 아니라 분석 대상 지정이라 그 예외의 근거가 성립하지 않는다.
     */
    String getVrfcEvntTypeCd();

    /** 원천 익명정보 포함여부(Y/N). 파생영상은 null. */
    String getSrcAnonyInclYn();

    /** 원천 가명정보 포함여부(Y/N). 파생영상은 null. */
    String getSrcPsdoInclYn();

    /** 원천 개인정보 포함여부(Y/N). 파생영상은 null. */
    String getSrcPrvcInclYn();
}

package kr.co.cudo.authoring.video.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import kr.co.cudo.authoring.video.entity.QLsDataIngest;
import kr.co.cudo.authoring.video.entity.QLsDataRaw;

/**
 * 영상({@code LS_DATA_RAW}) → <b>관제 인입 평면값</b>({@code LS_DATA_INGEST}) 연결 규칙의 단일 진실원.
 *
 * <h3>왜 한 곳에 모으는가</h3>
 * <p>관제 공유 마스터({@code MNG_RESOURCE_CCTV}·{@code MNG_EX_LOCAL_GOV}·{@code MNG_CLIP_MASTER}·
 * {@code MNG_CLIP_EVNT_LST}) 제거 후, CCTV 명·지자체명·파일형식·좌표는 전부 <b>관제가 인입 행에 실어
 * 보낸 평면값</b>에서 온다. 이 연결 규칙(특히 <b>파생영상 폴백</b>과 <b>개인정보 3필드 예외</b>)이
 * 호출부마다 복제되면 한쪽만 갱신돼 조용히 어긋난다 — 이 저장소의 반복 사고 패턴이다.
 *
 * <h3>연결 규칙 — {@code i.RAW_SN = COALESCE(r.ORGNL_RAW_SN, r.RAW_SN)}</h3>
 * <p>증강·해상도 <b>파생영상은 자기 인입 행이 없다</b>(저작도구가 직접 만든 것이라 관제 인입으로 오지
 * 않는다). 그러나 <b>파생 깊이가 1 로 고정</b>돼 있으므로(모든 파생의 부모는 항상 원본 — CLAUDE.md
 * 구속 정책) {@code ORGNL_RAW_SN} <b>1단계 폴백</b>이면 충분하다. 재귀 순회를 만들지 말 것.
 * 원본은 관제 인입은 물론 내부 업로드({@code InternalUploadIngestWriter})도 인입 행을 INSERT 하므로
 * <b>항상</b> 인입 행을 갖는다.
 *
 * <h3>⚠ 개인정보 3필드만 파생에서 폴백을 타지 않는다</h3>
 * <p>{@code ANONY_INCL_YN}/{@code PSDO_INCL_YN}/{@code PRVC_INCL_YN} 은
 * {@link #SQL_SOURCE_PRIVACY_COLUMNS} 가 {@code CASE WHEN r.ORGNL_RAW_SN IS NULL} 으로 <b>원본에만</b>
 * 채운다. 파생의 개인정보 판정은 <b>비식별 축</b>이고 그 값은 생성 시점에
 * {@code LsDataRaw.copyPrivacyMetaFrom(parent)} 로 <b>이미 자기 RAW 행에 계승</b>돼 있다.
 * 부모의 <b>원천</b> 판정은 부모의 <b>비식별 전</b> 영상에 대한 것이라, 비식별본으로 만들어진 파생에는
 * 해당하지 않는다 — <b>파생의 원천 축 null 은 결손이 아니라 정상</b>이며 채우려 하지 말 것.
 *
 * <h3>단건 보장 — LATERAL 로 fan-out 을 원천 차단한다</h3>
 * <p>{@code LS_DATA_INGEST.RAW_SN} 에는 UNIQUE 가 없다({@code VMS_CLIP_ID} 만 UK). 실제로는 클립
 * 1건 = 인입 1행 = 영상 1건이지만, 수기 정정으로 2행이 생기면 평범한 {@code LEFT JOIN} 은 <b>목록의
 * 행을 증식</b>시켜 {@code totalElements} 까지 틀어진다. {@code LEFT JOIN LATERAL ... LIMIT 1} 은
 * 영상 1건당 정확히 1행을 보장하고, {@code ORDER BY RCPTN_SN DESC}(최신 수신) 로 같은 입력에 같은
 * 출력을 낸다(해시 비결정성 배제). {@code LsDataIngestRepository#findLatestByRawSn} 과 동일 관례다.
 *
 * <p>보안: 이 상수들은 <b>고정 SQL 조각</b>이며 사용자 입력이 섞이는 지점이 없다. 호출부는 값 조건을
 * 파라미터 바인딩으로만 붙인다(CWE-89).
 */
public final class IngestSourceLink {

    /**
     * native 쿼리용 인입 조인 조각 — {@code LS_DATA_RAW} 별칭이 <b>{@code r}</b> 이어야 하고,
     * 인입 평면값은 별칭 <b>{@code i}</b> 로 참조한다.
     */
    public static final String SQL_LATERAL_JOIN = """
             LEFT JOIN LATERAL (
                 SELECT src.*
                   FROM LS_DATA_INGEST src
                  WHERE src.RAW_SN = COALESCE(r.ORGNL_RAW_SN, r.RAW_SN)
                  ORDER BY src.RCPTN_SN DESC
                  LIMIT 1
             ) i ON TRUE
            """;

    /**
     * <b>원천</b> 개인정보 3필드 select 조각 — 파생영상에서는 인입값을 채우지 않는다(위 javadoc 참조).
     * 이 판정을 다른 곳에 복제하지 말 것.
     */
    public static final String SQL_SOURCE_PRIVACY_COLUMNS = """
            CASE WHEN r.ORGNL_RAW_SN IS NULL THEN i.ANONY_INCL_YN END AS "srcAnonyInclYn",
            CASE WHEN r.ORGNL_RAW_SN IS NULL THEN i.PSDO_INCL_YN  END AS "srcPsdoInclYn",
            CASE WHEN r.ORGNL_RAW_SN IS NULL THEN i.PRVC_INCL_YN  END AS "srcPrvcInclYn"
            """;

    /**
     * <b>JPQL 문자열</b>용 같은 연결 규칙 — 별칭이 인입 {@code i} · 영상 {@code v} 로 고정된
     * {@code @Query} 안에서 쓴다.
     *
     * <p>Querydsl 은 {@link #matchesSourceOf} 를 쓰지만 {@code @Query} 는 <b>어노테이션 문자열</b>이라
     * 메서드를 호출할 수 없다. 그래서 같은 규칙이 두 표현으로 존재하는데, 최소한 <b>텍스트 복제는
     * 이 상수 하나로 없앤다</b>(구 구현은 같은 술어를 {@code i}/{@code i2} 로 두 번 적어 뒀다).
     * 두 표현이 실제로 동치인지는 {@code IngestSourceLinkContractIT} 가 DB 에서 대조해 고정한다 —
     * 한쪽만 바꾸면 그 테스트가 깨진다.
     *
     * <p>컴파일 타임 상수({@code static final String} + 리터럴)라 어노테이션 값 연결에 쓸 수 있다.
     */
    public static final String JPQL_MATCHES_SOURCE =
            "(i.rawSn = v.orgnlRawSn OR (v.orgnlRawSn IS NULL AND i.rawSn = v.rawSn))";

    /**
     * QueryDSL(JPQL) 용 같은 연결 규칙 — 검색 술어(EXISTS 서브쿼리)에서 사용한다.
     *
     * <p>{@code COALESCE} 를 쓰지 않고 동치 분기로 표현한 이유: Querydsl 의 {@code Coalesce} 는
     * 타입 변환 체이닝이 버전마다 달라 템플릿 문자열로 흘러가기 쉬운데, 이 두 항 분기는 같은 의미를
     * 순수 DSL 로 표현하면서 생성 SQL 도 인덱스 친화적이다.
     *
     * @param i 인입 별칭
     * @param r 영상 별칭
     * @return {@code i.RAW_SN = COALESCE(r.ORGNL_RAW_SN, r.RAW_SN)} 와 동치인 술어
     */
    public static BooleanExpression matchesSourceOf(QLsDataIngest i, QLsDataRaw r) {
        return i.rawSn.eq(r.orgnlRawSn)
                .or(r.orgnlRawSn.isNull().and(i.rawSn.eq(r.rawSn)));
    }

    private IngestSourceLink() {
    }
}

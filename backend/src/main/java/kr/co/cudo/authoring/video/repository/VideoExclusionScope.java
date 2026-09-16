package kr.co.cudo.authoring.video.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.jpa.JPAExpressions;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.QLsDataRaw;

/**
 * <b>영상 제외(가시성) 배제 술어</b> — "이 영상을 저작도구 화면 목록에 보일 것인가" 판정의 단일 원천.
 * [@design ADR-069] [@design ERD-012]
 *
 * <h2>왜 한 곳이 소유하는가</h2>
 * <p>제외는 <b>여러 목록</b>(영상 처리 현황 · 작업 목록 · 검수 목록)에서 동시에 빠져야 하는데, 각
 * 조회 경로가 {@code EXCL_YN} 비교를 스스로 적으면 <b>한 곳만 빠져도 제외한 영상이 그 화면에 그대로
 * 뜬다</b>. 오류로 드러나지 않고 조용히 새는 축이라 술어를 한 클래스가 소유한다. 같은 저장소의
 * {@link InternalWorkScope}(채널 판별)와 {@code LsDataSrcRepository.NOT_DISCARDED}(프레임 폐기)가
 * 같은 이유로 선 자리이며, 이 클래스는 그 골격을 그대로 따른다.
 *
 * <p>구동 테이블이 제각각이라 <b>세 형태</b>를 전부 제공한다 — 한 형태만 두면 그 형태를 못 쓰는
 * 호출부가 비교를 재구현한다. 이 클래스는 {@code video} 도메인에 있지만 <b>작업 배정·검수 도메인이
 * 그대로 가져다 쓰는 공용 자산</b>이다({@link InternalWorkScope} 와 같은 위치·같은 쓰임).
 *
 * <h2>★ 경계 — 붙이면 안 되는 곳</h2>
 * <p>제외는 <b>저작도구 화면 시야만</b>이다. 다음에는 이 술어를 <b>붙이지 않는다</b>: 배치 파이프라인
 * (단계 진행 · 회수 스윕 포함) · 관제 통지 · 데이터마트 조회 뷰 · 학습데이터 산출물 · 관제 조회 창구 ·
 * 통계 대시보드 · 포털 채널. 「일관성」을 이유로 붙이는 순간 관제가 보던 행이 예고 없이 사라져
 * ADR-037(검수 완료·통지 건의 관제 접근 무조건 보장)을 정면으로 깨고, 총량 축인 통계가 사람의 화면
 * 정리에 따라 흔들린다.
 *
 * <h2>값이 비어 있을 수 없다 — 그래도 null 안전하게 적는다</h2>
 * <p>컬럼은 {@code NOT NULL DEFAULT 'N'}(V39)이라 빈 값이 존재할 수 없다. 그럼에도 「제외가 아니다」를
 * <b>{@code 'Y' 가 아니다}</b>로 적는 이유는 {@link InternalWorkScope} 와 같다 — 「{@code 'N'} 이다」로
 * 적으면 예기치 못한 값 하나가 정상 영상을 통째로 사라지게 만든다. <b>감추는 쪽이 아니라 보이는 쪽으로
 * 기울인다</b>(가시성 축이므로 조용한 손실이 더 나쁘다).
 *
 * <p>stateless 유틸 — 인스턴스화 금지.
 */
public final class VideoExclusionScope {

    /** 서브쿼리 별칭 — 호출부의 기본 별칭과 충돌하지 않게 고유 이름을 쓴다. */
    private static final QLsDataRaw SCOPE_RAW = new QLsDataRaw("exclusionScopeRaw");

    private VideoExclusionScope() {
    }

    /**
     * <b>JPQL 조각</b>(별칭 {@code r}) — {@code @Query} 문자열에 그대로 이어 붙이는 형태.
     *
     * <p>모양과 관례는 {@link InternalWorkScope#INTERNAL_JPQL} 을 그대로 따른다 — 컴파일 타임 상수라
     * 애너테이션에 이어 붙일 수 있고, 앞뒤 공백은 <b>의도된 것</b>이다(텍스트 블록이 후행 공백을
     * 제거하므로 조각이 스스로 공백을 보장하지 않으면 이어 붙인 자리에서 토큰이 붙어버린다).
     *
     * <p>★<b>별칭이 {@code r} 인 쿼리에만 쓴다.</b> 영상 목록 쿼리는 영상 원장을 {@code v} 로 부르므로
     * 이 상수를 그대로 쓰면 안 된다 — 그쪽은 {@link #EXCLUSION_TOGGLE_JPQL_V} 를 쓴다. 별칭이 다른
     * 쿼리에 잘못 붙이면 JPQL 파싱이 깨져 즉시 드러난다.
     */
    public static final String NOT_EXCLUDED_JPQL =
            " and (r.exclYn is null or r.exclYn <> '" + LsDataRaw.EXCL_YES + "') ";

    /**
     * <b>영상 목록 전용 두 갈래 필터</b>(별칭 {@code v}, 플래그 {@code :excludedOnlyOn}).
     * [@design API-042]
     *
     * <p>값역은 <b>표시분만</b>({@code 0}) · <b>제외분만</b>({@code 1}) 두 갈래뿐이다 — 섞어 보는 갈래를
     * 두지 않는다(섞이면 어느 것이 제외분인지 행마다 구분해야 한다).
     *
     * <p>★이 상수를 <b>목록 쿼리와 건수 쿼리에 함께</b> 붙인다. 한쪽에만 붙으면 화면의 건수와 그 건수를
     * 눌러 얻는 목록이 어긋난다 — 이 저장소가 같은 이유로 검색어·건너뜀·실패·배정 술어를 전부 상수로
     * 뽑아 둔 그 자리다.
     *
     * <p>플래그 관례({@code :fromFilterOn} 등)를 따라 on/off 정수를 쓴다 — 파라미터가 항상 비교 위치에
     * 등장해야 타입 추론이 확정된다. 모든 값은 파라미터 바인딩이다(CWE-89).
     */
    public static final String EXCLUSION_TOGGLE_JPQL_V =
            "AND ((:excludedOnlyOn = 0\n"
            + "      AND (v.exclYn IS NULL OR v.exclYn <> '" + LsDataRaw.EXCL_YES + "'))\n"
            + "     OR (:excludedOnlyOn = 1 AND v.exclYn = '" + LsDataRaw.EXCL_YES + "'))\n";

    /**
     * 별칭이 {@code r}·{@code v} 가 아닌 <b>런타임 조립</b> 경로용 JPQL 조각.
     *
     * <p>{@code @Query} 애너테이션에는 쓸 수 없다(컴파일 타임 상수가 아니다). 별칭을 문자열로 받지만
     * 호출부가 <b>코드에 적은 리터럴</b>만 넘기는 자리이며 사용자 입력이 닿지 않는다.
     *
     * @param alias 그 쿼리가 영상 원장을 부르는 별칭
     */
    public static String notExcludedJpql(String alias) {
        return " and (" + alias + ".exclYn is null or " + alias + ".exclYn <> '"
                + LsDataRaw.EXCL_YES + "') ";
    }

    /**
     * {@code FROM LS_DATA_RAW} 인 경로(작업 목록)용 — 그 영상이 화면에 보이는 영상인가.
     *
     * @param raw 조회 루트로 쓰인 영상 원장 별칭
     */
    public static BooleanExpression notExcluded(QLsDataRaw raw) {
        return raw.exclYn.isNull().or(raw.exclYn.ne(LsDataRaw.EXCL_YES));
    }

    /**
     * {@code FROM} 이 다른 표인 경로(배정 목록·검수 목록)용 — 그 식별자가 가리키는 영상이 보이는가.
     *
     * <p>영상 행이 없으면(정합 이상) 통과시킨다 — {@link InternalWorkScope#internalByRawSn} 과 같은
     * 판단이다. 이 술어의 책임은 「제외한 영상 배제」이고 영상 부재는 다른 축의 문제라, 여기서 결과를
     * 줄이면 기존 동작이 조용히 바뀐다.
     *
     * <p>조인을 만들지 않고 상관 {@code EXISTS} 한 겹만 쓴다 — 조인을 넣으면 행이 증식해 총건수가
     * 어긋난다.
     *
     * @param rawSnRef 바깥 쿼리의 영상 식별자 경로
     */
    public static BooleanExpression notExcludedByRawSn(NumberPath<Long> rawSnRef) {
        return JPAExpressions.selectOne()
                .from(SCOPE_RAW)
                .where(SCOPE_RAW.rawSn.eq(rawSnRef),
                        SCOPE_RAW.exclYn.eq(LsDataRaw.EXCL_YES))
                .exists()
                .not();
    }
}

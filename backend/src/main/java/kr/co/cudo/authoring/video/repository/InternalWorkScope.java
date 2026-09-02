package kr.co.cudo.authoring.video.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.jpa.JPAExpressions;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.QLsDataRaw;

/**
 * <b>내부 채널 작업 범위</b> — "이 영상이 관제 채널의 작업 대상인가" 판정의 단일 원천.
 *
 * <h2>왜 생겼나 — 흡수가 만든 <b>반대 방향</b>의 문제</h2>
 * <p>포털 업로드 자산이 공용 영상 원장에 앉으면서(ADR-058) 그 원장을 읽는 <b>관제 화면·집계가 포털
 * 행을 집어간다</b>. 막히는 축(포털이 관제 게이트에 걸린다)과 달리 이쪽은 <b>섞여 드는</b> 축이라
 * 오류로 드러나지 않고 조용히 결과가 늘어난다.
 *
 * <p>특히 작업목록의 배제 술어는 <b>파생 축뿐</b>이라, 포털 업로드 원본({@code ORGNL_RAW_SN} 이 비어
 * 있다)은 그 게이트를 <b>무조건 통과</b>한다. 그래서 채널 축을 별도로 세운다.
 *
 * <h2>★ 파생 등재 게이트를 재사용하지 않는다</h2>
 * <p>그 술어는 <b>검수자 결정 축</b>({@code LS_DATA_AUG_RVW})을 요구하는데, 포털에는 검수가 없어
 * 그 승인 행이 영영 생기지 않는다 — 그대로 갖다 쓰면 포털 파생본이 통째로 사라진다. 두 게이트는
 * 묻는 것이 다르므로(「검수를 통과했나」 vs 「어느 채널의 자산인가」) 합치지 않는다.
 *
 * <h2>판정축은 출처 유형 하나다</h2>
 * <p>소유자 보유를 함께 묻지 않는다 — 여기는 <b>가시 범위</b>를 정하는 자리라 한 축이면 충분하고,
 * 축이 늘면 한 곳만 빠뜨렸을 때 무엇이 새는지 추적하기 어려워진다. 반면 <b>비가역 삭제</b>는
 * 판별자 셋을 모두 요구한다 — 그쪽은 틀렸을 때의 대가가 다르다.
 *
 * <p>과거 행은 출처 유형이 비어 있다({@code null}). 그래서 판정은 <b>「포털이 아니다」</b>로 적는다 —
 * 「관제다」로 적으면 값이 빈 정상 영상이 통째로 사라진다.
 *
 * @design ADR-058
 * @design ERD-028
 */
public final class InternalWorkScope {

    /** 서브쿼리 별칭 — 호출부의 기본 별칭과 충돌하지 않게 고유 이름을 쓴다. */
    private static final QLsDataRaw SCOPE_RAW = new QLsDataRaw("channelScopeRaw");

    private InternalWorkScope() {
    }

    /**
     * {@code FROM LS_DATA_RAW} 인 경로(작업목록)용 — 그 영상이 내부 채널 자산인가.
     *
     * @param raw 조회 루트로 쓰인 영상 원장 별칭
     */
    public static BooleanExpression internal(QLsDataRaw raw) {
        return raw.srcType.isNull().or(raw.srcType.ne(LsDataRaw.SRC_TYPE_PORTAL_ULD));
    }

    /**
     * {@code FROM} 이 다른 표인 경로(배정 목록)용 — 그 식별자가 가리키는 영상이 내부 채널 자산인가.
     *
     * <p>영상 행이 없으면(정합 이상) 통과시킨다 — 이 술어의 책임은 「포털 자산 배제」이고, 영상 부재는
     * 다른 축의 문제라 여기서 결과를 줄이면 기존 동작이 조용히 바뀐다(파생 등재 게이트와 같은 판단).
     *
     * <p>조인을 만들지 않고 상관 {@code EXISTS} 한 겹만 쓴다 — 이 리포지토리들이 갖는 「행 증식 원천
     * 차단」 제약 때문이다. 조인을 넣으면 총건수가 어긋난다.
     *
     * @param rawSnRef 바깥 쿼리의 영상 식별자 경로
     */
    public static BooleanExpression internalByRawSn(NumberPath<Long> rawSnRef) {
        return JPAExpressions.selectOne()
                .from(SCOPE_RAW)
                .where(SCOPE_RAW.rawSn.eq(rawSnRef),
                        SCOPE_RAW.srcType.eq(LsDataRaw.SRC_TYPE_PORTAL_ULD))
                .exists()
                .not();
    }
}

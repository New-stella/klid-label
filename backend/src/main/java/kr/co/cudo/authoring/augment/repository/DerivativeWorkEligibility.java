package kr.co.cudo.authoring.augment.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.jpa.JPAExpressions;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.entity.QLsDataAug;
import kr.co.cudo.authoring.augment.entity.QLsDataAugRvw;
import kr.co.cudo.authoring.video.entity.QLsDataRaw;

/**
 * <b>파생영상 등재 게이트</b> — "이 영상이 작업(라벨링/검수) 대상으로 등재됐는가" 판정의 <b>단일 원천</b>.
 *
 * <h2>규칙</h2>
 * <pre>
 *   원본 영상({@code ORGNL_RAW_SN IS NULL})            → 무조건 통과 (게이트와 무관)
 *   파생 영상({@code ORGNL_RAW_SN IS NOT NULL})        → 아래를 모두 만족해야 통과
 *     · 해상도 파생({@code AUG_TYPE_CD} = {@code RESL_} 접두)      → 통과 (검수 대상이 아니다)
 *     · 그 파생을 만든 증강 행의 검수({@code LS_DATA_AUG_RVW})가 {@code ACCEPTED}
 *     · 매핑 행({@code NEW_RAW_SN}) 자체가 없음                    → 통과 (그랜드퍼더링)
 * </pre>
 *
 * <h2>판정 축이 {@code LS_DATA_AUG_RVW.RVW_STTS_CD} 인 이유 (되돌리지 말 것)</h2>
 * <p>구 술어 후보였던 {@code LS_DATA_AUG.AUG_PROC_STTS_CD='ACCEPTED'} 는 <b>폐기됐다</b>. 그 컬럼은
 * <b>생성 결과 축</b>이라 웹훅이 "생성 성공" 만으로 ACCEPTED 를 찍는다 — 그것으로 게이팅하면 생성된
 * 파생이 전부 통과해 게이트가 아무것도 막지 않는다. 사람의 사용/폐기 결정은 검수 행이 단독 소유한다
 * ({@link LsDataAug#applyGenerationResult(String)} javadoc).
 *
 * <h2>해상도 예외를 빼면 해상도 파생이 전멸한다</h2>
 * <p>해상도 파생은 내부 생성물이라 accept/reject 진입 자체가 차단돼 있다
 * ({@code AugmentReviewService.loadOrThrow}) — 즉 <b>검수 행이 영영 생기지 않는다</b>. 예외가 없으면
 * 해상도 파생 전량이 작업목록에서 사라진다.
 *
 * <h2>그랜드퍼더링</h2>
 * <p>{@code NEW_RAW_SN}(V155)은 백필하지 않았으므로 그 이전에 만들어진 파생은 매핑 행이 없다. 매핑이
 * 없으면 통과시킨다 — 그러지 않으면 기존 파생이 통째로 사라져 <b>고아 배정</b>(배정 row 는 남았는데
 * 목록에 없어 접근 불가)이 된다. 신규 파생이 이 예외로 새지 않는 근거는 {@code LsDataAug.newRawSn}
 * javadoc 참조(생성 경로 2곳이 모두 <b>같은 트랜잭션</b>에서 매핑을 채운다).
 *
 * <h2>판정 범위 = 자기 raw 행 하나 (조상/자손 순회 금지)</h2>
 * <p>부모가 파생인지·부모가 등재됐는지 <b>재귀 조회하지 않는다</b>. 이 프로젝트는 조상/자손 전파를
 * 4라운드 시도한 뒤 전부 철회했다(차단↔복구 비대칭, 팬아웃 상한 초과 시 정상 트리 fail-closed DoS).
 * 손자 파생(깊이 2+)을 만나도 예외 없이 자기 행만으로 판정한다.
 *
 * <h2>구현 제약 — 신규 JOIN 금지</h2>
 * <p>이 술어를 쓰는 목록 리포지토리들은 "행 증식 원천 차단" 을 위해 <b>어떤 조인도 쓰지 않고</b> 상관
 * {@code EXISTS} 로만 조건을 표현한다는 명시된 설계 제약을 갖는다. 여기서 INNER JOIN 을 도입하면
 * {@code totalElements} 가 어긋나고, {@code isNull().or(...)} 를 {@code .and(...)} 로 잘못 쓰면
 * <b>원본 영상까지 0건</b>이 된다. 그래서 판정을 이 한 곳에 모으고 호출부는 {@code buildWhere} 한
 * 지점에서만 붙인다.
 */
public final class DerivativeWorkEligibility {

    /** 서브쿼리 별칭 — 호출부의 기본 별칭({@code lsDataRaw} 등)과 충돌하지 않게 고유 이름을 쓴다. */
    private static final QLsDataRaw GATE_RAW = new QLsDataRaw("derivGateRaw");
    private static final QLsDataAug GATE_AUG = new QLsDataAug("derivGateAug");
    private static final QLsDataAugRvw GATE_RVW = new QLsDataAugRvw("derivGateRvw");

    private DerivativeWorkEligibility() {
    }

    /**
     * {@code FROM LS_DATA_RAW} 인 경로(작업목록)용 — 그 영상 자신이 등재됐는가.
     *
     * @param raw 조회 루트로 쓰인 {@code LS_DATA_RAW} 별칭
     */
    public static BooleanExpression eligible(QLsDataRaw raw) {
        return raw.orgnlRawSn.isNull().or(blockingAugExists(raw.rawSn).not());
    }

    /**
     * {@code FROM} 이 다른 테이블인 경로(배정 목록)용 — 그 {@code rawSn} 이 가리키는 영상이 등재됐는가.
     *
     * <p>영상 행이 없으면(정합 이상) 통과시킨다 — 이 게이트의 책임은 "미검수 파생 차단" 이고, 영상
     * 부재는 다른 축의 문제라 여기서 결과를 줄이면 기존 동작이 조용히 바뀐다.
     *
     * @param rawSnRef 바깥 쿼리의 영상 식별자 경로 (예: {@code assignment.rawDataId})
     */
    public static BooleanExpression eligibleByRawSn(NumberPath<Long> rawSnRef) {
        return JPAExpressions.selectOne()
                .from(GATE_RAW)
                .where(GATE_RAW.rawSn.eq(rawSnRef),
                        GATE_RAW.orgnlRawSn.isNotNull(),
                        blockingAugExists(GATE_RAW.rawSn))
                .exists()
                .not();
    }

    /**
     * 이 파생 영상의 등재를 <b>막는</b> 증강 행이 존재하는가 = "매핑이 있는데 검수 승인이 없다".
     *
     * <p>해상도 파생 판별에 {@code startsWith}(→ {@code LIKE 'RESL_%'})를 쓰지 않는다 — {@code _} 는
     * LIKE 의 단일 문자 와일드카드라 {@code RESLX…} 같은 값까지 예외로 새어 나간다. 상수 목록을
     * {@code IN} 으로 미러하지도 않는다(프리셋이 늘면 한쪽만 갱신돼 그 해상도 파생이 목록에서 사라진다).
     * {@code SUBSTRING} 비교는 접두 상수 하나에서 파생되므로 드리프트가 구조적으로 불가능하다.
     */
    private static BooleanExpression blockingAugExists(NumberPath<Long> derivativeRawSn) {
        return JPAExpressions.selectOne()
                .from(GATE_AUG)
                .where(GATE_AUG.newRawSn.eq(derivativeRawSn),
                        GATE_AUG.augTypeCd.substring(0, LsDataAug.RESL_PREFIX.length())
                                .ne(LsDataAug.RESL_PREFIX),
                        acceptedReviewExists(GATE_AUG.dataAugSn).not())
                .exists();
    }

    /** 그 증강 행에 대한 <b>사람의 사용 결정(ACCEPTED)</b> 이 존재하는가. */
    private static BooleanExpression acceptedReviewExists(NumberPath<Long> dataAugSn) {
        return JPAExpressions.selectOne()
                .from(GATE_RVW)
                .where(GATE_RVW.dataAugSn.eq(dataAugSn),
                        GATE_RVW.rvwSttsCd.eq(LsDataAugRvw.STTS_ACCEPTED))
                .exists();
    }
}

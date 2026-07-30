package kr.co.cudo.authoring.common.util;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;

import java.util.ArrayList;
import java.util.List;

/**
 * "이 문자열 컬럼이 <b>비어있는가</b>" 를 Java {@link String#isBlank()} 와 <b>동일한 의미</b>로 SQL 에서
 * 판정하는 QueryDSL 표현식 — 표시측(Java)과 검색측(SQL)의 blank 판정 불일치를 구조적으로 제거한다.
 *
 * <h2>왜 필요한가</h2>
 * <p>목록 화면의 표시명 폴백은 Java 로 판정한다(예: {@code cctvNm.isBlank() ? vmsCctvId : cctvNm}).
 * {@code String.isBlank()} 는 <b>모든 유니코드 공백</b>({@code \t}·{@code \n}·{@code U+3000} 전각 공백 등)을
 * 공백으로 보지만, SQL {@code trim(col) <> ''} 는 공백문자({@code U+0020})만 제거한다. 두 판정이 어긋나면
 * {@code CCTV_NM='\t'} 인 CCTV 처럼
 * <ul>
 *   <li>화면에는 폴백값({@code VMS_CCTV_ID})이 표시되는데,</li>
 *   <li>검색은 "이름이 있다" 고 보아 폴백 축을 열지 않아</li>
 * </ul>
 * <b>보이는 값으로 검색해도 결과가 나오지 않는</b> 영상이 생긴다.
 *
 * <h2>구현</h2>
 * <p>제거 대상 문자를 {@link Character#isWhitespace} 에서 <b>생성</b>하므로 Java 판정과 자동으로 같은
 * 집합을 쓴다(수동 목록 유지 불필요). 공백류를 HQL {@code replace} 로 모두 제거한 뒤 {@code trim()} 결과가
 * 빈 문자열이면 blank 다. {@code replace} 는 Hibernate 표준 HQL 함수이고 제거 대상 문자는 <b>파라미터로
 * 바인딩</b>된다(쿼리 문자열 연결 없음 — CWE-89).
 *
 * <h2>비용</h2>
 * <p>호출부(검수목록 {@code ReviewQueryRepository} · 작업목록 {@code TaskBoardQueryRepository} 의 영상명
 * 검색)는 이 식을 {@code VMS_CCTV_ID} 등가 조건으로 좁혀진 {@code EXISTS} 서브쿼리에 적용하므로,
 * 식 자체가 평가되는 대상은 <b>CCTV 마스터 1행</b>이다(마스터 규모도 작다).
 *
 * <p>다만 그 {@code EXISTS} 는 목록 검색 조건이라 <b>바깥 쿼리는 {@code LS_DATA_RAW} 를 훑는다</b> —
 * "대량 스캔 경로에 쓰지 않는다" 는 서술은 사실과 달라 삭제했다. 즉 이 식은 스캔되는 각 영상 행마다
 * 1회 평가될 수 있다. 그래도 평가 내용은 상수 {@code replace} 중첩 + {@code trim} 이라 비용이 작고,
 * 애초에 이 판정이 없으면 표시측(Java {@code isBlank()})과 어긋나 "보이는 값으로 검색해도 안 나오는"
 * 영상이 생기므로 정확성을 택한다. <b>인덱스 사용 여부에는 영향이 없다</b>(이 식은 CCTV 명 컬럼에
 * 적용되며 조인 키 {@code VMS_CCTV_ID} 등가 조건은 그대로 유지된다).
 *
 * <p>stateless 유틸 — 인스턴스화 금지.
 */
public final class BlankTextPredicate {

    /** {@code Character.isWhitespace} 인 문자에서 공백(U+0020)만 제외한 목록 — 공백은 {@code trim()} 이 처리한다. */
    private static final List<String> NON_SPACE_WHITESPACE = nonSpaceWhitespaceChars();

    private BlankTextPredicate() {
    }

    /**
     * Java {@code text.isBlank()} 와 동일한 판정 — 공백류를 모두 제거한 뒤 빈 문자열이면 참.
     *
     * <p>{@code null} 컬럼은 SQL 3값 논리상 이 식이 참이 되지 않으므로, "null 이거나 blank" 를 물어야 하는
     * 호출부는 {@code isNull().or(isBlankAsJava(col))} 처럼 명시적으로 조합한다.
     */
    public static BooleanExpression isBlankAsJava(StringExpression text) {
        Expression<String> stripped = text;
        for (String whitespace : NON_SPACE_WHITESPACE) {
            stripped = Expressions.stringTemplate("replace({0}, {1}, '')", stripped, whitespace);
        }
        return Expressions.stringTemplate("trim({0})", stripped).eq("");
    }

    private static List<String> nonSpaceWhitespaceChars() {
        List<String> chars = new ArrayList<>();
        for (int c = Character.MIN_VALUE; c <= Character.MAX_VALUE; c++) {
            if (c != ' ' && Character.isWhitespace((char) c)) {
                chars.add(String.valueOf((char) c));
            }
        }
        return List.copyOf(chars);
    }
}

package kr.co.cudo.authoring.common.util;

import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;

/**
 * "제어문자 제거 + 앞뒤 공백 제거" 정규화 — <b>Java 입력측과 SQL 컬럼측을 같은 규칙</b>으로 맞춘다.
 *
 * <h2>왜 한 곳에 두는가</h2>
 * <p>셀렉트 옵션(코드값 목록)과 그 값을 되돌려 받는 목록 필터가 <b>서로 다른 정규화</b>를 쓰면 왕복이
 * 데이터에 따라 깨진다. 실제 사례:
 * <ul>
 *   <li>옵션은 SQL {@code trim(col)}(공백 {@code U+0020} 만 제거)로 만들고,</li>
 *   <li>필터 입력은 Java 에서 <b>제어문자까지</b> 제거했다.</li>
 * </ul>
 * 관제 유래 코드가 {@code "\tEVT-FIRE"} 처럼 제어문자를 포함하면 옵션은 {@code "\tEVT-FIRE"} 를
 * 내려주는데 그 값을 그대로 재전송하면 입력이 {@code "EVT-FIRE"} 로 정규화돼 <b>0건</b>이 된다
 * (UI 로 도달할 수 없는 데이터). 정규화 규칙을 두 벌 두는 한 이 드리프트는 반복되므로, 규칙의
 * <b>단일 원천</b>을 여기 두고 Java/SQL 양쪽 표현을 함께 제공한다.
 *
 * <h2>규칙</h2>
 * <ol>
 *   <li>{@link Character#isISOControl} 인 문자를 <b>문자열 어디에 있든</b> 제거한다 —
 *       {@code trim()} 은 양끝만 자르므로 문자열 <b>중간</b>의 {@code U+0000} 이 살아남아
 *       PgJDBC 가 {@code Zero bytes may not occur in string parameters} 로 거부하고, 인증 사용자가
 *       반복 호출 가능한 <b>500 + 스택트레이스</b>가 된다(A10:2025). 개행/탭 등 나머지 제어문자도
 *       검색어·코드값으로서 의미가 없고 로그 오염(CWE-117) 소지가 있어 함께 제거한다.</li>
 *   <li>그 뒤 앞뒤 공백을 제거한다. 제어문자 제거 후 {@code U+0020} 이하 문자는 공백뿐이므로
 *       Java {@code String.trim()} 과 SQL {@code trim()} 의 결과가 <b>정확히 일치</b>한다.</li>
 * </ol>
 *
 * <h2>SQL 표현식 구현</h2>
 * <p>제거 대상 문자를 {@link Character#isISOControl} 에서 <b>생성</b>하므로 Java 판정과 자동으로 같은
 * 집합을 쓴다(수동 목록 유지 불필요 — {@link BlankTextPredicate} 와 동일 패턴). 제거는
 * {@code translate(컬럼, 제거대상문자들, '')} <b>단일 호출</b>로 한다 — 컬럼을 한 번만 훑고, 쿼리
 * 텍스트가 짧아 계획 캐시·로그가 읽을 수 있으며, 나중에 표현식 인덱스를 정의하기도 쉽다.
 * (구현 이력: 초기에는 {@code replace} 를 문자 수만큼 중첩했다 — 같은 컬럼을 64회 감싸는 식이었다.)
 *
 * <p><b>{@code regexp_replace} + {@code [[:cntrl:]]} 는 쓰지 않는다</b> — PostgreSQL 에서 그 문자
 * 클래스가 C1 제어문자({@code U+0080}~{@code U+009F})를 포함하는지가 로케일/인코딩에 좌우돼
 * {@link Character#isISOControl} 과의 등가성이 환경에 따라 깨진다. 문자 집합을 Java 에서 생성해
 * 넘기는 현재 방식은 그 의존이 없다.
 *
 * <p><b>제거 대상 문자 집합은 바인딩 파라미터가 아니라 {@code chr(코드포인트)} 상수식으로 인라인</b>한다 —
 * 이 식은 {@code SELECT DISTINCT} 의 select 목록과 {@code ORDER BY} 에 <b>동시에</b> 쓰이는데,
 * PostgreSQL 은 두 자리의 표현식이 <b>구조적으로 같아야</b> 허용한다({@code for SELECT DISTINCT,
 * ORDER BY expressions must appear in select list}). 호출부가 같은 표현식 인스턴스를 재사용해도
 * Hibernate 는 <b>출현 위치마다 다른 JDBC 파라미터 자리</b>를 발급하므로, 파라미터로 넘기면
 * {@code translate(col, $3, '')} 와 {@code translate(col, $17, '')} 가 되어 이 검사에서 탈락한다
 * (실측 확인 — 그 형태로 바꾸면 옵션 API 가 전부 500 이 된다). 상수식은 위치와 무관하게 같은 파스트리라
 * 검사를 통과하고, PostgreSQL 이 플랜 단계에서 하나의 상수로 접는다({@code chr} 은 immutable).
 * 삽입되는 값은 {@link Character#isISOControl} 로부터 생성한 <b>정수 코드</b>뿐이고 사용자 입력이 닿는
 * 지점이 없다(CWE-89 무관).
 *
 * <p><b>{@code U+0000} 은 SQL 표현식에서 제외</b>한다 — PostgreSQL 의 텍스트 타입은 NUL 바이트를
 * 저장할 수 없어 컬럼 값에 존재할 수 없고, 반대로 그 문자를 파라미터로 바인딩하면 PgJDBC 가 거부해
 * <b>이 식을 쓰는 모든 쿼리가 500</b> 이 된다. Java 입력측은 {@code U+0000} 을 그대로 제거한다
 * (입력에는 실제로 들어올 수 있고, 제거가 바로 위 500 을 막는 방어다).
 *
 * <p>stateless 유틸 — 인스턴스화 금지.
 */
public final class ControlCharNormalizer {

    /**
     * SQL {@code translate} 의 <b>제거 대상 문자 집합</b>을 만드는 상수식({@code chr(1)||chr(2)||...}) —
     * {@code U+0000} 제외(위 클래스 주석의 사유). {@code translate(x, from, '')} 는 {@code from} 에
     * 등장하는 문자를 모두 제거한다(대응 문자가 없으므로).
     */
    private static final String STRIPPED_CONTROL_CHARS_SQL = strippedControlCharsSql();

    private ControlCharNormalizer() {
    }

    /**
     * 입력 정규화(Java) — 제어문자 제거 후 앞뒤 공백 제거. 남는 게 없으면 {@code null}.
     *
     * <p>{@code null} 반환은 호출 측에서 "필터 미적용" 을 뜻한다. 거부(400)가 아니라 제거인 이유는
     * blank 처리 규약과 일관되게 하기 위함이다 — 제어문자만 보낸 입력은 빈 입력과 동일하게 취급된다.
     */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                sanitized.append(c);
            }
        }
        String trimmed = sanitized.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 컬럼 정규화(SQL) — {@link #normalizeOrNull} 과 <b>같은 결과 문자열</b>을 만드는 QueryDSL 표현식.
     *
     * <p>빈 값 판정({@code ne("")})과 값 비교({@code eq(입력)})를 모두 이 식 위에서 하면, 옵션으로
     * 내려간 값을 그대로 필터로 되돌려 보냈을 때 반드시 매칭된다.
     *
     * <p>{@code null} 컬럼은 SQL 3값 논리상 이 식의 비교가 참이 되지 않는다(호출부가 별도 처리).
     */
    public static StringExpression normalizedAsJava(StringExpression text) {
        return Expressions.stringTemplate(
                "trim(translate({0}, " + STRIPPED_CONTROL_CHARS_SQL + ", ''))", text);
    }

    private static String strippedControlCharsSql() {
        StringBuilder sql = new StringBuilder();
        for (int c = 1; c <= Character.MAX_VALUE; c++) {
            if (Character.isISOControl((char) c)) {
                if (sql.length() > 0) {
                    sql.append("||");
                }
                sql.append("chr(").append(c).append(')');
            }
        }
        return sql.toString();
    }
}

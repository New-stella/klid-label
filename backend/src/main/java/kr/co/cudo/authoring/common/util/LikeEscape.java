package kr.co.cudo.authoring.common.util;

/**
 * LIKE 패턴 이스케이프 <b>단일 원천</b> — 사용자 검색어에 섞인 와일드카드({@code % _})와 이스케이프
 * 문자({@code \})를 리터럴로 바꾼다 (CWE-89 안전 바인딩의 일부).
 *
 * <p>이스케이프를 빼먹으면 {@code q=%} 한 글자로 필터가 통째로 무력화되고(전체 조회),
 * {@code q=\} 는 SQL 에 끝나지 않는 이스케이프 시퀀스를 남겨 오류/오매칭을 만든다.
 *
 * <p><b>왜 유틸로 분리하는가</b> — 목록 검색은 화면마다 리포지토리가 따로 있고, 각자 사본을 두면
 * 한쪽만 규칙이 바뀌어도 아무도 눈치채지 못한다. 이스케이프 문자({@link #ESCAPE_CHAR})와 치환
 * 규칙은 <b>짝</b>이어야 하므로 둘을 같은 곳에 둔다 — 패턴만 이 유틸로 만들고 {@code like(pattern)}
 * 를 이스케이프 문자 없이 호출하면 방어가 성립하지 않는다.
 *
 * <p>사용법: {@code like(pattern, LikeEscape.ESCAPE_CHAR)} 와 함께 쓴다.
 */
public final class LikeEscape {

    /** LIKE 절에 함께 넘겨야 하는 이스케이프 문자 — {@code like(pattern, ESCAPE_CHAR)}. */
    public static final char ESCAPE_CHAR = '\\';

    private LikeEscape() {
    }

    /**
     * LIKE 특수문자({@code \ % _})를 이스케이프한다. 치환 순서가 중요하다 — 백슬래시를 먼저
     * 두 배로 만들지 않으면 뒤이어 삽입한 이스케이프 문자까지 다시 이스케이프된다.
     */
    public static String escape(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /** 부분일치({@code %값%}) 패턴 — 이스케이프까지 한 번에 적용한다. */
    public static String contains(String raw) {
        return "%" + escape(raw) + "%";
    }
}

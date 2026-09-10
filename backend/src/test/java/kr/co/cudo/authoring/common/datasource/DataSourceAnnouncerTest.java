package kr.co.cudo.authoring.common.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>기동 로그로 접속 비밀번호가 새지 않는가</b> — 회귀 가드.
 *
 * <p>이 로그를 넣은 목적은 「어느 DB 를 보는가」를 서버에 들어가지 않고 확인하는 것이다. 그
 * 편의가 <b>비밀번호 유출 경로</b>가 되면 안 된다 — JDBC URL 은 질의 문자열로 비밀번호를 실을
 * 수 있고({@code ?password=...}), 그대로 찍으면 로그 파일 전체가 자격증명 저장소가 된다(CWE-532).
 *
 * <p>⚠ 이 가드는 <b>「비밀번호라는 낱말이 없는지」를 세지 않는다</b> — 파라미터 이름은 드라이버마다
 * 다르고({@code password}·{@code pwd}·{@code passwd}) 새 이름이 생기면 낱말 목록이 조용히
 * 뒤처진다. 대신 <b>질의 문자열이 통째로 잘려 나가는지</b>를 본다 — 이름을 몰라도 성립한다.
 */
@DisplayName("기동 로그 — 접속 정보 표시(비밀번호 미노출)")
class DataSourceAnnouncerTest {

    /** {@code sanitize} 는 private static 이라 반사로 부른다 — 공개 API 를 늘리지 않기 위해서다. */
    private static String sanitize(String url) throws Exception {
        Method m = DataSourceAnnouncer.class.getDeclaredMethod("sanitize", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, url);
    }

    @Test
    @DisplayName("★ 질의 문자열을 통째로 잘라낸다 — 비밀번호가 거기 실린다")
    void 질의문자열을_잘라낸다() throws Exception {
        String url = "jdbc:postgresql://db.example:5432/klid_system?password=s3cr3t&ssl=true";

        String shown = sanitize(url);

        assertThat(shown).isEqualTo("jdbc:postgresql://db.example:5432/klid_system");
        assertThat(shown).doesNotContain("s3cr3t");
        assertThat(shown).doesNotContain("?");
    }

    @ParameterizedTest
    @DisplayName("파라미터 이름이 무엇이든 잘린다 — 낱말을 세지 않기 때문이다")
    @ValueSource(strings = {
            "jdbc:postgresql://h:5432/d?password=x",
            "jdbc:postgresql://h:5432/d?pwd=x",
            "jdbc:postgresql://h:5432/d?passwd=x",
            "jdbc:postgresql://h:5432/d?user=u&password=x&ssl=true",
    })
    void 파라미터_이름과_무관하게_잘린다(String url) throws Exception {
        assertThat(sanitize(url)).isEqualTo("jdbc:postgresql://h:5432/d");
    }

    @Test
    @DisplayName("질의 문자열이 없으면 원문 그대로 — 필요한 정보는 지우지 않는다")
    void 질의문자열이_없으면_그대로_둔다() throws Exception {
        String url = "jdbc:postgresql://db.example:5432/klid_system";

        assertThat(sanitize(url)).isEqualTo(url);
    }

    @Test
    @DisplayName("★ 미설정을 빈 문자열로 뭉개지 않는다 — 「로그가 없다」와 「값이 없다」는 다른 사실이다")
    void 미설정은_그_사실을_표시한다() throws Exception {
        assertThat(sanitize(null)).isEqualTo("(미설정)");
        assertThat(sanitize("   ")).isEqualTo("(미설정)");
    }
}

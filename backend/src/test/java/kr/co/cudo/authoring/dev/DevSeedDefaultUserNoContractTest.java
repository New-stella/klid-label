package kr.co.cudo.authoring.dev;

import kr.co.cudo.authoring.user.service.DevStandardAccounts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dev 토큰의 기본 USER_NO 상수와 {@code dev-seed.sql} 의 실제 시드 행이 <b>1:1 로 맞는지</b> 고정한다.
 *
 * <p>왜 필요한가: 두 파일은 서로를 모른 채 같은 번호를 적고 있다. 한쪽만 바꾸면 그 역할의 dev
 * 토큰이 <b>다른 사람의 행</b>을 가리키는데, 컴파일도 되고 토큰 발급도 성공해서 조용히 지나간다.
 * 과거 1002(WORKER)·2001(PORTAL) 오매핑이 정확히 그 사고였고, 증상은 「배정 0건」처럼 엉뚱한
 * 자리에서 나타나 원인을 찾기 어려웠다.
 *
 * <p>관리자(9001)는 2026-08-28 에 더해졌다 — 그전에는 시드에 관리자 행이 없어 ADMIN 토큰이
 * 「userNo 를 명시하라」로 막혔고, 그래서 관리자 화면을 dev 에서 <b>사람이 눌러 볼 수단이
 * 없었다.</b>
 *
 * <p>⚠ 상수는 2026-09-01 에 {@code DevTokenService} 에서 {@link DevStandardAccounts} 로 옮겨졌다 —
 * 진입 시 작업자 자동 등록의 <b>제외 판정</b>이 같은 번호를 알아야 해서다. 그 클래스가 상수를
 * 공개하므로 여기서는 소스 파싱 대신 <b>상수를 직접 참조</b>한다(정규식이 표기 변화에 깨지지 않는다).
 *
 * @design ADR-055
 * @design AC-1016
 */
class DevSeedDefaultUserNoContractTest {

    private static final Path SEED = Path.of("src/main/resources/db/seed/dev-seed.sql");

    @Test
    @DisplayName("dev_토큰_기본_USER_NO_는_전부_시드에_실재하고_역할도_일치한다")
    void defaultUserNosExistInSeedWithMatchingRole() throws IOException {
        String seed = read(SEED);

        // ★두 INSERT 블록을 먼저 <분리>한다 — 파일 전체를 상대로 찾으면 사용자 표의 단언이
        //   역할 표의 행 `(9001, 'ADMIN',` 에도 매칭되어, 사용자 행이 통째로 사라져도 통과한다.
        //   그러면 이 가드가 막겠다고 선언한 실패(토큰 주체가 존재하지 않는 사용자를 가리킴)를
        //   정확히 못 잡는다. 스코프가 곧 이 가드의 실효다.
        String userBlock = insertBlock(seed, "LS_ACNT_USER");
        String roleBlock = insertBlock(seed, "LS_USER_ROLE");

        // given: 상수 4종 — 이 목록이 곧 「기본값을 가진 역할」이다.
        record Pair(String constant, String userNo, String roleCd) {}
        var pairs = new Pair[]{
                new Pair("DEFAULT_USER_NO_REVIEWER", DevStandardAccounts.DEFAULT_USER_NO_REVIEWER, "REVIEWER"),
                new Pair("DEFAULT_USER_NO_WORKER", DevStandardAccounts.DEFAULT_USER_NO_WORKER, "WORKER"),
                new Pair("DEFAULT_USER_NO_PORTAL", DevStandardAccounts.DEFAULT_USER_NO_PORTAL, "PORTAL_USER"),
                new Pair("DEFAULT_USER_NO_ADMIN", DevStandardAccounts.DEFAULT_USER_NO_ADMIN, "ADMIN"),
        };

        for (Pair pair : pairs) {
            String userNo = pair.userNo();

            // then: 사용자 표(LS_ACNT_USER INSERT 블록 <안>)에 그 번호가 있다.
            assertThat(userBlock)
                    .as("%s=%s 인데 dev-seed.sql 의 LS_ACNT_USER 에 그 번호가 없다 — "
                            + "그 역할의 dev 토큰이 존재하지 않는 사용자를 가리킨다", pair.constant(), userNo)
                    .containsPattern("\\(\\s*" + userNo + "\\s*,\\s*'[^']+'\\s*,");

            // then: 역할 표(LS_USER_ROLE INSERT 블록 <안>)에서 그 번호가 기대한 역할로 매핑돼 있다.
            assertThat(roleBlock)
                    .as("%s=%s 의 시드 역할이 %s 가 아니다 — 토큰의 역할과 DB 의 역할이 갈리면 "
                            + "인가 판정이 토큰과 다르게 난다", pair.constant(), userNo, pair.roleCd())
                    .containsPattern("\\(\\s*" + userNo + "\\s*,\\s*'" + pair.roleCd() + "'\\s*,");
        }
    }

    /**
     * {@code INSERT INTO <table> … ;} 한 블록만 잘라 낸다 — 단언의 <b>스코프</b>를 만드는 장치다.
     *
     * <p>블록이 없거나 둘 이상이면 실패시킨다. 없으면 그 표의 시드가 사라진 것이고, 둘 이상이면
     * 어느 블록을 검사하는지가 파일 편집 순서에 따라 흔들려 가드가 조용히 무력해진다.
     */
    private static String insertBlock(String seed, String table) {
        Matcher m = Pattern.compile("INSERT\\s+INTO\\s+" + table + "\\b[^;]*;",
                Pattern.CASE_INSENSITIVE).matcher(seed);
        assertThat(m.find())
                .as("dev-seed.sql 에 %s INSERT 블록이 없다 — 그 표의 시드가 통째로 사라졌다", table)
                .isTrue();
        String block = m.group();
        assertThat(m.find())
                .as("dev-seed.sql 의 %s INSERT 블록이 둘 이상이다 — 이 가드가 어느 블록을 "
                        + "검사하는지 불확정해진다(가드를 함께 고칠 것)", table)
                .isFalse();
        return block;
    }

    @Test
    @DisplayName("관리자_기본_USER_NO_는_다른_역할과_겹치지_않는다")
    void adminDefaultUserNoIsDistinct() {
        String admin = DevStandardAccounts.DEFAULT_USER_NO_ADMIN;

        for (String other : new String[]{
                DevStandardAccounts.DEFAULT_USER_NO_REVIEWER,
                DevStandardAccounts.DEFAULT_USER_NO_WORKER,
                DevStandardAccounts.DEFAULT_USER_NO_PORTAL}) {
            assertThat(admin)
                    .as("관리자 기본 번호가 %s 와 같다 — 관리자 토큰의 sub 가 다른 사람의 행을 "
                            + "가리키게 되며, 과거 1002/2001 오매핑과 같은 사고다", other)
                    .isNotEqualTo(other);
        }
    }

    private static String read(Path relative) throws IOException {
        return Files.readString(relative, StandardCharsets.UTF_8);
    }
}

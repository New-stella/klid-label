package kr.co.cudo.authoring.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A-ISSUE-62 (HIGH, CWE-532 / CWE-359) — 로그 마스킹 규칙 회귀 가드.
 *
 * <p>기존 규칙은 {@code key=value} / {@code Authorization:} 두 형태뿐이라 JSON 형태 자격증명 ·
 * bare JWT · 전화번호 · 주민번호형 · 이메일 · {@code X-Access-Token} 이 전부 평문으로 기록됐다.
 * 각 형태별로 "원문이 남지 않는다"를 직접 검증한다.
 */
class LogMaskingPatternsTest {

    // ---------------------------------------------------------------- 기존 규칙 회귀(불변)

    @Test
    @DisplayName("기존_keyvalue_와_Authorization_헤더_마스킹_유지")
    void existingKvAndHeaderRulesStillApply() {
        assertThat(LogMaskingPatterns.mask("password=mypw123 something=ok"))
                .contains("password=***").doesNotContain("mypw123");
        assertThat(LogMaskingPatterns.mask("token=abcdef")).contains("token=***").doesNotContain("abcdef");
        assertThat(LogMaskingPatterns.mask("secret=topsecret")).contains("secret=***").doesNotContain("topsecret");
        assertThat(LogMaskingPatterns.mask("Authorization: Bearer abc.def.ghi"))
                .contains("Authorization: ***").doesNotContain("abc.def.ghi");
    }

    @Test
    @DisplayName("민감하지_않은_필드는_그대로_보존")
    void nonSensitiveFieldsUntouched() {
        String input = "[Sort] unsupported sort key key=capturedAt rawSn=1234 status=OPEN";
        assertThat(LogMaskingPatterns.mask(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("null_과_빈문자열은_그대로_반환")
    void nullAndEmptyPassThrough() {
        assertThat(LogMaskingPatterns.mask(null)).isNull();
        assertThat(LogMaskingPatterns.mask("")).isEmpty();
    }

    // ---------------------------------------------------------------- 신규 규칙 ①~⑤

    @Test
    @DisplayName("JSON_형태_자격증명_마스킹")
    void masksJsonCredentials() {
        assertThat(LogMaskingPatterns.mask("body={\"password\":\"hunter2\"}"))
                .doesNotContain("hunter2")
                .contains("\"password\":\"***\"");
        assertThat(LogMaskingPatterns.mask("resp={\"accessToken\":\"abcdefgh\",\"userId\":7}"))
                .doesNotContain("abcdefgh")
                .contains("\"userId\":7");
        assertThat(LogMaskingPatterns.mask("{\"clientSecret\" : \"s3cr3t-value\"}"))
                .doesNotContain("s3cr3t-value");
    }

    @Test
    @DisplayName("접두어_없는_JWT_전문_마스킹")
    void masksBareJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDAxIn0.SIGNATURE-VALUE";
        assertThat(LogMaskingPatterns.mask("upstream replied with " + jwt))
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .doesNotContain("SIGNATURE-VALUE")
                .contains("***");
        // 서명부가 없는(alg=none 위조 시도) 형태도 남기지 않는다.
        assertThat(LogMaskingPatterns.mask("token candidate eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0."))
                .doesNotContain("eyJhbGciOiJub25lIn0");
    }

    @Test
    @DisplayName("전화번호_가운데자리_마스킹")
    void masksPhoneNumber() {
        assertThat(LogMaskingPatterns.mask("reporter phone 010-1234-5678"))
                .doesNotContain("1234")
                .contains("010-****-5678");
        assertThat(LogMaskingPatterns.mask("phone=01012345678 end"))
                .doesNotContain("01012345678");
        // 전화번호가 아닌 일반 숫자열은 건드리지 않는다.
        assertThat(LogMaskingPatterns.mask("rawSn=98765")).isEqualTo("rawSn=98765");
    }

    @Test
    @DisplayName("주민번호형_13자리_마스킹")
    void masksResidentRegistrationNumber() {
        assertThat(LogMaskingPatterns.mask("ssn-900101-1234567 detected"))
                .doesNotContain("900101-1234567");
        assertThat(LogMaskingPatterns.mask("id 9001011234567"))
                .doesNotContain("9001011234567");
    }

    @Test
    @DisplayName("이메일_로컬파트_마스킹")
    void masksEmail() {
        String masked = LogMaskingPatterns.mask("contact user@example.com now");
        assertThat(masked).doesNotContain("user@example.com").contains("@example.com");
    }

    @Test
    @DisplayName("Authorization_이외_토큰_헤더_XAccessToken_마스킹")
    void masksNonAuthorizationTokenHeaders() {
        assertThat(LogMaskingPatterns.mask("X-Access-Token: TOKENVAL-1234"))
                .doesNotContain("TOKENVAL-1234")
                .contains("X-Access-Token: ***");
        assertThat(LogMaskingPatterns.mask("X-Api-Key: k-abcdef"))
                .doesNotContain("k-abcdef");
    }

    /**
     * ★ LOW-8 — 관리자 단기 유효창 토큰 헤더({@code X-Admin-Session}) 마스킹.
     *
     * <p><b>방어심층</b>이다 — 현재 이 헤더를 로깅하는 지점은 0 건이라 실유출 경로가 없다. 값이 서명
     * 토큰이라 <b>기록되기 시작하는 순간</b> 자격증명이 평문으로 남으므로 규칙을 미리 올려 둔다.
     */
    @Test
    @DisplayName("★관리자_세션_토큰_헤더도_마스킹된다 (LOW-8, 방어심층)")
    void masksAdminSessionHeader() {
        assertThat(LogMaskingPatterns.mask("X-Admin-Session: v1.SIGNED-ADMIN-TOKEN"))
                .doesNotContain("SIGNED-ADMIN-TOKEN")
                .contains("X-Admin-Session: ***");
        // 소문자 표기도 같은 규칙을 탄다(헤더명은 대소문자를 가리지 않는다).
        assertThat(LogMaskingPatterns.mask("x-admin-session: v1.SIGNED-ADMIN-TOKEN"))
                .doesNotContain("SIGNED-ADMIN-TOKEN");
    }

    @Test
    @DisplayName("A_ISSUE_62_실동작_누출_케이스_6종_전부_평문_미잔존")
    void allReportedLeakCasesAreMasked() {
        // 이슈 재현 로그 원문 6줄 — 수정 전에는 전부 평문으로 기록됐다.
        String log = String.join(" | ",
                "key={\"password\":\"hunter2\"}",
                "key=010-1234-5678",
                "key=ssn-900101-1234567",
                "key=user@example.com",
                "key=Bearer eyJhbGciOiJIUzI1NiJ9.PAYLOADPART.SIGPART",
                "key=X-Access-Token: TOKENVAL");

        String masked = LogMaskingPatterns.mask(log);

        assertThat(masked)
                .doesNotContain("hunter2")
                .doesNotContain("010-1234-5678")
                .doesNotContain("900101-1234567")
                .doesNotContain("user@example.com")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .doesNotContain("TOKENVAL");
    }

    // ---------------------------------------------------------------- 쿠키 헤더 (CWE-532)

    @Test
    @DisplayName("쿠키_헤더는_줄_끝까지_마스킹되어_세션토큰이_남지_않음")
    void masksCookieHeaders() {
        // given: 세션 토큰이 실린 Cookie 헤더 — 값이 ';' 로 구분된 여러 쌍이다.
        String log = "req headers Cookie: JSESSIONID=ABC123; refresh=RT-9999";

        // when
        String masked = LogMaskingPatterns.mask(log);

        // then: 첫 쌍뿐 아니라 뒤따르는 쌍까지 전부 사라져야 한다.
        assertThat(masked)
                .doesNotContain("ABC123")
                .doesNotContain("RT-9999")
                .contains("Cookie: ***");
        assertThat(LogMaskingPatterns.mask("Set-Cookie: SESSION=s3ss10n; HttpOnly"))
                .doesNotContain("s3ss10n")
                .contains("Set-Cookie: ***");
    }

    @Test
    @DisplayName("쿠키_마스킹은_줄_경계를_넘지_않음")
    void cookieMaskingStopsAtLineBreak() {
        // given: 쿠키 헤더 다음 줄에는 진단에 필요한 일반 로그가 이어진다.
        String log = "Cookie: SESSION=abc\n[Order] created orderId=42";

        // then: 다음 줄은 그대로 남는다.
        assertThat(LogMaskingPatterns.mask(log))
                .doesNotContain("SESSION=abc")
                .contains("[Order] created orderId=42");
    }

    // ------------------------------------------------- ReDoS 회귀 가드 (CWE-1333 / CWE-400)

    @Test
    @DisplayName("무구분_영숫자_런_입력에서_마스킹이_선형시간에_끝남_ReDoS_회귀")
    void maskingIsLinearOnLongUndelimitedRuns() {
        // given: 키워드가 전혀 없는 8KB·16KB 무구분 영숫자 런.
        //  수정 전 패턴(키워드 앞 [A-Za-z0-9_.\-]*)은 런의 모든 시작 위치에서 되돌아와
        //  8,000자 898ms / 16,000자 3,702ms 가 걸렸다(openjdk17 실측).
        String run8k = alphanumericRun(8_000);
        String run16k = alphanumericRun(16_000);

        warmUp();

        // when
        long elapsed8k = bestOfThreeMillis(run8k);
        long elapsed16k = bestOfThreeMillis(run16k);

        // then: 선형이면 한 자릿수 ms 다. 2차 백트래킹이 되살아나면 수백 ms~수 초로 튄다.
        assertThat(elapsed8k)
                .as("8KB 무구분 런 마스킹 소요(ms) — 수정 전 898ms")
                .isLessThan(200L);
        assertThat(elapsed16k)
                .as("16KB 무구분 런 마스킹 소요(ms) — 수정 전 3,702ms")
                .isLessThan(200L);
        // 입력이 2배가 됐을 때 비용이 4배(2차)가 아니라 대략 비례해야 한다.
        assertThat(elapsed16k).isLessThan(Math.max(elapsed8k * 4 + 50, 100L));
    }

    @Test
    @DisplayName("따옴표가_다수_실린_입력에서도_JSON_규칙이_선형시간에_끝남")
    void maskingIsLinearOnQuoteHeavyInput() {
        // given: 여는 따옴표 8,000개 뒤에 긴 런 — JSON 자격증명 규칙의 적대 입력(수정 전 924ms).
        String input = "\"".repeat(8_000) + alphanumericRun(8_000);

        warmUp();

        // then
        assertThat(bestOfThreeMillis(input))
                .as("따옴표 다수 입력 마스킹 소요(ms) — 수정 전 924ms")
                .isLessThan(200L);
    }

    @Test
    @DisplayName("상한_초과_입력은_앞부분만_마스킹하고_나머지는_폐기_fail_secure")
    void oversizedInputIsTruncatedInsteadOfLeaking() {
        // given: 상한(64KB)을 넘는 입력이고, 상한 뒤쪽에 자격증명이 실려 있다.
        String input = alphanumericRun(LogMaskingPatterns.MAX_MASK_LENGTH + 5_000) + " password=LEAKME";

        // when
        String masked = LogMaskingPatterns.mask(input);

        // then: 초과분은 마스킹 없이 흘리지 않고 잘라낸다.
        assertThat(masked)
                .doesNotContain("LEAKME")
                .contains("chars truncated");
        assertThat(masked.length()).isLessThan(input.length());
    }

    @Test
    @DisplayName("상한_이하_입력은_절삭되지_않고_그대로_마스킹")
    void inputWithinLimitIsNotTruncated() {
        String input = "a".repeat(1_000) + " password=pw1";
        assertThat(LogMaskingPatterns.mask(input))
                .doesNotContain("truncated")
                .contains("password=***");
    }

    // ------------------------------------------- ReDoS 수정이 마스킹 범위를 줄이지 않았는지

    @Test
    @DisplayName("키워드_앞뒤에_조각이_붙은_키도_계속_마스킹_경계_lookbehind_회귀")
    void masksKeysWithPrefixAndSuffixFragments() {
        // given: 경계 lookbehind 도입 시 가장 깨지기 쉬운 형태 — 키워드 앞에 조각이 붙은 키.
        String log = "cfg db.password=pw1 accessToken=tok2 clientSecret=s3 X_API_KEY_VALUE=k4";

        // when
        String masked = LogMaskingPatterns.mask(log);

        // then
        assertThat(masked)
                .doesNotContain("pw1").doesNotContain("tok2")
                .doesNotContain("s3").doesNotContain("k4")
                .contains("db.password=***")
                .contains("accessToken=***")
                .contains("clientSecret=***");
    }

    @Test
    @DisplayName("도메인_라벨_분해_이후에도_이메일_변형이_모두_마스킹")
    void masksEmailVariantsAfterDomainRewrite() {
        // given: 서브도메인 다단 / 문장 끝 마침표 / 괄호 감싼 형태 — 도메인부 possessive 화의 함정.
        assertThat(LogMaskingPatterns.mask("mail sub.user+tag@mail.sub.example.co.kr end"))
                .doesNotContain("sub.user+tag@")
                .contains("@mail.sub.example.co.kr");
        assertThat(LogMaskingPatterns.mask("sentence ends with user@example.com."))
                .doesNotContain("user@example.com");
        assertThat(LogMaskingPatterns.mask("<worker@example.com>"))
                .doesNotContain("worker@example.com");
    }

    // ------------------- 2차 ReDoS: 키워드가 런에 반복 포함된 경우 (CWE-1333 / CWE-400)

    @Test
    @DisplayName("키워드가_반복_포함된_런에서도_마스킹이_선형시간에_끝남_2차_ReDoS_회귀")
    void maskingIsLinearOnRunsThatRepeatTheKeyword() {
        // given: 경계 lookbehind 만으로는 못 막는 진짜 공격 표면 — 키워드가 반복 포함된 단일 런.
        //  lookbehind 는 매칭 "시작 위치"만 런 첫 글자로 제한할 뿐, 그 한 위치에서 앞쪽 반복이
        //  키워드를 만날 때마다 되돌아가고 매번 뒤쪽 반복이 런 끝까지 재스캔한다.
        //  수정 전 실측(openjdk17): 8K 33ms / 16K 133ms / 32K 535ms / 65K 2,265ms — 2배마다 4배.
        warmUp();

        long e8k = bestOfThreeMillis("token".repeat(8_000 / 5));
        long e16k = bestOfThreeMillis("token".repeat(16_000 / 5));
        long e32k = bestOfThreeMillis("token".repeat(32_000 / 5));
        long e65k = bestOfThreeMillis("token".repeat(65_000 / 5));

        // then: 반복 상한으로 시작 위치당 비용이 상수가 되어 전부 한 자릿수 ms 다.
        assertThat(e8k).as("8K 키워드 반복 런(ms) — 수정 전 33ms").isLessThan(100L);
        assertThat(e16k).as("16K 키워드 반복 런(ms) — 수정 전 133ms").isLessThan(100L);
        assertThat(e32k).as("32K 키워드 반복 런(ms) — 수정 전 535ms").isLessThan(100L);
        assertThat(e65k).as("65K 키워드 반복 런(ms) — 수정 전 2,265ms").isLessThan(100L);
        // 입력이 8배가 됐을 때 2차(64배)가 아니어야 한다.
        assertThat(e65k).as("2차 복잡도 회귀 감지").isLessThan(Math.max(e8k * 8 + 50, 100L));
    }

    // ------------------- 3차 ReDoS: bare JWT 규칙의 경계 lookbehind 누락 (CWE-1333 / CWE-400)

    @Test
    @DisplayName("eyJ_가_반복된_런에서도_bare_JWT_규칙이_선형시간에_끝남_3차_ReDoS_회귀")
    void maskingIsLinearOnRunsThatRepeatTheJwtPrefix() {
        // given: 다른 규칙과 달리 BARE_JWT_PATTERN 만 경계 lookbehind 가 빠져 있었다.
        //  eyJ 는 JWT 문자 클래스([A-Za-z0-9_-]) 안의 조각이라 런 한가운데에서도 매칭이 시작되고,
        //  뒤의 {4,}+ 가 런 끝까지 스캔했다 '.' 를 못 만나 실패하는 동작이 런 안 eyJ 출현
        //  횟수(O(n))만큼 반복돼 O(n²) 이 됐다.
        //  수정 전 실측(openjdk17): 8K 72ms / 16K 241ms / 32K 935ms / 64K 3,703ms — 2배마다 4배.
        warmUp();

        long e8k = bestOfThreeMillis("eyJ".repeat(8_000 / 3));
        long e16k = bestOfThreeMillis("eyJ".repeat(16_000 / 3));
        long e32k = bestOfThreeMillis("eyJ".repeat(32_000 / 3));
        long e64k = bestOfThreeMillis("eyJ".repeat(64_000 / 3));

        // then: 시작 위치가 런 첫 글자 한 곳으로 제한돼 전부 한 자릿수 ms 다.
        assertThat(e8k).as("8K eyJ 반복 런(ms) — 수정 전 72ms").isLessThan(100L);
        assertThat(e16k).as("16K eyJ 반복 런(ms) — 수정 전 241ms").isLessThan(100L);
        assertThat(e32k).as("32K eyJ 반복 런(ms) — 수정 전 935ms").isLessThan(100L);
        assertThat(e64k).as("64K eyJ 반복 런(ms) — 수정 전 3,703ms").isLessThan(100L);
        // 입력이 8배가 됐을 때 2차(64배)가 아니어야 한다.
        assertThat(e64k).as("2차 복잡도 회귀 감지").isLessThan(Math.max(e8k * 8 + 50, 100L));
    }

    @Test
    @DisplayName("bare_JWT_경계_lookbehind_도입_후에도_구분자별_JWT_가_모두_마스킹")
    void masksBareJwtAfterBoundaryLookbehind() {
        // given: 경계 lookbehind 도입 시 깨지기 쉬운 형태 — 실제 로그에 JWT 가 실리는 구분자 전부.
        //  lookbehind 는 앞 글자가 [A-Za-z0-9_-] 이면 매칭을 시작하지 않으므로, 앞에 오는 구분자가
        //  =·"·공백·[ 처럼 문자 클래스 밖인지가 관건이다.
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDAxIn0.SIGNATURE-VALUE";
        String[] contexts = {
                "%s",                       // 단독
                "token=%s",                 // key=value
                "{\"jwt\":\"%s\"}",         // JSON
                "Authorization: Bearer %s", // 헤더 + Bearer
                "before %s after",          // 공백 사이
                "resp=[%s]",                // 괄호 안
        };

        // then: 어느 구분자에서도 평문이 남지 않는다.
        for (String ctx : contexts) {
            assertThat(LogMaskingPatterns.mask(String.format(ctx, jwt)))
                    .as("구분자 컨텍스트: " + ctx)
                    .doesNotContain("eyJ");
        }
        // 서명부 없는 alg=none 형태도 계속 마스킹.
        assertThat(LogMaskingPatterns.mask("alg none eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0."))
                .doesNotContain("eyJ");
    }

    // --------------------------- 스택 고갈: 이메일 도메인 라벨 분해 (CWE-674)

    @Test
    @DisplayName("도메인_라벨이_수천개인_입력에서_StackOverflowError가_나지_않음")
    void deeplyLabeledDomainDoesNotOverflowTheStack() {
        // given: 8KB — 상한(64KB)의 1/8 이라 길이 방어선에 걸리지도 않는다.
        //  수정 전 도메인부 (?:[A-Za-z0-9\-]++\.)+ 는 재귀 Loop 노드로 컴파일돼 라벨 수만큼
        //  콜스택이 쌓였고, 이 Error 는 Logback catch(Exception) 에 안 잡혀 로깅 스레드가 죽었다.
        String input4k = "a@" + "b.".repeat(4_000);
        String input10k = "a@" + "b.".repeat(10_000);

        // then: Error 없이 정상 반환한다(마스킹 여부와 무관하게 스레드가 죽지 않는 것이 핵심).
        assertThatCode(() -> LogMaskingPatterns.mask(input4k)).doesNotThrowAnyException();
        assertThatCode(() -> LogMaskingPatterns.mask(input10k)).doesNotThrowAnyException();
        // 유효한 TLD 가 붙어 실제 이메일 꼴이어도 마찬가지다.
        assertThatCode(() -> LogMaskingPatterns.mask("a@" + "b.".repeat(4_000) + "com"))
                .doesNotThrowAnyException();
        // 다른 규칙과 함께 흐르는 실제 로그 라인 형태에서도 안전해야 한다.
        assertThatCode(() -> LogMaskingPatterns.mask("user report a@" + "b.".repeat(5_000) + " done"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("도메인_라벨_상한_도입_후에도_실무_이메일은_정확히_마스킹")
    void realWorldEmailsStillMaskedAfterLabelBound() {
        assertThat(LogMaskingPatterns.mask("contact user@example.com now"))
                .isEqualTo("contact u***@example.com now");
        assertThat(LogMaskingPatterns.mask("a.b@sub.example.co.kr"))
                .doesNotContain("a.b@")
                .contains("@sub.example.co.kr");
        assertThat(LogMaskingPatterns.mask("id worker-1@my-domain.example.io"))
                .doesNotContain("worker-1@")
                .contains("@my-domain.example.io");
    }

    // --------------------------------------- 절삭 경계 자격증명 부분 누출 (CWE-532)

    @Test
    @DisplayName("절삭_경계에_걸친_자격증명이_반토막으로_누출되지_않음")
    void credentialStraddlingTheTruncationBoundaryIsNotPartiallyLeaked() {
        // given: 자격증명이 정확히 64KB 경계에 걸치도록 배치한다.
        //  수정 전에는 {"password":"SE 까지만 잘려 규칙이 매칭에 실패하고 조각이 그대로 남았다.
        String head = alphanumericRun(LogMaskingPatterns.MAX_MASK_LENGTH - 15);
        String input = head + " {\"password\":\"SECRETVALUE\"}";

        // when
        String masked = LogMaskingPatterns.mask(input);

        // then: 잘린 앞조각("SE" 등)이 남지 않는다.
        assertThat(masked).contains("chars truncated").doesNotContain("SECRETVALUE");
        assertThat(masked.substring(masked.length() - 60)).doesNotContain("SE");
    }

    @Test
    @DisplayName("공백_없이_경계에_걸친_JSON_자격증명도_따옴표_균형으로_되감아_차단")
    void quoteBalancedRewindBlocksLeakWhenNoWhitespaceIsAvailable() {
        // given: 공백이 전혀 없어 1단계(공백 되감기)로는 되감을 수 없는 형태.
        String head = "{\"a\":\"" + alphanumericRun(LogMaskingPatterns.MAX_MASK_LENGTH - 30) + "\",";
        String input = head + "\"password\":\"SECRETVALUE\"}";

        // when
        String masked = LogMaskingPatterns.mask(input);

        // then
        assertThat(masked).contains("chars truncated").doesNotContain("SECRETVALUE");
    }

    @Test
    @DisplayName("절삭_되감기가_진단정보를_과도하게_버리지_않음")
    void truncationRewindKeepsMostOfTheDiagnosticPrefix() {
        // given: 되감을 경계가 전혀 없는 순수 런 — 되감기 창(1KB) 이상을 버리면 안 된다.
        String input = alphanumericRun(LogMaskingPatterns.MAX_MASK_LENGTH + 5_000);

        // when
        String masked = LogMaskingPatterns.mask(input);

        // then
        assertThat(masked.length())
                .isGreaterThan(LogMaskingPatterns.MAX_MASK_LENGTH - 1_100);
    }

    @Test
    @DisplayName("두_마스커_구현이_동일_규칙_사용_드리프트_없음")
    void bothMaskerImplementationsShareTheSameRules() {
        String input = "password=pw1 010-1234-5678 user@example.com X-Access-Token: TV1";
        String viaLayout = new MaskingPatternLayout().mask(input);
        String viaJson = new MaskingJsonValueMasker().maskString(input);

        assertThat(viaLayout).isEqualTo(viaJson).isEqualTo(LogMaskingPatterns.mask(input));
    }

    // ---------------------------------------------------------------- helpers

    private static String alphanumericRun(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(i % alphabet.length()));
        }
        return sb.toString();
    }

    /** JIT 예열 — 측정값이 인터프리터 구간에 좌우되지 않게 한다. */
    private static void warmUp() {
        String sample = alphanumericRun(500);
        for (int i = 0; i < 300; i++) {
            LogMaskingPatterns.mask(sample);
        }
    }

    private static long bestOfThreeMillis(String input) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            LogMaskingPatterns.mask(input);
            best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
        }
        return best;
    }
}

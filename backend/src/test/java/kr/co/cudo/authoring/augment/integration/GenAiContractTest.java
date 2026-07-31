package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.integration.dto.GenAiContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「생성형 AI API 연동명세서 v1.1」 §3.2/§3.3 코드 공간 가드.
 *
 * <p>상태 화이트리스트가 {@link LsDataAugJob} 의 저장 코드와 갈라지면, 조회는 통과했는데 DB 에는
 * 못 넣거나(또는 그 반대) 하는 조용한 불일치가 생긴다. 두 곳이 같은 코드 공간임을 테스트로 못박는다.
 *
 * <h3>드리프트 감시는 <b>status 축만으로 부족하다</b> (DEV_FIX MED-5)</h3>
 * <p>구 테스트는 {@code status} 만 비교하고 {@code error_code} 는 아예 검사하지 않아,
 * 목(계약 정본)에만 있는 3건({@code REQUEST_NOT_FOUND}/{@code CALLBACK_FAILED}/
 * {@code INTERNAL_SERVER_ERROR})이 우리 화이트리스트에서 빠진 채로 통과했다. 그 코드가 오면
 * 정상 등록 코드인데도 "unknown error_code" WARN 이 뜬다(운영 오탐).
 *
 * <p>그래서 이 테스트는 <b>목 파일({@code mock-server/app/schemas/genai.py})을 직접 파싱</b>해
 * 두 축(status/error_code)을 모두 대조한다. 목은 <b>읽기 전용</b>이며 여기서 수정하지 않는다.
 */
class GenAiContractTest {

    /** 계약 정본 — 벤더 목 스키마. 저작도구 모노레포에 함께 커밋되어 있다. */
    private static final Path MOCK_SCHEMA =
            Path.of("..", "mock-server", "app", "schemas", "genai.py");

    /** {@code NAME = "VALUE"} 형태의 파이썬 Enum 멤버. */
    private static final Pattern ENUM_MEMBER = Pattern.compile("=\\s*\"([^\"]+)\"");

    @Test
    @DisplayName("상태_코드_공간이_LsDataAugJob_과_일치한다")
    void statusSpaceMatchesEntity() {
        assertThat(GenAiContract.knownStatuses()).isEqualTo(Set.of(
                LsDataAugJob.STTS_RECEIVED, LsDataAugJob.STTS_RUNNING,
                LsDataAugJob.STTS_SUCCEEDED, LsDataAugJob.STTS_FAILED,
                LsDataAugJob.STTS_CANCELED));
    }

    @Test
    @DisplayName("상태_코드_공간이_목_계약정본과_일치한다")
    void statusSpaceMatchesMockContract() throws IOException {
        assertThat(GenAiContract.knownStatuses())
                .as("mock-server JobStatus 와 드리프트가 있으면 조회가 계약 밖 status 를 만난다")
                .isEqualTo(parseEnumValues("JobStatus"));
    }

    /**
     * {@code error_code} 축 드리프트 — 우리 목록에 없으면 <b>정상 등록 코드가 미지로 오탐</b>된다.
     * (판정 축은 status 라 응답을 실패시키지는 않지만, 운영이 벤더 장애를 오독하게 만든다.)
     */
    @Test
    @DisplayName("오류코드_공간이_목_계약정본과_일치한다")
    void errorCodeSpaceMatchesMockContract() throws IOException {
        assertThat(GenAiContract.knownErrorCodes())
                .as("mock-server ErrorCode 와 드리프트가 있으면 등록 코드가 unknown 으로 오탐된다")
                .isEqualTo(parseEnumValues("ErrorCode"));
    }

    @Test
    @DisplayName("목에만_있던_3건이_등록_코드로_인식된다")
    void recognizesPreviouslyMissingErrorCodes() {
        assertThat(GenAiContract.isKnownErrorCode("REQUEST_NOT_FOUND")).isTrue();
        assertThat(GenAiContract.isKnownErrorCode("CALLBACK_FAILED")).isTrue();
        assertThat(GenAiContract.isKnownErrorCode("INTERNAL_SERVER_ERROR")).isTrue();
    }

    @Test
    @DisplayName("미지의_status_와_media_type_은_화이트리스트에서_거부된다")
    void rejectsUnknownCodes() {
        assertThat(GenAiContract.isKnownStatus("PARTIALLY_DONE")).isFalse();
        assertThat(GenAiContract.isKnownStatus(null)).isFalse();
        assertThat(GenAiContract.isKnownMediaType("AUDIO")).isFalse();
        assertThat(GenAiContract.isKnownMediaType("IMAGE")).isTrue();
    }

    @Test
    @DisplayName("job_id_형식_검증은_경로조작_문자를_거부한다")
    void rejectsMalformedJobId() {
        assertThat(GenAiContract.isValidJobId("genai-0001")).isTrue();
        assertThat(GenAiContract.isValidJobId("../../etc/passwd")).isFalse();
        assertThat(GenAiContract.isValidJobId("job/1")).isFalse();
        assertThat(GenAiContract.isValidJobId("job 1")).isFalse();
        assertThat(GenAiContract.isValidJobId("")).isFalse();
        assertThat(GenAiContract.isValidJobId(null)).isFalse();
        assertThat(GenAiContract.isValidJobId("j".repeat(201))).isFalse();
    }

    /**
     * DEV_FIX LOW-7 (CWE-22) — 구 화이트리스트는 {@code .} 가 리터럴이라 <b>점만으로 이뤄진 세그먼트</b>가
     * 통과했다. {@code .} 는 RFC 3986 unreserved 라 URI 템플릿이 인코딩하지 않고 {@code URI.create} 는
     * 정규화하지 않으므로, 요청 라인에 {@code /api/genai/jobs/../results} 가 그대로 실려 정규화하는
     * 서버·프록시에서 한 단계 상승한다.
     *
     * <p>기존 테스트({@code "../../secret"})는 점이 아니라 {@code /} 때문에 거부돼 <b>거짓 안심</b>을 줬다.
     */
    @Test
    @DisplayName("점만으로_이뤄진_job_id_는_거부된다")
    void rejectsDotOnlyJobId() {
        assertThat(GenAiContract.isValidJobId("..")).as("슬래시 없이도 한 단계 상승한다").isFalse();
        assertThat(GenAiContract.isValidJobId(".")).isFalse();
        assertThat(GenAiContract.isValidJobId("...")).isFalse();
        // 점을 포함하되 점만은 아닌 정상 id 는 계속 허용한다(과잉 차단 금지).
        assertThat(GenAiContract.isValidJobId("job.1")).isTrue();
        assertThat(GenAiContract.isValidJobId("..job")).isTrue();
    }

    // --- helpers ---

    /** 목 스키마의 파이썬 Enum 클래스 본문에서 값 집합을 뽑는다. */
    private Set<String> parseEnumValues(String enumClassName) throws IOException {
        assertThat(Files.isReadable(MOCK_SCHEMA))
                .as("계약 정본(%s)을 읽을 수 있어야 한다", MOCK_SCHEMA.toAbsolutePath())
                .isTrue();
        String source = Files.readString(MOCK_SCHEMA, StandardCharsets.UTF_8);
        int start = source.indexOf("class " + enumClassName + "(");
        assertThat(start).as("목 스키마에 %s 정의가 있어야 한다", enumClassName).isNotNegative();
        int end = source.indexOf("\nclass ", start + 1);
        String body = end < 0 ? source.substring(start) : source.substring(start, end);

        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = ENUM_MEMBER.matcher(body);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        assertThat(values).as("%s 값 파싱 실패", enumClassName).isNotEmpty();
        return values;
    }
}

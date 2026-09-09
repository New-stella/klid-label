package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Quartz 잡 정의 덮어쓰기가 꺼져 있음</b>을 고정하는 가드 — 실제 yml·배포 템플릿을 읽어 판정한다.
 * [@design ADR-057]
 *
 * <h3>★★ 이 값이 켜지면 무엇이 달라지나 (값만 보지 말고 이 문단을 먼저 읽어라)</h3>
 * <p>{@code spring.quartz.overwrite-existing-jobs} 는 <b>기본값이 꺼짐</b>이고, 그래서
 * <b>이미 저장소에 등록된 잡 정의는 새 배포에서도 갱신되지 않는다</b>. AI 장비 상태점검을 계통별로
 * 가르면서 <b>추론 잡·트리거의 이름을 새로 준 유일한 이유</b>가 이것이다 — 옛 이름을 그대로 쓰면
 * 이미 등록된 정의가 갱신되지 않아 계통 표식이 영원히 비고, 그러면 계통 구분이 <b>새로 설치하는
 * 환경에서만</b> 성립한다.
 *
 * <p>즉 이 스위치가 켜지는 순간 그 전제가 통째로 달라진다. 그리고 <b>더 나쁜 것은 파급 범위</b>다 —
 * 그 스위치는 이 잡 하나가 아니라 <b>전 잡</b>에 걸려, 기동할 때마다 <b>다른 잡의 정의·주기까지 함께
 * 갈아엎는다</b>(운영자가 저장소에서 조정해 둔 값이 배포 때마다 조용히 되돌아간다).
 *
 * <h3>왜 별도 가드가 필요한가 — 등록 규칙 시험은 이 축을 잡지 못한다</h3>
 * <p>{@code AiSrvrHealthPollJobRegistrationTest} 는 「이미 있으면 건드리지 않는다」는 등록 규칙을
 * <b>시험 안에서 재구현</b>한 모델 시험이라, 누가 이 속성을 켜도 <b>그대로 초록</b>이다. 그 시험이
 * 재현하는 전제가 실제 설정과 어긋나지 않는지는 <b>설정을 직접 읽어야만</b> 알 수 있다.
 *
 * <p>프로퍼티를 시험에서 주입하는 방식으로는 실 yml 회귀를 잡지 못하므로
 * {@link MainResourceYaml} 로 운영 yml 원본을 그대로 읽는다(이 패키지의 다른 설정 가드와 같은 관례).
 *
 * <p>⚠ <b>이 가드를 「값이 없으니 의미 없다」며 지우지 말 것</b> — 이 가드가 지키는 것은 <b>없다는
 * 사실 그 자체</b>다. 덮어쓰기가 필요해지는 날이 오면 그때는 상태점검 잡 이름 전략과 다른 잡들의
 * 정의·주기 관리 방식을 <b>함께</b> 다시 정해야 하며, 그 판단 없이 한 줄로 켜지는 것을 막는 것이
 * 이 시험의 목적이다.
 */
class QuartzJobOverwriteConfigGuardTest {

    private static final String COMMON_YML = "application.yml";

    /** 정규 키. 켜지면 이 라운드가 세운 전제가 통째로 달라진다. */
    private static final String OVERWRITE_KEY = "spring.quartz.overwrite-existing-jobs";

    /**
     * 느슨한 바인딩(relaxed binding) 대응 — 같은 속성을 {@code overwriteExistingJobs} ·
     * {@code OVERWRITE_EXISTING_JOBS} 로도 쓸 수 있어 정규 키만 보면 <b>표기만 바꿔 우회</b>된다.
     */
    private static final Pattern ANY_SPELLING =
            Pattern.compile("(?i)overwrite[-_]?existing[-_]?jobs");

    /** 온프렘 배포 환경변수 템플릿(모듈 밖 — Gradle test 작업 디렉토리는 backend 모듈 루트). */
    private static final Path ENV_TEMPLATE =
            Paths.get("../deploy/onprem/config/backend/env.template");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application.yml", "application-local.yml", "application-dev.yml",
            "application-stg.yml", "application-prd.yml"})
    @DisplayName("★★어느_프로파일_yml도_잡_정의_덮어쓰기를_켜지_않는다")
    void 어느_프로파일_yml도_잡_정의_덮어쓰기를_켜지_않는다(String profileYml) {
        // ① 정규 키가 선언돼 있지 않다(= 기본값 꺼짐).
        assertThat(MainResourceYaml.keys(profileYml))
                .as("""
                        %s 가 %s 를 선언했다.
                        이 스위치는 <전 잡>에 걸려 기동할 때마다 다른 잡의 정의·주기까지 갈아엎고,
                        AI 장비 상태점검의 추론 잡에 <새 이름>을 준 전제(이미 등록된 정의는 덮이지 않는다)를
                        통째로 무너뜨린다. 켜야 한다면 잡 이름 전략과 다른 잡들의 정의 관리 방식을
                        함께 다시 정한 뒤에 켤 것 — 한 줄로 켤 값이 아니다.""",
                        profileYml, OVERWRITE_KEY)
                .doesNotContain(OVERWRITE_KEY);

        // ② 표기만 바꿔 우회한 선언도 없다(느슨한 바인딩).
        assertThat(ANY_SPELLING.matcher(read(MAIN_RESOURCES.resolve(profileYml))).find())
                .as("%s 에 잡 정의 덮어쓰기 속성이 <다른 표기>로 선언돼 있다(느슨한 바인딩으로 먹는다)",
                        profileYml)
                .isFalse();
    }

    /**
     * ★ 실효값 확인 — 키 부재만 보면 <b>공통 yml 이 켜고 프로파일이 지우지 않은</b> 조합을 놓친다.
     *
     * <p>공통 → 프로파일 순으로 얹은 실효 설정에서 이 값이 참이면 안 된다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-local.yml", "application-dev.yml",
            "application-stg.yml", "application-prd.yml"})
    @DisplayName("★공통을_얹은_실효_설정에서도_덮어쓰기가_켜져_있지_않다")
    void 공통을_얹은_실효_설정에서도_덮어쓰기가_켜져_있지_않다(String profileYml) {
        Environment env = MainResourceYaml.environment(COMMON_YML, profileYml);

        assertThat(env.getProperty(OVERWRITE_KEY))
                .as("%s 의 실효값 — 켜지면 배포 때마다 전 잡의 정의·주기가 갈아엎힌다", profileYml)
                .isNotEqualTo("true");
    }

    /**
     * ★ 배포 템플릿 축 — yml 이 깨끗해도 <b>환경변수로 켤 수 있다</b>.
     *
     * <p>스프링은 {@code SPRING_QUARTZ_OVERWRITE_EXISTING_JOBS} 환경변수를 같은 속성으로 읽으므로,
     * 템플릿에 그 항목이 실리면 현장에서 그대로 켜진다. yml 만 보는 가드는 그 경로를 못 본다.
     */
    @Test
    @DisplayName("★배포_환경변수_템플릿도_덮어쓰기를_싣지_않는다")
    void 배포_환경변수_템플릿도_덮어쓰기를_싣지_않는다() {
        assertThat(Files.isReadable(ENV_TEMPLATE))
                .as("배포 환경변수 템플릿을 찾을 수 없다: %s", ENV_TEMPLATE)
                .isTrue();

        assertThat(ANY_SPELLING.matcher(read(ENV_TEMPLATE)).find())
                .as("""
                        env.template 이 잡 정의 덮어쓰기를 싣고 있다.
                        환경변수로 켜지면 yml 은 깨끗한데 현장만 다르게 도는 상태가 되고,
                        그 차이는 배포 로그에 남지 않아 아무도 알아채지 못한다.""")
                .isFalse();
    }

    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }
}

package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [design: ADR-049] 폐지된 시계열 위탁 토글({@code vlm.client.enabled}) 회귀 가드.
 *
 * <h3>무엇이 폐지됐나</h3>
 * <p>토글이 꺼져 있으면 위탁 단계가 외부 호출 없이 즉시 건너뛴 것으로 처리되고 아무 기록도 남지
 * 않았다. 그런데 공통 기본값이 비활성이고 배포 템플릿도 비활성이며 stg/prd 프로파일에는 활성화
 * 설정 자체가 없어, <b>납품본이 시계열이 꺼진 채로 나가고 그 사실이 산출물에도 이력에도 드러나지
 * 않았다.</b> 미연동 판정은 이제 <b>연동 주소 주입 여부</b>가 담당하고, 벤더 미연동 구간의 운영은
 * 사람이 사유를 남기고 누르는 단계 스킵이 담당한다.
 *
 * <p>토글이 되살아나면 그 조용한 경로가 함께 되살아나므로 <b>코드·설정 양쪽에서 0건</b>을 고정한다.
 *
 * <h3>★ 스캔 제외 (Critical — 늘리지 말 것)</h3>
 * <ul>
 *   <li><b>이 파일 자신</b> — 폐지된 키 이름을 담아야 하는 곳이라 자기 자신을 잡을 수 없다.</li>
 *   <li><b>{@link VlmTimeseriesStep} 의 {@code SKIP_REASON_DISABLED} 상수 선언</b> — 그 상수의
 *       <b>값</b>에 폐지된 키 이름이 들어 있으나, 이미 적재된 {@code LS_BATCH_PROC_LOG} 행의 판독
 *       키라 <b>삭제도 문구 변경도 하지 않는다</b>. 그래서 그 파일만 "상수 선언 줄을 제외한 나머지"
 *       를 판정한다(파일 통째 제외가 아니다 — 그러면 그 파일에 토글 분기가 되살아나도 못 잡는다).</li>
 *   <li>{@code src/test/resources/db-archive/**} — 스쿼시 이전 마이그레이션 원문 아카이브.</li>
 * </ul>
 * <p>{@code docs/**} 와 과거 검증 기록은 애초에 스캔 대상이 아니다 — 실측 당시의 실제 형상을
 * 서술하는 것이 정상이기 때문이다.
 */
class VlmEnabledToggleRemovalTest {

    /** 폐지된 설정 키(프로퍼티 표기). 이 문자열은 backend/src 에서 아래 예외 두 곳에만 존재해야 한다. */
    private static final String ABOLISHED_PROPERTY_KEY = "vlm.client.enabled";
    /** 폐지된 설정 키(환경변수 표기). */
    private static final String ABOLISHED_ENV_KEY = "VLM_CLIENT_ENABLED";

    private static final Path BACKEND_SRC = Paths.get("src");
    /** 이 가드 자신 — 폐지된 키 이름을 담아야 하므로 스캔에서 제외한다. */
    private static final String SELF_FILE_NAME = "VlmEnabledToggleRemovalTest.java";
    /** 스쿼시 이전 마이그레이션 원문 아카이브 — 과거 기록이라 판정 대상이 아니다. */
    private static final String ARCHIVE_SEGMENT = "db-archive";
    /** 존치 상수(과거 행 판독 키)를 가진 파일 — 그 <b>선언 줄만</b> 면제한다. */
    private static final String SKIP_REASON_HOLDER = "VlmTimeseriesStep.java";
    private static final String SKIP_REASON_FIELD = "SKIP_REASON_DISABLED";

    private static final List<String> CONFIG_FILES = List.of(
            "application.yml", "application-local.yml", "application-dev.yml",
            "application-stg.yml", "application-prd.yml");

    @Test
    @DisplayName("폐지된_시계열_위탁_토글이_백엔드_소스에_남아있지_않다")
    void abolishedToggleIsAbsentFromBackendSources() {
        // given: backend/src 전체(java·yml·sql). file(1) 오판으로 조용히 건너뛰는 grep 대신 직접 읽는다.
        List<Path> scanned = backendSourceFiles();

        // when
        List<String> offenders = new ArrayList<>();
        for (Path file : scanned) {
            for (String line : read(file).split("\\R", -1)) {
                if (!line.contains(ABOLISHED_PROPERTY_KEY) && !line.contains(ABOLISHED_ENV_KEY)) {
                    continue;
                }
                if (isExemptSkipReasonDeclaration(file, line)) {
                    continue;
                }
                offenders.add(file + " :: " + line.strip());
            }
        }

        // then: 스캔이 실제로 돌았음을 먼저 확인한다(0건 스캔이 통과로 보이는 것을 막는다).
        assertThat(scanned)
                .as("스캔 대상 파일이 없다 — 작업 디렉토리 전제(backend 모듈 루트)가 깨졌다")
                .hasSizeGreaterThan(500);
        assertThat(offenders)
                .as("폐지된 토글(%s / %s)이 남아 있다 — 조용한 건너뛰기 경로가 되살아난다: %s",
                        ABOLISHED_PROPERTY_KEY, ABOLISHED_ENV_KEY, offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("폐지된_토글_설정키가_어느_프로파일에도_존재하지_않는다")
    void abolishedTogglePropertyIsGoneFromAllProfiles() {
        // given / when / then: yml 에만 남으면 아무도 읽지 않는 죽은 손잡이가 되고, 운영자는 그것을
        //   켜면 위탁이 도는 줄 안다(설정은 바뀌는데 동작은 그대로 — 이 저장소의 반복 결함 형태).
        for (String yml : CONFIG_FILES) {
            assertThat(MainResourceYaml.rawValue(yml, ABOLISHED_PROPERTY_KEY))
                    .as("%s 에 폐지된 토글(%s)이 남아 있다", yml, ABOLISHED_PROPERTY_KEY)
                    .isNull();
        }
        assertThat(MainResourceYaml.environment("application.yml", "application-dev.yml")
                .getProperty(ABOLISHED_PROPERTY_KEY))
                .as("어떤 프로파일 조합으로도 해석되지 않아야 한다")
                .isNull();
    }

    /**
     * 존치 상수는 그대로 살아 있어야 한다 — 이미 적재된 감사 행의 판독 키다.
     *
     * <p>가드가 "0건"만 요구하면 다음 사람이 이 상수까지 지워 과거 행을 판독할 수 없게 만든다.
     * 재개 대상 판정 축({@code RESUMABLE_SKIP_REASONS})에 <b>들어가지 않는 것</b>도 함께 고정한다 —
     * 넣으면 재개 러너가 과거 비활성 구간의 영상을 재위탁 후보로 집는다.
     */
    @Test
    @DisplayName("과거행_판독용_보류사유_상수는_존치되고_재개목록에는_들어가지_않는다")
    void legacySkipReasonConstantSurvivesOutsideResumeList() {
        assertThat(VlmTimeseriesStep.RESUMABLE_SKIP_REASONS)
                .as("재개 목록이 비면 판정 축 자체가 사라진 것이다")
                .isNotEmpty()
                .noneSatisfy(reason -> assertThat(reason).contains(ABOLISHED_PROPERTY_KEY));
    }

    /** {@code SKIP_REASON_DISABLED} 상수 <b>선언 줄</b>만 면제한다(그 파일의 나머지 줄은 판정 대상). */
    private static boolean isExemptSkipReasonDeclaration(Path file, String line) {
        return file.getFileName().toString().equals(SKIP_REASON_HOLDER)
                && line.contains(SKIP_REASON_FIELD)
                && line.contains("=");
    }

    private List<Path> backendSourceFiles() {
        assertThat(Files.isDirectory(BACKEND_SRC))
                .as("스캔 대상 디렉토리(%s)가 존재해야 한다", BACKEND_SRC.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.walk(BACKEND_SRC)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(VlmEnabledToggleRemovalTest::isTextSource)
                    .filter(p -> !p.getFileName().toString().equals(SELF_FILE_NAME))
                    .filter(p -> !p.toString().contains(ARCHIVE_SEGMENT))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("backend/src 스캔 실패", e);
        }
    }

    private static boolean isTextSource(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".sql");
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }
}

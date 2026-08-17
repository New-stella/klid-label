package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.dev.controller.DevAutolabelTestController;
import kr.co.cudo.authoring.dev.service.DevAutolabelTestService;
import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * dev 업로드 경로 개명 회귀 가드 — 구 이름 {@code autolabel-test} 가 되살아나는 것을 차단한다.
 *
 * <h3>무엇이 바뀌었나 (3축 + 설정 키)</h3>
 * <ul>
 *   <li>BE API: {@code /v1/dev/autolabel-test} → <b>{@code /v1/dev/upload}</b></li>
 *   <li>화면 URL: {@code /dev/autolabel-test} → <b>{@code /dev/upload}</b> (FE 가드는 vitest 쪽)</li>
 *   <li>저장 서브디렉터리: {@code autolabel-test} → <b>{@code dev-upload}</b></li>
 *   <li>설정 키: {@code authoring.dev.autolabel-test.max-file-size}
 *       → <b>{@code authoring.dev.upload.max-file-size}</b></li>
 * </ul>
 *
 * <p>이 화면이 올린 영상으로 확인하는 것은 오토라벨만이 아니라 적재·비식별·마킹까지의 전 구간이라,
 * 이름을 역할(영상 업로드)에 맞췄다. 구 경로는 <b>별칭·리다이렉트 없이 폐기</b>했다 — dev 토글로
 * 게이팅되는 내부 endpoint 라 관제·외부 호출자가 없고, 별칭을 두면 구 이름이 영구히 남아 개명
 * 목적 자체가 사라진다. <b>구 경로 404 가 정상 동작이다.</b>
 *
 * <h3>클래스명·파일명은 개명 대상이 아니다</h3>
 * <p>{@code DevAutolabelTestController}·{@code DevAutolabelTestService}·{@code AutolabelTestResponse}
 * 등 Java 심볼과 파일 경로는 <b>그대로 둔다</b>(같은 파일들을 건드리는 후속 작업의 diff 를 읽을 수
 * 있게 유지하기 위한 의도적 범위 제한). 그래서 이 가드는 <b>케밥 표기 {@code autolabel-test} 만</b>
 * 본다 — 카멜 표기 심볼({@code AutolabelTest...})은 애초에 매칭되지 않는다.
 *
 * <h3>스캔 제외 (Critical — 늘리지 말 것)</h3>
 * <ul>
 *   <li><b>이 파일 자신</b> — 구 이름을 설명해야 하는 유일한 곳이다(자기 자신을 잡는 가드는 못 쓴다).
 *       구 이름은 여기 한 곳에만 두어, 소스 전역에서 0건을 유지한다.</li>
 *   <li>{@code src/test/resources/db-archive/**} — 스쿼시 이전 마이그레이션 원문 아카이브(과거 기록이라
 *       소급 수정 대상이 아니다). 현재 이 경로에 구 문자열은 없지만, 아카이브를 판정 대상으로 삼는 것
 *       자체가 틀렸으므로 명시적으로 제외한다.</li>
 * </ul>
 * <p>{@code docs/**} 는 애초에 스캔 대상이 아니다 — 변경 이력·과거 검증 기록(실측 당시의 실제 경로)이
 * 구 이름을 언급하는 것이 정상이기 때문이다.
 */
class DevUploadPathRenameGuardTest {

    /** 구 이름(케밥 표기). 이 문자열은 소스 전역에서 이 파일에만 존재해야 한다. */
    private static final String OLD_NAME = "autolabel-test";
    private static final String NEW_API_PATH = "/v1/dev/upload";
    private static final String NEW_CONFIG_KEY = "authoring.dev.upload.max-file-size";
    private static final String OLD_CONFIG_KEY = "authoring.dev." + OLD_NAME + ".max-file-size";

    private static final Path BACKEND_SRC = Paths.get("src");
    /** 이 가드 자신 — 구 이름을 담아야 하므로 스캔에서 제외한다. */
    private static final String SELF_FILE_NAME = "DevUploadPathRenameGuardTest.java";
    /** 스쿼시 이전 마이그레이션 원문 아카이브 — 과거 기록이라 판정 대상이 아니다. */
    private static final String ARCHIVE_SEGMENT = "db-archive";

    private static final List<String> CONFIG_FILES = List.of(
            "application.yml", "application-local.yml", "application-dev.yml",
            "application-stg.yml", "application-prd.yml");

    @Test
    @DisplayName("구_경로_이름이_백엔드_소스에_남아있지_않다")
    void oldNameIsAbsentFromBackendSources() {
        // given: backend/src 전체(java·yml·sql — 아카이브와 이 가드 자신만 제외)
        List<Path> scanned = backendSourceFiles();

        // when
        List<String> offenders = new ArrayList<>();
        for (Path file : scanned) {
            String content = read(file);
            if (content.contains(OLD_NAME)) {
                offenders.add(file.toString());
            }
        }

        // then: 스캔이 실제로 돌았음을 먼저 확인한다(0건 스캔이 통과로 보이는 것을 막는다)
        assertThat(scanned)
                .as("스캔 대상 파일이 없다 — 작업 디렉토리 전제(backend 모듈 루트)가 깨졌다")
                .hasSizeGreaterThan(500);
        assertThat(offenders)
                .as("구 이름 '%s' 이 백엔드 소스에 남아 있다(경로·설정키·저장 디렉터리 어디든): %s",
                        OLD_NAME, offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("컨트롤러가_새_경로에_매핑되고_구_경로는_404다")
    void controllerIsMappedToNewPathAndOldPathIsNotFound() throws Exception {
        // given: 매핑만 판정하므로 보안 필터 없는 standalone MockMvc 를 쓴다.
        //   (실컨텍스트는 SecurityConfig 의 /v1/dev/** 매처가 매핑 부재와 무관하게 401/403 을 내
        //    "구 경로가 사라졌다"를 판정할 수 없다 — 인가 응답이 매핑 부재를 가린다.)
        DevAutolabelTestController controller =
                new DevAutolabelTestController(mock(DevAutolabelTestService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        // then(1): 선언된 매핑이 새 경로다
        RequestMapping mapping = DevAutolabelTestController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value())
                .as("dev 업로드 엔드포인트는 %s 하나여야 한다", NEW_API_PATH)
                .containsExactly(NEW_API_PATH);

        // then(2): 새 경로는 핸들러가 있다 — 파트 누락으로 400 이며 404 가 아니다
        MvcResult matched = mvc.perform(multipart(NEW_API_PATH)).andReturn();
        assertThat(matched.getResponse().getStatus())
                .as("%s 에 핸들러가 매핑되어 있어야 한다(404 면 매핑 소실)", NEW_API_PATH)
                .isNotEqualTo(404);

        // then(3): 구 경로는 404 — 하위호환 별칭·리다이렉트를 두지 않는다
        MvcResult old = mvc.perform(multipart("/v1/dev/" + OLD_NAME)).andReturn();
        assertThat(old.getResponse().getStatus())
                .as("구 경로에 별칭·리다이렉트를 두지 않는다(두면 구 이름이 영구히 남는다)")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("업로드_한도_설정키가_새_이름으로_선언되고_구_키는_남아있지_않다")
    void uploadSizeLimitKeyIsRenamed() {
        // given
        Set<String> declared = new LinkedHashSet<>();
        CONFIG_FILES.forEach(file -> declared.addAll(MainResourceYaml.keys(file)));

        // then: 게이팅 키와 같은 prefix 로 합쳐졌는지 확인한다.
        //   두 키가 다른 prefix 로 갈려 있으면 한쪽만 고쳐도 조용히 기본값으로 동작한다.
        assertThat(declared)
                .as("업로드 한도 키는 %s 로 선언돼야 한다", NEW_CONFIG_KEY)
                .contains(NEW_CONFIG_KEY);
        assertThat(declared)
                .as("게이팅 키와 같은 prefix(authoring.dev.upload) 여야 한다")
                .contains("authoring.dev.upload.enabled");
        assertThat(declared)
                .as("구 설정 키 %s 가 yml 에 남아 있다 — 아무도 읽지 않는 손잡이가 된다", OLD_CONFIG_KEY)
                .doesNotContain(OLD_CONFIG_KEY);
    }

    private List<Path> backendSourceFiles() {
        assertThat(Files.isDirectory(BACKEND_SRC))
                .as("스캔 대상 디렉토리(%s)가 존재해야 한다", BACKEND_SRC.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.walk(BACKEND_SRC)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(DevUploadPathRenameGuardTest::isTextSource)
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

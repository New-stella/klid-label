package kr.co.cudo.authoring.transfer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.transfer.dto.ImportBrowseResponse;
import kr.co.cudo.authoring.transfer.service.ImportBrowseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 위치 탐색이 <b>무엇을 담고 무엇을 담지 않는지</b>를 실제 파일시스템 위에서 고정한다(AC-120).
 *
 * <h3>왜 통합 시험과 따로 두는가</h3>
 * <p>여기서 확인하는 것은 저장소의 표본 폴더로는 만들 수 없는 상황이다 — 밖을 가리키는 바로가기,
 * 점으로 시작하는 항목, 담는 개수 상한 초과. 표본을 그렇게 만들려면 저장소에 인공 폴더와 링크를
 * 늘려야 하고, 상한을 재현하려면 수천 개를 만들어야 한다. 그리고 이 창구가 지켜야 하는 것은 대부분
 * <b>담지 않는 것</b>이라 가짜 파일시스템으로 대신하면 그 규칙이 진짜 링크 위에서 성립하는지는
 * 아무도 확인하지 않은 채 통과한다.
 *
 * <h3>담는 개수 상한은 설정값이다</h3>
 * <p>잘림을 값싸게 재현하려고 작게 넣는다. 시험이 상한 값 자체를 고정하지는 않는다 — 그 값은
 * 근거가 없어 설정으로 뽑아 둔 것이라, 시험이 붙잡으면 배포로 조정할 수 없게 된다.
 *
 * @design DOMAIN-017
 * @design API-221
 * @design API-222
 * @design AC-120
 * @design AC-048
 */
class ImportBrowseServiceTest {

    private static final int MAX_ENTRIES = 5;

    @TempDir
    Path tempRoot;

    /** 허용 루트 <b>밖</b>. 바로가기가 여기를 가리켜도 목록에 담기면 안 된다. */
    @TempDir
    Path tempOutside;

    /**
     * 허용 루트 — 임시 폴더의 <b>실경로</b>다.
     *
     * <p>⚠ 표기 그대로 쓰면 안 된다. 임시 폴더 자체가 바로가기 뒤에 있는 플랫폼이 있고
     * (맥의 {@code /var} → {@code /private/var}), 그러면 판정기의 <b>표기 기준</b> 검사가 이 창구가
     * 돌려준 실경로를 범위 밖으로 본다. 그 성질은 판정기의 것이지 이 창구의 것이 아니므로, 표본을
     * 실경로로 고정해 플랫폼 차이를 걷어낸 뒤 창구의 규칙만 본다.
     *
     * <p>(운영에서 허용 루트를 바로가기 뒤에 두면 같은 일이 실제로 일어난다 — 판정기 소유 축이라
     * 여기서 고치지 않고 보고한다.)
     */
    private Path root;
    private Path outside;

    private ImportSourcePolicy policy;
    private ImportBrowseService service;

    @BeforeEach
    void setUp() throws IOException {
        root = tempRoot.toRealPath();
        outside = tempOutside.toRealPath();
        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                root.toString(), "", root.toString(), root.toString(), root.toString(), "co-locate");
        policy = new ImportSourcePolicy(resolver);
        service = new ImportBrowseService(policy);
        ReflectionTestUtils.setField(service, "maxEntries", MAX_ENTRIES);
    }

    // ------------------------------------------------------------------ 루트 목록

    @Test
    @DisplayName("위치를_지정하지_않으면_허용_저장소_루트_목록이_그대로_돌아온다")
    void 위치를_지정하지_않으면_허용_저장소_루트_목록이_그대로_돌아온다() {
        ImportBrowseResponse response = service.listFolders(null);

        // 검사·적재가 받아들이는 범위와 <같은 자리>에서 나온다 — 목록이 두 벌이면 한쪽만 넓어져
        // 한 창구에서 막히는 자리가 다른 창구에서 열린다(AC-048).
        assertThat(response.entries()).extracting(ImportBrowseResponse.Entry::path)
                .containsExactlyElementsOf(policy.readableRoots().stream().map(Path::toString).toList());
        // 기준 위치가 하나로 정해지지 않으므로 둘 다 비어 있다.
        assertThat(response.path()).isNull();
        assertThat(response.parent()).isNull();
        assertThat(response.truncated()).isFalse();
    }

    @Test
    @DisplayName("빈_문자열도_생략과_같게_루트_목록으로_답한다")
    void 빈_문자열도_생략과_같게_루트_목록으로_답한다() {
        // 화면이 입력칸을 비운 것과 아예 넣지 않은 것이 갈리면, 같은 조작에 다른 답이 돌아온다.
        assertThat(service.listFolders("   ").entries())
                .isEqualTo(service.listFolders(null).entries());
    }

    // ------------------------------------------------------------------ 폴더 탐색

    @Test
    @DisplayName("폴더_목록에는_폴더만_담기고_바로가기와_숨김과_파일은_빠진다")
    void 폴더_목록에는_폴더만_담기고_바로가기와_숨김과_파일은_빠진다() throws IOException {
        Path folder = handoverFixture();

        ImportBrowseResponse response = service.listFolders(folder.toString());

        assertThat(response.entries()).extracting(ImportBrowseResponse.Entry::name)
                // 이름 오름차순 고정 — 저장 장치 순서를 그대로 내보내면 다시 열 때 순서가 흔들린다.
                .containsExactly("alpha", "beta")
                // .hidden(숨김) · linkdir(바로가기) · 파일 5건은 담기지 않는다.
                .doesNotContain(".hidden", "linkdir", "a.mp4", "notes.txt");
        assertThat(response.truncated()).isFalse();
    }

    @Test
    @DisplayName("한_단계만_돌려주고_여러_단계를_한_번에_펼치지_않는다")
    void 한_단계만_돌려주고_여러_단계를_한_번에_펼치지_않는다() throws IOException {
        Path folder = handoverFixture();
        Files.createDirectories(folder.resolve("alpha").resolve("deeper"));

        assertThat(service.listFolders(folder.toString()).entries())
                .extracting(ImportBrowseResponse.Entry::name)
                .containsExactly("alpha", "beta")
                .doesNotContain("deeper");
    }

    @Test
    @DisplayName("돌려주는_위치는_표기가_아니라_실제로_닿는_자리다")
    void 돌려주는_위치는_표기가_아니라_실제로_닿는_자리다() throws IOException {
        Path folder = handoverFixture();

        // 판정과 훑기가 같은 경로를 써야 그 사이에 대상이 바뀌지 않는다(CWE-367).
        assertThat(service.listFolders(folder.toString()).path())
                .isEqualTo(folder.toRealPath().toString());
    }

    @Test
    @DisplayName("부모가_허용_루트여도_그_위치를_돌려준다_비우면_루트_바로_아래에서_갇힌다")
    void 부모가_허용_루트여도_그_위치를_돌려준다_비우면_루트_바로_아래에서_갇힌다() throws IOException {
        Path folder = handoverFixture();

        // ★이 케이스가 결함 가드다 — 루트의 자식에서 한 단계 위는 루트이고, 그 자리는 <범위 안>이라
        //   비우지 않는다. 비우면 화면의 「상위로」가 꺼져 루트 바로 아래로 한 번만 내려가도
        //   사람이 그 자리에 갇힌다(실제로 그렇게 만들었다가 잡혔다).
        assertThat(service.listFolders(folder.toString()).parent())
                .isEqualTo(root.toString());
        // 그보다 아래도 당연히 위로 갈 수 있다.
        assertThat(service.listFolders(folder.resolve("alpha").toString()).parent())
                .isEqualTo(folder.toRealPath().toString());
    }

    @Test
    @DisplayName("지금_자리가_허용_저장소_루트일_때만_위가_비어서_돌아온다")
    void 지금_자리가_허용_저장소_루트일_때만_위가_비어서_돌아온다() throws IOException {
        handoverFixture();

        // 루트에서 한 단계 위는 범위 밖이다 — 비어서 돌아오고, 화면은 그때 루트 목록으로 돌아간다.
        assertThat(service.listFolders(root.toString()).parent()).isNull();
    }

    @Test
    @DisplayName("돌려준_위가_비어_있지_않으면_그_값으로_한_단계씩_루트까지_올라갈_수_있다")
    void 돌려준_위가_비어_있지_않으면_그_값으로_한_단계씩_루트까지_올라갈_수_있다() throws IOException {
        Path deepest = handoverFixture().resolve("alpha");

        // 돌려준 위를 그대로 다시 넣는 왕복을 반복한다 — 한 번이라도 거부되거나 근거 없이 비면
        // 그 자리에서 길이 끊긴다. 루트에 닿아 비는 것만이 정상 종료다.
        String cursor = service.listFolders(deepest.toString()).parent();
        int hops = 0;
        while (cursor != null) {
            hops++;
            assertThat(hops).as("루트에 닿지 못하고 계속 올라간다 — 종료 조건이 깨졌다").isLessThan(10);
            cursor = service.listFolders(cursor).parent();
        }
        // alpha → handover → root 로 두 번 올라간 뒤 루트에서 비어야 한다.
        assertThat(hops).isEqualTo(2);
    }

    @Test
    @DisplayName("하위_항목이_상한을_넘으면_잘리고_잘렸다는_사실이_함께_돌아온다")
    void 하위_항목이_상한을_넘으면_잘리고_잘렸다는_사실이_함께_돌아온다() throws IOException {
        Path many = Files.createDirectories(root.resolve("many"));
        for (int i = 0; i < MAX_ENTRIES * 2; i++) {
            Files.createDirectories(many.resolve("d" + i));
        }

        ImportBrowseResponse response = service.listFolders(many.toString());

        assertThat(response.entries()).hasSize(MAX_ENTRIES);
        // 조용히 자르면 화면이 잘린 목록을 전부인 것으로 보여 준다(CWE-770).
        assertThat(response.truncated()).isTrue();
    }

    @Test
    @DisplayName("상한_이하면_잘리지_않았다고_답한다")
    void 상한_이하면_잘리지_않았다고_답한다() throws IOException {
        Path exact = Files.createDirectories(root.resolve("exact"));
        for (int i = 0; i < MAX_ENTRIES; i++) {
            Files.createDirectories(exact.resolve("d" + i));
        }

        ImportBrowseResponse response = service.listFolders(exact.toString());

        assertThat(response.entries()).hasSize(MAX_ENTRIES);
        // 딱 맞게 담긴 것을 잘렸다고 하면 화면이 없는 항목을 계속 찾게 된다.
        assertThat(response.truncated()).isFalse();
    }

    // ------------------------------------------------------------------ 파일 탐색

    @Test
    @DisplayName("영상_파일_목록에는_폴더와_비영상과_바로가기와_숨김이_섞이지_않는다")
    void 영상_파일_목록에는_폴더와_비영상과_바로가기와_숨김이_섞이지_않는다() throws IOException {
        Path folder = handoverFixture();

        ImportBrowseResponse response = service.listVideoFiles(folder.toString());

        assertThat(response.entries()).extracting(ImportBrowseResponse.Entry::name)
                // 대문자 확장자도 영상이다. 이름 오름차순 고정.
                .containsExactly("B.MOV", "a.mp4", "c.avi")
                .doesNotContain("notes.txt", ".secret.mp4", "linkvid", "alpha");
    }

    @Test
    @DisplayName("영상_파일_목록의_항목은_원본_영상_판정을_그대로_통과한다")
    void 영상_파일_목록의_항목은_원본_영상_판정을_그대로_통과한다() throws IOException {
        Path folder = handoverFixture();

        // 여기서 고른 값은 결국 적재가 다시 판정한다 — 통과할 수 없는 것을 내주면 사람이 고른
        // 뒤에야 거부를 받는다.
        for (ImportBrowseResponse.Entry entry : service.listVideoFiles(folder.toString()).entries()) {
            assertThat(policy.verifyVideoFile(entry.path())).isNotNull();
        }
    }

    @Test
    @DisplayName("위치를_생략하면_영상_파일_탐색은_거부한다")
    void 위치를_생략하면_영상_파일_탐색은_거부한다() {
        // 루트 목록 조회가 없는 창구라 기준 위치가 없으면 답할 것이 없다.
        assertThatThrownBy(() -> service.listVideoFiles(null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ------------------------------------------------------------------ 거부 (AC-048)

    @Test
    @DisplayName("허용_범위_밖_경로는_잘못된_입력으로_거부하고_메시지에_입력_원문을_담지_않는다")
    void 허용_범위_밖_경로는_잘못된_입력으로_거부하고_메시지에_입력_원문을_담지_않는다() throws IOException {
        Path elsewhere = Files.createDirectories(outside.resolve("elsewhere"));

        for (String path : List.of(elsewhere.toString(), root.resolve("../..").toString())) {
            assertThatThrownBy(() -> service.listFolders(path))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> {
                        assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                        // CWE-209 — 어디를 물었는지 응답이 되풀이하지 않는다.
                        assertThat(e.getMessage()).doesNotContain(path);
                    });
            assertThatThrownBy(() -> service.listVideoFiles(path))
                    .isInstanceOf(CustomException.class);
        }
    }

    @Test
    @DisplayName("루트_안의_바로가기가_밖을_가리키면_거부한다")
    void 루트_안의_바로가기가_밖을_가리키면_거부한다() throws IOException {
        Files.createDirectories(outside.resolve("secret"));
        Path escape = Files.createSymbolicLink(root.resolve("escape"), outside);

        // 표기만 보면 범위 안이다 — 실제로 닿는 자리로 다시 판정해야 걸린다(CWE-59).
        assertThatThrownBy(() -> service.listFolders(escape.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("허용_범위_안이지만_없는_경로는_찾을_수_없음으로_범위_밖_거부와_갈린다")
    void 허용_범위_안이지만_없는_경로는_찾을_수_없음으로_범위_밖_거부와_갈린다() {
        assertThatThrownBy(() -> service.listFolders(root.resolve("nope").toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("지정한_위치가_폴더가_아니면_거부한다")
    void 지정한_위치가_폴더가_아니면_거부한다() throws IOException {
        Path file = Files.writeString(root.resolve("a.mp4"), "a");

        assertThatThrownBy(() -> service.listVideoFiles(file.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ------------------------------------------------------------------ 관측

    /**
     * 길이로 걸러낸 항목은 목록에도 {@code truncated} 에도 나타나지 않는다 — 그래서 <b>건수만이라도</b>
     * 남는지 고정한다. 이 경고가 사라지면 "실재하는 폴더가 목록에 없다"는 문의를 확인할 방법이 없다.
     */
    @Test
    @DisplayName("길이로_걸러낸_항목이_있으면_건수를_경고로_남기고_경로_원문은_담지_않는다")
    void 길이로_걸러낸_항목이_있으면_건수를_경고로_남기고_경로_원문은_담지_않는다() throws IOException {
        Path folder = Files.createDirectories(root.resolve("longs"));
        // 이름 하나로 상한을 넘기기 어려우면 중첩해서 넘긴다 — 판정 상한은 <경로 전체> 길이다.
        Path deep = folder;
        String segment = "n".repeat(60);
        while (deep.resolve(segment).toString().length() <= ImportSourcePolicy.FOLDER_PATH_MAX) {
            deep = Files.createDirectories(deep.resolve(segment));
        }
        Path overLength = Files.createDirectories(deep.resolve(segment));

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            ImportBrowseResponse response = service.listFolders(deep.toString());

            // 기능은 그대로다 — 걸러내고, 개수 상한 축인 truncated 는 건드리지 않는다.
            assertThat(response.entries()).isEmpty();
            assertThat(response.truncated()).isFalse();

            List<String> warns = logs.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warns)
                    .as("조용히 빼지 않는다 — 건수가 서버에 남아야 문의를 확인할 수 있다")
                    .anyMatch(m -> m.contains("over-length") && m.contains("count=1"));
            // CWE-117/경로 노출 — 이름 조각도 경로도 로그에 실리지 않는다.
            assertThat(warns).noneMatch(m -> m.contains(segment));
            assertThat(warns).noneMatch(m -> m.contains(overLength.toString()));
        } finally {
            serviceLogger().detachAppender(logs);
        }
    }

    @Test
    @DisplayName("길이로_걸러낸_항목이_없으면_그_경고를_남기지_않는다")
    void 길이로_걸러낸_항목이_없으면_그_경고를_남기지_않는다() throws IOException {
        Path folder = handoverFixture();

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            service.listFolders(folder.toString());

            // 0 건일 때도 찍으면 정상 탐색마다 경고가 쌓여 진짜 신호가 묻힌다.
            assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                    .noneMatch(m -> m.contains("over-length"));
        } finally {
            serviceLogger().detachAppender(logs);
        }
    }

    private static ch.qos.logback.classic.Logger serviceLogger() {
        return (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ImportBrowseService.class);
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger().addAppender(appender);
        return appender;
    }

    // ------------------------------------------------------------------ 표본

    /**
     * 폴더·파일·바로가기·숨김이 섞인 자리.
     *
     * <pre>
     * {root}/handover/  alpha/ beta/ .hidden/
     *                   a.mp4 B.MOV c.avi notes.txt .secret.mp4
     *                   linkdir -> {outside}          (밖을 가리키는 바로가기)
     *                   linkvid -> {outside}/x.mp4
     * </pre>
     */
    private Path handoverFixture() throws IOException {
        Files.writeString(Files.createDirectories(outside).resolve("x.mp4"), "x");

        Path handover = Files.createDirectories(root.resolve("handover"));
        Files.createDirectories(handover.resolve("alpha"));
        Files.createDirectories(handover.resolve("beta"));
        Files.createDirectories(handover.resolve(".hidden"));
        Files.writeString(handover.resolve("a.mp4"), "a");
        Files.writeString(handover.resolve("B.MOV"), "b");
        Files.writeString(handover.resolve("c.avi"), "c");
        Files.writeString(handover.resolve("notes.txt"), "n");
        Files.writeString(handover.resolve(".secret.mp4"), "s");
        Files.createSymbolicLink(handover.resolve("linkdir"), outside);
        Files.createSymbolicLink(handover.resolve("linkvid"), outside.resolve("x.mp4"));
        return handover;
    }
}

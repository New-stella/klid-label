package kr.co.cudo.authoring.transfer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.transfer.dto.ImportBrowseResponse;
import kr.co.cudo.authoring.transfer.service.ImportBrowseService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <h3>한 쪽 크기와 살펴보는 상한은 설정값이다</h3>
 * <p>나눠서 이어 주는 것을 값싸게 재현하려고 작게 넣는다. 시험이 그 값 자체를 고정하지는 않는다 —
 * 근거가 없어 설정으로 뽑아 둔 것이라, 시험이 붙잡으면 배포로 조정할 수 없게 된다.
 *
 * <h3>⚠ 이 시험이 덮지 <b>못하는</b> 것 (덮은 척하지 않는다)</h3>
 * <ul>
 *   <li><b>이어받는 도중에 폴더 내용이 바뀌는 축.</b> 이어받을 자리보다 앞선 이름으로 항목이 새로
 *       생기면 그 탐색에서는 보이지 않는데(설계가 인지·수용한 대가), 시험으로 만들려면 두 요청
 *       사이에 다른 프로세스가 끼어들어야 해 결정적으로 재현되지 않는다.</li>
 * </ul>
 * <p>초록을 이 축의 근거로 읽지 말 것.
 *
 * <h3>✅ 구 서술 폐기 — 앞뒤 공백 축은 이제 덮는다</h3>
 * <p>구 서술은 <i>"이어받을 자리의 앞뒤 공백을 잘라 내지 않는다는 축은 그런 이름이 허용되는지가
 * 파일시스템마다 달라 결정적이지 않다"</i> 였다. <b>과했다</b> — POSIX(리눅스·맥)에서는 앞뒤에 공백을
 * 가진 이름도, 이름이 <b>공백뿐인</b> 이름도 만들 수 있고 실제로 만들어 덮는다. 만들 수 없는
 * 파일시스템에서는 통과가 아니라 <b>건너뜀</b>으로 답한다({@link Assumptions}) — 재현되지 않는 것을
 * 통과로 세지 않는다.
 *
 * @design DOMAIN-017
 * @design API-221
 * @design API-222
 * @design ADR-065
 * @design AC-120
 * @design AC-1079
 * @design AC-1080
 */
class ImportBrowseServiceTest {

    /** 한 번에 <담는> 수. */
    private static final int PAGE_SIZE = 5;

    /**
     * 한 번에 <b>살펴보는</b> 수 — {@link #PAGE_SIZE} 보다 넉넉해야 담는 쪽 상한이 <b>먼저</b> 걸리는
     * 상황을 만들 수 있다. 두 축이 각각 걸리는 자리를 따로 고정하므로 값이 겹치면 안 된다.
     */
    private static final int SCAN_LIMIT = 20;

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
     * <p>✅ 구 서술 폐기 — <i>"운영에서 허용 루트를 바로가기 뒤에 두면 같은 일이 실제로 일어난다 —
     * 판정기 소유 축이라 여기서 고치지 않고 보고한다"</i>. 그 결함은 닫혔다(ADR-065). 다만 <b>여기서
     * 실경로로 고정하는 것은 그대로 둔다</b> — 이 자리의 시험들은 창구의 담기·나누기 규칙을 보는 것이라
     * 루트 형상을 섞으면 무엇이 무엇을 고정하는지 흐려진다. 바로가기 루트 축은 아래 전용 절이 덮는다.
     */
    private Path root;
    private Path outside;

    /**
     * 허용 루트로 <b>선언할 바로가기</b>를 놓을 자리 — 설정에 적힌 표기와 실제로 닿는 자리가 갈리는
     * 형상을 플랫폼에 기대지 않고 직접 만든다(ADR-065).
     */
    @TempDir
    Path linkHome;

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
        ReflectionTestUtils.setField(service, "pageSize", PAGE_SIZE);
        ReflectionTestUtils.setField(service, "scanLimit", SCAN_LIMIT);
    }

    // ------------------------------------------------------------------ 루트 목록

    @Test
    @DisplayName("위치를_지정하지_않으면_허용_저장소_루트_목록이_그대로_돌아온다")
    void 위치를_지정하지_않으면_허용_저장소_루트_목록이_그대로_돌아온다() {
        ImportBrowseResponse response = service.listFolders(null, null);

        // 검사·적재가 받아들이는 범위와 <같은 자리>에서 나온다 — 목록이 두 벌이면 한쪽만 넓어져
        // 한 창구에서 막히는 자리가 다른 창구에서 열린다(AC-1080).
        assertThat(response.entries()).extracting(ImportBrowseResponse.Entry::path)
                .containsExactlyElementsOf(policy.readableRoots().stream().map(Path::toString).toList());
        // 기준 위치가 하나로 정해지지 않으므로 둘 다 비어 있다.
        assertThat(response.path()).isNull();
        assertThat(response.parent()).isNull();
        // 루트 목록은 나눠 주지 않는다 — 설정에서 그대로 나오는 목록이라 훑는 부하가 없다.
        assertThat(response.nextCursor()).isNull();
    }

    /**
     * 허용 저장소 루트가 <b>여럿</b>일 때 선언된 <b>순서 그대로</b> 돌아온다.
     *
     * <p>⚠ 이 시험이 따로 있어야 하는 이유 — 다른 시험의 형상은 허용 루트가 <b>1건뿐</b>이라
     * 「순서까지 같다」는 축이 <b>돌지 않는다</b>(원소가 하나면 어떤 순서로 내놓아도 같다). 그래서
     * 목록을 이름순으로 다시 정렬해도 그쪽은 전부 초록이다. 여기서 이름 오름차순과 <b>어긋나게</b>
     * 선언해 그 자리를 메운다.
     *
     * <p>순서가 축인 까닭은 이 목록이 검사·적재가 받아들이는 범위의 <b>사본</b>이기 때문이다 — 다시
     * 정렬하면 그것이 같은 목록임을 순서까지 포함해 확인할 수 없게 된다(AC-1080).
     */
    @Test
    @DisplayName("허용_저장소_루트가_여럿이면_선언된_순서_그대로_돌아온다")
    void 허용_저장소_루트가_여럿이면_선언된_순서_그대로_돌아온다() throws IOException {
        Path zulu = Files.createDirectories(root.resolve("zulu"));
        Path alpha = Files.createDirectories(root.resolve("alpha"));
        Path mike = Files.createDirectories(root.resolve("mike"));
        ImportBrowseService multi = multiRootService(zulu, alpha, mike);

        assertThat(multi.listFolders(null, null).entries())
                .extracting(ImportBrowseResponse.Entry::path)
                .as("여기서 다시 정렬하면 검사·적재가 받아들이는 목록과 같다는 것을 순서까지 확인할 수 없다")
                .containsExactly(zulu.toString(), alpha.toString(), mike.toString());
    }

    /**
     * 루트 목록은 이어받을 자리를 줘도 <b>값을 쓰지 않고 길이만</b> 본다.
     *
     * <p>⚠ 이 시험이 없으면 그 조합이 <b>아무 시험에도 걸리지 않아</b>, 400 으로 거부하도록 바꿔도
     * 전부 초록이다. 거부하면 안 되는 이유는 동선에 있다 — 화면이 위치를 지우고 루트로 돌아갈 때
     * 앞선 응답의 이어받을 자리가 남아 함께 실려 오는 것이 정상이라, 거부하면 그 길이 막힌다.
     */
    @Test
    @DisplayName("루트_목록은_이어받을_자리를_줘도_값을_쓰지_않고_길이만_본다")
    void 루트_목록은_이어받을_자리를_줘도_값을_쓰지_않고_길이만_본다() {
        ImportBrowseResponse baseline = service.listFolders(null, null);

        ImportBrowseResponse given = service.listFolders(null, "anything");

        assertThat(given.entries())
                .as("나눠 주지 않는 목록에는 이어받을 자리가 가리킬 것이 없다 — 값을 쓰면 안 된다")
                .isEqualTo(baseline.entries());
        assertThat(given.nextCursor()).isNull();
    }

    @Test
    @DisplayName("빈_문자열도_생략과_같게_루트_목록으로_답한다")
    void 빈_문자열도_생략과_같게_루트_목록으로_답한다() {
        // 화면이 입력칸을 비운 것과 아예 넣지 않은 것이 갈리면, 같은 조작에 다른 답이 돌아온다.
        assertThat(service.listFolders("   ", null).entries())
                .isEqualTo(service.listFolders(null, null).entries());
    }

    // ------------------------------------------------------------------ 폴더 탐색

    @Test
    @DisplayName("폴더_목록에는_폴더만_담기고_바로가기와_숨김과_파일은_빠진다")
    void 폴더_목록에는_폴더만_담기고_바로가기와_숨김과_파일은_빠진다() throws IOException {
        Path folder = handoverFixture();

        ImportBrowseResponse response = service.listFolders(folder.toString(), null);

        assertThat(response.entries()).extracting(ImportBrowseResponse.Entry::name)
                // 이름 오름차순 고정 — 저장 장치 순서를 그대로 내보내면 다시 열 때 순서가 흔들린다.
                .containsExactly("alpha", "beta")
                // .hidden(숨김) · linkdir(바로가기) · 파일 5건은 담기지 않는다.
                .doesNotContain(".hidden", "linkdir", "a.mp4", "notes.txt");
        // 한 쪽에 다 담겼다 — 끝까지 봤으므로 이어받을 자리가 없다.
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("한_단계만_돌려주고_여러_단계를_한_번에_펼치지_않는다")
    void 한_단계만_돌려주고_여러_단계를_한_번에_펼치지_않는다() throws IOException {
        Path folder = handoverFixture();
        Files.createDirectories(folder.resolve("alpha").resolve("deeper"));

        assertThat(service.listFolders(folder.toString(), null).entries())
                .extracting(ImportBrowseResponse.Entry::name)
                .containsExactly("alpha", "beta")
                .doesNotContain("deeper");
    }

    @Test
    @DisplayName("돌려주는_위치는_표기가_아니라_실제로_닿는_자리다")
    void 돌려주는_위치는_표기가_아니라_실제로_닿는_자리다() throws IOException {
        Path folder = handoverFixture();

        // 판정과 훑기가 같은 경로를 써야 그 사이에 대상이 바뀌지 않는다(CWE-367).
        assertThat(service.listFolders(folder.toString(), null).path())
                .isEqualTo(folder.toRealPath().toString());
    }

    @Test
    @DisplayName("부모가_허용_루트여도_그_위치를_돌려준다_비우면_루트_바로_아래에서_갇힌다")
    void 부모가_허용_루트여도_그_위치를_돌려준다_비우면_루트_바로_아래에서_갇힌다() throws IOException {
        Path folder = handoverFixture();

        // ★이 케이스가 결함 가드다 — 루트의 자식에서 한 단계 위는 루트이고, 그 자리는 <범위 안>이라
        //   비우지 않는다. 비우면 화면의 「상위로」가 꺼져 루트 바로 아래로 한 번만 내려가도
        //   사람이 그 자리에 갇힌다(실제로 그렇게 만들었다가 잡혔다).
        assertThat(service.listFolders(folder.toString(), null).parent())
                .isEqualTo(root.toString());
        // 그보다 아래도 당연히 위로 갈 수 있다.
        assertThat(service.listFolders(folder.resolve("alpha").toString(), null).parent())
                .isEqualTo(folder.toRealPath().toString());
    }

    @Test
    @DisplayName("지금_자리가_허용_저장소_루트일_때만_위가_비어서_돌아온다")
    void 지금_자리가_허용_저장소_루트일_때만_위가_비어서_돌아온다() throws IOException {
        handoverFixture();

        // 루트에서 한 단계 위는 범위 밖이다 — 비어서 돌아오고, 화면은 그때 루트 목록으로 돌아간다.
        assertThat(service.listFolders(root.toString(), null).parent()).isNull();
    }

    @Test
    @DisplayName("돌려준_위가_비어_있지_않으면_그_값으로_한_단계씩_루트까지_올라갈_수_있다")
    void 돌려준_위가_비어_있지_않으면_그_값으로_한_단계씩_루트까지_올라갈_수_있다() throws IOException {
        Path deepest = handoverFixture().resolve("alpha");

        // 돌려준 위를 그대로 다시 넣는 왕복을 반복한다 — 한 번이라도 거부되거나 근거 없이 비면
        // 그 자리에서 길이 끊긴다. 루트에 닿아 비는 것만이 정상 종료다.
        String cursor = service.listFolders(deepest.toString(), null).parent();
        int hops = 0;
        while (cursor != null) {
            hops++;
            assertThat(hops).as("루트에 닿지 못하고 계속 올라간다 — 종료 조건이 깨졌다").isLessThan(10);
            cursor = service.listFolders(cursor, null).parent();
        }
        // alpha → handover → root 로 두 번 올라간 뒤 루트에서 비어야 한다.
        assertThat(hops).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 파일 탐색

    @Test
    @DisplayName("영상_파일_목록에는_폴더와_비영상과_바로가기와_숨김이_섞이지_않는다")
    void 영상_파일_목록에는_폴더와_비영상과_바로가기와_숨김이_섞이지_않는다() throws IOException {
        Path folder = handoverFixture();

        ImportBrowseResponse response = service.listVideoFiles(folder.toString(), null);

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
        for (ImportBrowseResponse.Entry entry : service.listVideoFiles(folder.toString(), null).entries()) {
            assertThat(policy.verifyVideoFile(entry.path())).isNotNull();
        }
    }

    @Test
    @DisplayName("위치를_생략하면_영상_파일_탐색은_거부한다")
    void 위치를_생략하면_영상_파일_탐색은_거부한다() {
        // 루트 목록 조회가 없는 창구라 기준 위치가 없으면 답할 것이 없다.
        assertThatThrownBy(() -> service.listVideoFiles(null, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ------------------------------------------------------------------ 나눠서 이어 주기

    @Test
    @DisplayName("한_쪽_크기만큼만_담고_이어받을_자리를_함께_돌려준다")
    void 한_쪽_크기만큼만_담고_이어받을_자리를_함께_돌려준다() throws IOException {
        Path many = numberedFolders("many", PAGE_SIZE * 2);

        ImportBrowseResponse first = service.listFolders(many.toString(), null);

        assertThat(first.entries()).hasSize(PAGE_SIZE);
        // 넘치는 것을 <버리지 않고> 이어받을 자리를 준다. 여기를 비우면 화면이 그 자리에서 멈춰
        // 나머지가 통째로 사라진다 — 잘라 버리던 구 동작과 같아진다.
        assertThat(first.nextCursor())
                .as("남은 항목이 있는데 이어받을 자리를 비우면 그 폴더의 나머지가 통째로 사라진다")
                .isEqualTo(first.entries().get(PAGE_SIZE - 1).name());
    }

    /**
     * ★<b>배타</b> 가드 — 이어받을 자리로 <b>준 그 이름 자체</b>는 다음 응답에 담기지 않는다.
     *
     * <p>이 축이 깨지면 화면이 이어붙일 때 같은 항목이 두 번 쌓여 <b>거짓을 보여준다</b>. 비교를
     * {@code <=} 에서 {@code <} 로 되돌리면(포함) 여기서만 실패한다.
     */
    @Test
    @DisplayName("★이어받을_자리로_준_이름은_다시_돌아오지_않는다")
    void 이어받을_자리로_준_이름은_다시_돌아오지_않는다() throws IOException {
        Path many = numberedFolders("many", PAGE_SIZE * 2);

        ImportBrowseResponse first = service.listFolders(many.toString(), null);
        String cursor = first.nextCursor();
        assertThat(cursor).as("이어받을 자리가 없으면 이 시험의 전제가 깨진다").isNotNull();

        ImportBrowseResponse second = service.listFolders(many.toString(), cursor);

        assertThat(second.entries()).extracting(ImportBrowseResponse.Entry::name)
                .as("준 이름이 다시 돌아오면 화면이 이어붙일 때 중복이 쌓인다")
                .doesNotContain(cursor)
                .allMatch(name -> name.compareTo(cursor) > 0);
        // 앞 쪽에 담겼던 것도 하나도 다시 오지 않는다.
        assertThat(second.entries()).doesNotContainAnyElementsOf(first.entries());
    }

    /**
     * ★<b>완주</b> 가드 — 이어받기를 되풀이하면 그 자리의 항목에 <b>빠짐없이 중복 없이</b> 도달한다.
     *
     * <p>목록을 잘라 버리지 않는다는 것이 이 라운드의 축이고, 그것을 실제로 확인하는 자리다.
     * 만드는 순서를 알파벳 순서와 <b>어긋나게</b> 두므로 정렬을 빼면 저장 장치 순서가 그대로 나와
     * 이어받기가 같은 자리를 두 번 주거나 통째로 건너뛴다.
     */
    @Test
    @DisplayName("★이어받기를_되풀이하면_그_자리의_항목이_빠짐없이_중복_없이_모인다")
    void 이어받기를_되풀이하면_그_자리의_항목이_빠짐없이_중복_없이_모인다() throws IOException {
        Path many = Files.createDirectories(root.resolve("many"));
        List<String> expected = new ArrayList<>();
        for (int i = PAGE_SIZE * 3 + 1; i >= 0; i--) {
            String name = String.format("d%02d", i);
            Files.createDirectories(many.resolve(name));
            expected.add(name);
        }
        expected.sort(Comparator.naturalOrder());

        assertThat(drainFolders(many.toString()))
                .as("이어받기로 전부 도달하지 못하면 목록을 잘라 버리는 것과 다르지 않다")
                .containsExactlyElementsOf(expected);
    }

    /**
     * ★<b>살펴보기 상한</b> 가드 — 담은 것이 <b>0건인데도</b> 이어받을 자리가 돌아온다.
     *
     * <p>담는 것은 종류가 들어맞는 것뿐이라, 들어맞지 않는 항목이 이어지면 담을 것을 채우지 못한 채
     * 계속 종류를 확인하게 된다(영상 몇 개가 이미지 수천 장 사이에 놓인 자리가 이 도메인의 정상
     * 형상이다). 살펴보는 쪽 상한이 없으면 그 부하가 한 요청에 통째로 몰려 나눠 주는 취지가 사라진다.
     */
    @Test
    @DisplayName("★들어맞지_않는_항목이_이어지면_담은_것이_0건이어도_이어받을_자리가_돌아온다")
    void 들어맞지_않는_항목이_이어지면_담은_것이_0건이어도_이어받을_자리가_돌아온다() throws IOException {
        Path mixed = textFiles("mixed", SCAN_LIMIT * 2);

        ImportBrowseResponse first = service.listFolders(mixed.toString(), null);

        // 담는 것은 폴더뿐이라 한 건도 담기지 않는다. 그래도 <끝난 것이 아니다>.
        assertThat(first.entries()).isEmpty();
        assertThat(first.nextCursor())
                .as("담은 것이 없다고 끝난 것으로 보면 그 폴더의 나머지가 통째로 사라진다")
                .isNotNull();
    }

    @Test
    @DisplayName("★담은_것이_0건인_쪽을_지나서도_뒤에_있는_항목에_도달한다")
    void 담은_것이_0건인_쪽을_지나서도_뒤에_있는_항목에_도달한다() throws IOException {
        Path mixed = textFiles("mixed", SCAN_LIMIT * 2);
        // 이름 오름차순으로 <맨 뒤>라, 살펴보기 상한에 여러 번 걸린 뒤에야 닿는다.
        Files.createDirectories(mixed.resolve("zz-target"));

        assertThat(drainFolders(mixed.toString()))
                .as("담은 것이 0건인 쪽에서 멈추면 이 항목에 영영 닿지 못한다")
                .containsExactly("zz-target");
    }

    /**
     * ★<b>이름으로 걸러진 항목도 「살펴본 것」으로 센다</b> — 영상 창구의 축이다.
     *
     * <p>영상 창구는 확장자만 보고 아닌 것을 걸러 내며 <b>그때 종류를 묻지 않는다</b>(되묻는 왕복이
     * 이 훑기의 지배적 비용이라, 이미지 수천 장 사이에 영상이 몇 개 놓인 정상 형상에서 그 왕복을
     * 열 배 가까이 줄인다). 그 대가로 <b>살펴본 수에서 그 항목을 빼면 상한이 걸리지 않아</b> 한
     * 요청이 폴더 전체를 훑게 되고, 나눠 주는 취지가 통째로 사라진다.
     *
     * <p>폴더 창구에는 이 축이 없다 — 폴더인지 파일인지는 이름으로 알 수 없어 앞당길 조건이 없다.
     * 그래서 이 자리를 영상 창구로 따로 붙잡는다.
     */
    @Test
    @DisplayName("★영상이_아닌_이름이_이어져도_살펴보기_상한이_걸려_이어받을_자리가_돌아온다")
    void 영상이_아닌_이름이_이어져도_살펴보기_상한이_걸려_이어받을_자리가_돌아온다() throws IOException {
        Path mixed = textFiles("nonvideo", SCAN_LIMIT * 2);

        ImportBrowseResponse first = service.listVideoFiles(mixed.toString(), null);

        // 담는 것은 영상뿐이라 한 건도 담기지 않는다. 그래도 <끝난 것이 아니다>.
        assertThat(first.entries()).isEmpty();
        assertThat(first.nextCursor())
                .as("이름으로 걸러진 항목을 살펴본 수에서 빼면 한 요청이 폴더 전체를 훑는다")
                .isNotNull();
    }

    @Test
    @DisplayName("★영상_창구도_담은_것이_0건인_쪽을_지나서_뒤에_있는_영상에_도달한다")
    void 영상_창구도_담은_것이_0건인_쪽을_지나서_뒤에_있는_영상에_도달한다() throws IOException {
        Path mixed = textFiles("nonvideo2", SCAN_LIMIT * 2);
        // 이름 오름차순으로 <맨 뒤>라, 살펴보기 상한에 여러 번 걸린 뒤에야 닿는다.
        Files.writeString(mixed.resolve("zz-target.mp4"), "v");

        assertThat(drainVideos(mixed.toString()))
                .as("담은 것이 0건인 쪽에서 멈추면 이 영상에 영영 닿지 못한다")
                .containsExactly("zz-target.mp4");
    }

    @Test
    @DisplayName("한_쪽에_다_담기면_이어받을_자리가_비어서_돌아온다")
    void 한_쪽에_다_담기면_이어받을_자리가_비어서_돌아온다() throws IOException {
        Path exact = numberedFolders("exact", PAGE_SIZE);

        ImportBrowseResponse response = service.listFolders(exact.toString(), null);

        assertThat(response.entries()).hasSize(PAGE_SIZE);
        // 경계값 — 딱 맞게 담겼는데 이어받을 자리를 주면 화면이 빈 쪽을 한 번 더 받아 온다.
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("이어받을_자리가_목록_끝을_지나면_빈_목록과_비어_있는_이어받을_자리로_답한다")
    void 이어받을_자리가_목록_끝을_지나면_빈_목록과_비어_있는_이어받을_자리로_답한다() throws IOException {
        Path folder = handoverFixture();

        ImportBrowseResponse response = service.listFolders(folder.toString(), "zzzzzz");

        // 볼 이름이 남지 않았다 — 여기서 이어받을 자리를 주면 화면이 영원히 이어받는다.
        assertThat(response.entries()).isEmpty();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("영상_파일_탐색도_같은_규약으로_나눠서_이어_준다")
    void 영상_파일_탐색도_같은_규약으로_나눠서_이어_준다() throws IOException {
        Path clips = Files.createDirectories(root.resolve("clips"));
        List<String> expected = new ArrayList<>();
        for (int i = PAGE_SIZE * 2; i >= 0; i--) {
            String name = String.format("clip%02d.mp4", i);
            Files.writeString(clips.resolve(name), "v");
            expected.add(name);
        }
        expected.sort(Comparator.naturalOrder());

        ImportBrowseResponse first = service.listVideoFiles(clips.toString(), null);
        assertThat(first.entries()).hasSize(PAGE_SIZE);
        assertThat(first.nextCursor()).isNotNull();

        List<String> collected = new ArrayList<>();
        String cursor = null;
        int rounds = 0;
        do {
            assertThat(++rounds).as("이어받기가 끝나지 않는다 — 종료 조건이 깨졌다").isLessThan(50);
            ImportBrowseResponse page = service.listVideoFiles(clips.toString(), cursor);
            page.entries().forEach(entry -> collected.add(entry.name()));
            cursor = page.nextCursor();
        } while (cursor != null);

        // 두 창구가 같은 자리에서 나오므로 규약도 같다.
        assertThat(collected).containsExactlyElementsOf(expected);
    }

    /**
     * 빈 문자열은 <b>특례 없이</b> 처음부터 보는 것과 같다 — 모든 이름이 빈 문자열보다 뒤이기 때문이다.
     *
     * <p>⚠ 「주지 않음」은 {@code null} 하나로만 판정한다. 공백만인 값을 주지 않은 것으로 바꾸던 구
     * 동작은 <b>폐기</b>했다 — 근거가 계약 본문에 없었고, 이어받기가 제자리를 도는 결함이었다
     * (바로 아래 시험이 그 자리를 붙잡는다).
     */
    @Test
    @DisplayName("이어받을_자리가_빈_문자열이면_특례_없이_처음부터_본다")
    void 이어받을_자리가_빈_문자열이면_특례_없이_처음부터_본다() throws IOException {
        Path folder = handoverFixture();

        ImportBrowseResponse baseline = service.listFolders(folder.toString(), null);
        ImportBrowseResponse empty = service.listFolders(folder.toString(), "");

        assertThat(empty.entries())
                .as("빈 문자열은 아무 이름보다 앞서므로 아무것도 건너뛰지 않는다")
                .isEqualTo(baseline.entries());
        assertThat(empty.nextCursor()).isEqualTo(baseline.nextCursor());
    }

    /**
     * ★<b>제자리 돌기</b> 가드 — 이름이 <b>공백뿐인</b> 항목이 쪽 경계에 걸려도 이어받기가 끝난다.
     *
     * <p>이어받을 자리는 우리가 <b>살펴본</b> 항목의 이름이라 이런 이름도 그대로 돌아온다. 그 값을
     * 되받아 「주지 않은 것」으로 읽으면 그 폴더를 <b>처음부터</b> 다시 주게 되고, 화면이 이어붙여도
     * 같은 자리를 영원히 맴돌아 <b>그 뒤 항목에 영영 닿지 못한다</b>.
     *
     * <p><b>변이 실증</b>: 공백만인 값을 {@code null} 로 바꾸는 구 동작을 되살리면 여기서 실패한다 —
     * 무한 반복이 아니라 {@link #drainFolders} 의 라운드 상한에 걸려 <b>빨간 실패로</b> 드러난다.
     */
    @Test
    @DisplayName("★이름이_공백뿐인_항목이_쪽_경계에_걸려도_이어받기가_끝나고_뒤의_항목에_닿는다")
    void 이름이_공백뿐인_항목이_쪽_경계에_걸려도_이어받기가_끝나고_뒤의_항목에_닿는다() throws IOException {
        Path folder = Files.createDirectories(root.resolve("blank-name"));
        assumeCreatable(folder, " ");
        Files.createDirectories(folder.resolve("a"));
        Files.createDirectories(folder.resolve("b"));
        // 한 번에 한 건만 담아 공백뿐인 이름이 <쪽 경계>에 오게 만든다.
        ReflectionTestUtils.setField(service, "pageSize", 1);

        assertThat(drainFolders(folder.toString()))
                .as("공백뿐인 이름을 주지 않은 것으로 읽으면 그 자리를 영원히 맴돈다")
                .containsExactly(" ", "a", "b");
    }

    /**
     * 이어받을 자리의 <b>앞뒤 공백을 잘라 내지 않는다</b> — 잘라 내면 준 이름이 다시 돌아온다.
     *
     * <p>{@code "a "}(뒤에 공백)를 잘라 {@code "a"} 로 만들면 그 이름 자신이 <b>자기 뒤</b>로 판정돼
     * 다시 담기고, 이어받을 자리도 같은 값이라 되풀이가 끝나지 않는다.
     */
    @Test
    @DisplayName("★이어받을_자리의_앞뒤_공백을_잘라_내지_않아_그_이름이_다시_돌아오지_않는다")
    void 이어받을_자리의_앞뒤_공백을_잘라_내지_않아_그_이름이_다시_돌아오지_않는다() throws IOException {
        Path folder = Files.createDirectories(root.resolve("padded-name"));
        assumeCreatable(folder, "a ");
        // "a "(0x20) 는 "a0"(0x30) 보다 앞선다 — 공백을 잘라 내면 "a" 가 되어 "a " 가 자기 뒤로 온다.
        Files.createDirectories(folder.resolve("a0"));
        ReflectionTestUtils.setField(service, "pageSize", 1);

        assertThat(drainFolders(folder.toString()))
                .as("앞뒤 공백을 잘라 내면 준 이름이 다시 돌아와 되풀이가 끝나지 않는다")
                .containsExactly("a ", "a0");
    }

    /**
     * ★<b>숨김도 살펴본 것으로 센다</b> — 숨김만으로 상한에 닿아도 이어받을 자리가 돌아온다.
     *
     * <p>⚠ 이 시험이 없으면 이 성질이 <b>아무 시험에도 걸리지 않는다</b>. 다른 표본은 전부 숨김이
     * 아니어서, 숨김을 살펴본 수에서 빼도 모두 초록이다. 그러면 숨김이 수천 개 이어지는 자리에서
     * 살펴보기 상한이 <b>걸리지 않아</b> 부하가 한 요청에 통째로 몰리고, 나눠 주는 취지가 사라진다.
     */
    @Test
    @DisplayName("★숨김_항목만으로_살펴보기_상한에_닿아도_이어받을_자리가_돌아온다")
    void 숨김_항목만으로_살펴보기_상한에_닿아도_이어받을_자리가_돌아온다() throws IOException {
        Path hidden = Files.createDirectories(root.resolve("hidden-heavy"));
        for (int i = 0; i < SCAN_LIMIT * 2; i++) {
            // <폴더>로 만든다 — 숨김 규칙이 빠지면 그대로 담기므로 그 축도 함께 붙잡힌다.
            Files.createDirectories(hidden.resolve(String.format(".h%03d", i)));
        }
        // 이름 오름차순으로 맨 뒤 — 살펴보기 상한에 여러 번 걸린 뒤에야 닿는다.
        Files.createDirectories(hidden.resolve("zz-target"));

        ImportBrowseResponse first = service.listFolders(hidden.toString(), null);

        assertThat(first.entries()).isEmpty();
        assertThat(first.nextCursor())
                .as("숨김을 살펴본 수에서 빼면 상한이 걸리지 않아 한 요청이 전부를 훑는다")
                .isNotNull();
        assertThat(drainFolders(hidden.toString())).containsExactly("zz-target");
    }

    @Test
    @DisplayName("이어받을_자리가_허용_길이를_넘으면_거부하고_메시지에_입력_원문을_담지_않는다")
    void 이어받을_자리가_허용_길이를_넘으면_거부하고_메시지에_입력_원문을_담지_않는다() throws IOException {
        Path folder = handoverFixture();
        String over = "a".repeat(ImportBrowseService.CURSOR_MAX + 1);

        // 두 창구 모두, 그리고 <위치를 주지 않은 루트 목록에서도> 같은 폭으로 거부한다 — 같은 값이
        // 위치를 줬는지에 따라 받아들여지면 화면이 무엇을 보낼 수 있는지 알 수 없게 된다.
        assertThatThrownBy(() -> service.listFolders(folder.toString(), over))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.getMessage()).doesNotContain(over);
                });
        assertThatThrownBy(() -> service.listVideoFiles(folder.toString(), over))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.listFolders(null, over))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 경계값은 통과한다 — 상한을 한 칸 좁게 잡으면 계약이 공표한 폭이 거짓이 된다.
        // 표본에서 "aaa...a" 뒤에 오는 이름은 alpha·beta 뿐이다(대문자와 점으로 시작하는 항목은 앞선다).
        assertThat(service.listFolders(folder.toString(),
                        "a".repeat(ImportBrowseService.CURSOR_MAX)).entries())
                .extracting(ImportBrowseResponse.Entry::name)
                .containsExactly("alpha", "beta");
    }

    /** 이어받을 자리가 빌 때까지 따라가며 <b>담긴 이름</b>을 순서대로 모은다. */
    private List<String> drainFolders(String path) {
        List<String> names = new ArrayList<>();
        String cursor = null;
        int rounds = 0;
        do {
            assertThat(++rounds).as("이어받기가 끝나지 않는다 — 종료 조건이 깨졌다").isLessThan(50);
            ImportBrowseResponse page = service.listFolders(path, cursor);
            page.entries().forEach(entry -> names.add(entry.name()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return names;
    }

    private List<String> drainVideos(String path) {
        List<String> names = new ArrayList<>();
        String cursor = null;
        int rounds = 0;
        do {
            assertThat(++rounds).as("이어받기가 끝나지 않는다 — 종료 조건이 깨졌다").isLessThan(50);
            ImportBrowseResponse page = service.listVideoFiles(path, cursor);
            page.entries().forEach(entry -> names.add(entry.name()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return names;
    }

    /**
     * 허용 저장소 루트를 <b>선언한 순서대로</b> 여럿 가진 창구를 만든다.
     *
     * <p>{@code raw-mount-roots} 는 콤마로 나뉘고 그 순서가 그대로 유지된다.
     */
    private ImportBrowseService multiRootService(Path... roots) {
        String declared = Arrays.stream(roots).map(Path::toString).collect(Collectors.joining(","));
        ImportBrowseService multi = new ImportBrowseService(new ImportSourcePolicy(
                new VideoArtifactRootResolver(declared, "", root.toString(), root.toString(),
                        root.toString(), "co-locate")));
        ReflectionTestUtils.setField(multi, "pageSize", PAGE_SIZE);
        ReflectionTestUtils.setField(multi, "scanLimit", SCAN_LIMIT);
        return multi;
    }

    /**
     * 그 이름의 하위 폴더를 만든다 — 만들 수 없는 파일시스템이면 <b>건너뛴다</b>.
     *
     * <p>공백이 앞뒤에 있거나 이름이 공백뿐인 항목은 POSIX(리눅스·맥)에서 만들 수 있다. 만들 수 없는
     * 곳에서는 통과가 아니라 건너뜀으로 답한다 — 재현되지 않는 것을 초록으로 세지 않는다.
     */
    private static void assumeCreatable(Path parent, String name) {
        try {
            Path made = Files.createDirectories(parent.resolve(name));
            Assumptions.assumeTrue(name.equals(made.getFileName().toString()),
                    "이름의 공백이 보존되지 않는 파일시스템이다 — 이 축은 여기서 재현되지 않는다");
        } catch (IOException | RuntimeException e) {
            Assumptions.abort("이런 이름의 폴더를 만들 수 없는 파일시스템이다: " + e.getClass().getSimpleName());
        }
    }

    /** 이름이 {@code d00}.. 인 하위 폴더 {@code count} 개를 만든다. */
    private Path numberedFolders(String name, int count) throws IOException {
        Path folder = Files.createDirectories(root.resolve(name));
        for (int i = 0; i < count; i++) {
            Files.createDirectories(folder.resolve(String.format("d%02d", i)));
        }
        return folder;
    }

    /** 폴더로도 영상으로도 담기지 <b>않는</b> 파일 {@code count} 개를 만든다. */
    private Path textFiles(String name, int count) throws IOException {
        Path folder = Files.createDirectories(root.resolve(name));
        for (int i = 0; i < count; i++) {
            Files.writeString(folder.resolve(String.format("f%03d.txt", i)), "x");
        }
        return folder;
    }

    // ----------------------------------------------------------------- 거부 (AC-1080)

    @Test
    @DisplayName("허용_범위_밖_경로는_잘못된_입력으로_거부하고_메시지에_입력_원문을_담지_않는다")
    void 허용_범위_밖_경로는_잘못된_입력으로_거부하고_메시지에_입력_원문을_담지_않는다() throws IOException {
        Path elsewhere = Files.createDirectories(outside.resolve("elsewhere"));

        for (String path : List.of(elsewhere.toString(), root.resolve("../..").toString())) {
            assertThatThrownBy(() -> service.listFolders(path, null))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> {
                        assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                        // CWE-209 — 어디를 물었는지 응답이 되풀이하지 않는다.
                        assertThat(e.getMessage()).doesNotContain(path);
                    });
            assertThatThrownBy(() -> service.listVideoFiles(path, null))
                    .isInstanceOf(CustomException.class);
        }
    }

    @Test
    @DisplayName("루트_안의_바로가기가_밖을_가리키면_거부한다")
    void 루트_안의_바로가기가_밖을_가리키면_거부한다() throws IOException {
        Files.createDirectories(outside.resolve("secret"));
        Path escape = Files.createSymbolicLink(root.resolve("escape"), outside);

        // 표기만 보면 범위 안이다 — 실제로 닿는 자리로 다시 판정해야 걸린다(CWE-59).
        assertThatThrownBy(() -> service.listFolders(escape.toString(), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("허용_범위_안이지만_없는_경로는_찾을_수_없음으로_범위_밖_거부와_갈린다")
    void 허용_범위_안이지만_없는_경로는_찾을_수_없음으로_범위_밖_거부와_갈린다() {
        assertThatThrownBy(() -> service.listFolders(root.resolve("nope").toString(), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("지정한_위치가_폴더가_아니면_거부한다")
    void 지정한_위치가_폴더가_아니면_거부한다() throws IOException {
        Path file = Files.writeString(root.resolve("a.mp4"), "a");

        assertThatThrownBy(() -> service.listVideoFiles(file.toString(), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ------------------------------------------------------------------ 관측

    /**
     * 길이로 걸러낸 항목은 목록 어디에도 나타나지 않는다 — 그래서 <b>건수만이라도</b> 남는지 고정한다. 이 경고가 사라지면 "실재하는 폴더가 목록에 없다"는 문의를 확인할 방법이 없다.
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
            ImportBrowseResponse response = service.listFolders(deep.toString(), null);

            // 기능은 그대로다 — 걸러내되 이어받기 축과 시맨틱을 섞지 않는다(끝까지 봤으므로 비어 있다).
            assertThat(response.entries()).isEmpty();
            assertThat(response.nextCursor()).isNull();

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
            service.listFolders(folder.toString(), null);

            // 0 건일 때도 찍으면 정상 탐색마다 경고가 쌓여 진짜 신호가 묻힌다.
            assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                    .noneMatch(m -> m.contains("over-length"));
        } finally {
            serviceLogger().detachAppender(logs);
        }
    }

    /**
     * 읽지 못해 빠진 항목도 <b>건수만</b> 남는지 고정한다.
     *
     * <p>길이로 빠진 항목에는 이미 기록이 있는데 이쪽만 아무 흔적이 없으면 비대칭이다 — 목록이 비어
     * 돌아왔을 때 "진짜로 비어 있는 자리"와 "못 읽어서 빠진 자리"가 응답만으로는 구분되지 않는다.
     *
     * <p>⚠ 이 상황은 <b>권한 검사가 실제로 걸리는 환경에서만</b> 재현된다(root 로 돌리면 우회된다).
     * 그래서 재현 여부를 먼저 확인하고 아니면 건너뛴다 — 재현되지 않는 것을 통과로 세지 않는다.
     */
    @Test
    @DisplayName("읽을_수_없어_빠진_항목이_있으면_건수를_경고로_남기고_이름과_경로는_담지_않는다")
    void 읽을_수_없어_빠진_항목이_있으면_건수를_경고로_남기고_이름과_경로는_담지_않는다() throws IOException {
        Path locked = Files.createDirectories(root.resolve("locked"));
        Path child = Files.createDirectories(locked.resolve("unreadablechild"));
        Assumptions.assumeTrue(
                Files.getFileAttributeView(locked, PosixFileAttributeView.class) != null,
                "권한 축이 없는 파일시스템이라 이 상황을 만들 수 없다");

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked);
        try {
            // 읽기만 남기고 실행을 뺀다 — 이름은 읽히는데 종류는 확인할 수 없는 자리가 된다.
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r--------"));
            Assumptions.assumeTrue(cannotReadAttributes(child),
                    "권한 검사가 우회되는 실행 환경(root 등)이라 재현되지 않는다");

            ListAppender<ILoggingEvent> logs = attachLogAppender();
            try {
                ImportBrowseResponse response = service.listFolders(locked.toString(), null);

                // 기능은 그대로다 — 그 항목만 빠지고 요청 전체가 실패하지 않는다.
                assertThat(response.entries()).isEmpty();
                // 이어받기 축이 아니다 — 시맨틱을 섞지 않는다(끝까지 봤으므로 비어 있다).
                assertThat(response.nextCursor()).isNull();

                List<String> warns = warnMessages(logs);
                assertThat(warns)
                        .as("조용히 빼면 빈 자리와 못 읽은 자리가 응답만으로 구분되지 않는다")
                        .anyMatch(m -> m.contains("unreadable") && m.contains("count=1"));
                // CWE-117/CWE-359 — 이름 조각도 경로도 로그에 실리지 않는다.
                assertThat(warns).noneMatch(m -> m.contains("unreadablechild"));
                assertThat(warns).noneMatch(m -> m.contains(locked.toString()));
            } finally {
                serviceLogger().detachAppender(logs);
            }
        } finally {
            // 되돌리지 않으면 임시 폴더 정리가 실패해 다른 시험까지 흔들린다.
            Files.setPosixFilePermissions(locked, original);
        }
    }

    @Test
    @DisplayName("읽을_수_없어_빠진_항목이_없으면_그_경고를_남기지_않는다")
    void 읽을_수_없어_빠진_항목이_없으면_그_경고를_남기지_않는다() throws IOException {
        Path folder = handoverFixture();

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            service.listFolders(folder.toString(), null);

            // 0 건일 때도 찍으면 정상 탐색마다 경고가 쌓여 진짜 신호가 묻힌다.
            assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                    .noneMatch(m -> m.contains("unreadable"));
        } finally {
            serviceLogger().detachAppender(logs);
        }
    }

    /**
     * ★<b>이름으로 아닌 것이 판명된 항목은 종류를 묻지 않는다</b> — 그 사실이 관측으로 드러난다.
     *
     * <p>읽을 수 없는 항목의 <b>이름이 영상이 아니면</b> 그 항목은 읽어 보지도 않으므로, 읽히지
     * 않는다는 사실도 알지 못한다. 그래서 경고가 남지 않는다.
     *
     * <p>이것이 계약에 더 맞다 — 계약이 세라고 한 것은 <b>"읽을 수 없어 목록에서 빠진 항목"</b>인데,
     * 이름이 영상이 아닌 항목은 읽히든 아니든 애초에 빠졌을 것이라 <b>읽을 수 없어서 빠진 것이
     * 아니다</b>. 세면 "영상 파일이 없는 폴더"와 "읽을 수 없어 비어 보이는 폴더"를 가르려던 신호가
     * 이미지 폴더마다 쌓여 묻힌다.
     *
     * <p>⚠ 이 시험은 <b>종류 확인을 이름 판정 앞으로 되돌리면 RED</b> 가 된다(그때는 건수 1 로
     * 남는다). 되묻는 왕복을 줄인 재배치를 붙잡는 자리다.
     */
    @Test
    @DisplayName("★영상_이름이_아닌_항목은_읽어_보지_않으므로_읽을_수_없음으로_세지_않는다")
    void 영상_이름이_아닌_항목은_읽어_보지_않으므로_읽을_수_없음으로_세지_않는다() throws IOException {
        Path locked = Files.createDirectories(root.resolve("lockedimages"));
        Path child = Files.createDirectories(locked.resolve("frame001.jpg"));
        Assumptions.assumeTrue(
                Files.getFileAttributeView(locked, PosixFileAttributeView.class) != null,
                "권한 축이 없는 파일시스템이라 이 상황을 만들 수 없다");

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked);
        try {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r--------"));
            Assumptions.assumeTrue(cannotReadAttributes(child),
                    "권한 검사가 우회되는 실행 환경(root 등)이라 재현되지 않는다");

            ListAppender<ILoggingEvent> logs = attachLogAppender();
            try {
                ImportBrowseResponse response = service.listVideoFiles(locked.toString(), null);

                assertThat(response.entries()).isEmpty();
                assertThat(warnMessages(logs))
                        .as("이름으로 이미 아닌 것이 판명된 항목은 후보가 아니라 읽지 않는다")
                        .noneMatch(m -> m.contains("unreadable"));
            } finally {
                serviceLogger().detachAppender(logs);
            }
        } finally {
            // 되돌리지 않으면 임시 폴더 정리가 실패해 다른 시험까지 흔들린다.
            Files.setPosixFilePermissions(locked, original);
        }
    }

    /**
     * ★<b>길이로 걸러낼 항목도 종류를 묻기 전에 걸러낸다</b> — 경로 길이는 이름만으로 판정된다.
     *
     * <p>자리 경로가 이미 상한에 가까우면 그 아래 항목이 <b>전부</b> 걸리는데, 이 검사를 종류 확인
     * 뒤에 두면 그 전부를 되물은 뒤에 버린다.
     *
     * <p>읽을 수 없으면서 길이도 넘치는 항목을 만들어 <b>어느 검사가 먼저 걸렸는지</b>를 드러낸다 —
     * 길이가 먼저면 길이 초과로 세고, 종류 확인이 먼저면 읽을 수 없음으로 센다.
     */
    @Test
    @DisplayName("★길이로_걸러낼_항목은_종류를_묻기_전에_걸러낸다")
    void 길이로_걸러낼_항목은_종류를_묻기_전에_걸러낸다() throws IOException {
        Path folder = Files.createDirectories(root.resolve("longlocked"));
        Path deep = folder;
        String segment = "n".repeat(60);
        while (deep.resolve(segment).toString().length() <= ImportSourcePolicy.FOLDER_PATH_MAX) {
            deep = Files.createDirectories(deep.resolve(segment));
        }
        Files.createDirectories(deep.resolve(segment));
        Assumptions.assumeTrue(
                Files.getFileAttributeView(deep, PosixFileAttributeView.class) != null,
                "권한 축이 없는 파일시스템이라 이 상황을 만들 수 없다");

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(deep);
        try {
            Files.setPosixFilePermissions(deep, PosixFilePermissions.fromString("r--------"));
            Assumptions.assumeTrue(cannotReadAttributes(deep.resolve(segment)),
                    "권한 검사가 우회되는 실행 환경(root 등)이라 재현되지 않는다");

            ListAppender<ILoggingEvent> logs = attachLogAppender();
            try {
                ImportBrowseResponse response = service.listFolders(deep.toString(), null);

                assertThat(response.entries()).isEmpty();
                List<String> warns = warnMessages(logs);
                assertThat(warns)
                        .as("길이 판정이 종류 확인보다 뒤면 되묻는 왕복이 그대로 남는다")
                        .anyMatch(m -> m.contains("over-length") && m.contains("count=1"));
                assertThat(warns).noneMatch(m -> m.contains("unreadable"));
            } finally {
                serviceLogger().detachAppender(logs);
            }
        } finally {
            Files.setPosixFilePermissions(deep, original);
        }
    }

    private static boolean cannotReadAttributes(Path path) {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static List<String> warnMessages(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
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

    // ------------------------------------------------------ 허용 루트가 바로가기인 형상 (ADR-065)

    /**
     * 허용 루트 <b>자체가 바로가기</b>인 창구를 만든다. 바로가기를 만들 수 없는 환경이면 통과가
     * 아니라 <b>건너뜀</b>으로 답한다 — 재현되지 않는 것을 초록으로 세면 가드가 없는 것과 같다.
     */
    private ImportBrowseService symlinkRootService() {
        Path link = linkHome.resolve("root-link");
        try {
            Files.createSymbolicLink(link, tempRoot);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("바로가기를 만들 수 없는 환경이다 — 이 축은 여기서 재현되지 않는다: "
                    + e.getClass().getSimpleName());
        }
        ImportBrowseService linked = new ImportBrowseService(new ImportSourcePolicy(
                new VideoArtifactRootResolver(link.toString(), "", link.toString(),
                        link.toString(), link.toString(), "co-locate")));
        ReflectionTestUtils.setField(linked, "pageSize", PAGE_SIZE);
        ReflectionTestUtils.setField(linked, "scanLimit", SCAN_LIMIT);
        return linked;
    }

    /**
     * ★왕복 가드 — 이 창구가 돌려준 위치를 <b>그대로 되넣으면 통과</b>한다(API-221).
     *
     * <p>이 창구는 판정에 쓴 실경로를 싣고 화면은 그 값을 그대로 되돌려 보낸다. 판정의 표기 단계가
     * 설정 원문만 인정하면 <b>서버가 자기가 내준 위치를 자기가 거부</b>해, 루트 바로 아래에서 한
     * 걸음도 나아가지 못한다. 현장에서 그 상태가 실제로 났다.
     */
    @Test
    @DisplayName("★바로가기_루트가_돌려준_위치를_그대로_되넣으면_한_단계씩_더_들어갈_수_있다")
    void 바로가기_루트가_돌려준_위치를_그대로_되넣으면_한_단계씩_더_들어갈_수_있다() throws IOException {
        ImportBrowseService linked = symlinkRootService();
        handoverFixture();
        String declaredRoot = linkHome.resolve("root-link").toString();

        // 1홉 — 선언된 루트 표기로 들어간다. 돌아오는 위치는 바로가기를 따라간 실제 자리다.
        ImportBrowseResponse first = linked.listFolders(declaredRoot, null);
        assertThat(first.path()).isEqualTo(root.toString());
        String handoverPath = first.entries().stream()
                .filter(e -> "handover".equals(e.name())).findFirst().orElseThrow().path();

        // 2홉 — 그 위치를 그대로 되넣는다. 여기서 거부되면 화면이 그 자리에 멈춘다.
        ImportBrowseResponse second = linked.listFolders(handoverPath, null);
        assertThat(second.path()).isEqualTo(handoverPath);

        // 3홉 — 한 단계 더. 왕복이 한 번이 아니라 계속 닫히는지 본다.
        String alphaPath = second.entries().stream()
                .filter(e -> "alpha".equals(e.name())).findFirst().orElseThrow().path();
        assertThat(linked.listFolders(alphaPath, null).path()).isEqualTo(alphaPath);
    }

    /**
     * ★갇힘 가드 — 바로가기 루트에서 한 단계 내려간 자리의 <b>위가 비어 돌아오지 않는다</b>.
     *
     * <p>화면은 이 값 하나로 「상위로」의 활성 여부를 정한다. 비면 사람이 그 자리에 갇힌다.
     */
    @Test
    @DisplayName("★바로가기_루트에서_한_단계_내려간_자리의_위가_비어_돌아오지_않는다")
    void 바로가기_루트에서_한_단계_내려간_자리의_위가_비어_돌아오지_않는다() throws IOException {
        ImportBrowseService linked = symlinkRootService();
        Path handover = handoverFixture();

        assertThat(linked.listFolders(handover.toString(), null).parent())
                .as("여기가 비면 루트 바로 아래로 한 번만 내려가도 위로 갈 길이 사라진다")
                .isEqualTo(root.toString());
        // 그 값을 되넣어 실제로 한 단계 위로 올라가진다 — 값만 채워지고 쓸 수 없으면 소용이 없다.
        assertThat(linked.listFolders(root.toString(), null).parent())
                .as("루트에 닿으면 그때 비는 것이 정상 종료다").isNull();
    }

    /**
     * ⚠ 완화 범위 한정 — 바로가기 루트에서도 <b>범위 밖을 가리키는 바로가기</b>와 <b>상위로 거슬러
     * 올라가는 표기</b>는 여전히 거부된다(CWE-59 · CWE-22).
     *
     * <p>이 단언이 없으면 표기 단계를 넓힌 것과 세 단계를 함께 푼 것이 구분되지 않는다.
     */
    @Test
    @DisplayName("바로가기_루트에서도_범위_밖_바로가기와_상위_이동_표기는_여전히_거부한다")
    void 바로가기_루트에서도_범위_밖_바로가기와_상위_이동_표기는_여전히_거부한다() throws IOException {
        ImportBrowseService linked = symlinkRootService();
        Files.createDirectories(outside.resolve("secret"));
        Files.createSymbolicLink(root.resolve("escape"), outside);
        Path declaredRoot = linkHome.resolve("root-link");

        for (String path : List.of(
                declaredRoot.resolve("escape").toString(),          // 표기는 범위 안, 실제는 밖
                root.resolve("escape").toString(),                  // 실경로 표기여도 마찬가지다
                declaredRoot.resolve("../..").resolve(tempOutside.getFileName())
                        .resolve("secret").toString())) {           // 상위로 거슬러 올라간다
            assertThatThrownBy(() -> linked.listFolders(path, null))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> {
                        assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                        // CWE-209 — 어디를 물었는지 응답이 되풀이하지 않는다.
                        assertThat(e.getMessage()).doesNotContain(path);
                    });
        }
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

package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;
import kr.co.cudo.authoring.transfer.dto.ImportBrowseResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 이관 대상 위치를 <b>골라 넣기 위한</b> 탐색 — 한 단계씩 하위 폴더와 영상 파일을 돌려준다.
 *
 * <h3>손으로 적는 길을 대신하지 않는다</h3>
 * <p>이 창구는 보조 수단이다. 응답하지 않아도 검수자는 경로를 적어 검사를 요청할 수 있어야 하므로,
 * 여기에 검사·적재가 의존하는 상태를 만들지 않는다. 이 서비스에는 저장 통로가 없고 파일도 읽기만
 * 한다(디렉터리 이름만 읽는다 — 파일 내용은 열지 않는다).
 *
 * <h3>허용 범위 판정을 여기서 하지 않는다 (AC-048)</h3>
 * <p>경로순회·심링크 우회·길이·범위 밖 거부·부재 구분·거부 메시지에 입력 원문 미노출, 그리고
 * <b>존재 확인을 범위 검사보다 뒤에 두는 순서</b>까지 전부 {@link ImportSourcePolicy} 가 이미 한다.
 * 여기서 같은 검사를 다시 쓰면 허용 목록이 두 벌이 되어, 한 창구에서 막히는 자리가 다른 창구에서
 * 열린다. 그래서 이 서비스는 <b>정책을 부르기만</b> 하고 그 결과인 실경로로만 훑는다(CWE-367).
 *
 * <h3>고를 수 없는 것을 목록에 담지 않는다</h3>
 * <p>여기서 고른 값은 결국 검사·적재가 다시 판정한다. 그 판정을 통과할 수 없는 것을 내주면 사람이
 * 고른 뒤에야 거부를 받는다. 그래서 종류(폴더/영상 파일)뿐 아니라 <b>경로 길이 상한</b>도 각
 * 창구의 판정과 같은 값으로 미리 걸러 낸다.
 *
 * <h3>트랜잭션을 잡지 않는다</h3>
 * <p>비용이 전부 파일시스템 훑기다. 그 구간을 트랜잭션에 두면 큰 폴더 하나가 커넥션을 오래 쥔다
 * (이 저장소에 커넥션 기아 전례가 있다). 이 경로는 DB 를 아예 건드리지 않는다.
 *
 * @design API-221
 * @design API-222
 * @design AC-120
 * @design AC-048
 */
@Service
@RequiredArgsConstructor
public class ImportBrowseService {

    private static final Logger log = LoggerFactory.getLogger(ImportBrowseService.class);

    /**
     * 영상 파일로 인정하는 확장자 — <b>이 한 곳</b>이 정본이다.
     *
     * <p>흩어 두면 목록에 담는 기준과 고른 뒤 판정하는 기준이 갈려, 화면에 보이는데 넣으면 거부되는
     * 항목이 생긴다. 비교는 소문자로 접어 하므로 대소문자를 가리지 않는다.
     */
    public static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov", ".avi");

    private final ImportSourcePolicy sourcePolicy;

    /**
     * 한 번에 담을 수 있는 항목 수 상한.
     *
     * <p>인계받은 저장소 폴더가 수천 건인 것은 정상 범위라 상한이 없으면 응답이 폭주한다(CWE-770).
     * 설계에 근거 값이 없어 설정으로 뽑아 둔다 — 코드에 박으면 규모가 커졌을 때 배포를 다시 해야
     * 하고, 근거 없는 숫자가 사양처럼 굳는다.
     */
    @Value("${authoring.import.browse.max-entries:2000}")
    private int maxEntries;

    /**
     * 하위 <b>폴더</b> 목록을 돌려준다.
     *
     * @param path 탐색할 폴더 위치. 비었으면 허용 저장소 루트 목록을 돌려준다
     * @throws CustomException 허용 범위 위반·폴더 아님(INVALID_INPUT) / 부재(NOT_FOUND)
     */
    public ImportBrowseResponse listFolders(String path) {
        if (path == null || path.isBlank()) {
            return readableRoots();
        }
        Path folder = sourcePolicy.verifyFolder(path);
        return collect(folder,
                entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS),
                ImportSourcePolicy.FOLDER_PATH_MAX);
    }

    /**
     * 그 폴더 안의 <b>영상 파일</b> 목록을 돌려준다.
     *
     * <p>루트 목록 조회가 없으므로 위치를 생략할 수 없다 — 비면 정책이 잘못된 입력으로 거부한다.
     *
     * @throws CustomException 위치 없음·허용 범위 위반·폴더 아님(INVALID_INPUT) / 부재(NOT_FOUND)
     */
    public ImportBrowseResponse listVideoFiles(String path) {
        Path folder = sourcePolicy.verifyFolder(path);
        return collect(folder,
                entry -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && isVideo(entry),
                ImportSourcePolicy.VIDEO_PATH_MAX);
    }

    // ------------------------------------------------------------------ 루트

    /**
     * 허용 저장소 루트 목록 — 검사·적재가 받아들이는 <b>바로 그 목록</b>을 그대로 내준다.
     *
     * <p>여기서 이름순으로 다시 정렬하지 않는다. 정렬 규칙이 있는 이유는 저장 장치가 돌려주는 순서가
     * 실행마다 달라지기 때문인데, 이 목록은 설정에서 나와 순서가 이미 고정돼 있다. 다시 정렬하면
     * "검사가 받아들이는 목록과 같다"는 것을 순서까지 포함해 확인할 수 없게 된다.
     *
     * <p>실재하지 않는 루트도 거르지 않는다 — 거르면 이 목록이 허용 범위의 사본이 아니라 <b>다른
     * 목록</b>이 된다. 없는 자리를 골랐을 때의 부재 판정은 다음 요청에서 정책이 한다.
     */
    private ImportBrowseResponse readableRoots() {
        List<Path> roots = sourcePolicy.readableRoots();
        List<ImportBrowseResponse.Entry> entries = new ArrayList<>();
        boolean truncated = false;
        for (Path root : roots) {
            if (entries.size() >= maxEntries) {
                truncated = true;
                break;
            }
            Path name = root.getFileName();
            entries.add(new ImportBrowseResponse.Entry(
                    name == null ? root.toString() : name.toString(), root.toString()));
        }
        // 기준 위치가 하나로 정해지지 않으므로 path 도 parent 도 없다.
        return new ImportBrowseResponse(null, null, List.copyOf(entries), truncated);
    }

    // ------------------------------------------------------------------ 훑기

    /**
     * 폴더 바로 아래를 한 단계만 훑는다.
     *
     * <h3>담지 않는 것</h3>
     * <ul>
     *   <li><b>바로가기</b> — 폴더로도 파일로도 세지 않고 건너뛴다. 따라가면 목록이 허용 범위 밖을
     *       가리키는 자리를 내주게 된다.</li>
     *   <li>이름이 점으로 시작하는 항목.</li>
     *   <li><b>읽을 수 없는 항목</b> — 종류를 확인할 수 없으면 그 항목만 빠진다. 한 항목의 권한
     *       문제로 탐색이 통째로 죽으면 안 된다({@code Files.isDirectory} 는 확인 실패 시 거짓을
     *       돌려주므로 이 성질이 그대로 성립한다).</li>
     *   <li>고른 뒤 판정에서 <b>길이로 거부될 항목</b>. 이것만은 <b>건수를 세어 경고로 남긴다</b> —
     *       {@code truncated} 에 잡히지 않아 조용히 빠지는데, 사용자가 실재하는 폴더를 목록에서
     *       못 찾고 "없다"로 오인해도 서버에 흔적이 없으면 확인할 방법이 없기 때문이다.</li>
     * </ul>
     *
     * <p>지정한 폴더 <b>자체</b>를 열 수 없으면 그건 오류다 — 빈 목록으로 답하면 "비어 있는 것"과
     * "읽지 못한 것"이 구분되지 않는다.
     *
     * <h3>상한에 걸리면 그 자리에서 멈춘다</h3>
     * <p>다 읽고 자르면 항목 수만큼 메모리와 시간이 든다. 그래서 상한에 닿는 즉시 멈춘다.
     *
     * <p>⚠ <b>정렬은 담긴 것들 사이에서만 성립한다 — 무엇이 담기는지의 보장이 아니다.</b> 잘린
     * 경우 <b>어느 항목이 담기는지는 저장 장치가 돌려주는 순서를 따르므로</b> 이름이 앞선 N 건이
     * 담긴다고 읽으면 안 된다. 이름 오름차순으로 <b>정렬해서 담는 것이 아니라 담은 것을
     * 정렬</b>하기 때문이다. 그래서 잘렸다는 사실을 반드시 실어 알리고, 화면은 그때 직접 입력을
     * 안내한다. 알파벳 앞선 N 건으로 고정하려면 디렉터리를 전량 순회해야 해 상한을 둔 취지
     * (시간까지 묶는 것)가 무너진다 — 지금 형태가 확정이다.
     */
    private ImportBrowseResponse collect(Path folder, Predicate<Path> accept, int pathMax) {
        List<ImportBrowseResponse.Entry> entries = new ArrayList<>();
        boolean truncated = false;
        int tooLong = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                Path name = entry.getFileName();
                if (name == null) {
                    continue;
                }
                String fileName = name.toString();
                if (fileName.startsWith(".")) {
                    continue;
                }
                if (Files.isSymbolicLink(entry)) {
                    continue;
                }
                if (!accept.test(entry)) {
                    continue;
                }
                String entryPath = entry.toString();
                if (entryPath.length() > pathMax) {
                    // 동작은 그대로 건너뛰되 <건수만> 센다 — 왜 세는지는 아래 로그 주석 참조.
                    tooLong++;
                    continue;
                }
                if (entries.size() >= maxEntries) {
                    truncated = true;
                    break;
                }
                entries.add(new ImportBrowseResponse.Entry(fileName, entryPath));
            }
        } catch (IOException | DirectoryIteratorException e) {
            // CWE-209 — 내부 경로·원문을 메시지에 담지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT, "산출물 폴더를 읽을 수 없습니다.");
        }
        if (truncated) {
            log.warn("[Import] browse truncated — entry limit reached limit={}", maxEntries);
        }
        if (tooLong > 0) {
            // 길이로 걸러낸 항목은 <조용히> 빠진다 — truncated 에도 잡히지 않는다(그건 개수 상한
            // 축이고 이건 길이 축이라 다른 것이다). 그래서 사용자는 실재하는 폴더를 목록에서 못
            // 찾고 "없다"로 오인할 수 있는데, 세어 두지 않으면 그런 항목이 있었다는 사실이 서버에도
            // 남지 않아 문의가 와도 확인할 방법이 없다. 걸러내는 동작은 그대로 두고 관측만 남긴다.
            // ⚠ 경로 원문은 담지 않는다(CWE-117 로그 인젝션 · 경로 노출) — 건수와 상한만.
            log.warn("[Import] browse skipped over-length entries — count={}, limit={}", tooLong, pathMax);
        }
        entries.sort(Comparator.comparing(ImportBrowseResponse.Entry::name));
        return new ImportBrowseResponse(
                folder.toString(), resolveParent(folder), List.copyOf(entries), truncated);
    }

    /**
     * 한 단계 위 폴더 — 그 자리가 <b>허용 저장소 범위를 벗어나면</b> {@code null}.
     *
     * <h3>비우는 조건은 하나다</h3>
     * <p>비어서 돌아온다는 것은 <b>지금 자리가 허용 저장소 루트</b>라는 뜻이고, 그때 화면은 루트
     * 목록으로 돌아간다. <b>부모가 또 다른 허용 루트인 것은 범위 안이므로 비우지 않고 그 위치를
     * 그대로 싣는다.</b>
     *
     * <p>⚠ 여기서 "부모가 루트면 비운다"로 좁히면 <b>루트 바로 아래로 한 번만 내려가도 올라갈 길이
     * 사라져 사람이 그 자리에 갇힌다</b> — 화면은 이 값 하나로 「상위로」의 활성 여부를 정하기
     * 때문이다. 실제로 그렇게 만들었다가 결함으로 잡혔다. 되돌리지 말 것.
     *
     * <h3>범위 판정은 정책에 물어본다</h3>
     * <p>여기서 자체 판정을 짜면 허용 목록이 두 벌이 되고, 두 벌이 어긋나는 순간 응답이 판정으로
     * 막은 자리를 되돌려 준다. 그래서 부모를 <b>같은 판정기에 넣어 보고</b> 거부되면 그 거부가 곧
     * "위로 갈 수 없다"는 답이 된다. 통과하면 그 자리는 검사·적재도 받아들이는 자리이므로 그대로
     * 돌려준다.
     */
    private String resolveParent(Path folder) {
        Path parent = folder.getParent();
        if (parent == null) {
            return null;
        }
        try {
            // 통과한 부모는 그대로 싣는다 — 그 자리가 허용 루트여도 범위 안이라 비울 이유가 없다.
            return sourcePolicy.verifyFolder(parent.toString()).toString();
        } catch (CustomException e) {
            // 범위 밖이거나 열 수 없다 — 지금 자리가 허용 저장소 루트라는 뜻이다.
            return null;
        }
    }

    private static boolean isVideo(Path entry) {
        Path name = entry.getFileName();
        if (name == null) {
            return false;
        }
        String lower = name.toString().toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}

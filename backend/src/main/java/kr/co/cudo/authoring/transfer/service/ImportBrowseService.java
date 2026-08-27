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
import java.nio.file.attribute.BasicFileAttributes;
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
 * <h3>한 번에 다 주지 않고 나눠서 이어 준다</h3>
 * <p>인계받은 저장소 폴더가 수천 건인 것은 정상 범위다. 한 번에 담으면 응답이 폭주하고, 항목마다
 * 종류를 확인해야 하므로 저장 장치에 걸리는 부하도 한꺼번에 몰린다(CWE-770). 그래서 정해진 수만큼만
 * 담고 <b>이어받을 자리</b>를 함께 돌려준다. <b>목록을 잘라 버리지 않으므로</b> 이어받기를 되풀이하면
 * 그 자리의 항목에 모두 도달할 수 있다.
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

    /**
     * 이어받을 자리의 길이 상한 — 계약(API-221·API-222)이 정한 값이다.
     *
     * <p>이 값은 <b>우리가 돌려준 항목 이름</b>이 그대로 돌아오는 자리라, 정상 범위라면 파일 이름
     * 길이를 넘지 않는다. 그럼에도 상한을 두는 이유는 계약이 공표한 폭을 넘는 값을 입구에서 거부해야
     * 화면이 무엇을 보낼 수 있는지 알 수 있기 때문이고, 이 값이 <b>항목 수만큼 반복 비교</b>되므로
     * 길이가 그대로 훑기 비용에 곱해지기 때문이다(CWE-770).
     */
    public static final int CURSOR_MAX = 255;

    /**
     * 이름만으로는 아무것도 거르지 않는다 — <b>폴더 목록</b>이 쓰는 자리다.
     *
     * <p>폴더인지 파일인지는 이름으로 알 수 없으므로 이 창구에는 이름으로 앞당길 조건이 없다.
     * 짝 창구(영상 파일)와 달리 종류 확인을 줄일 여지가 없다는 사실을 <b>빈 규칙으로 드러내</b>,
     * 나중에 이름 기반 조건이 생기면 그 자리에 붙게 한다.
     */
    private static final Predicate<String> ANY_NAME = name -> true;

    private final ImportSourcePolicy sourcePolicy;

    /**
     * 한 번의 요청이 <b>담는</b> 항목 수.
     *
     * <p>상한이 아니라 <b>한 쪽의 크기</b>다 — 넘치는 항목을 버리지 않고 이어받을 자리를 함께
     * 돌려주므로, 이어받기를 되풀이하면 전부 도달한다. 설계에 근거 값이 없어 설정으로 뽑아 둔다.
     */
    @Value("${authoring.import.browse.page-size:200}")
    private int pageSize;

    /**
     * 한 번의 요청이 <b>살펴보는</b> 항목 수.
     *
     * <p>★이 값이 없으면 페이징을 둔 취지가 사라진다. 담는 것은 종류가 들어맞는 것뿐이라, 들어맞지
     * 않는 항목이 이어지면 <b>담을 것을 채우지 못한 채 계속 종류를 확인</b>하게 된다. 영상 파일 몇 개가
     * 이미지 수천 장 사이에 놓인 자리가 이 도메인의 정상 형상이라, 그때 저장 장치 부하가 한 요청에
     * 통째로 몰린다.
     *
     * <p>그래서 담는 수와 살펴보는 수에 <b>각각</b> 상한을 둔다. 살펴보는 쪽에 먼저 걸리면 담은 것이
     * {@link #pageSize} 보다 적을 수 있고 <b>하나도 없을 수도 있다</b> — 그때도 이어받을 자리는 돌아간다.
     */
    @Value("${authoring.import.browse.scan-limit:2000}")
    private int scanLimit;

    /**
     * 하위 <b>폴더</b> 목록을 돌려준다.
     *
     * @param path   탐색할 폴더 위치. 비었으면 허용 저장소 루트 목록을 돌려준다
     * @param cursor 이어받을 자리(앞선 응답이 돌려준 값). 주지 않은 것은 {@code null} 뿐이다
     * @throws CustomException 허용 범위 위반·폴더 아님·이어받을 자리 길이 초과(INVALID_INPUT) / 부재(NOT_FOUND)
     */
    public ImportBrowseResponse listFolders(String path, String cursor) {
        // 이어받을 자리 검사를 <먼저> 한다 — 파일시스템을 건드리지 않는 순수 입력 검증이라 이 순서가
        // 응답으로 경로의 존재 여부를 흘리지 않는다(둘 다 400 이라 코드도 갈리지 않는다).
        String after = verifyCursorWidth(cursor);
        if (path == null || path.isBlank()) {
            return readableRoots();
        }
        Path folder = sourcePolicy.verifyFolder(path);
        return collect(folder,
                ANY_NAME,
                BasicFileAttributes::isDirectory,
                ImportSourcePolicy.FOLDER_PATH_MAX,
                after);
    }

    /**
     * 그 폴더 안의 <b>영상 파일</b> 목록을 돌려준다.
     *
     * <p>루트 목록 조회가 없으므로 위치를 생략할 수 없다 — 비면 정책이 잘못된 입력으로 거부한다.
     *
     * @throws CustomException 위치 없음·허용 범위 위반·폴더 아님·이어받을 자리 길이 초과(INVALID_INPUT)
     *                         / 부재(NOT_FOUND)
     */
    public ImportBrowseResponse listVideoFiles(String path, String cursor) {
        String after = verifyCursorWidth(cursor);
        Path folder = sourcePolicy.verifyFolder(path);
        return collect(folder,
                ImportBrowseService::isVideoName,
                BasicFileAttributes::isRegularFile,
                ImportSourcePolicy.VIDEO_PATH_MAX,
                after);
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
     *
     * <h3>여기서는 나눠서 이어 주지 않는다</h3>
     * <p>같은 이유다 — 이 목록은 <b>훑어서 만드는 것이 아니라 그대로 내주는 것</b>이고, 그 "그대로"가
     * 검증 축이다. 나눠 주는 이유도 여기엔 성립하지 않는다: 그것은 <b>항목마다 종류를 확인하는 부하</b>가
     * 한 요청에 몰리는 것을 막으려는 것인데, 이 목록은 설정에서 나와 크기가 이미 고정돼 있고 저장
     * 장치를 건드리지도 않는다. 그래서 언제나 전부 싣고 <b>이어받을 자리는 비워서</b> 돌려준다.
     *
     * <p>다만 이어받을 자리의 <b>길이 검사</b>는 이 경로에서도 그대로 걸린다 — 같은 창구가 같은 값을
     * 받아 놓고 위치를 줬는지에 따라 받아들이는 폭이 달라지면 화면이 무엇을 보낼 수 있는지 알 수
     * 없게 된다. <b>길이만 보고 값은 쓰지 않는다</b> — 나눠 주지 않는 목록에는 이어받을 자리가 가리킬
     * 것이 없다. 값을 줬다고 거부하지도 않는다: 화면이 위치를 지우면 앞선 응답의 이어받을 자리가
     * 남아 함께 실려 오는 것이 정상 동선이라, 거부하면 루트로 돌아가는 길이 막힌다.
     */
    private ImportBrowseResponse readableRoots() {
        List<ImportBrowseResponse.Entry> entries = new ArrayList<>();
        for (Path root : sourcePolicy.readableRoots()) {
            Path name = root.getFileName();
            entries.add(new ImportBrowseResponse.Entry(
                    name == null ? root.toString() : name.toString(), root.toString()));
        }
        // 기준 위치가 하나로 정해지지 않으므로 path 도 parent 도 없고, 끝까지 본 것이라 이어받을
        // 자리도 없다.
        return new ImportBrowseResponse(null, null, List.copyOf(entries), null);
    }

    // ------------------------------------------------------------------ 훑기

    /**
     * 폴더 바로 아래를 한 단계만 훑되, 이어받을 자리부터 정해진 만큼만 본다.
     *
     * <h3>담지 않는 것</h3>
     * <ul>
     *   <li><b>바로가기</b> — 폴더로도 파일로도 세지 않고 건너뛴다. 따라가면 목록이 허용 범위 밖을
     *       가리키는 자리를 내주게 된다.</li>
     *   <li>이름이 점으로 시작하는 항목.</li>
     *   <li><b>읽을 수 없는 항목</b> — 종류를 확인할 수 없으면 그 항목만 빠진다. 한 항목의 권한
     *       문제로 탐색이 통째로 죽으면 안 된다. 이것도 <b>건수를 세어 경고로 남긴다</b> — 빠진
     *       자리와 진짜로 비어 있는 자리가 응답만으로는 구분되지 않기 때문이다.</li>
     *   <li>고른 뒤 판정에서 <b>길이로 거부될 항목</b>. 이것도 <b>건수를 세어 경고로 남긴다</b> —
     *       조용히 빠지는데, 사용자가 실재하는 폴더를 목록에서 못 찾고 "없다"로 오인해도 서버에
     *       흔적이 없으면 확인할 방법이 없기 때문이다.</li>
     * </ul>
     *
     * <p>지정한 폴더 <b>자체</b>를 열 수 없으면 그건 오류다 — 빈 목록으로 답하면 "비어 있는 것"과
     * "읽지 못한 것"이 구분되지 않는다.
     *
     * <h3>이름을 먼저 전부 읽고 정렬한다</h3>
     * <p>이름만 읽는 것은 값싸다(종류를 묻지 않으므로 저장 장치에 항목마다 되묻지 않는다). 비싼 것은
     * <b>종류 확인</b>이고, 나눠 주는 것이 막으려는 것도 그 부하다. 그리고 이어받을 자리를 이름으로
     * 삼으므로 <b>이름 오름차순 고정이 이어받기의 전제</b>다 — 순서가 실행마다 흔들리면 같은 자리를
     * 두 번 주거나 통째로 건너뛴다.
     *
     * <h3>이름으로 판정할 수 있는 것을 먼저 본다</h3>
     * <p>비싼 것은 <b>종류 확인</b>이다 — 인계받은 저장소가 네트워크 저장 장치에 놓이면 항목마다
     * 되묻는 왕복이 되고, 그 왕복이 이 훑기의 지배적 비용이 된다. 반면 이름은 이미 손에 있다.
     *
     * <p>그래서 <b>이름만으로 판정되는 조건</b>(확장자·점으로 시작·경로 길이)을 <b>종류 확인 앞</b>에
     * 둔다. 영상 파일 창구에서 이미지 수천 장 사이에 영상이 몇 개 놓인 자리가 이 도메인의 정상
     * 형상이라, 뒤에 두면 그 수천 장을 전부 되물은 뒤 확장자로 버린다.
     *
     * <p>★그래도 <b>살펴본 수는 달라지지 않는다</b> — 이름으로 걸러진 항목도 「이어받을 자리 뒤의
     * 항목을 하나 집어 판정한 것」이라 1건으로 센다({@link #scanLimit} 문단·계약과 같은 정의). 그래서
     * 이어받을 자리도, 담기는 항목도, 왕복 횟수도 이 재배치로 바뀌지 않는다. <b>바뀌는 것은 되묻는
     * 횟수뿐이다.</b>
     *
     * <p>⚠ <b>바로가기 제외는 앞으로 옮길 수 없다</b> — 이름으로 알 수 없어 종류를 확인해야 한다.
     *
     * <p>⚠ 그 대신 <b>읽을 수 없어 빠진 항목의 건수</b>가 「후보였던 것」만 세게 된다. 이름으로 이미
     * 아닌 것이 판명된 항목은 읽어 보지 않으므로, 그것이 읽히지 않는다는 사실도 알지 못한다. 계약이
     * 세라고 한 것은 <b>"읽을 수 없어 목록에서 빠진 항목"</b> 인데 그런 항목은 읽히든 아니든 빠졌을
     * 것이므로, 세지 않는 편이 계약에 더 맞다.
     *
     * <h3>이어받는 자리는 배타다</h3>
     * <p>이어받을 자리로 <b>준 그 이름 자체는 담지 않고</b> 그 뒤부터 본다. 그래서 한 번 돌려준 항목이
     * 다시 돌아오지 않고, 화면이 이어붙일 때 같은 항목이 두 번 쌓이지 않는다.
     *
     * <p>자리 <b>번호</b>로 이어받지 않는 이유도 여기 있다 — 앞쪽 항목이 지워지면 뒤에 있던 항목이 그
     * 자리로 당겨져 같은 항목을 두 번 주게 된다.
     *
     * <h3>멈추는 조건은 셋이다</h3>
     * <ol>
     *   <li>담은 것이 {@link #pageSize} 에 도달</li>
     *   <li><b>살펴본 것</b>이 {@link #scanLimit} 에 도달</li>
     *   <li>이름이 소진</li>
     * </ol>
     * <p>돌려주는 이어받을 자리는 <b>마지막으로 살펴본</b> 항목의 이름이고, 이름이 소진됐으면
     * {@code null} 이다. 「살펴본다」는 이어받을 자리 뒤의 항목을 하나 집어 판정하는 것으로, 길이
     * 초과로 걸러지든 종류를 확인하든 <b>모두 1건으로 센다</b>.
     *
     * <p>★그래서 <b>이어받을 자리는 목록에서 빠진 항목의 이름일 수 있다</b> — 숨김·바로가기·읽을 수
     * 없는 항목·길이가 넘치는 항목도 살펴본 것이라 그 이름이 그대로 돌아온다. 담긴 것 중에서만
     * 고르면 그 뒤로 이어지는 <b>빠진 항목들을 매번 다시 살펴보게</b> 되어 나아가지 못한다. 그래서
     * 화면은 이 값을 <b>고를 수 있는 항목으로 다루지 않고</b> 그대로 되돌려 보내기만 한다.
     *
     * <p>⚠ 그래서 <b>담은 것이 0건이어도 이어받을 자리는 돌아온다</b>. 끝났는지는 담은 개수가 아니라
     * 이어받을 자리가 비었는지로 판정한다 — 담은 것이 없다고 끝난 것으로 보면 그 폴더의 나머지가
     * 통째로 사라진다.
     *
     * <h3>⚠ 이어받는 동안 폴더가 바뀌면 (인지·수용한 한계)</h3>
     * <p>인계받은 저장소는 외부가 계속 쓰고 있는 자리라 탐색 도중 항목이 늘어나는 것이 정상이다.
     * <b>이어받을 자리보다 앞선 이름으로 항목이 새로 생기면 이번 탐색에서는 보이지 않는다.</b> 중복이
     * 생기는 것보다 이쪽이 안전하다 — 같은 항목이 두 번 쌓이면 화면이 거짓을 보여주지만, 못 본 항목은
     * 다시 열면 보이고 손으로 적는 길도 그대로 있다. 이 축은 시험이 덮지 못한다(탐색 중간에 다른
     * 프로세스가 폴더를 바꾸는 상황이라 결정적으로 재현되지 않는다).
     */
    private ImportBrowseResponse collect(Path folder,
                                         Predicate<String> nameAccept,
                                         Predicate<BasicFileAttributes> typeAccept,
                                         int pathMax,
                                         String after) {
        List<Path> names = readNames(folder);
        names.sort(Comparator.comparing(entry -> entry.getFileName().toString()));

        List<ImportBrowseResponse.Entry> entries = new ArrayList<>();
        int examined = 0;
        int tooLong = 0;
        int unreadable = 0;
        String lastExamined = null;
        // 이름을 소진했는가 — 소진했으면 이어받을 자리를 비워 "끝까지 봤다"를 알린다.
        boolean exhausted = true;

        for (Path entry : names) {
            String fileName = entry.getFileName().toString();
            if (after != null && fileName.compareTo(after) <= 0) {
                // ★배타 — 이어받을 자리로 준 그 이름 자체는 다시 돌려주지 않는다. 여기를 <= 에서
                //  < 로 되돌리면 그 항목이 두 번 돌아가 화면이 이어붙일 때 중복이 쌓인다.
                continue;
            }
            if (entries.size() >= pageSize || examined >= scanLimit) {
                // 아직 볼 이름이 남았다 — 마지막으로 살펴본 자리를 이어받을 자리로 남긴다.
                exhausted = false;
                break;
            }
            examined++;
            lastExamined = fileName;

            if (fileName.startsWith(".")) {
                // 숨김은 <규칙>으로 빼는 것이라 아래 관측 대상이 아니다 — 세면 정상 폴더마다
                // 경고가 쌓여 진짜로 못 읽은 항목이 묻힌다.
                // ★다만 <살펴본 수>에는 이미 들어가 있다(위 examined++). 숨김도 이름을 집어 판정한
                //  것이라 세지 않으면, 숨김이 수천 개 이어지는 자리에서 살펴보기 상한이 걸리지 않아
                //  나눠 주는 취지가 사라진다. examined++ 를 이 검사 <뒤>로 옮기지 말 것.
                continue;
            }
            if (!nameAccept.test(fileName)) {
                // ★이름만으로 아닌 것이 판명된 항목은 <종류를 묻지 않는다>. 영상 파일 창구에서
                //  이미지 수천 장 사이에 영상이 몇 개 놓인 자리가 이 도메인의 정상 형상인데, 이
                //  조건을 종류 확인 뒤에 두면 그 수천 장을 전부 되물은 뒤 확장자로 버리게 된다.
                //  이 검사를 아래로 내리지 말 것.
                continue;
            }
            String entryPath = entry.toString();
            if (entryPath.length() > pathMax) {
                // 이것도 이름만으로 판정된다(자리 경로 + 이름). 그래서 종류 확인 <앞>에 둔다 —
                // 자리 경로가 이미 상한에 가까우면 그 아래 항목이 전부 걸리는데, 뒤에 두면 전부
                // 되물은 뒤에 버린다.
                // 동작은 그대로 건너뛰되 <건수만> 센다 — 왜 세는지는 아래 로그 주석 참조.
                tooLong++;
                continue;
            }

            BasicFileAttributes attrs;
            try {
                // 종류 판정을 한 번의 조회로 모은다. Files.isDirectory/isRegularFile/isSymbolicLink
                // 는 확인 실패 시 <조용히 거짓>을 돌려주므로, 그것만 쓰면 "그 종류가 아닌 것"과
                // "읽지 못한 것"이 구분되지 않아 관측 자체가 불가능하다.
                attrs = Files.readAttributes(entry, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                // 동작은 그대로 건너뛴다 — 한 항목의 권한 문제로 탐색이 통째로 죽으면 안 된다.
                // 건수만 센다(아래 로그 주석 참조).
                unreadable++;
                continue;
            }
            if (attrs.isSymbolicLink()) {
                continue;
            }
            if (!typeAccept.test(attrs)) {
                continue;
            }
            entries.add(new ImportBrowseResponse.Entry(fileName, entryPath));
        }

        if (tooLong > 0) {
            // 길이로 걸러낸 항목은 <조용히> 빠진다. 그래서 사용자는 실재하는 폴더를 목록에서 못
            // 찾고 "없다"로 오인할 수 있는데, 세어 두지 않으면 그런 항목이 있었다는 사실이 서버에도
            // 남지 않아 문의가 와도 확인할 방법이 없다. 걸러내는 동작은 그대로 두고 관측만 남긴다.
            // ⚠ 경로 원문은 담지 않는다(CWE-117 로그 인젝션 · 경로 노출) — 건수와 상한만.
            // ⚠ 이 건수는 <종류를 묻기 전>에 세므로 폴더 목록에서는 파일도 함께 센다. 길이 검사를
            //   종류 확인 뒤로 내리면 그 항목들을 전부 되물은 뒤에야 버리게 되므로, 되묻는 왕복을
            //   줄이는 쪽을 택했다. 이 경고가 뜨는 자리는 이미 <그 아래 항목이 대체로 전부 걸리는>
            //   깊은 자리라, 세는 대상이 넓어져도 "왜 목록에 없나"라는 물음의 답은 달라지지 않는다.
            log.warn("[Import] browse skipped over-length entries — count={}, limit={}", tooLong, pathMax);
        }
        if (unreadable > 0) {
            // 읽지 못해 빠진 항목도 같은 이유로 <건수만> 남긴다. 길이로 빠진 항목에는 이미 기록을
            // 두고 있어 이쪽만 아무 흔적이 없으면 비대칭이다 — 목록이 비어 돌아왔을 때 "진짜로 비어
            // 있는 자리"와 "못 읽어서 빠진 자리"가 응답만으로는 구분되지 않는다.
            // ⚠ 이름 조각도 경로도 담지 않는다(CWE-117·CWE-359). 이 서비스가 항목 이름을 아예
            //   로그에 쓰지 않는 성질이 로그 인젝션을 구조적으로 막고 있으므로 그것을 깨지 않는다.
            log.warn("[Import] browse skipped unreadable entries — count={}", unreadable);
        }
        // 이름을 정렬해 두고 그 순서대로 담았으므로 목록은 이미 이름 오름차순이다 — 여기서 다시
        // 정렬하지 않는다(정렬이 이어받기의 전제라 담은 뒤에 손대면 두 축이 갈린다).
        return new ImportBrowseResponse(folder.toString(), resolveParent(folder),
                List.copyOf(entries), exhausted ? null : lastExamined);
    }

    /**
     * 폴더 바로 아래의 <b>이름만</b> 읽는다 — 종류를 묻지 않으므로 값싸다.
     *
     * @throws CustomException 폴더 자체를 열 수 없음(INVALID_INPUT)
     */
    private static List<Path> readNames(Path folder) {
        List<Path> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                if (entry.getFileName() == null) {
                    continue;
                }
                names.add(entry);
            }
        } catch (IOException | DirectoryIteratorException e) {
            // CWE-209 — 내부 경로·원문을 메시지에 담지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT, "산출물 폴더를 읽을 수 없습니다.");
        }
        return names;
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

    /**
     * 이어받을 자리의 <b>길이만</b> 재고 값은 그대로 돌려준다.
     *
     * <h3>「주지 않음」은 {@code null} 하나로만 판정한다</h3>
     * <p>이 값은 우리가 돌려준 <b>항목 이름</b>이 그대로 돌아오는 자리다. 그래서 다듬지 않는다 —
     * 앞뒤 공백을 잘라 내지 않고, <b>공백만으로 이뤄진 값도 주지 않은 것으로 바꾸지 않는다.</b>
     *
     * <p>⚠ 공백만인 값을 {@code null} 로 바꾸면 <b>이어받기가 제자리를 돈다.</b> 이어받을 자리는
     * 우리가 <b>살펴본</b> 항목의 이름이라, 이름이 공백뿐인 항목(만들 수 있다)이 쪽 경계에 걸리면 그
     * 이름이 그대로 돌아온다. 그것을 되받아 「주지 않은 것」으로 읽으면 그 폴더를 <b>처음부터</b> 다시
     * 주게 되어, 화면이 이어붙여도 같은 자리를 영원히 맴돌고 그 뒤 항목에는 영영 닿지 못한다.
     * 되돌리지 말 것.
     *
     * <p>빈 문자열을 따로 다룰 필요도 없다 — <b>모든 항목 이름이 빈 문자열보다 뒤</b>이므로 그 값은
     * 아무것도 건너뛰지 않아 저절로 「처음부터」가 된다. 규칙을 하나 없앤 자리를 정렬이 대신 메운다.
     *
     * <p>길이 검사는 <b>받은 값 그대로</b>에 건다 — 계약이 공표한 폭은 화면이 무엇을 보낼 수 있는지에
     * 대한 것이라, 다듬은 뒤 재면 폭이 값에 따라 달라진다.
     *
     * @throws CustomException 계약 폭을 넘는 길이(INVALID_INPUT)
     */
    private static String verifyCursorWidth(String cursor) {
        if (cursor == null) {
            return null;
        }
        if (cursor.length() > CURSOR_MAX) {
            // CWE-209 — 입력 원문을 메시지에 담지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT, "이어받을 자리가 허용 길이를 넘습니다.");
        }
        return cursor;
    }

    /**
     * 영상 파일 이름인가 — <b>이름만</b> 본다(저장 장치를 건드리지 않는다).
     *
     * <p>순수 함수라는 것이 이 판정을 종류 확인 앞으로 옮길 수 있는 근거다.
     */
    private static boolean isVideoName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}

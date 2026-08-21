package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 사람이 입력한 <b>산출물 위치</b>가 읽어도 되는 자리인지 판정하는 단일 지점.
 *
 * <h3>왜 새로 만드는가</h3>
 * <p>기존 판정기 두 개는 이 자리에 그대로 쓸 수 없다.
 * {@code StorageSubtreePolicy.verifyDeidentifiedFile} 은 <b>비식별 전용 서브트리</b>에 있는
 * <b>파일 한 개</b>를 판정하는 것이고, {@code VideoArtifactRootResolver.verifyExternalReadablePath}
 * 는 판정만 하고 <b>판정한 실경로를 돌려주지 않는다</b>. 이관 검사는 <b>디렉터리</b>를 받아 그 아래를
 * 훑어야 하고, 훑을 때 쓰는 경로가 판정한 그 경로여야 한다. 그래서 같은 규칙으로 새로 만들되
 * <b>허용 루트 목록만은 기존 리졸버에서 가져온다</b> — 목록이 두 벌이 되면 한쪽만 넓어진다.
 *
 * <h3>판정 순서와 그 이유</h3>
 * <ol>
 *   <li><b>형식·길이</b> — 컬럼·계약 폭을 넘는 값은 입구에서 거부한다.</li>
 *   <li><b>허용 루트(표기 기준)</b> — 상위 이동 표기({@code ..})는 정규화로 접힌 뒤 루트 밖으로
 *       떨어져 여기서 걸린다(CWE-22).</li>
 *   <li><b>허용 루트(실경로 기준)</b> — 루트 안의 심링크가 밖을 가리키는 우회를 막는다(CWE-59).</li>
 *   <li><b>존재·종류</b> — 마지막에 본다. 앞의 두 검사보다 먼저 하면 <b>허용 범위 밖 경로가 있는지
 *       없는지를 응답이 알려주게</b> 된다(CWE-209).</li>
 * </ol>
 *
 * <h3>돌려주는 것은 실경로다 (CWE-367)</h3>
 * <p>표기 경로로 판정하고 표기 경로로 열면, 판정과 열기 사이에 마지막 요소를 심링크로 바꿔 허용
 * 범위 밖 파일을 읽게 만들 수 있다. 그래서 {@code toRealPath()} 로 얻은 경로를 돌려주고 호출부는
 * <b>그 경로만</b> 쓴다.
 *
 * <h3>허용 루트 밖은 400 이다</h3>
 * <p>이 도메인의 검사·적재 계약이 허용 범위 밖을 <b>잘못된 입력</b>으로 규정한다(AC-048). 권한
 * 없음(403)과 코드를 갈라 두어야 응답이 "권한이 없는 것"과 "경로가 잘못된 것"을 섞지 않는다.
 *
 * @design DOMAIN-017
 * @design AC-048
 * @design API-205
 */
@Component
@RequiredArgsConstructor
public class ImportSourcePolicy {

    /** 산출물 폴더 경로 길이 상한 — 폴더경로명 표준 도메인 폭(API-205 계약). */
    public static final int FOLDER_PATH_MAX = 300;

    /** 원본 영상 파일 경로 길이 상한 — API-205 계약. */
    public static final int VIDEO_PATH_MAX = 1000;

    private final VideoArtifactRootResolver rootResolver;

    /**
     * 산출물 <b>폴더</b> 위치를 판정한다.
     *
     * @return 판정에 사용한 실경로(호출부는 이 경로로만 훑는다)
     * @throws CustomException 형식·길이·허용 범위 위반(INVALID_INPUT) / 폴더 부재(NOT_FOUND)
     */
    public Path verifyFolder(String folderPath) {
        Path real = verifyUnderReadableRoots(folderPath, FOLDER_PATH_MAX, "산출물 폴더 경로");
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "지정한 위치가 폴더가 아닙니다.");
        }
        return real;
    }

    /**
     * 원본 <b>영상 파일</b> 위치를 판정한다.
     *
     * @return 판정에 사용한 실경로
     * @throws CustomException 형식·길이·허용 범위 위반(INVALID_INPUT) / 파일 부재(NOT_FOUND)
     */
    public Path verifyVideoFile(String videoPath) {
        Path real = verifyUnderReadableRoots(videoPath, VIDEO_PATH_MAX, "원본 영상 경로");
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "지정한 위치가 파일이 아닙니다.");
        }
        return real;
    }

    /** 허용 루트(읽기 축) 목록 — 판정 기준을 밖에서 확인할 수 있게 열어 둔다. VisibleForTesting. */
    public List<Path> readableRoots() {
        return rootResolver.readableRoots();
    }

    private Path verifyUnderReadableRoots(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, label + "가 비어 있습니다.");
        }
        if (value.length() > maxLength) {
            throw new CustomException(ErrorCode.INVALID_INPUT, label + "가 허용 길이를 넘습니다.");
        }
        Path candidate;
        try {
            candidate = Paths.get(value).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            // CWE-209 — 입력 원문을 메시지에 담지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT, label + "가 유효하지 않습니다.");
        }
        List<Path> roots = rootResolver.readableRoots();
        // 표기 기준 — '..' 는 정규화로 접힌 뒤 여기서 걸린다.
        if (roots.stream().noneMatch(candidate::startsWith)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "허용된 저장소 범위 밖의 경로입니다.");
        }
        // 실경로 기준 — 아직 없는 경로면 가장 가까운 실재 조상으로 판정한다(존재 여부를 여기서 흘리지 않는다).
        Path nearest = realOrNearest(candidate);
        if (roots.stream().noneMatch(root -> nearest.startsWith(realOrNearest(root)))) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "허용된 저장소 범위 밖의 경로입니다.");
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "지정한 경로를 찾을 수 없습니다.");
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, label + "를 확인할 수 없습니다.");
        }
        // 실재하는 대상의 실경로로 <b>다시</b> 판정한다 — 위 nearest 판정은 조상까지만 본 값이라
        // 마지막 요소가 심링크로 밖을 가리키는 경우를 여기서 걸러야 한다.
        if (roots.stream().noneMatch(root -> real.startsWith(realOrNearest(root)))) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "허용된 저장소 범위 밖의 경로입니다.");
        }
        return real;
    }

    /** 실경로(없으면 가장 가까운 실재 조상의 실경로 + 남은 표기 조각). */
    private static Path realOrNearest(Path path) {
        Path cursor = path;
        Path suffix = null;
        while (cursor != null) {
            try {
                Path real = cursor.toRealPath();
                return suffix == null ? real : real.resolve(suffix);
            } catch (IOException e) {
                Path name = cursor.getFileName();
                suffix = (name == null) ? suffix : (suffix == null ? name : name.resolve(suffix));
                cursor = cursor.getParent();
            }
        }
        return path;
    }
}

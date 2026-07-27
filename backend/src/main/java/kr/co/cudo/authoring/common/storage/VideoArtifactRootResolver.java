package kr.co.cudo.authoring.common.storage;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 영상 1건의 <b>산출물 트리 루트</b>를 결정하는 단일 지점 (Phase 5A — co-locate).
 *
 * <h3>확정 구조</h3>
 * <pre>
 * {dirname(LS_DATA_RAW.RAW_FILE_PATH_NM)}/      ← 관제 NAS. 원본 영상은 이 디렉터리에 그대로 둔다(불변).
 *     clip-001.mp4                              ← 원본(읽기 전용, 저작도구가 만들지도 지우지도 않는다)
 *     {rawSn}/                                  ← 저작도구가 신규 생성하는 산출물 루트
 *         deid/…                                ← 비식별 영상(버전 무관)
 *         v{n}/orgnl|deid/…                     ← 검수 승인 export(버전별)
 * </pre>
 *
 * <h3>왜 별도 컴포넌트인가</h3>
 * <p>base 가 <b>DB 값(RAW_FILE_PATH_NM)에서 도출되는 동적 값</b>으로 바뀌면서, 기존의 "고정 루트
 * {@code startsWith}" 가드가 무력화된다. export writer·비식별 출력(mock/KPST)·마킹 스트리밍이 각자
 * 자기 방식으로 base 를 계산하면 가드가 4벌로 갈라져 하나씩 샌다. 검증 로직을 여기 한 곳에 모은다.
 *
 * <h3>2단계 경로 검증 (CWE-22 / CWE-59) — 자기참조(rubber-stamp) 금지</h3>
 * <ol>
 *   <li><b>base 무결성</b> — {@code dirname(RAW_FILE_PATH_NM)} 이 <b>고정 allowlist</b>
 *       ({@code authoring.storage.raw-mount-roots}, 요청과 무관한 설정값) 하위인지 검증한다.
 *       lexical {@code normalize()} 통과 후 <b>실경로({@code toRealPath}) 재검증</b>까지 수행해
 *       allowlist 밖을 가리키는 심링크를 차단한다.</li>
 *   <li><b>target 무결성</b> — ①을 통과한 base(<b>요청 스코프 지역변수</b>, 필드 아님)를 기준으로만
 *       하위 세그먼트를 {@link Path#resolve} 로 조립하고, {@code normalize()} + 실경로 재검증 후
 *       그 base 하위인지 다시 확인한다({@link #resolveUnder}).</li>
 * </ol>
 * <p>검증 실패는 <b>기본 루트로 fallback 하지 않고</b> 예외로 종결한다(fail-secure). 예외 메시지에는
 * 내부 경로/NAS 구조를 담지 않는다(CWE-209) — 호출부 로그도 rawSn 만 남긴다.
 *
 * <h3>롤백 플래그</h3>
 * <p>{@code authoring.dataset-export.base-strategy} 가 {@code labeling-root} 면 구 구조
 * ({@code {labeling_root}/{rawSn}/…}, 비식별 영상은 {@code {deid_base}/videos/{rawSn}/}) 로 되돌아간다.
 * 이 플래그는 <b>신규 산출 시점의 base 선택에만</b> 관여한다 — 이미 DB 에 적재된 절대경로
 * ({@code LS_DATASET_EXPORT.EXPORT_PATH_NM}, {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM})는
 * 재계산하지 않으므로 전환 후에도 구 산출물 조회가 깨지지 않는다.
 */
@Component
public class VideoArtifactRootResolver {

    private static final Logger LOG = LoggerFactory.getLogger(VideoArtifactRootResolver.class);

    /** 산출 루트 전략 — 원본 영상과 같은 디렉터리 하위(기본). */
    public static final String STRATEGY_CO_LOCATE = "co-locate";
    /** 산출 루트 전략 — 구 구조({@code labeling-path} 고정 루트). 롤백용. */
    public static final String STRATEGY_LABELING_ROOT = "labeling-root";

    /** 비식별 영상 디렉터리 세그먼트 — {@code {rawSn}/deid/}. */
    public static final String SEG_DEID = "deid";

    private final List<Path> allowedRoots;
    private final Path labelingRoot;
    private final Path deidentifiedBase;
    private final boolean coLocate;

    public VideoArtifactRootResolver(
            @Value("${authoring.storage.raw-mount-roots:}") String rawMountRoots,
            @Value("${authoring.storage.raw-path:./storage/raw}") String rawPath,
            @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String deidentifiedPath,
            @Value("${authoring.storage.labeling-path:./storage/labeling}") String labelingPath,
            @Value("${authoring.dataset-export.base-strategy:co-locate}") String baseStrategy) {
        this.labelingRoot = normalize(labelingPath);
        this.deidentifiedBase = normalize(deidentifiedPath);
        this.allowedRoots = buildAllowedRoots(rawMountRoots, rawPath, deidentifiedPath);
        this.coLocate = !STRATEGY_LABELING_ROOT.equalsIgnoreCase(trimOrEmpty(baseStrategy));
    }

    /**
     * 허용 마운트 루트(고정 allowlist) — {@code authoring.storage.raw-mount-roots}(콤마 구분).
     * 미설정이면 저장소 base 2종({@code raw-path}, {@code deidentified-path})을 기본 allowlist 로 쓴다.
     * 원본 영상은 관제 NAS 마운트({@code STORAGE_RAW_PATH}) 하위, 파생영상은 비식별 저장소 하위에 있다.
     *
     * <p><b>안전하지 않은 설정 거부(CWE-1188, fail-closed)</b> — 원소가 <b>파일시스템 루트</b>
     * ({@code /}, Windows 의 {@code C:\})면 allowlist 가 "어디든 허용"이 되어 co-locate 경로 가드가
     * 통째로 무력화된다. 조용히 넘어가면 운영자가 가드가 살아있다고 오인하므로 <b>기동을 실패</b>시킨다.
     * 폴백(미설정) 경로에도 같은 규칙을 적용한다 — {@code STORAGE_RAW_PATH=/} 같은 설정도 막힌다.
     */
    private static List<Path> buildAllowedRoots(String configured, String rawPath, String deidentifiedPath) {
        Set<Path> roots = new LinkedHashSet<>();
        String value = trimOrEmpty(configured);
        if (!value.isEmpty()) {
            for (String token : value.split(",")) {
                String candidate = token.trim();
                if (candidate.isEmpty()) {
                    continue;
                }
                // 상대경로는 프로세스 작업 디렉터리 기준으로 해석된다 — local/test 형상(모듈 상대 경로)에서
                // 정상적으로 쓰이므로 거부하지 않되, 운영 형상에서의 오설정을 눈에 띄게 하려고 경고만 남긴다.
                if (!Paths.get(candidate).isAbsolute()) {
                    LOG.warn("[ArtifactRoot] raw-mount-roots 원소가 상대경로다 — 작업 디렉터리 기준으로 해석된다."
                            + " 운영 형상에서는 절대경로(NAS 마운트 루트)를 지정할 것");
                }
                roots.add(normalize(candidate));
            }
        }
        if (roots.isEmpty()) {
            roots.add(normalize(rawPath));
            roots.add(normalize(deidentifiedPath));
        }
        for (Path root : roots) {
            if (root.getNameCount() == 0) {
                // CWE-209 — 메시지에 경로 원문을 담지 않는다(설정 키만 지목).
                throw new IllegalStateException(
                        "authoring.storage.raw-mount-roots 에 파일시스템 루트를 지정할 수 없습니다."
                                + " 경로 가드가 무력화되므로 실제 마운트 서브트리를 지정하세요.");
            }
        }
        return List.copyOf(roots);
    }

    private static Path normalize(String value) {
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** co-locate(원본 디렉터리 하위) 전략 활성 여부. false 면 구 {@code labeling-root} 구조. */
    public boolean coLocate() {
        return coLocate;
    }

    /** 고정 allowlist(정규화된 절대경로). VisibleForTesting. */
    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    /** 구 구조 산출 루트({@code labeling-path}). VisibleForTesting. */
    public Path labelingRoot() {
        return labelingRoot;
    }

    /** 비식별 저장소 base({@code deidentified-path}). VisibleForTesting. */
    public Path deidentifiedBase() {
        return deidentifiedBase;
    }

    /**
     * 영상 1건의 산출물 트리 루트({@code {base}/{rawSn}})를 계산한다.
     *
     * @param rawSn          영상 PK(≥1)
     * @param rawFilePathNm  {@code LS_DATA_RAW.RAW_FILE_PATH_NM}(co-locate 전략에서만 사용)
     * @throws CustomException 입력 검증 실패(INVALID_INPUT) / allowlist·심링크 위반(FORBIDDEN)
     */
    public Path videoRoot(long rawSn, String rawFilePathNm) {
        if (rawSn < 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 ID는 1 이상이어야 합니다.");
        }
        if (!coLocate) {
            return resolveUnder(labelingRoot, String.valueOf(rawSn));
        }
        return coLocateVideoRoot(rawSn, rawFilePathNm);
    }

    /**
     * co-locate 산출 루트({@code dirname(원본)/{rawSn}}) — <b>전략과 무관</b>하게 계산한다.
     * 신규 산출은 {@link #videoRoot} 를 통해서만 호출되고(전략 존중), 읽기 경로
     * ({@link #readableDeidVideoDirs})는 롤백 전환 후에도 구 co-locate 산출물을 찾아야 하므로 직접 쓴다.
     */
    private Path coLocateVideoRoot(long rawSn, String rawFilePathNm) {
        VerifiedBase verified = verifiedColocateBase(rawFilePathNm);
        Path root = resolveUnder(verified.base(), String.valueOf(rawSn));
        // S5 — 산출 루트가 원본 영상 파일 자체를 가리키면(예: RAW_FILE_PATH_NM 이 '/nas/12' 이고 rawSn=12)
        // 원본을 덮어쓸 수 있다. 쓰기 전에 명시적으로 거부한다(원본 불변 원칙).
        if (root.equals(verified.original())) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다.");
        }
        return root;
    }

    /** 검증을 통과한 co-locate base 와 그 원본 영상 절대경로(원본 훼손 방지 대조용). */
    private record VerifiedBase(Path base, Path original) {
    }

    /** {@link #videoRoot} 의 예외 없는 변형 — 검증 실패 시 {@link Optional#empty()}. */
    public Optional<Path> videoRootQuietly(long rawSn, String rawFilePathNm) {
        try {
            return Optional.of(videoRoot(rawSn, rawFilePathNm));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 비식별 <b>영상</b>(버전 무관) 저장 디렉터리.
     * <ul>
     *   <li>co-locate — {@code {dirname(원본)}/{rawSn}/deid/}</li>
     *   <li>labeling-root(롤백) — {@code {deid_base}/videos/{rawSn}/}(구 위치)</li>
     * </ul>
     */
    public Path deidVideoDir(long rawSn, String rawFilePathNm) {
        if (rawSn < 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 ID는 1 이상이어야 합니다.");
        }
        if (!coLocate) {
            return resolveUnder(deidentifiedBase, StorageSubtreePolicy.SEG_VIDEOS, String.valueOf(rawSn));
        }
        return resolveUnder(videoRoot(rawSn, rawFilePathNm), SEG_DEID);
    }

    /** {@link #deidVideoDir} 의 예외 없는 변형 — 검증 실패 시 {@link Optional#empty()}. */
    public Optional<Path> deidVideoDirQuietly(long rawSn, String rawFilePathNm) {
        try {
            return Optional.of(deidVideoDir(rawSn, rawFilePathNm));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * <b>읽기</b> 경로용 허용 base 후보 — 구 위치({@code {deid_base}/videos/{rawSn}/})와 co-locate 위치
     * ({@code dirname(원본)/{rawSn}/deid/}) <b>둘 다</b>를 돌려준다(S6/S8, 2-way allowlist).
     *
     * <p>{@link #deidVideoDir}(쓰기 대상 1곳)와 달리 <b>전략 플래그와 무관</b>하다. 롤백 플래그는
     * "신규 산출 시점의 base 선택"에만 관여하며, 이미 적재된 절대경로
     * ({@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM})는 재계산하지 않는다 — 전환 전후 행이 섞여도
     * 양쪽 다 읽혀야 하기 때문이다. co-locate 후보는 <b>base 검증(고정 allowlist + 실경로)을 통과할
     * 때만</b> 포함되며, 실패하면 조용히 빠진다(구 위치만으로 판정 — fail-secure).
     */
    public List<Path> readableDeidVideoDirs(long rawSn, String rawFilePathNm) {
        if (rawSn < 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 ID는 1 이상이어야 합니다.");
        }
        Set<Path> dirs = new LinkedHashSet<>();
        dirs.add(resolveUnder(deidentifiedBase, StorageSubtreePolicy.SEG_VIDEOS, String.valueOf(rawSn)));
        try {
            dirs.add(resolveUnder(coLocateVideoRoot(rawSn, rawFilePathNm), SEG_DEID));
        } catch (RuntimeException e) {
            // co-locate base 도출/검증 실패 — 구 위치만으로 판정한다.
        }
        return List.copyOf(dirs);
    }

    /**
     * <b>적재(ingest) 시점 경로 검증</b> — 외부(증강 콜백 등)가 건네는 영상 파일 경로를
     * {@code LS_DATA_RAW.RAW_FILE_PATH_NM} 에 적재하기 <b>전에</b> 고정 allowlist 하위인지 확인한다.
     *
     * <p>Phase 5A 이후 이 값은 단순 읽기 힌트가 아니라 <b>산출물 쓰기 base</b>
     * ({@code dirname(값)/{rawSn}/} 에 프레임 JPG·JSON·비식별 영상이 기록된다)로 승격됐다. 승인 시점
     * 리졸버가 최종 방어선이지만, 그것만으로는 오염된 값이 DB 에 남는 것을 막지 못하므로 입구에서도
     * 같은 기준으로 거른다(CWE-20 + CWE-22, 다층 방어).
     *
     * @throws CustomException 경로 손상(INVALID_INPUT) / allowlist·심링크 위반(FORBIDDEN)
     */
    public void verifyIngestablePath(String rawFilePathNm) {
        verifiedColocateBase(rawFilePathNm);
    }

    /**
     * <b>①base 무결성</b> — {@code dirname(rawFilePathNm)} 이 고정 allowlist 하위인지 검증한다.
     * lexical 검사 + 실경로(심링크 해석) 재검사를 모두 통과해야 하며, 통과한 base 는 호출자의
     * 지역변수로만 흐른다(필드 캐싱 금지 — 검증 기준과 검증 대상이 같은 값에서 파생되지 않도록).
     */
    private VerifiedBase verifiedColocateBase(String rawFilePathNm) {
        if (rawFilePathNm == null || rawFilePathNm.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 영상 경로가 비어 있습니다.");
        }
        Path videoPath;
        try {
            videoPath = Paths.get(rawFilePathNm).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            // CWE-209 — 입력 원문을 메시지에 담지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 영상 경로가 유효하지 않습니다.");
        }
        Path base = videoPath.getParent();
        if (base == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 영상 경로의 상위 디렉터리를 확인할 수 없습니다.");
        }
        // lexical — '..' 순회는 normalize 로 접힌 뒤 allowlist 밖으로 떨어져 여기서 거부된다.
        boolean lexicalOk = allowedRoots.stream().anyMatch(base::startsWith);
        if (!lexicalOk) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 원본 저장 경로입니다.");
        }
        // 실경로(CWE-59) — allowlist 안의 심링크가 밖을 가리키는 우회를 차단한다.
        Path realBase = realOrNearest(base);
        boolean realOk = allowedRoots.stream().anyMatch(root -> realBase.startsWith(realOrNearest(root)));
        if (!realOk) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 원본 저장 경로입니다.");
        }
        return new VerifiedBase(base, videoPath);
    }

    /**
     * <b>②target 무결성</b> — 검증된 base 하위로만 세그먼트를 조립한다.
     *
     * <p>세그먼트는 문자열 연결이 아닌 {@link Path#resolve}(CWE-73)로 붙이며, 구분자/상위참조가 섞인
     * 세그먼트는 즉시 거부한다. 조립 결과는 {@code normalize()} 후 base 하위인지, 이어서 실경로 기준으로
     * 다시 base 실경로 하위인지 확인한다(대상 디렉터리가 아직 없으면 <b>가장 가까운 실재 조상</b>의
     * 실경로로 판정한다 — {@code {rawSn}} 이 밖을 가리키는 심링크인 경우가 여기서 걸린다).
     */
    public static Path resolveUnder(Path base, String... segments) {
        if (base == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "기준 경로가 없습니다.");
        }
        Path target = base;
        for (String segment : segments) {
            if (segment == null || segment.isBlank()
                    || segment.contains("/") || segment.contains("\\") || segment.contains("..")) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않은 경로 세그먼트입니다.");
            }
            target = target.resolve(segment);
        }
        target = target.normalize();
        if (!target.startsWith(base)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다.");
        }
        if (!realOrNearest(target).startsWith(realOrNearest(base))) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다.");
        }
        return target;
    }

    /**
     * <b>③쓰기 직전 재확인(TOCTOU, CWE-367/59)</b> — ①②검증 시점과 실제 쓰기 사이에 경로가
     * 심링크로 바꿔치기되는 창을 닫는다.
     *
     * <p>호출 규약: <b>쓰기 직전에 base 를 다시 계산</b>({@link #videoRoot}/{@link #deidVideoDir} 등 —
     * 이때 고정 allowlist + 실경로 검증이 <b>현재 파일시스템 상태로</b> 재수행된다)한 뒤, 그 결과를
     * {@code verifiedBase} 로 넘겨 실제 쓰기 대상({@code target}, 이미 생성된 디렉터리/파일)의 실경로가
     * 여전히 그 하위인지 확인한다. 디렉터리를 단계별로 비재귀 생성하는 방식보다 비용이 훨씬 낮으면서,
     * "검증한 base 와 실제로 쓰는 위치가 같은 실경로인가"라는 핵심 불변식을 직접 단언한다.
     *
     * @throws CustomException 실경로가 base 밖이거나 확인 불가(FORBIDDEN — fail-secure)
     */
    public static void verifyRealPathUnder(Path target, Path verifiedBase) {
        if (target == null || verifiedBase == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "기준 경로가 없습니다.");
        }
        Path realTarget = realOrNearest(target.toAbsolutePath().normalize());
        if (!realTarget.startsWith(realOrNearest(verifiedBase.toAbsolutePath().normalize()))) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다.");
        }
    }

    /**
     * 경로의 <b>실경로</b>를 구한다. 대상이 아직 존재하지 않으면 가장 가까운 실재 조상을
     * {@code toRealPath()} 로 해석한 뒤 남은 세그먼트를 다시 붙인다(심링크 우회 탐지에 필요).
     *
     * <p>해석 불가(권한/깨진 링크)는 fail-secure — {@link ErrorCode#FORBIDDEN} 으로 거부한다.
     */
    private static Path realOrNearest(Path path) {
        Path cursor = path;
        Deque<Path> tail = new ArrayDeque<>();
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            Path name = cursor.getFileName();
            if (name != null) {
                tail.push(name);
            }
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            return path;
        }
        Path real;
        try {
            real = cursor.toRealPath();
        } catch (IOException e) {
            throw new CustomException(ErrorCode.FORBIDDEN, "경로를 확인할 수 없습니다.");
        }
        List<Path> remaining = new ArrayList<>(tail);
        for (Path segment : remaining) {
            real = real.resolve(segment);
        }
        return real;
    }

    /** 설정 전략 문자열(로그/진단용). */
    public String strategy() {
        return coLocate ? STRATEGY_CO_LOCATE : STRATEGY_LABELING_ROOT.toLowerCase(Locale.ROOT);
    }
}

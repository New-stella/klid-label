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
 * <h3>쓰기 허용 루트 ≠ 읽기 허용 루트 (DEV_FIX 2차 HIGH-1)</h3>
 * <p>외부 벤더(생성형 AI)가 반환하는 산출물은 <b>우리가 읽어서</b> 파생 프레임 위치로 복사할 대상이고,
 * 우리가 쓰는 곳이 아니다. 벤더는 자기 NAS/컨테이너 트리(예: {@code /app/genai-out}, 실벤더는 자기
 * 공유 NAS 경로)에 결과를 쓰므로 그 경로는 쓰기 allowlist
 * ({@code authoring.storage.raw-mount-roots})에 <b>있을 수 없다</b>. 그렇다고 쓰기 allowlist 에
 * 추가하면 "우리가 산출물을 쓸 수 있는 트리" 가 벤더 트리까지 넓어져 PII 격리 축(원본/비식별 산출
 * 위치 통제)이 흐려진다.
 * <p>그래서 축을 둘로 나눈다 — {@link #verifyIngestablePath}(쓰기 base 도출용, allowlist 불변) 과
 * {@link #verifyExternalReadablePath}(외부 산출물 <b>읽기</b> 전용). 읽기 루트는
 * {@code 쓰기 allowlist ∪ authoring.storage.external-read-roots} 이고, 기본값은 <b>빈 값</b>이라
 * 미설정 형상에서는 읽기 허용 범위가 쓰기 allowlist 와 완전히 동일하다(fail-closed — 설정으로만 넓어진다).
 * 검증 절차(정규화 + lexical + 실경로 재검증)는 두 축이 <b>같은 코드</b>를 쓴다.
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

    /** 설정 키(예외 메시지에 경로 원문 대신 지목할 대상). */
    static final String KEY_RAW_MOUNT_ROOTS = "authoring.storage.raw-mount-roots";
    static final String KEY_EXTERNAL_READ_ROOTS = "authoring.storage.external-read-roots";

    private final List<Path> allowedRoots;
    private final List<Path> readableRoots;
    private final Path labelingRoot;
    private final Path deidentifiedBase;
    private final boolean coLocate;

    public VideoArtifactRootResolver(
            @Value("${authoring.storage.raw-mount-roots:}") String rawMountRoots,
            @Value("${authoring.storage.external-read-roots:}") String externalReadRoots,
            @Value("${authoring.storage.raw-path:./storage/raw}") String rawPath,
            @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String deidentifiedPath,
            @Value("${authoring.storage.labeling-path:./storage/labeling}") String labelingPath,
            @Value("${authoring.dataset-export.base-strategy:co-locate}") String baseStrategy) {
        this.labelingRoot = normalize(labelingPath);
        this.deidentifiedBase = normalize(deidentifiedPath);
        this.allowedRoots = buildAllowedRoots(rawMountRoots, rawPath, deidentifiedPath);
        this.readableRoots = buildReadableRoots(this.allowedRoots, externalReadRoots);
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
        rejectFilesystemRoots(roots, KEY_RAW_MOUNT_ROOTS);
        return List.copyOf(roots);
    }

    /**
     * 외부 산출물 <b>읽기</b> 허용 루트 — {@code 쓰기 allowlist ∪ authoring.storage.external-read-roots}.
     *
     * <p>미설정(기본)이면 쓰기 allowlist 와 동일하다 — 즉 <b>설정하지 않는 한 읽기 범위가 넓어지지
     * 않는다</b>(fail-closed). 쓰기 allowlist 를 자동 포함하는 이유는 우리 소유 트리(원본 NAS·비식별
     * 저장소)에서 읽는 것은 이미 허용된 동작이기 때문이며, 반대 방향(읽기 루트를 쓰기 루트로 승격)은
     * 하지 않는다.
     *
     * <p>{@code /} 같은 파일시스템 루트는 쓰기 축과 동일하게 <b>기동을 실패</b>시킨다 — 여기서 통과하면
     * 벤더가 넘긴 임의 경로(예: {@code /etc/shadow})가 파생 프레임으로 복사돼 서빙·export 로 새어 나간다.
     */
    private static List<Path> buildReadableRoots(List<Path> writeRoots, String configured) {
        Set<Path> roots = new LinkedHashSet<>(writeRoots);
        String value = trimOrEmpty(configured);
        if (!value.isEmpty()) {
            for (String token : value.split(",")) {
                String candidate = token.trim();
                if (candidate.isEmpty()) {
                    continue;
                }
                if (!Paths.get(candidate).isAbsolute()) {
                    LOG.warn("[ArtifactRoot] external-read-roots 원소가 상대경로다 — 작업 디렉터리 기준으로"
                            + " 해석된다. 운영 형상에서는 절대경로(벤더 공유 마운트 루트)를 지정할 것");
                }
                roots.add(normalize(candidate));
            }
        }
        rejectFilesystemRoots(roots, KEY_EXTERNAL_READ_ROOTS);
        return List.copyOf(roots);
    }

    /** 파일시스템 루트({@code /}, {@code C:\}) 지정 거부 — 가드 무력화 방지(CWE-1188, fail-closed). */
    private static void rejectFilesystemRoots(Set<Path> roots, String settingKey) {
        for (Path root : roots) {
            if (root.getNameCount() == 0) {
                // CWE-209 — 메시지에 경로 원문을 담지 않는다(설정 키만 지목).
                throw new IllegalStateException(
                        settingKey + " 에 파일시스템 루트를 지정할 수 없습니다."
                                + " 경로 가드가 무력화되므로 실제 마운트 서브트리를 지정하세요.");
            }
        }
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

    /** 고정 allowlist(정규화된 절대경로) — <b>쓰기 base</b> 판정 축. VisibleForTesting. */
    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    /**
     * 외부 산출물 <b>읽기</b> 허용 루트(쓰기 allowlist ∪ external-read-roots). 쓰기에는 쓰지 않는다.
     */
    public List<Path> readableRoots() {
        return readableRoots;
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
     * 비식별 <b>영상</b> 읽기 허용 base <b>전체 집합</b> — {@code deidentified-path}(구 위치·파생영상 포함)
     * ∪ {@link #readableDeidVideoDirs}(구 {@code {deid_base}/videos/{rawSn}} + co-locate
     * {@code dirname(원본)/{rawSn}/deid}).
     *
     * <p><b>왜 필요한가</b> — {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM} 에 적재된 경로를 읽는
     * 소비자는 하나가 아니다(마킹 스트리밍 · 프레임 추출). 각자 자기 방식으로 base 를 계산하면
     * 클래스 주석의 "가드가 4벌로 갈라져 하나씩 샌다" 가 실제로 일어난다 — 실측: 프레임 추출기는
     * {@code deidentified-path} <b>하나만</b> 기준으로 검증해, Phase 5A co-locate 로 산출된 우리 자신의
     * 비식별 영상을 "신뢰불가 경로"로 판정하고 비식별 프레임 벌을 <b>항상</b> 건너뛰었다
     * (DE_IDNTF_SRC_FILE_PATH_NM 전 행 NULL → export 비식별 벌 결손). 판정 축을 여기 한 곳에 모은다.
     *
     * <p>후보 도출 실패(co-locate base 검증 위반 등)는 조용히 빠지고 구 위치만 남는다(fail-secure).
     * 이 목록은 <b>읽기 허용 범위</b>일 뿐이며, 실제 경로는 항상 DB 적재값을 쓰고 조합·추측하지 않는다.
     */
    public List<Path> readableDeidVideoBases(long rawSn, String rawFilePathNm) {
        Set<Path> bases = new LinkedHashSet<>();
        bases.add(deidentifiedBase);
        try {
            bases.addAll(readableDeidVideoDirs(rawSn, rawFilePathNm));
        } catch (RuntimeException e) {
            // 후보 도출 실패 — 구 위치(비식별 저장소 base)만으로 판정한다(fail-secure).
        }
        return List.copyOf(bases);
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
     * <b>외부 산출물 읽기 경로 검증</b> — 벤더(생성형 AI)가 콜백으로 건네는
     * {@code output_file_path} 가 <b>읽기</b> 허용 루트 하위인지 확인한다(CWE-22 / CWE-59).
     *
     * <p>{@link #verifyIngestablePath} 와 검증 절차는 완전히 동일하고 <b>기준 루트 집합만</b> 다르다
     * (클래스 주석 "쓰기 허용 루트 ≠ 읽기 허용 루트" 참조). 이 경로는 우리가 <b>읽기만</b> 하며,
     * 산출물 쓰기 base 로는 절대 승격되지 않는다.
     *
     * @throws CustomException 경로 손상(INVALID_INPUT) / 읽기 allowlist·심링크 위반(FORBIDDEN)
     */
    public void verifyExternalReadablePath(String filePath) {
        verifiedBaseUnder(filePath, readableRoots);
    }

    /**
     * <b>①base 무결성</b> — {@code dirname(rawFilePathNm)} 이 고정 allowlist 하위인지 검증한다.
     * lexical 검사 + 실경로(심링크 해석) 재검사를 모두 통과해야 하며, 통과한 base 는 호출자의
     * 지역변수로만 흐른다(필드 캐싱 금지 — 검증 기준과 검증 대상이 같은 값에서 파생되지 않도록).
     */
    private VerifiedBase verifiedColocateBase(String rawFilePathNm) {
        return verifiedBaseUnder(rawFilePathNm, allowedRoots);
    }

    /** ①base 무결성 — 기준 루트 집합만 달리 받는 공용 판정(쓰기/읽기 축이 같은 코드를 쓴다). */
    private VerifiedBase verifiedBaseUnder(String rawFilePathNm, List<Path> roots) {
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
        boolean lexicalOk = roots.stream().anyMatch(base::startsWith);
        if (!lexicalOk) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 원본 저장 경로입니다.");
        }
        // 실경로(CWE-59) — allowlist 안의 심링크가 밖을 가리키는 우회를 차단한다.
        Path realBase = realOrNearest(base);
        boolean realOk = roots.stream().anyMatch(root -> realBase.startsWith(realOrNearest(root)));
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
        resolveRealPathUnder(target, verifiedBase);
    }

    /**
     * {@link #verifyRealPathUnder} 와 <b>동일 판정</b>이되, 통과한 <b>실경로를 돌려준다</b>
     * (B-ISSUE-81 — 읽기 소비자용).
     *
     * <h3>왜 실경로를 돌려줘야 하는가 (CWE-367/59)</h3>
     * <p>쓰기 경로는 "검증만" 하면 됐지만, 읽기 소비자(영상 스트리밍 등)는 <b>검증한 그 경로를 그대로
     * 열어야</b> 한다. lexical 경로로 판정하고 lexical 경로로 열면 판정~open 사이에 최종 컴포넌트를
     * 원본(비식별 이전) 영상 심링크로 바꿔치기해 <b>마스킹 전 픽셀이 "비식별본" 으로 서빙</b>된다
     * (CWE-359). {@code StorageSubtreePolicy.verifyDeidentifiedFile} 이 프레임 4경로에 대해 이미
     * 채택한 규약("판정이 돌려준 실경로를 그대로 사용")과 동일하다.
     *
     * @return base 하위임이 확인된 정규화 절대 <b>실경로</b>
     * @throws CustomException 실경로가 base 밖이거나 확인 불가(FORBIDDEN — fail-secure)
     */
    public static Path resolveRealPathUnder(Path target, Path verifiedBase) {
        if (target == null || verifiedBase == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "기준 경로가 없습니다.");
        }
        Path realTarget = realOrNearest(target.toAbsolutePath().normalize());
        if (!realTarget.startsWith(realOrNearest(verifiedBase.toAbsolutePath().normalize()))) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다.");
        }
        return realTarget;
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

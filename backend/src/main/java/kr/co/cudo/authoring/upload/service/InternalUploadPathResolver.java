package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 내부 업로드(REVIEWER TUS) 파일이 놓일 <b>인입 영역 경로</b> 결정 (Phase 1).
 *
 * <h3>확정 구조</h3>
 * <pre>
 * {authoring.storage.raw-path}/data/upload/v2/{vmsClipId}.{ext}
 * </pre>
 * <p>관제 NAS 클립 배치 규약(CONST-050)을 우리 저장소 base 기준 상대로 조립한 것이다. 세그먼트는
 * 문자열 연결이 아니라 {@link VideoArtifactRootResolver#resolveUnder} 로 붙여 구분자·상위참조가 섞인
 * 값을 즉시 거부한다(CWE-22 / CWE-73).
 *
 * <h3>왜 {@code vmsClipId} 에 allowlist 를 거는가</h3>
 * <p>이 값이 <b>파일명이 된다</b>. 관제 인입의 {@code VMS_CLIP_ID} 는 신뢰 경계 밖 자유 문자열이고,
 * 내부 업로드에서는 사용자가 직접 넣는 값이라 경로 순회({@code ../}), 구분자({@code /}·{@code \}),
 * NUL, 선행 점(숨김 파일)이 그대로 들어올 수 있다. 영문/숫자/{@code _}/{@code -} 1~64자만 허용한다.
 *
 * <h3>★ base 무결성은 <b>배포 시점</b>에 판정한다 (P0)</h3>
 * <p>여기서 만든 경로가 적재 allowlist({@code authoring.storage.raw-mount-roots}) 밖이면, 인입 폴링
 * ({@code TrainingVideoIngestTx#verifyPath})이 그 행을 {@code REJECTED → markFailed} 로 <b>영구
 * 종결</b>시킨다. 재큐해도 같은 실패가 반복되므로 업로드는 200 을 받고도 절대 적재되지 않는다.
 * onprem 설치 안내({@code deploy/onprem/config/backend/env.template})가 운영자에게 마운트 루트를
 * <b>좁히라고</b> 권장하므로 실제로 도달 가능한 형상이다. 그래서 요청마다 늦게 죽는 대신
 * {@link InternalUploadWiringGuard} 가 기동 시점에 판정해 <b>업로드 기능만</b> 닫는다(503) — 이
 * 클래스는 판정 결과({@link #baseUnderAllowedRoots()})만 계산하고, 계산 자체는 예외 없이
 * 끝낸다(가드가 판단 주체).
 *
 * <h3>★ 쓰기 직전 재판정은 <b>고정 allowlist</b> 축으로 한다 ({@link #verifyIngestable})</h3>
 * <p>기동 시점 판정은 그 시점의 파일시스템만 본다. 이후 {@code {raw-path}/data} 같은 <b>경로 중간
 * 디렉터리</b>가 다른 트리를 가리키는 심링크로 교체되면(CWE-59/367) 파일이 allowlist 밖에 기록된다.
 * 그래서 실제 쓰기 직전에 한 번 더 판정하되, 기준은 반드시 <b>요청과 무관한 고정 allowlist</b>여야
 * 한다 — 대상 자신({@link #uploadDir()})을 기준으로 삼으면 항등식이라 아무것도 판정하지 못한다.
 */
@Slf4j
@Component
public class InternalUploadPathResolver {

    /** 저장 파일명이 되는 값이라 파일시스템 안전 문자만 허용한다(CWE-22). */
    static final Pattern VMS_CLIP_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** 확장자 방어 — 호출부(세션 생성 allowlist) 통과분만 오지만 조립 직전에 한 번 더 좁힌다. */
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("^[a-z0-9]{1,8}$");

    /** 인입 영역 세그먼트 — {@code {base}/data/upload/v2/}. */
    static final String SEG_DATA = "data";
    static final String SEG_UPLOAD = "upload";
    static final String SEG_V2 = "v2";

    /** 인입 영역 디렉터리(절대경로). base 해석 자체가 실패했으면 null. */
    private final Path uploadDir;
    /** 인입 영역이 적재 allowlist 하위인가 — 배포 형상 정합의 유일한 판정값. */
    private final boolean baseUnderAllowedRoots;
    /** 쓰기 직전 재판정의 <b>독립 축</b>(고정 allowlist) — {@link #verifyIngestable} 위임 대상. */
    private final VideoArtifactRootResolver rootResolver;

    public InternalUploadPathResolver(
            VideoArtifactRootResolver rootResolver,
            @Value("${authoring.storage.raw-path:./storage/raw}") String rawPath) {
        this.rootResolver = rootResolver;
        this.uploadDir = resolveUploadDirQuietly(rawPath);
        this.baseUnderAllowedRoots = uploadDir != null && underAllowedRoots(rootResolver, uploadDir);
    }

    /**
     * <b>쓰기 직전 재판정</b> (CWE-59/367) — 실제 저장 경로가 <b>지금</b> 적재 allowlist
     * ({@code authoring.storage.raw-mount-roots}) 하위인지 다시 확인한다.
     *
     * <p>판정 기준은 대상 자신이 아니라 <b>고정 allowlist(독립 축)</b>다. 적재가 실제로 쓰는 판정기
     * ({@link VideoArtifactRootResolver#verifyIngestablePath})에 그대로 위임하므로 "업로드는 통과했는데
     * 적재는 거부"가 생기지 않으며, lexical + 실경로(심링크 해석) 2단 검사를 모두 거친다.
     *
     * @param target 저장할 파일의 절대경로(상위 디렉터리 기준으로 판정된다)
     * @throws CustomException allowlist·심링크 위반(FORBIDDEN) / 경로 손상(INVALID_INPUT)
     */
    public void verifyIngestable(Path target) {
        if (target == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "저장 경로가 없습니다.");
        }
        rootResolver.verifyIngestablePath(target.toString());
    }

    /**
     * 인입 대상 파일명을 <b>원자적·배타적으로 선점</b>한다 (DEV_FIX 2차 [A] — F3 원자성 복원).
     *
     * <h3>왜 "있는지 보고 옮긴다"로는 안 되는가 (CWE-362/367)</h3>
     * <p>{@code Files.move(…, ATOMIC_MOVE)} 는 대상이 있으면 <b>조용히 대체</b>한다. 그래서
     * {@code if (Files.exists(target)) …} 사전 검사 + 이동은 전형적인 <b>검사 후 사용</b>이다 —
     * 검사와 이동 사이에 다른 주체가 같은 이름을 만들면 그 실체를 <b>말없이 덮어쓴다</b>. 인입 영역
     * 파일은 곧 영상 1건이므로 그 대체는 "남의 영상이 사라졌는데 양쪽 다 성공(204)" 이다.
     *
     * <p>{@link Files#createFile} 은 {@code O_EXCL} 로 <b>이름 선점과 존재 검사를 한 연산</b>으로
     * 수행한다(원자적). 선점에 성공한 실행만 이후 그 이름에 내용을 실을 수 있고, 진 쪽은
     * {@link java.nio.file.FileAlreadyExistsException} 으로 <b>확실히</b> 진다.
     *
     * <h3>0바이트 예약이 적재되지 않는 이유 (H1 과 양립)</h3>
     * <p>예약은 최종 경로에 잠깐 <b>0바이트</b> 파일을 만든다. 그 상태가 폴링에 노출되어도
     * 적재 측 <b>완결성 게이트</b>({@code TrainingVideoIngestTx#isEmptyFile} — 크기 0 은
     * {@code NOT_ARRIVED})가 <b>대기</b>로 떨어뜨린다. 회수 실패로 예약이 잔존해도 그 게이트가 계속
     * 막으므로 빈 영상이 적재되는 일은 없다(그 대신 그 clipId 는 사람이 잔여물을 치울 때까지 재사용할
     * 수 없다 — 호출부가 WARN 으로 드러낸다).
     *
     * <p>검증은 예약도 <b>쓰기</b>이므로 {@link #verifyIngestable}(고정 allowlist 재판정)을 먼저 거친다.
     *
     * @param target 선점할 최종 경로(상위 디렉터리는 호출자가 만든다)
     * @throws java.nio.file.FileAlreadyExistsException 이미 다른 주체가 그 이름을 점유(호출부 409)
     * @throws IOException 그 외 생성 실패
     * @throws CustomException allowlist·심링크 위반(FORBIDDEN)
     */
    public void reserveIngestTarget(Path target) throws IOException {
        verifyIngestable(target);
        Files.createFile(target);
    }

    /**
     * 그 경로에 <b>파일이 실제로 도착</b>했는가 — 심링크·allowlist 판정을 포함한 진실원 (DEV_FIX 2차 [D]).
     *
     * <p>{@code Files.exists} 단독 판정은 <b>임의 대상 심링크</b>에 속는다. 그 경로가 허용 루트 밖의
     * 아무 파일이나 가리켜도 "도착했다"가 되어 ①인입 행 종결이 보류되고 ②그 clipId 의 되살리기가
     * 막힌다(가용성 방해). 적재 측 {@code TrainingVideoIngestTx#verifyPath} 와 <b>같은 규약</b>으로
     * 판정한다 — 고정 allowlist 재판정 → 실경로 해석 → 일반 파일 여부.
     *
     * <p>판정 불가(경로 손상·권한·깨진 링크·allowlist 밖)는 모두 <b>false</b>(미도착)다. 이 값은
     * "이 행을 종결해도 고아 파일이 생기지 않는가" 판정에 쓰이며, 여기서 미도착으로 보면 행이
     * {@code FAILED} 로 종결될 뿐(재큐 가능) 파일을 삭제하지는 않는다 — 반대 방향(가짜 도착)보다 안전하다.
     *
     * <p><b>크기는 보지 않는다</b> — 0바이트 예약 잔여물도 "그 이름이 점유돼 있다"는 사실은 같으므로
     * 도착으로 본다(그 행을 되살려 봐야 완료 시점 예약이 409 로 진다. 입구에서 먼저 알리는 편이 낫다).
     */
    public boolean arrivedRegularFile(String rawFilePathNm) {
        if (rawFilePathNm == null || rawFilePathNm.isBlank()) {
            return false;
        }
        Path path;
        try {
            path = Paths.get(rawFilePathNm).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return false;
        }
        Path parent = path.getParent();
        if (parent == null) {
            return false;
        }
        try {
            // ① 고정 allowlist + 상위 디렉터리 실경로 재판정(적재와 동일 판정기)
            verifyIngestable(path);
            // ② 최종 컴포넌트 심링크가 상위 밖을 가리키면 거부 — 판정한 실경로로만 판단한다(CWE-59)
            Path real = VideoArtifactRootResolver.resolveRealPathUnder(path, parent);
            return Files.isRegularFile(real);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 인입 영역 디렉터리. 조립 실패 형상에서는 null 이므로 <b>호출 전에</b>
     * {@link #baseUnderAllowedRoots()} 가 참임을 보장해야 한다(정상 형상에서는 항상 참).
     */
    public Path uploadDir() {
        return uploadDir;
    }

    /** 인입 영역이 적재 allowlist({@code raw-mount-roots}) 하위인가. */
    public boolean baseUnderAllowedRoots() {
        return baseUnderAllowedRoots;
    }

    /**
     * 그 경로가 <b>우리(내부 업로드)가 만든 인입 영역 파일</b>인가 (DEV_FIX M1).
     *
     * <h3>왜 이 판정이 필요한가 — "우리 행"과 "관제 행"의 구분</h3>
     * <p>종결된 인입 행을 <b>되살려 재사용</b>하려면 그 행을 우리가 만들었는지 알아야 한다. 관제가
     * 넣은 행을 우리가 새 메타로 덮어쓰면 <b>신뢰 경계를 넘는 쓰기</b>(CWE-915)이자 관제의 감사
     * 기록 위조다. {@code SRC_TYPE} 은 폼에서 고를 수 있어 판별자가 될 수 없고, 반대로
     * {@code RAW_FILE_PATH_NM} 은 <b>우리가 결정</b>한다 — 관제 행은 관제 NAS 경로를,
     * 우리 행은 {@code {base}/data/upload/v2/} 하위를 가리킨다.
     *
     * <p>비교는 <b>부모 디렉터리 동일성</b>으로 한다({@code startsWith} 가 아니다) — 이 영역은 평평한
     * 단일 디렉터리이므로, 하위 트리를 허용하면 존재하지 않는 구조를 인정하게 된다.
     *
     * @param rawFilePathNm 인입 행의 원시 파일 경로명(관제 자유값일 수 있다 — 손상 경로는 false)
     */
    public boolean isUploadAreaPath(String rawFilePathNm) {
        if (uploadDir == null || rawFilePathNm == null || rawFilePathNm.isBlank()) {
            return false;
        }
        try {
            Path parent = Paths.get(rawFilePathNm).toAbsolutePath().normalize().getParent();
            return uploadDir.equals(parent);
        } catch (RuntimeException e) {
            // 경로 손상(InvalidPathException 등) — 우리 파일일 수 없다.
            return false;
        }
    }

    /**
     * 업로드 파일의 최종 저장 경로 — {@code {base}/data/upload/v2/{vmsClipId}.{ext}}.
     *
     * @throws CustomException clipId·확장자 검증 실패(INVALID_INPUT) / base 오설정(INTERNAL_ERROR)
     */
    public Path resolveUploadTarget(String vmsClipId, String extension) {
        validateClipId(vmsClipId);
        if (extension == null || !EXTENSION_PATTERN.matcher(extension).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않는 확장자입니다.");
        }
        if (!baseUnderAllowedRoots) {
            // 가드가 배포 형상에서 이미 기동을 막는다 — 여기 도달은 local 형상뿐이다(fail-closed).
            log.error("[Tus] 내부 업로드 저장 base 가 적재 allowlist 밖이다 — {} 설정을 확인하세요.",
                    "authoring.storage.raw-mount-roots");
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "업로드 저장 경로 설정이 올바르지 않습니다.");
        }
        return VideoArtifactRootResolver.resolveUnder(uploadDir, vmsClipId + "." + extension);
    }

    /**
     * {@code vmsClipId} allowlist 검증 — 세션 생성(POST) 시점 fail-fast 용으로도 쓴다.
     *
     * @throws CustomException 비어 있거나 허용 문자 밖(INVALID_INPUT)
     */
    public static void validateClipId(String vmsClipId) {
        if (vmsClipId == null || vmsClipId.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "vmsClipId 는 필수입니다.");
        }
        if (!VMS_CLIP_ID_PATTERN.matcher(vmsClipId).matches()) {
            // CWE-209 — 입력 원문을 사용자 메시지에 담지 않는다(허용 규칙만 알린다).
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "vmsClipId 는 영문·숫자·'_'·'-' 조합 1~64자만 허용됩니다.");
        }
    }

    /** {@code {rawPath}/data/upload/v2} — 조립 실패(경로 손상·심링크 이탈)는 null 로 접는다. */
    private static Path resolveUploadDirQuietly(String rawPath) {
        try {
            Path base = Paths.get(rawPath).toAbsolutePath().normalize();
            return VideoArtifactRootResolver.resolveUnder(base, SEG_DATA, SEG_UPLOAD, SEG_V2);
        } catch (RuntimeException e) {
            log.error("[Tus] 내부 업로드 저장 base 해석 실패 — authoring.storage.raw-path 설정을 확인하세요."
                    + " causeType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 인입 영역이 적재 allowlist 하위인지 — <b>적재가 실제로 쓰는 판정기</b>에 그대로 위임한다.
     *
     * <p>같은 규칙을 여기서 다시 구현하면 두 판정이 갈라져 "업로드는 통과했는데 적재는 거부"가 다시
     * 생긴다. 실재하지 않는 파일명으로 probe 해도 {@code verifyIngestablePath} 는 <b>상위 디렉터리</b>
     * 기준으로 판정하므로 결과가 같다.
     */
    private static boolean underAllowedRoots(VideoArtifactRootResolver rootResolver, Path uploadDir) {
        try {
            rootResolver.verifyIngestablePath(uploadDir.resolve("probe.mp4").toString());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

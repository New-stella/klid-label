package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

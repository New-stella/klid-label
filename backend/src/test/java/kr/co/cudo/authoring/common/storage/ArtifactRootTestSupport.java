package kr.co.cudo.authoring.common.storage;

import java.nio.file.Path;

/**
 * {@link VideoArtifactRootResolver} 테스트 픽스처 — 전략별 리졸버를 한 줄로 만든다.
 *
 * <p>리졸버 생성자는 5개의 설정 문자열을 받으므로, 테스트마다 순서를 외우지 않도록 여기 모은다.
 */
public final class ArtifactRootTestSupport {

    /**
     * 통합 테스트(@SpringBootTest)용 <b>고정</b> allowlist 마운트 루트
     * ({@code authoring.storage.raw-mount-roots}). 테스트가 만드는 원본 영상은 이 하위에 놓아야
     * co-locate 산출 base 가 도출된다.
     *
     * <p><b>왜 여기(별도 클래스)인가</b> — 이 값은 클래스 <b>애노테이션</b>
     * ({@code @TestPropertySource(properties = ...)}) 인자로 쓰인다. ①애노테이션 인자는 컴파일 타임
     * 상수여야 하므로 {@code @TempDir} 같은 동적 값을 쓸 수 없고, ②클래스 자신의 애노테이션은 그 클래스
     * <b>body 밖</b>이라 자기 멤버를 단순명으로 참조할 수 없다(JLS 6.3 — 실제 컴파일 에러의 원인).
     * 따라서 <b>다른 클래스의 public static final 문자열 상수</b>여야 하며, 세 IT 가 같은 의미의 루트를
     * 쓰므로 산출 루트 테스트 지원 클래스인 여기에 한 벌만 둔다.
     *
     * <p>경로는 모듈 상대(build/ 하위) — Gradle {@code clean} 대상이라 워크스페이스를 오염시키지 않는다.
     */
    public static final String IT_MOUNT_ROOT = "build/tmp/deid-colocate-it";

    private ArtifactRootTestSupport() {
    }

    /**
     * {@link #IT_MOUNT_ROOT} 하위에 임시 <b>원본 영상</b>을 만든다(내용 {@code "raw-bytes"}).
     * co-locate 전략에서 원본 디렉터리가 곧 산출 base 이므로, IT 는 반드시 이 헬퍼로 원본을 심어야 한다.
     */
    public static Path seedOriginalVideo(String prefix) throws java.io.IOException {
        Path dir = Path.of(IT_MOUNT_ROOT, "videos").toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(dir);
        Path video = dir.resolve(prefix + "-" + System.nanoTime() + ".mp4");
        java.nio.file.Files.writeString(video, "raw-bytes");
        return video;
    }

    /**
     * co-locate mock 비식별 산출 경로 — {@code dirname(원본)/{rawSn}/deid/deidentified.mp4}.
     *
     * <p>파일명 {@code deidentified.mp4} 는 <b>mock 경로 전용</b>(우리가 직접 쓰는 파일)이다. KPST
     * 실연동 산출물명({@code {stem}-mask{ext}})은 외부가 정하므로 조합하지 않고
     * {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM} 값을 읽는다.
     */
    public static Path expectedMockDeidPath(Path originalVideo, long rawSn) {
        return originalVideo.getParent()
                .resolve(String.valueOf(rawSn))
                .resolve(VideoArtifactRootResolver.SEG_DEID)
                .resolve("deidentified.mp4");
    }

    /**
     * co-locate 전략(기본) — {@code allowedRoot} 하위의 원본 영상만 산출 base 로 인정한다.
     *
     * @param allowedRoot 고정 allowlist 마운트 루트(= raw-mount-roots)
     */
    public static VideoArtifactRootResolver coLocate(Path allowedRoot) {
        return new VideoArtifactRootResolver(
                allowedRoot.toString(),
                allowedRoot.resolve("raw").toString(),
                allowedRoot.resolve("deidentified").toString(),
                allowedRoot.resolve("labeling").toString(),
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
    }

    /** co-locate 전략 + raw/deid base 를 명시 지정(비식별 구 위치 검증용). */
    public static VideoArtifactRootResolver coLocate(Path allowedRoot, Path rawBase, Path deidBase) {
        return new VideoArtifactRootResolver(
                allowedRoot.toString(),
                rawBase.toString(),
                deidBase.toString(),
                allowedRoot.resolve("labeling").toString(),
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
    }

    /** labeling-root 전략(롤백) — 구 구조({@code {labelingRoot}/{rawSn}/…})를 그대로 쓴다. */
    public static VideoArtifactRootResolver labelingRoot(Path labelingRoot) {
        return labelingRoot(labelingRoot, labelingRoot.resolveSibling("deidentified"));
    }

    /**
     * labeling-root 전략(롤백) + <b>고정 allowlist 를 명시</b>한다.
     *
     * <p>롤백 후에도 이미 적재된 co-locate 절대경로를 읽을 수 있어야 하므로(S8), 읽기 후보 계산
     * ({@link VideoArtifactRootResolver#readableDeidVideoDirs})이 allowlist 를 필요로 한다.
     */
    public static VideoArtifactRootResolver labelingRootWithAllowedRoot(
            Path allowedRoot, Path labelingRoot, Path deidBase) {
        return new VideoArtifactRootResolver(
                allowedRoot.toString(),
                allowedRoot.resolve("raw").toString(),
                deidBase.toString(),
                labelingRoot.toString(),
                VideoArtifactRootResolver.STRATEGY_LABELING_ROOT);
    }

    /** labeling-root 전략(롤백) + 비식별 저장소 base 지정. */
    public static VideoArtifactRootResolver labelingRoot(Path labelingRoot, Path deidBase) {
        return new VideoArtifactRootResolver(
                "",
                labelingRoot.resolveSibling("raw").toString(),
                deidBase.toString(),
                labelingRoot.toString(),
                VideoArtifactRootResolver.STRATEGY_LABELING_ROOT);
    }
}

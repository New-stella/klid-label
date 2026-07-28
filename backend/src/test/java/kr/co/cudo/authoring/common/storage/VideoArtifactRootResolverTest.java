package kr.co.cudo.authoring.common.storage;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VideoArtifactRootResolver} 전용 단위 테스트.
 *
 * <p>다른 테스트는 전부 {@link ArtifactRootTestSupport} 를 통해 {@code raw-mount-roots} 를 <b>명시
 * 설정</b>하므로, 설정을 비웠을 때의 <b>폴백 allowlist</b> 분기가 어떤 테스트도 타지 않는다. 배포 형상
 * (docker-compose / onprem)이 실제로 이 분기에 의존하므로 여기서 직접 검증한다.
 *
 * <p>또한 안전하지 않은 설정({@code /} 등 파일시스템 루트) 거부(CWE-1188)와 쓰기 직전 재확인
 * ({@link VideoArtifactRootResolver#verifyRealPathUnder}, TOCTOU)을 검증한다.
 */
class VideoArtifactRootResolverTest {

    @TempDir
    Path tmp;

    /** {@code raw-mount-roots} 를 비운(=폴백) 리졸버. */
    private VideoArtifactRootResolver fallbackResolver(String configured, Path rawPath, Path deidPath) {
        return new VideoArtifactRootResolver(
                configured,
                "",
                rawPath.toString(),
                deidPath.toString(),
                tmp.resolve("labeling").toString(),
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
    }

    // ── 폴백 allowlist(A-3) ────────────────────────────────────────

    @Test
    @DisplayName("raw_mount_roots_미설정시_raw_path와_deidentified_path가_기본_allowlist로_사용된다")
    void unsetMountRoots_fallsBackToStorageBases() throws IOException {
        // given — 설정값 없음. 원본은 raw-path 하위, 파생영상은 deidentified-path 하위.
        Path rawPath = Files.createDirectories(tmp.resolve("storage/raw"));
        Path deidPath = Files.createDirectories(tmp.resolve("storage/deidentified"));
        VideoArtifactRootResolver resolver = fallbackResolver("", rawPath, deidPath);

        Path rawVideo = Files.write(rawPath.resolve("clip-001.mp4"), new byte[]{1});
        Path derivedVideo = Files.write(deidPath.resolve("aug-001.mp4"), new byte[]{1});

        // when
        Path fromRaw = resolver.videoRoot(11L, rawVideo.toString());
        Path fromDeid = resolver.videoRoot(12L, derivedVideo.toString());

        // then — 두 저장소 base 가 그대로 allowlist 로 쓰인다.
        assertThat(resolver.allowedRoots())
                .containsExactly(rawPath.toAbsolutePath().normalize(), deidPath.toAbsolutePath().normalize());
        assertThat(fromRaw).isEqualTo(rawPath.resolve("11"));
        assertThat(fromDeid).isEqualTo(deidPath.resolve("12"));
    }

    @Test
    @DisplayName("raw_mount_roots_가_공백이거나_콤마뿐이면_폴백_allowlist_가_적용된다")
    void blankOrCommaOnlyMountRoots_fallsBack() throws IOException {
        // given
        Path rawPath = Files.createDirectories(tmp.resolve("storage/raw"));
        Path deidPath = Files.createDirectories(tmp.resolve("storage/deidentified"));
        Path rawVideo = Files.write(rawPath.resolve("clip-001.mp4"), new byte[]{1});

        for (String configured : new String[]{"", "   ", ",,", " , , "}) {
            VideoArtifactRootResolver resolver = fallbackResolver(configured, rawPath, deidPath);

            // when / then — 어느 경우든 저장소 base 2종이 allowlist 다.
            assertThat(resolver.allowedRoots())
                    .as("configured=[%s]", configured)
                    .containsExactly(rawPath.toAbsolutePath().normalize(),
                            deidPath.toAbsolutePath().normalize());
            assertThat(resolver.videoRoot(21L, rawVideo.toString())).isEqualTo(rawPath.resolve("21"));
        }
    }

    @Test
    @DisplayName("폴백_allowlist_밖의_base_는_거부되고_기본_루트로_새지_않는다")
    void outsideFallbackAllowlist_isRejectedWithoutFallbackRoot() throws IOException {
        // given — 원본이 저장소 base 어디에도 속하지 않는다.
        Path rawPath = Files.createDirectories(tmp.resolve("storage/raw"));
        Path deidPath = Files.createDirectories(tmp.resolve("storage/deidentified"));
        VideoArtifactRootResolver resolver = fallbackResolver("", rawPath, deidPath);
        Path outsideVideo = Files.write(
                Files.createDirectories(tmp.resolve("elsewhere")).resolve("clip-x.mp4"), new byte[]{1});

        // when / then — FORBIDDEN 으로 종결(labeling root·raw path 로 조용히 새지 않는다).
        assertThatThrownBy(() -> resolver.videoRoot(31L, outsideVideo.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(resolver.videoRootQuietly(31L, outsideVideo.toString())).isEmpty();
        // 예외 메시지에 NAS 내부 경로를 담지 않는다(CWE-209).
        assertThatThrownBy(() -> resolver.videoRoot(31L, outsideVideo.toString()))
                .hasMessageNotContaining(tmp.toString());
    }

    // ── 안전하지 않은 설정 거부(B-1, CWE-1188) ─────────────────────

    @Test
    @DisplayName("raw_mount_roots_에_파일시스템_루트가_들어오면_기동에_실패한다")
    void filesystemRootAsMountRoot_failsStartup() {
        // given / when / then — allowlist 가 '어디든 허용'이 되면 가드가 사라지므로 빈 생성이 실패해야 한다.
        Path root = tmp.getRoot();
        assertThatThrownBy(() -> fallbackResolver(root.toString(), tmp.resolve("raw"), tmp.resolve("deid")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("raw-mount-roots");

        // 여러 값 중 하나만 루트여도 거부된다.
        assertThatThrownBy(() -> fallbackResolver(
                tmp.resolve("raw") + "," + root, tmp.resolve("raw"), tmp.resolve("deid")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("폴백_경로가_파일시스템_루트여도_기동에_실패한다")
    void filesystemRootAsFallbackBase_failsStartup() {
        // given — raw-mount-roots 미설정 + STORAGE_RAW_PATH=/ 같은 오설정.
        assertThatThrownBy(() -> fallbackResolver("", tmp.getRoot(), tmp.resolve("deid")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── 쓰기 직전 재확인(B-3, TOCTOU) ──────────────────────────────

    @Test
    @DisplayName("쓰기직전_재확인은_검증된_base_하위면_통과한다")
    void verifyRealPathUnder_passesForTargetInsideBase() throws IOException {
        // given
        Path base = Files.createDirectories(tmp.resolve("nas/clips/77"));
        Path target = Files.createDirectories(base.resolve("deid"));

        // when / then — 예외 없음
        VideoArtifactRootResolver.verifyRealPathUnder(target, base);
    }

    @Test
    @DisplayName("쓰기직전_대상이_base밖을_가리키는_심링크면_거부된다")
    void verifyRealPathUnder_rejectsSymlinkEscapingBase() throws IOException {
        // given — 검증 이후 {rawSn}/deid 가 base 밖 심링크로 바꿔치기된 상황을 모사한다.
        Path base = Files.createDirectories(tmp.resolve("nas/clips/78"));
        Path outside = Files.createDirectories(tmp.resolve("outside/target"));
        Path link = base.resolve("deid");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 심링크 미지원 환경(권한 등)에서는 검증 생략
        }

        // when / then
        assertThatThrownBy(() -> VideoArtifactRootResolver.verifyRealPathUnder(link, base))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ── 적재 시점 검증(B-2 지원) ───────────────────────────────────

    @Test
    @DisplayName("적재경로_검증은_allowlist_하위만_통과시킨다")
    void verifyIngestablePath_allowsOnlyAllowlisted() throws IOException {
        // given
        Path rawPath = Files.createDirectories(tmp.resolve("storage/raw"));
        Path deidPath = Files.createDirectories(tmp.resolve("storage/deidentified"));
        VideoArtifactRootResolver resolver = fallbackResolver("", rawPath, deidPath);

        // when / then — 허용 루트 하위(파일 미존재여도 base 는 검증 가능)
        resolver.verifyIngestablePath(rawPath.resolve("aug/winter.mp4").toString());

        // 밖이면 FORBIDDEN, 빈 값이면 INVALID_INPUT
        assertThatThrownBy(() -> resolver.verifyIngestablePath(tmp.resolve("elsewhere/x.mp4").toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> resolver.verifyIngestablePath("  "))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ── 외부 산출물 읽기 축(DEV_FIX 2차 HIGH-1) ─────────────────────

    @Test
    @DisplayName("목_산출_경로가_허용루트로_인식되어_프레임_반입이_성립한다")
    void externalReadRoot_allowsVendorOutputPath() throws IOException {
        // given — 실제 compose 형상: 쓰기 allowlist 는 /app/storage/{raw,deidentified} 이고
        //         목 산출물은 <별개 트리> /app/genai-out/genai/{job_id}/ 에 생성된다.
        Path storage = Files.createDirectories(tmp.resolve("app/storage/raw"));
        Path deid = Files.createDirectories(tmp.resolve("app/storage/deidentified"));
        Path genaiOut = Files.createDirectories(tmp.resolve("app/genai-out"));
        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                storage + "," + deid,
                genaiOut.toString(),
                storage.toString(), deid.toString(), tmp.resolve("labeling").toString(),
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
        String vendorOutput = genaiOut.resolve("genai/job-1/frame-1.jpg").toString();

        // when / then — 읽기 축은 통과한다(이게 없으면 콜백이 400 → 증강 영구 PENDING)
        resolver.verifyExternalReadablePath(vendorOutput);
        assertThat(resolver.readableRoots()).contains(genaiOut);

        // and — <쓰기> 축은 넓어지지 않는다(PII 격리 축 유지)
        assertThat(resolver.allowedRoots()).doesNotContain(genaiOut);
        assertThatThrownBy(() -> resolver.verifyIngestablePath(vendorOutput))
                .as("벤더 트리는 산출물 쓰기 base 로 승격되지 않아야 한다")
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("허용루트_밖_경로는_여전히_거부된다")
    void externalReadRoot_stillRejectsOutsidePaths() throws IOException {
        // given
        Path storage = Files.createDirectories(tmp.resolve("app/storage/raw"));
        Path deid = Files.createDirectories(tmp.resolve("app/storage/deidentified"));
        Path genaiOut = Files.createDirectories(tmp.resolve("app/genai-out"));
        Files.createDirectories(tmp.resolve("elsewhere"));
        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                storage.toString(), genaiOut.toString(),
                storage.toString(), deid.toString(), tmp.resolve("labeling").toString(),
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);

        // when / then — 읽기 루트 밖 + '..' 순회 + 빈 값 모두 거부(검증 절차는 쓰기 축과 동일)
        assertThatThrownBy(() ->
                resolver.verifyExternalReadablePath(tmp.resolve("elsewhere/secret.jpg").toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() ->
                resolver.verifyExternalReadablePath(genaiOut.resolve("../../etc/passwd").toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> resolver.verifyExternalReadablePath("   "))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("읽기루트_미설정이면_쓰기_allowlist와_동일하다")
    void externalReadRootUnset_equalsWriteAllowlist() throws IOException {
        // given — 기본 형상(미설정)에서 읽기 범위가 넓어지지 않아야 한다(fail-closed).
        Path rawPath = Files.createDirectories(tmp.resolve("storage/raw"));
        Path deidPath = Files.createDirectories(tmp.resolve("storage/deidentified"));
        VideoArtifactRootResolver resolver = fallbackResolver("", rawPath, deidPath);

        // then
        assertThat(resolver.readableRoots()).isEqualTo(resolver.allowedRoots());
    }

    @Test
    @DisplayName("읽기루트에_파일시스템_루트를_지정하면_기동이_실패한다")
    void externalReadRootFilesystemRoot_failsStartup() {
        // given / when / then — 넓힌 축에도 쓰기 축과 동일한 fail-closed 를 적용한다(CWE-1188).
        assertThatThrownBy(() -> new VideoArtifactRootResolver(
                tmp.resolve("storage/raw").toString(), java.io.File.separator,
                tmp.resolve("storage/raw").toString(), tmp.resolve("storage/deid").toString(),
                tmp.resolve("labeling").toString(), VideoArtifactRootResolver.STRATEGY_CO_LOCATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("external-read-roots");
    }
}

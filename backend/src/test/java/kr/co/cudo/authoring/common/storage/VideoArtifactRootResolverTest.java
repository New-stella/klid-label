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

    // ── B-ISSUE-41 — 비식별 영상 <읽기 허용 base> 스냅샷 + 산출물 4종 전수 커버리지 ─────────
    //
    // readableDeidVideoBases 는 "비식별 영상 파일을 열어도 되는 범위"를 정하는 입력값이고,
    // 그 범위 안이면 realpath 판정(resolveRealPathUnder)이 통과시킨다. 따라서 범위가 넓으면
    // 두 저장소 base 가 같은 온프렘 형상(/nas-storage)에서 그 안의 원본 산출물을 가리키는
    // 심링크가 "비식별본"으로 서빙된다(CWE-59/359). 아래는 ①현재 목록을 고정하고
    // ②좁히기 이후에도 실제 산출물 4종이 하나도 빠지지 않는지 못 박는 특성 테스트다.

    /** 온프렘 정상 형상 — {@code STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH}(= /nas-storage). */
    private VideoArtifactRootResolver sharedBaseResolver(Path nas) {
        return new VideoArtifactRootResolver(
                nas.toString(),
                "",
                nas.toString(),
                nas.toString(),
                tmp.resolve("labeling").toString(),
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
    }

    /** 어느 허용 base 하위이기라도 하면 true — 스트리밍 가드({@code resolveSafe})의 통과 조건과 동치. */
    private static boolean coveredByAnyBase(java.util.List<Path> bases, Path artifact) {
        return bases.stream().anyMatch(artifact::startsWith);
    }

    @Test
    @DisplayName("readableDeidVideoBases가_반환하는_모든_경로_목록을_스냅샷으로_고정한다")
    void readableDeidVideoBases_snapshot() throws IOException {
        // given — 온프렘 형상(raw==deid) + 원본 영상이 마운트 루트 하위에 존재(co-locate base 도출 조건)
        Path nas = Files.createDirectories(tmp.resolve("nas-storage"));
        Path original = Files.write(nas.resolve("clip-001.mp4"), new byte[]{1});
        VideoArtifactRootResolver resolver = sharedBaseResolver(nas);

        // when
        java.util.List<Path> bases = resolver.readableDeidVideoBases(77L, original.toString());

        // then — 목록은 정확히 이 4개다.
        //   (구 목록은 [{deid} 전체, {deid}/videos/77, {nas}/77/deid] 로 선두가 광역 base 였다 —
        //    B-ISSUE-41 로 광역 base 를 제거하고 파생영상 서브트리 2종을 명시 원소로 바꿨다.)
        assertThat(bases).containsExactly(
                // ① 구 위치 — {deid}/videos/{rawSn}/ (배포 전 산출물 · labeling-root 전략 산출물)
                nas.resolve("videos").resolve("77"),
                // ② 신 위치 — dirname(원본)/{rawSn}/deid/ (Phase 5A co-locate)
                nas.resolve("77").resolve("deid"),
                // ③ 증강 파생 영상 서브트리 — {deid}/videos/augment/{부모}/{파생}/…
                nas.resolve("videos").resolve("augment"),
                // ④ 해상도 파생 영상 서브트리 — {deid}/videos/resolution/{부모}/{파생}/…
                nas.resolve("videos").resolve("resolution"));
    }

    @Test
    @DisplayName("산출물_4종_실제_저장경로가_모두_읽기허용_base로_커버된다 — 좁히기 회귀 가드")
    void readableDeidVideoBases_coversAllFourArtifactKinds() throws IOException {
        // given — 온프렘 형상(raw==deid). 파생본은 자기 RAW_SN 으로 스트리밍되므로 base 도 자기 기준.
        Path nas = Files.createDirectories(tmp.resolve("nas-storage"));
        Path original = Files.write(nas.resolve("clip-001.mp4"), new byte[]{1});
        VideoArtifactRootResolver resolver = sharedBaseResolver(nas);

        // ②-a 비식별 영상 구 위치 — DeidentifyStep/KpstDeidentService(labeling-root 전략)
        Path legacyDeid = nas.resolve("videos/77/deidentified.mp4");
        // ②-b 비식별 영상 신 위치 — co-locate(KPST 실연동명 {stem}-mask.mp4)
        Path coLocateDeid = nas.resolve("77/deid/clip-001-mask.mp4");
        // ③ 증강 파생 — StorageSubtreePolicy.augmentVideoFile(부모=77, 파생=1001, WINTER)
        Path augment = nas.resolve(StorageSubtreePolicy.augmentVideoFile(77L, 1001L, "WINTER"));
        // ③' 증강 파생(구 규약 — 파생 RAW_SN 키 도입 전 잔존 행)
        Path augmentLegacy = nas.resolve("videos/augment/77/WINTER.mp4");
        // ④ 해상도 파생 — StorageSubtreePolicy.resolutionVideoFile(부모=77, 파생=1002, RESL_720P)
        Path resolution = nas.resolve(StorageSubtreePolicy.resolutionVideoFile(77L, 1002L, "RESL_720P"));
        // ④' 해상도 파생(구 규약)
        Path resolutionLegacy = nas.resolve("videos/resolution/77/RESL_720P.mp4");

        // when — 파생본은 자기 rawSn 으로 스트리밍된다(부모 rawSn 아님).
        java.util.List<Path> forOrigin = resolver.readableDeidVideoBases(77L, original.toString());
        java.util.List<Path> forAugment = resolver.readableDeidVideoBases(1001L, original.toString());
        java.util.List<Path> forResolution = resolver.readableDeidVideoBases(1002L, original.toString());

        // then — 4종(+구 규약 2종) 전부 커버된다.
        assertThat(coveredByAnyBase(forOrigin, legacyDeid)).as("②-a 비식별 구 위치").isTrue();
        assertThat(coveredByAnyBase(forOrigin, coLocateDeid)).as("②-b 비식별 co-locate").isTrue();
        assertThat(coveredByAnyBase(forAugment, augment)).as("③ 증강 파생").isTrue();
        assertThat(coveredByAnyBase(forAugment, augmentLegacy)).as("③' 증강 파생 구 규약").isTrue();
        assertThat(coveredByAnyBase(forResolution, resolution)).as("④ 해상도 파생").isTrue();
        assertThat(coveredByAnyBase(forResolution, resolutionLegacy)).as("④' 해상도 파생 구 규약").isTrue();
    }

    @Test
    @DisplayName("★두_저장소_base가_같아도_원본_산출물은_읽기허용_base에_들어오지_않는다 — B-ISSUE-41")
    void readableDeidVideoBases_excludesRawArtifacts() throws IOException {
        // given — 온프렘 정상 형상(raw==deid=/nas-storage). 이 형상에서 광역 base 를 허용하면
        //         그 안의 원본 영상·원본 프레임을 가리키는 심링크가 realpath 판정을 그대로 통과한다.
        Path nas = Files.createDirectories(tmp.resolve("nas-storage"));
        Path original = Files.write(nas.resolve("clip-001.mp4"), new byte[]{1});
        VideoArtifactRootResolver resolver = sharedBaseResolver(nas);

        // when
        java.util.List<Path> bases = resolver.readableDeidVideoBases(77L, original.toString());

        // then — 비식별 저장소 base 전체가 허용 범위에 들어오면 안 된다(원본까지 열린다).
        assertThat(bases).as("광역 base 미포함").doesNotContain(nas);
        assertThat(coveredByAnyBase(bases, original)).as("① 원본 영상은 비대상").isFalse();
        assertThat(coveredByAnyBase(bases, nas.resolve("frames/raw/77/frame-0.jpg")))
                .as("① 원본 프레임은 비대상").isFalse();
    }

    // ── B-ISSUE-41 CRITICAL 보강 — 세그먼트 시퀀스 정확 일치(CWE-59/706) ─────────
    //
    // 광역 base 를 제거해도 <허용 base 를 만드는> resolveUnder 의 내부 판정이
    // realOrNearest(target).startsWith(realOrNearest(base)) 인 한 취약점은 남는다 — base 가 광역
    // deidentifiedBase 이므로, 중간 디렉터리 자체가 <같은 base 안의 원본 서브트리>를 가리키는
    // 심링크여도 실경로가 여전히 base 하위라 통과한다(target·base 가 같은 링크를 거쳐 접히는 자기참조).
    // 판정을 "base 기준 상대 실경로의 세그먼트 시퀀스가 기대값과 정확히 일치하는가"로 바꾼다.

    /** 심링크를 만들 수 없는 환경이면 케이스를 건너뛴다. */
    private static void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "심링크 생성 불가 환경 — 케이스 skip");
        }
    }

    @Test
    @DisplayName("★resolveUnder는_중간세그먼트가_base안_다른위치를_가리키는_심링크면_거부한다 — 자기참조 차단")
    void resolveUnder_rejectsSegmentSymlinkPointingInsideSameBase() throws IOException {
        // given — base 안에 표적(원본 프레임 서브트리)을 두고, videos/{rawSn} 자체를 그쪽 심링크로 만든다.
        Path nas = Files.createDirectories(tmp.resolve("nas-storage"));
        Path rawFrames = Files.createDirectories(nas.resolve("frames/raw/900"));
        Files.createDirectories(nas.resolve("videos"));
        Path linked = nas.resolve("videos").resolve("900");
        createSymlinkOrSkip(linked, rawFrames);

        // when / then — 실경로 상대경로는 [frames, raw, 900] 이라 기대 [videos, 900] 과 불일치.
        assertThatThrownBy(() -> VideoArtifactRootResolver.resolveUnder(nas, "videos", "900"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("★심링크로_치환된_비식별영상_디렉터리는_읽기허용_base_목록에서_탈락한다")
    void readableDeidVideoBases_dropsSymlinkedSegments() throws IOException {
        // given — 온프렘 형상(raw==deid). videos/{rawSn}·videos/augment·videos/resolution·{rawSn}
        //         네 진입점을 모두 같은 base 안의 원본 프레임 서브트리로 돌린다.
        Path nas = Files.createDirectories(tmp.resolve("nas-storage"));
        Path original = Files.write(nas.resolve("clip-001.mp4"), new byte[]{1});
        VideoArtifactRootResolver resolver = sharedBaseResolver(nas);

        Path rawFrames = Files.createDirectories(nas.resolve("frames/raw/901"));
        Files.createDirectories(nas.resolve("videos"));
        createSymlinkOrSkip(nas.resolve("videos").resolve("77"), rawFrames);
        createSymlinkOrSkip(nas.resolve("videos").resolve(StorageSubtreePolicy.SEG_AUGMENT), rawFrames);
        createSymlinkOrSkip(nas.resolve("videos").resolve(StorageSubtreePolicy.SEG_RESOLUTION), rawFrames);
        createSymlinkOrSkip(nas.resolve("77"), rawFrames);

        // when
        java.util.List<Path> bases = resolver.readableDeidVideoBases(77L, original.toString());

        // then — 후보가 전부 탈락한다(fail-secure). 원본 프레임은 어떤 base 로도 커버되지 않는다.
        assertThat(bases).as("치환된 진입점은 허용 base 가 아니다").isEmpty();
        assertThat(coveredByAnyBase(bases, rawFrames.resolve("deidentified.mp4")))
                .as("원본 프레임 서브트리 미커버").isFalse();
    }

    @Test
    @DisplayName("심링크가_없으면_읽기허용_base_4종은_그대로_유지된다 — 좁히기 무회귀")
    void readableDeidVideoBases_unaffectedWithoutSymlinks() throws IOException {
        // given — 실제 디렉터리로 존재하는 정상 형상
        Path nas = Files.createDirectories(tmp.resolve("nas-storage"));
        Path original = Files.write(nas.resolve("clip-001.mp4"), new byte[]{1});
        Files.createDirectories(nas.resolve("videos/77"));
        Files.createDirectories(nas.resolve("videos/augment"));
        Files.createDirectories(nas.resolve("videos/resolution"));
        Files.createDirectories(nas.resolve("77/deid"));
        VideoArtifactRootResolver resolver = sharedBaseResolver(nas);

        // when / then
        assertThat(resolver.readableDeidVideoBases(77L, original.toString())).containsExactly(
                nas.resolve("videos").resolve("77"),
                nas.resolve("77").resolve("deid"),
                nas.resolve("videos").resolve("augment"),
                nas.resolve("videos").resolve("resolution"));
    }
}

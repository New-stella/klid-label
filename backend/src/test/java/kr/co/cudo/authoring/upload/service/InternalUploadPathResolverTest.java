package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 내부 업로드 인입 경로 결정 + 배포 형상 정합 검증 (Phase 1).
 *
 * <h3>왜 이 테스트가 P0 인가</h3>
 * <p>업로드 파일이 놓이는 경로가 {@code authoring.storage.raw-mount-roots}(적재 allowlist) 밖이면,
 * 인입 폴링({@code TrainingVideoIngestTx#verifyPath})이 그 행을 <b>REJECTED → markFailed</b> 로
 * <b>영구 종결</b>시킨다. 재큐해도 같은 실패가 반복되므로 업로드는 200 을 받고도 절대 적재되지 않는다.
 * onprem 설치 안내({@code env.template})가 운영자에게 마운트 루트를 <b>좁히라고</b> 권장하고 있어
 * 실제로 도달 가능한 형상이다 — 그래서 요청 시점이 아니라 <b>배포 시점</b>에 드러나야 한다.
 */
class InternalUploadPathResolverTest {

    /** docker-compose 실형상 — {@code STORAGE_RAW_MOUNT_ROOTS} 기본값. */
    private static final String DOCKER_ROOTS = "/app/storage/raw,/app/storage/deidentified";
    private static final String DOCKER_RAW_PATH = "/app/storage/raw";

    /** onprem 기본 형상 — {@code env.template} 의 출고 기본값. */
    private static final String ONPREM_ROOTS = "/nas-storage";
    private static final String ONPREM_RAW_PATH = "/nas-storage";

    /** onprem <b>좁힌</b> 형상 — {@code env.template:116} 이 운영자에게 권장하는 형태. */
    private static final String ONPREM_NARROWED_ROOTS =
            "/nas-storage/data/clip/gov,/nas-storage/label-studio/src";

    private static InternalUploadPathResolver resolver(String mountRoots, String rawPath) {
        VideoArtifactRootResolver rootResolver = new VideoArtifactRootResolver(
                mountRoots, "", rawPath, rawPath + "/deidentified", rawPath + "/labeling",
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
        return new InternalUploadPathResolver(rootResolver, rawPath);
    }

    // ======================== 경로 조립 ========================

    @Test
    @DisplayName("업로드_대상경로는_raw경로_하위_data_upload_v2_에_clipId_파일명으로_조립된다")
    void 업로드_대상경로는_raw경로_하위_data_upload_v2_에_clipId_파일명으로_조립된다() {
        // given — docker 실형상
        InternalUploadPathResolver sut = resolver(DOCKER_ROOTS, DOCKER_RAW_PATH);

        // when
        Path target = sut.resolveUploadTarget("CLIP_A-001", "mp4");

        // then — 관제 NAS 규약(CONST-050) 상대 조립. 문자열 연결이 아니라 세그먼트 resolve.
        assertThat(target).isEqualTo(Path.of("/app/storage/raw/data/upload/v2/CLIP_A-001.mp4"));
    }

    // ======================== vmsClipId allowlist (CWE-22) ========================

    @ParameterizedTest
    @ValueSource(strings = {
            "../evil",          // 상위 순회
            "a/b",              // 구분자
            "a\\b",             // Windows 구분자
            "..",               // 상위 참조 단독
            "clip id",          // 공백
            ".hidden",          // 선행 점
            "clip$id",          // 특수문자
            "클립1",             // 비 ASCII
    })
    @DisplayName("vmsClipId가_allowlist_밖이면_경로조립이_거부된다")
    void vmsClipId가_allowlist_밖이면_경로조립이_거부된다(String clipId) {
        InternalUploadPathResolver sut = resolver(DOCKER_ROOTS, DOCKER_RAW_PATH);

        assertThatThrownBy(() -> sut.resolveUploadTarget(clipId, "mp4"))
                .isInstanceOf(CustomException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("vmsClipId가_없으면_경로조립이_거부된다")
    void vmsClipId가_없으면_경로조립이_거부된다(String clipId) {
        InternalUploadPathResolver sut = resolver(DOCKER_ROOTS, DOCKER_RAW_PATH);

        assertThatThrownBy(() -> sut.resolveUploadTarget(clipId, "mp4"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("vmsClipId에_NUL문자가_섞이면_경로조립이_거부된다")
    void vmsClipId에_NUL문자가_섞이면_경로조립이_거부된다() {
        InternalUploadPathResolver sut = resolver(DOCKER_ROOTS, DOCKER_RAW_PATH);

        assertThatThrownBy(() -> sut.resolveUploadTarget("clip" + (char) 0x00 + "id", "mp4"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("vmsClipId가_64자를_넘으면_경로조립이_거부되고_64자는_통과한다")
    void vmsClipId_길이_경계() {
        InternalUploadPathResolver sut = resolver(DOCKER_ROOTS, DOCKER_RAW_PATH);

        assertThatCode(() -> sut.resolveUploadTarget("A".repeat(64), "mp4")).doesNotThrowAnyException();
        assertThatThrownBy(() -> sut.resolveUploadTarget("A".repeat(65), "mp4"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("확장자가_allowlist_밖이면_경로조립이_거부된다")
    void 확장자가_allowlist_밖이면_경로조립이_거부된다() {
        InternalUploadPathResolver sut = resolver(DOCKER_ROOTS, DOCKER_RAW_PATH);

        assertThatThrownBy(() -> sut.resolveUploadTarget("CLIP-1", "../sh"))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> sut.resolveUploadTarget("CLIP-1", ""))
                .isInstanceOf(CustomException.class);
    }

    // ======================== 배포 형상 정합 (P0) ========================

    @Test
    @DisplayName("docker_형상에서는_업로드_경로가_적재_allowlist를_통과한다")
    void docker_형상에서는_업로드_경로가_적재_allowlist를_통과한다() {
        assertThat(resolver(DOCKER_ROOTS, DOCKER_RAW_PATH).baseUnderAllowedRoots()).isTrue();
    }

    @Test
    @DisplayName("onprem_기본_형상에서는_업로드_경로가_적재_allowlist를_통과한다")
    void onprem_기본_형상에서는_업로드_경로가_적재_allowlist를_통과한다() {
        assertThat(resolver(ONPREM_ROOTS, ONPREM_RAW_PATH).baseUnderAllowedRoots()).isTrue();
    }

    @Test
    @DisplayName("마운트루트_미설정_폴백_형상에서도_업로드_경로가_적재_allowlist를_통과한다")
    void 마운트루트_미설정_폴백_형상에서도_통과한다() {
        // given — raw-mount-roots 미설정 → allowlist 폴백 = [raw-path, deidentified-path]
        assertThat(resolver("", "/app/storage/raw").baseUnderAllowedRoots()).isTrue();
    }

    @Test
    @DisplayName("onprem_좁힌_형상은_업로드_경로가_적재_allowlist_밖이라_업로드_기능이_닫힌다")
    void onprem_좁힌_형상은_업로드_기능이_닫힌다() {
        // given — 운영자가 env.template 권장대로 마운트 루트를 클립 서브트리로 좁힌 형상
        InternalUploadPathResolver sut = resolver(ONPREM_NARROWED_ROOTS, ONPREM_RAW_PATH);

        // then — 업로드 경로가 적재 allowlist 밖이다(= 인입이 REJECTED 로 영구 종결될 형상)
        assertThat(sut.baseUnderAllowedRoots()).isFalse();

        // then — ★배포 형상에서는 요청마다 늦게 죽는 대신 배포 시점 판정이 거부한다.
        //   F4-b 이후 이 거부의 결과는 <앱 기동 차단>이 아니라 <업로드 기능 503>이다
        //   (배선·범위 검증은 InternalUploadWiringGuardTest).
        assertThatThrownBy(() -> InternalUploadWiringGuard.verify(List.of("prd"), "prd", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("raw-mount-roots");
    }

    @Test
    @DisplayName("배포형상_판정은_전부_엄격하고_local에서만_통과한다")
    void 기동가드_allowlist() {
        // 정상 형상은 어디서든 통과
        assertThatCode(() -> InternalUploadWiringGuard.verify(List.of("prd"), "prd", true))
                .doesNotThrowAnyException();

        // local(테스트/개발 단일 형상)만 합성 allowlist 를 허용한다
        assertThatCode(() -> InternalUploadWiringGuard.verify(List.of("local"), null, false))
                .doesNotThrowAnyException();

        // dev/stg/prd·미지정·혼합·오타는 자동으로 엄격(fail-closed)
        assertThatThrownBy(() -> InternalUploadWiringGuard.verify(List.of("dev"), null, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InternalUploadWiringGuard.verify(List.of(), null, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InternalUploadWiringGuard.verify(List.of("local", "prd"), null, false))
                .isInstanceOf(IllegalStateException.class);
        // 배포 표식(ENV)이 프로파일 축을 이긴다 — local 로 낮춰도 우회 불가
        assertThatThrownBy(() -> InternalUploadWiringGuard.verify(List.of("local"), "prd", false))
                .isInstanceOf(IllegalStateException.class);
    }
}

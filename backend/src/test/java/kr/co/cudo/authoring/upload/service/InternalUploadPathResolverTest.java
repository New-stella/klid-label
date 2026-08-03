package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ======================== 원자 예약 (DEV_FIX 2차 [A] — F3) ========================

    @Test
    @DisplayName("F3_같은_이름을_동시에_예약하면_정확히_한_쪽만_성공한다 — 배타성이_없으면_FAIL")
    void 인입대상_예약은_원자적_배타여야_한다(@TempDir Path storage) throws Exception {
        // given — 같은 파일명을 노리는 8개 실행(경합 시작을 배리어로 맞춘다)
        InternalUploadPathResolver sut = tempResolver(storage);
        Path target = sut.resolveUploadTarget("RACE-1", "mp4");
        Files.createDirectories(target.getParent());

        int racers = 8;
        CyclicBarrier start = new CyclicBarrier(racers);
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        sut.reserveIngestTarget(target);
                        reserved.incrementAndGet();
                    } catch (FileAlreadyExistsException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // then — ★한 실행만 이름을 얻고 나머지는 <확실히> 진다.
        //   "있는지 보고 만든다" 식 비배타 구현이면 여럿이 성공한다(그 뒤 서로의 파일을 덮는다).
        assertThat(reserved.get()).as("예약 성공 수").isEqualTo(1);
        assertThat(rejected.get()).as("예약 실패 수").isEqualTo(racers - 1);
        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    @DisplayName("F3_예약은_쓰기_직전_allowlist_재판정을_거친다 — 허용_밖에는_예약도_만들지_않는다")
    void 예약도_쓰기이므로_경로판정을_거친다(@TempDir Path storage) {
        // given — allowlist 밖 경로(다른 트리)
        InternalUploadPathResolver sut = tempResolver(storage);
        Path outside = storage.getParent().resolve("outside-" + System.nanoTime() + ".mp4");

        // when / then — 예약 자체가 거부된다(파일도 만들어지지 않는다)
        assertThatThrownBy(() -> sut.reserveIngestTarget(outside))
                .isInstanceOf(CustomException.class);
        assertThat(Files.exists(outside)).isFalse();
    }

    // ======================== 도착 판정 (DEV_FIX 2차 [D]) ========================

    @Test
    @DisplayName("D_도착판정은_실제_일반파일만_인정한다 — 없는_경로_디렉터리_손상경로는_미도착")
    void 도착판정_기본(@TempDir Path storage) throws Exception {
        InternalUploadPathResolver sut = tempResolver(storage);
        Path target = sut.resolveUploadTarget("ARRIVE-1", "mp4");
        Files.createDirectories(target.getParent());

        assertThat(sut.arrivedRegularFile(target.toString())).as("아직 없다").isFalse();
        assertThat(sut.arrivedRegularFile(null)).isFalse();
        assertThat(sut.arrivedRegularFile("  ")).isFalse();
        assertThat(sut.arrivedRegularFile(target.getParent().toString()))
                .as("디렉터리는 도착이 아니다").isFalse();

        Files.writeString(target, "video");
        assertThat(sut.arrivedRegularFile(target.toString())).as("도착").isTrue();
    }

    @Test
    @DisplayName("D_임의_대상_심링크는_도착으로_보지_않는다 — exists_단독판정은_속는다")
    void 도착판정_심링크(@TempDir Path storage) throws Exception {
        // given — 인입 경로가 허용 루트 <밖>의 아무 파일이나 가리키는 심링크다.
        //   구 구현(Files.exists)은 true 를 돌려줘 ①인입 행 종결이 보류되고 ②그 clipId 되살리기가
        //   영구히 막혔다(가용성 방해).
        InternalUploadPathResolver sut = tempResolver(storage);
        Path target = sut.resolveUploadTarget("SYMLINK-1", "mp4");
        Files.createDirectories(target.getParent());
        Path outside = Files.createTempFile("outside-victim", ".mp4");
        try {
            try {
                Files.createSymbolicLink(target, outside);
            } catch (UnsupportedOperationException | java.io.IOException e) {
                org.junit.jupiter.api.Assumptions.abort("심링크 미지원 파일시스템");
                return;
            }

            // when / then — 실경로가 인입 영역 밖이므로 도착이 아니다
            assertThat(Files.exists(target)).as("exists 단독 판정은 참이다").isTrue();
            assertThat(sut.arrivedRegularFile(target.toString()))
                    .as("실경로 판정이 없으면 임의 대상 심링크로 종결·되살리기를 막을 수 있다")
                    .isFalse();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /** {@code @TempDir} 를 raw-path 이자 allowlist 로 쓰는 resolver(실디스크 검증용). */
    private static InternalUploadPathResolver tempResolver(Path storage) {
        VideoArtifactRootResolver rootResolver = new VideoArtifactRootResolver(
                storage.toString(), "", storage.toString(),
                storage.resolve("deidentified").toString(), storage.resolve("labeling").toString(),
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
        return new InternalUploadPathResolver(rootResolver, storage.toString());
    }
}

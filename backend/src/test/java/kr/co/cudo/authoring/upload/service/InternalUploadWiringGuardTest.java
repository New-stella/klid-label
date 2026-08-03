package kr.co.cudo.authoring.upload.service;

import jakarta.annotation.PostConstruct;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.upload.controller.TusUploadController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 내부 업로드 배선 가드의 <b>배선 자체</b>를 검증한다 (DEV_FIX F4 / F4-b).
 *
 * <h3>왜 순수 함수 테스트만으로는 부족한가</h3>
 * <p>기존 테스트는 순수 판정 함수({@code verify})만 봤다. 그래서 {@code check()} 본문을
 * {@code return;} 으로 바꾸는 mutation 을 넣어도 <b>101 테스트가 전원 통과</b>했다(적대검증 실측).
 * "코드·테스트 다 맞는데 배포 형상 실동작 0건" 계열 사고와 동일한 실패형이므로,
 * ①{@code @PostConstruct} 가 실제로 붙어 있는지 ②{@code check()} 가 판정 결과를 실제 상태로
 * 반영하는지 ③그 상태가 엔드포인트까지 <b>배선</b>돼 있는지를 직접 단언한다.
 *
 * <h3>F4-b — 실패 범위는 앱 전체가 아니라 업로드 기능</h3>
 * <p>onprem 설치 안내가 마운트 루트를 좁히라고 권장하므로, 그 안내를 따른 형상에서 기동을 거부하면
 * 업로드 1개 기능의 오설정으로 라벨링·검수·배치까지 정지한다. 앱은 뜨고 TUS 엔드포인트만 503 이다.
 */
class InternalUploadWiringGuardTest {

    /** onprem <b>좁힌</b> 형상 — {@code env.template} 이 운영자에게 권장하는 형태(업로드 경로 제외). */
    private static final String NARROWED_ROOTS =
            "/nas-storage/data/clip/gov,/nas-storage/label-studio/src";
    private static final String RAW_PATH = "/nas-storage";
    private static final String OK_ROOTS = "/nas-storage";

    private static InternalUploadPathResolver resolver(String mountRoots, String rawPath) {
        return new InternalUploadPathResolver(
                new VideoArtifactRootResolver(mountRoots, "", rawPath, rawPath + "/deidentified",
                        rawPath + "/labeling", VideoArtifactRootResolver.STRATEGY_CO_LOCATE),
                rawPath);
    }

    private static MockEnvironment env(String profile, String envMarker) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        if (envMarker != null) {
            environment.setProperty("ENV", envMarker);
        }
        return environment;
    }

    // ======================== 배선 (F4) ========================

    @Test
    @DisplayName("F4_check는_PostConstruct로_기동시_실행된다")
    void checkIsAnnotatedWithPostConstruct() throws Exception {
        Method check = InternalUploadWiringGuard.class.getDeclaredMethod("check");

        assertThat(check.getAnnotation(PostConstruct.class))
                .as("가드 판정이 기동 시점에 실행되지 않으면 오설정이 요청 시점까지 드러나지 않는다")
                .isNotNull();
    }

    @Test
    @DisplayName("F4_check가_판정결과를_실제_기능상태로_반영한다 — no-op이면_이_테스트가_깨진다")
    void checkAppliesVerdictToFeatureState() {
        // given — 배포 형상 + 업로드 경로가 적재 allowlist 밖(= verify 가 거부하는 형상)
        InternalUploadWiringGuard blocked = new InternalUploadWiringGuard(
                env("prd", "prd"), resolver(NARROWED_ROOTS, RAW_PATH));
        // 사전 조건: 판정 자체는 거부다(순수 함수 축)
        assertThatThrownBy(() -> InternalUploadWiringGuard.verify(java.util.List.of("prd"), "prd", false))
                .isInstanceOf(IllegalStateException.class);

        // when — 기동 훅 실행
        blocked.check();

        // then — 기능이 닫힌다
        assertThat(blocked.uploadEnabled()).isFalse();
        assertThatThrownBy(blocked::requireUploadEnabled)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);

        // then — ★양방향을 함께 단언해야 mutation 에 민감하다. 상태 기본값이 fail-closed(false)라
        //   "닫힘"만 단언하면 check() 를 no-op 으로 만들어도 통과한다(실측). 정상 형상에서 <열리는지>가
        //   check() 가 실제로 판정을 반영하는지를 가르는 축이다.
        InternalUploadWiringGuard opened = new InternalUploadWiringGuard(
                env("prd", "prd"), resolver(OK_ROOTS, RAW_PATH));
        opened.check();
        assertThat(opened.uploadEnabled())
                .as("check() 가 판정을 반영하지 않으면(no-op) 정상 형상에서도 열리지 않는다")
                .isTrue();
    }

    @Test
    @DisplayName("F4_판정_전에는_fail_closed다 — PostConstruct가_실행되지_않으면_닫힌_채로_남는다")
    void featureIsClosedBeforeCheckRuns() {
        InternalUploadWiringGuard notChecked = new InternalUploadWiringGuard(
                env("prd", "prd"), resolver(OK_ROOTS, RAW_PATH));

        assertThat(notChecked.uploadEnabled())
                .as("가드가 실행되지 않은 상태에서 조용히 열리면 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("F4b_정상형상에서는_앱도_기동하고_업로드도_열린다")
    void validConfigurationKeepsUploadOpen() {
        InternalUploadWiringGuard ok = new InternalUploadWiringGuard(
                env("prd", "prd"), resolver(OK_ROOTS, RAW_PATH));

        assertThatCode(ok::check).doesNotThrowAnyException();
        assertThat(ok.uploadEnabled()).isTrue();
        assertThatCode(ok::requireUploadEnabled).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("F4b_오설정이어도_기동은_막지_않는다 — 실패범위는_업로드_기능뿐")
    void misconfigurationDoesNotBlockApplicationStartup() {
        InternalUploadWiringGuard blocked = new InternalUploadWiringGuard(
                env("prd", "prd"), resolver(NARROWED_ROOTS, RAW_PATH));

        // then — ★앱 전체 기동을 거부하면 업로드 1개 기능 오설정으로 라벨링·검수·배치까지 멈춘다
        assertThatCode(blocked::check)
                .as("기동 차단이 아니라 기능 차단이어야 한다")
                .doesNotThrowAnyException();
        assertThat(blocked.uploadEnabled()).isFalse();
    }

    // ======================== F5: 인입 스캔 비활성 관측 ========================

    @Test
    @DisplayName("F5_인입스캔이_꺼져있으면_ingestScanEnabled가_false로_드러난다")
    void ingestScanDisabledIsObservable() {
        MockEnvironment environment = env("local", null);
        environment.setProperty(InternalUploadWiringGuard.KEY_TRAINING_SCAN_ENABLED, "false");
        InternalUploadWiringGuard guard =
                new InternalUploadWiringGuard(environment, resolver(OK_ROOTS, RAW_PATH));

        guard.check();

        assertThat(guard.ingestScanEnabled()).isFalse();
        // 기능은 닫지 않는다 — local/test 형상은 스케줄러를 의도적으로 끈다
        assertThat(guard.uploadEnabled()).isTrue();
    }

    @Test
    @DisplayName("F5_미설정이면_인입스캔은_켜진_것으로_본다")
    void ingestScanDefaultsToEnabled() {
        InternalUploadWiringGuard guard =
                new InternalUploadWiringGuard(env("local", null), resolver(OK_ROOTS, RAW_PATH));

        guard.check();

        assertThat(guard.ingestScanEnabled()).isTrue();
    }

    // ======================== F4-b: 엔드포인트 배선 (503) ========================

    @Test
    @DisplayName("F4b_업로드_기능이_닫히면_TUS_엔드포인트가_503이고_서비스는_호출되지_않는다")
    void tusEndpointsReturn503WhenUploadDisabled() {
        // given — 오설정으로 기능이 닫힌 가드가 컨트롤러에 배선돼 있다
        InternalUploadWiringGuard blocked = new InternalUploadWiringGuard(
                env("prd", "prd"), resolver(NARROWED_ROOTS, RAW_PATH));
        blocked.check();
        TusUploadService service = mock(TusUploadService.class);
        TusUploadController controller = new TusUploadController(service, blocked);
        UUID uploadId = UUID.randomUUID();

        // when/then — POST/HEAD/PATCH/DELETE 전부 503 (조용한 성공·원본 경로 폴백 금지)
        assertServiceUnavailable(() -> controller.create("1.0.0", 10L, null, null));
        assertServiceUnavailable(() -> controller.head("1.0.0", uploadId, null));
        assertServiceUnavailable(() -> controller.patch("1.0.0", 0L, 10L, uploadId, null, null));
        assertServiceUnavailable(() -> controller.delete("1.0.0", uploadId, null));

        // then — 업로드 로직에 도달조차 하지 않는다(REJECTED 인입 행을 만들지 않는다)
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("F5_업로드_완료응답에_인입_대기_상태가_드러난다 — 스캔이_꺼져있으면_그_사실도")
    void completionResponseExposesIngestStatus() {
        // given — 정상 형상 + 인입 스캔 OFF
        MockEnvironment environment = env("local", null);
        environment.setProperty(InternalUploadWiringGuard.KEY_TRAINING_SCAN_ENABLED, "false");
        InternalUploadWiringGuard guard =
                new InternalUploadWiringGuard(environment, resolver(OK_ROOTS, RAW_PATH));
        guard.check();
        TusUploadService service = mock(TusUploadService.class);
        UUID uploadId = UUID.randomUUID();
        when(service.appendChunk(any(), any(), anyLong(), any(), anyLong()))
                .thenReturn(new TusUploadService.TusPatchResult(8L, true, 7001L));

        // when — 마지막 청크(완료)
        var response = new TusUploadController(service, guard)
                .patch("1.0.0", 0L, 8L, uploadId, requestWith(new byte[8]), claims());

        // then — ★완료 != 적재. 폴링이 꺼져 있다는 사실이 응답에 드러난다.
        assertThat(response.getHeaders().getFirst("X-Ingest-Status"))
                .isEqualTo("PENDING_SCAN_DISABLED");
    }

    @Test
    @DisplayName("F5_인입스캔이_켜져있으면_완료응답은_PENDING이고_미완료청크에는_헤더가_없다")
    void completionResponseExposesPendingWhenScanEnabled() {
        InternalUploadWiringGuard guard =
                new InternalUploadWiringGuard(env("local", null), resolver(OK_ROOTS, RAW_PATH));
        guard.check();
        TusUploadService service = mock(TusUploadService.class);
        TusUploadController controller = new TusUploadController(service, guard);
        UUID uploadId = UUID.randomUUID();

        when(service.appendChunk(any(), any(), anyLong(), any(), anyLong()))
                .thenReturn(new TusUploadService.TusPatchResult(8L, true, 7001L));
        assertThat(controller.patch("1.0.0", 0L, 8L, uploadId, requestWith(new byte[8]), claims())
                .getHeaders().getFirst("X-Ingest-Status")).isEqualTo("PENDING");

        // 중간 청크(미완료)에는 인입 상태가 없다 — 아직 인입 행이 만들어지지 않았다
        when(service.appendChunk(any(), any(), anyLong(), any(), anyLong()))
                .thenReturn(new TusUploadService.TusPatchResult(4L, false, null));
        assertThat(controller.patch("1.0.0", 0L, 4L, uploadId, requestWith(new byte[4]), claims())
                .getHeaders().getFirst("X-Ingest-Status")).isNull();
    }

    private static MockHttpServletRequest requestWith(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        return request;
    }

    private static TokenClaims claims() {
        return new TokenClaims("reviewer-1", Role.REVIEWER, Channel.INTERNAL, null);
    }

    private static void assertServiceUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }
}

package kr.co.cudo.authoring.upload.service;

import jakarta.annotation.PostConstruct;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 내부 업로드(REVIEWER TUS) 배선 가드 — 배포 형상에서 저장 경로가 오설정이면
 * <b>업로드 기능만</b> 닫는다(앱은 정상 기동, 엔드포인트 503).
 *
 * <h3>왜 조용한 성공이 아니라 차단인가 (P0 의도 — 유지)</h3>
 * <p>업로드 저장 경로({@code {raw-path}/data/upload/v2})가 적재 allowlist
 * ({@code authoring.storage.raw-mount-roots}) 밖이면, 업로드는 <b>200 을 받고 정상으로 보이지만</b>
 * 인입 폴링({@code TrainingVideoIngestTx#verifyPath})이 그 행을 {@code REJECTED → markFailed} 로
 * <b>영구 종결</b>시킨다. 재큐해도 같은 실패가 반복되므로 사람이 설정을 고치기 전에는 어떤 업로드도
 * 적재되지 않는다. onprem 설치 안내({@code deploy/onprem/config/backend/env.template})가 운영자에게
 * 마운트 루트를 <b>좁히라고</b> 권장하고 있어 실제로 도달 가능한 형상이다. 따라서 ①배포 시점에
 * 드러나야 하고 ②원본 경로로 폴백하거나 조용히 성공시키지 않으며 ③REJECTED 인입 행을 만들지 않는다.
 *
 * <h3>★ 실패 범위는 <b>앱 전체가 아니라 업로드 기능</b>이다 (DEV_FIX F4-b)</h3>
 * <p>구 구현은 {@code @PostConstruct} 에서 예외를 던져 <b>애플리케이션 기동 자체를 거부</b>했다.
 * 그런데 마운트 루트를 좁히라는 안내를 그대로 따른 형상이 바로 이 조건에 걸리므로, 업로드 1개 기능의
 * 오설정 때문에 <b>라벨링·검수·배치까지 전부 정지</b>한다. 실패 범위를 유발 기능으로 한정한다 —
 * 기동 시 <b>ERROR</b> 1회(WARN 은 배포 로그에 묻힌다)를 남기고 TUS 엔드포인트만 503 으로 닫는다.
 * ({@code QuartzClusteringGuard} 의 기동 차단과 강도가 다른 이유는 그쪽 오설정이 <b>전 기능에 걸친
 * 데이터 정합</b>을 깨는 반면 여기는 업로드 1개 기능이기 때문이다.)
 *
 * <p>⚠ <b>구 서술 폐기</b> — 위 괄호가 {@code GenAiIntegrationWiringGuard} 도 기동 차단 사례로 함께
 * 들고 있었으나, 그 가드는 2026-09-03([@design ADR-062])에 <b>이 클래스와 같은 형태</b>(기동 시
 * ERROR 1회 + 해당 기능만 거부)로 옮겨졌다 — 대비 사례가 아니라 <b>선례가 같아진 쪽</b>이다.
 * 반면 {@code QuartzClusteringGuard} 는 그 결정의 대상이 <b>아니라</b> 기동 차단을 유지한다.
 *
 * <h3>allowlist 설계 — "설정만으로 배포 형상을 뚫을 수 없다"</h3>
 * <ol>
 *   <li><b>{@link #SYNTHETIC_ROOT_PROFILES} 에서만</b> 합성 allowlist(=업로드 경로와 어긋난 마운트
 *       루트)를 허용한다. local 형상의 통합 테스트들은 <b>다른 서브시스템</b> 검증을 위해 좁은
 *       마운트 루트를 일부러 지정하고 그 형상에서 내부 업로드를 쓰지 않는다. denylist 가 아니므로
 *       오타·미지정(default)·혼합 프로파일은 자동으로 엄격이다(fail-closed).</li>
 *   <li><b>배포 표식({@code ENV}) 독립 축</b> — {@code SPRING_PROFILES_ACTIVE} 를 local 로 낮춰
 *       배포 서버에 올리는 실수를 프로파일 축만으로는 잡지 못한다.
 *       {@code DevProfileGuard.DEPLOYED_ENVS} 와 동일 기준을 재사용해 <b>배포 쪽이 이긴다</b>.</li>
 *   <li><b>판정은 순수 함수({@link #verify})</b> — 컨테이너 없이 단위 검증한다. 다만 순수 함수만
 *       테스트하면 {@link #check()} 를 no-op 으로 만들어도 전원 통과하므로(실측), 배선 자체를
 *       검증하는 테스트({@code InternalUploadWiringGuardTest})가 함께 있어야 한다.</li>
 * </ol>
 *
 * <h3>인입 스캔 비활성 (ERROR — 기능 차단 아님)</h3>
 * <p>{@code /v1/uploads} 는 {@code @Profile}·{@code @ConditionalOnProperty} 가 없어 <b>전 환경 활성</b>
 * 인데, 인입 폴링({@code authoring.control.training-scan.enabled})이 꺼져 있으면 업로드가 200 을 받고도
 * 인입 행이 영원히 {@code PENDING} 에 머문다. 적재 주체 반전 이후 이 잡은 <b>자체 업로드분 적재의
 * 유일한 통로</b>라 꺼두면 업로드가 사실상 무의미하다. 다만 local/test 형상은 스케줄러를
 * <b>의도적으로</b> 꺼 두므로(테스트가 잡을 직접 호출한다) 기능 차단이 아니라 ERROR 로그 + 업로드
 * 완료 응답의 인입 상태 노출({@link #ingestScanEnabled()})로 드러낸다.
 */
@Slf4j
@Component
public class InternalUploadWiringGuard {

    /** 인입 스캔(폴링) 활성 스위치 — 꺼져 있으면 업로드분이 PENDING 에 머문다. */
    static final String KEY_TRAINING_SCAN_ENABLED = "authoring.control.training-scan.enabled";
    /** 업로드 저장 경로 정합의 판정 축이 되는 설정 키(예외 메시지에 경로 원문 대신 지목). */
    static final String KEY_RAW_MOUNT_ROOTS = "authoring.storage.raw-mount-roots";

    /** 업로드 경로와 어긋난 마운트 루트를 허용하는 <b>유일한</b> 프로파일. 여기 밖은 무조건 엄격. */
    static final Set<String> SYNTHETIC_ROOT_PROFILES = Set.of("local");

    /** 배포 환경 표식({@code ENV}) — {@code DevProfileGuard.DEPLOYED_ENVS} 와 동일 기준. */
    static final Set<String> DEPLOYED_ENV_MARKERS = Set.of("stg", "prd");

    private final Environment environment;
    private final InternalUploadPathResolver pathResolver;

    /**
     * 업로드 기능 활성 여부. <b>기본값 false(fail-closed)</b> — {@link #check()} 가 어떤 이유로든
     * 실행되지 않으면 조용히 열리는 대신 닫힌 채로 남는다.
     */
    private volatile boolean uploadEnabled;
    /** 인입 폴링 활성 여부(업로드 완료 응답에 인입 대기 상태를 드러내기 위한 관측값). */
    private volatile boolean ingestScanEnabled;

    public InternalUploadWiringGuard(Environment environment, InternalUploadPathResolver pathResolver) {
        this.environment = environment;
        this.pathResolver = pathResolver;
    }

    @PostConstruct
    void check() {
        this.uploadEnabled = evaluateUploadEnabled();
        this.ingestScanEnabled =
                environment.getProperty(KEY_TRAINING_SCAN_ENABLED, Boolean.class, Boolean.TRUE);
        if (!ingestScanEnabled) {
            // WARN 은 배포 로그에 묻힌다 — 적재 주체 반전 이후 이 잡이 자체 업로드분의 유일한
            //   적재 통로라 꺼져 있으면 업로드가 전부 PENDING 에 고인다.
            log.error("[Tus] 내부 업로드는 활성인데 인입 스캔({}=false)이 꺼져 있습니다"
                            + " — 업로드는 성공해도 인입 행이 PENDING 에 머물러 적재되지 않습니다."
                            + " 자체 업로드를 쓰려면 반드시 켜야 합니다.",
                    KEY_TRAINING_SCAN_ENABLED);
        }
    }

    /** 저장 경로 정합 판정 — 실패해도 기동을 막지 않고 ERROR 를 남긴 뒤 기능만 닫는다(F4-b). */
    private boolean evaluateUploadEnabled() {
        try {
            verify(List.of(environment.getActiveProfiles()), environment.getProperty("ENV"),
                    pathResolver.baseUnderAllowedRoots());
            return true;
        } catch (IllegalStateException e) {
            log.error("[Tus] 내부 업로드 기능을 <비활성>합니다 (POST /v1/uploads 등 TUS 엔드포인트 503). {}",
                    e.getMessage());
            return false;
        }
    }

    /** 업로드 기능이 사용 가능한 형상인가. */
    public boolean uploadEnabled() {
        return uploadEnabled;
    }

    /** 인입 폴링이 켜져 있는가 — 꺼져 있으면 업로드분이 적재되지 않고 PENDING 에 머문다. */
    public boolean ingestScanEnabled() {
        return ingestScanEnabled;
    }

    /**
     * TUS 엔드포인트 게이트 — 오설정 형상에서 <b>조용히 성공시키지 않고</b> 503 으로 닫는다.
     *
     * <p>원본 경로 폴백은 두지 않는다(fail-closed). 여기서 열어 주면 인입 행이 매 건 REJECTED 로
     * 영구 종결되는 형상 그대로 업로드가 200 을 받는다.
     *
     * @throws CustomException 업로드 기능 비활성(SERVICE_UNAVAILABLE)
     */
    public void requireUploadEnabled() {
        if (!uploadEnabled) {
            throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE,
                    "영상 업로드 기능을 사용할 수 없습니다. 저장 경로 설정(" + KEY_RAW_MOUNT_ROOTS
                            + ")을 확인하세요.");
        }
    }

    /**
     * 순수 판정 — 배포 형상인데 업로드 저장 경로가 적재 allowlist 밖이면 거부한다.
     *
     * @param activeProfiles        활성 스프링 프로파일(비어 있으면 default)
     * @param envName               배포 환경 표식 {@code ENV} 값(없으면 {@code null})
     * @param baseUnderAllowedRoots {@link InternalUploadPathResolver#baseUnderAllowedRoots()}
     * @throws IllegalStateException 합성 allowlist 허용 범위 밖에서 경로가 어긋났을 때
     */
    static void verify(List<String> activeProfiles, String envName, boolean baseUnderAllowedRoots) {
        if (baseUnderAllowedRoots || syntheticRootAllowed(activeProfiles, envName)) {
            return;
        }
        throw new IllegalStateException(
                "내부 업로드 저장 경로가 " + KEY_RAW_MOUNT_ROOTS + " 하위가 아닙니다"
                        + " (현재 활성 프로파일=" + activeProfiles + ", ENV=" + deployedEnvMarker(envName) + ")."
                        + " 업로드 파일은 authoring.storage.raw-path 하위 data/upload/v2 에 저장되는데,"
                        + " 그 경로가 적재 allowlist 밖이면 인입이 매 건 REJECTED 로 영구 종결됩니다"
                        + "(재큐해도 동일 실패 반복). " + KEY_RAW_MOUNT_ROOTS
                        + " 에 raw-path 를 포함시키거나 raw-path 를 allowlist 하위로 옮기세요.");
    }

    /** 활성 프로파일이 <b>전부</b> allowlist 안이고 배포 표식({@code ENV})이 없을 때만 인정한다. */
    private static boolean syntheticRootAllowed(List<String> activeProfiles, String envName) {
        if (deployedEnvMarker(envName) != null) {
            return false;
        }
        return !activeProfiles.isEmpty() && SYNTHETIC_ROOT_PROFILES.containsAll(activeProfiles);
    }

    /** {@code ENV} 가 배포 표식이면 정규화된 값을, 아니면 {@code null} 을 돌려준다. */
    private static String deployedEnvMarker(String envName) {
        if (envName == null || envName.isBlank()) {
            return null;
        }
        String normalized = envName.trim().toLowerCase(Locale.ROOT);
        return DEPLOYED_ENV_MARKERS.contains(normalized) ? normalized : null;
    }
}

package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.BatchStageSkipResponse;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.LsBatchProcLog;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 배치 <b>작업 묶음</b> 수동 스킵/해제 서비스 — REVIEWER 전용. [@design API-198] [@design API-200]
 *
 * <h3>무엇을 하는가</h3>
 * <p>외부 VLM 벤더 장애처럼 <b>기다려도 성공하지 않는</b> 작업 때문에 영상 전체가 FAILED 로 고착될 때,
 * REVIEWER 가 그 묶음만 건너뛰고 나머지 파이프라인을 완주시킨다. 대상은
 * {@link BatchStageBundle} 두 묶음(시계열 · 오토라벨)뿐이다.
 *
 * <h3>★단위가 개별 단계가 아니라 묶음인 이유</h3>
 * <p>오토라벨(AI 탐지 · AI 분할 · 트랙 보간)은 뒤 작업이 앞 결과를 입력으로 받고 보간이 그 산출물을
 * 재계산하므로 쪼개면 산출물끼리 어긋난다. 무엇보다 보간을 묶음 밖에 두면 어떤 재수행에서도 보간이
 * 무조건 돌아 사람이 손댄 보간 라벨을 지운다 — 묶음이 그 사고를 구조적으로 없앤다(근거는
 * {@link BatchStageBundle}).
 *
 * <h3>되돌릴 수 있어야 한다</h3>
 * <p>차단에는 되돌리는 길이 있어야 한다 — 해제({@link #clearSkip})는 <b>표식만 지우고 작업을 실행하지
 * 않는다</b>. 실제 실행은 재기동(단건/일괄)이 담당하며, 그래야 "해제 버튼이 무거운 외부 호출을 몰래
 * 일으키는" 동작이 생기지 않는다.
 *
 * <h3>파생영상 차단</h3>
 * <p>파생영상({@code ORGNL_RAW_SN} 보유)은 배치 파이프라인을 타지 않으므로 스킵/해제가 아무 의미가
 * 없다. 거부는 <b>400</b> 이다 — 이 저장소 관례상 400 은 "영구 조건"(재시도 여지 없음)이고 412 는
 * "지금은 안 되지만 해소되면 된다"인데, 파생 여부는 생성 후 불변이라 영구 조건이다. 미지원 묶음과
 * <b>같은 상태코드</b>를 쓰는 것도 의도적이다 — 코드가 갈리면 응답이 "이 영상이 어떤 상태인지"를
 * 알려주는 오라클이 된다(CWE-209).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchStageSkipService {

    /** 행위자 식별자 저장 상한 — {@code LS_BATCH_PROC_LOG.REG_ID} 컬럼 폭(30). */
    private static final int ACTOR_MAX_LENGTH = 30;

    private final VideoRepository videoRepository;
    private final BatchStatusService batchStatusService;

    /**
     * 작업 묶음을 수동 스킵한다.
     *
     * <p><b>append-only</b> — 이미 스킵 상태여도 새 표식 행을 남긴다. 사유가 바뀌었을 수 있고, 그
     * 변경 이력 자체가 감사 대상이기 때문이다(같은 사유로 반복 호출해도 판정은 흔들리지 않는다 —
     * 마지막 행이 여전히 스킵이다).
     *
     * <p><b>기록은 단일 INSERT</b> 라 원자적이다 — 오토라벨 3단계 중 일부만 스킵된 상태가 표현 자체로
     * 불가능하다(근거는 {@link ManualStageSkip} 「저장 축」 절).
     *
     * @param rawSn      대상 영상
     * @param bundleName 경로 변수의 묶음 문자열(VLM/AUTOLABEL 외에는 400)
     * @param reason     사유(공백 불가 — DTO 검증이 1차, 여기서 정제 후 2차 확인)
     */
    public BatchStageSkipResponse skip(Long rawSn, String bundleName, String reason) {
        BatchStageBundle bundle = requireSkippableBundle(bundleName);
        requireSkippableVideo(rawSn);

        String sanitized = sanitizeReason(reason);
        String stored = ManualStageSkip.REASON_PREFIX + sanitized;
        String actor = currentActor();

        batchStatusService.recordManualStageSkip(rawSn, bundle, stored, actor);
        log.info("[BatchStageSkip] bundle skipped rawSn={} bundle={} actor={}", rawSn, bundle, actor);

        LocalDateTime skippedAt = batchStatusService.latestManualSkipMarker(rawSn, bundle)
                .map(LsBatchProcLog::getStartedAt)
                .orElse(null);
        return new BatchStageSkipResponse(rawSn, bundle.name(), true, stored, skippedAt);
    }

    /**
     * 수동 스킵을 해제한다 — 표식만 지우고 작업을 실행하지 않는다.
     *
     * <p><b>멱등</b>: 스킵 상태가 아니면 아무 행도 남기지 않고 조용히 끝난다(DELETE 의 표준 의미).
     * 그렇지 않으면 해제 버튼을 두 번 누른 것만으로 의미 없는 감사 행이 쌓인다.
     *
     * <p>해제도 <b>단일 INSERT</b> 라 묶음 일부만 풀린 상태가 생기지 않는다.
     */
    public void clearSkip(Long rawSn, String bundleName) {
        BatchStageBundle bundle = requireSkippableBundle(bundleName);
        requireSkippableVideo(rawSn);

        if (!batchStatusService.isBundleManuallySkipped(rawSn, bundle)) {
            log.info("[BatchStageSkip] clear no-op — not skipped rawSn={} bundle={}", rawSn, bundle);
            return;
        }
        String actor = currentActor();
        batchStatusService.recordManualStageSkipCleared(
                rawSn, bundle, ManualStageSkip.CLEARED_REASON_PREFIX + "운영자 해제", actor);
        log.info("[BatchStageSkip] bundle skip cleared rawSn={} bundle={} actor={}", rawSn, bundle, actor);
    }

    /** 허용 묶음 해석 — 미지원 값은 400. 메시지에 요청 값을 되비추지 않는다(반사 XSS·로그 오염 차단). */
    private BatchStageBundle requireSkippableBundle(String bundleName) {
        BatchStageBundle bundle = BatchStageBundle.parse(bundleName);
        if (bundle == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "건너뛸 수 있는 작업 묶음이 아닙니다. 허용 값: " + ManualStageSkip.skippableBundleNames());
        }
        return bundle;
    }

    /** 영상 존재(404) + 파생영상 차단(400). 판정 원천은 {@link LsDataRaw#isDerivative()} 단일 지점. */
    private void requireSkippableVideo(Long rawSn) {
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        if (raw.isDerivative()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "이 영상은 다른 영상에서 파생된 영상이라 배치 단계를 조작할 수 없습니다.");
        }
    }

    /**
     * 사유 정제 — 제어문자 제거(CWE-117) + 길이 하드 절단. 정제 결과가 비면 400.
     *
     * <p>제어문자만으로 이루어진 사유는 {@code @NotBlank} 를 통과하지만(공백이 아니므로) 정제 후에는
     * 빈 문자열이 된다. 그 상태로 저장하면 "사유 필수"가 사실상 무력화되므로 여기서 다시 막는다.
     *
     * <p>⚠ 정제기의 상한 인자를 그대로 쓰지 않는다 — 상한에 <b>도달</b>하면 {@code "...(truncated)"} 를
     * 덧붙이므로, 정확히 상한 길이인 정상 입력에도 그 꼬리가 붙는다. 제어문자 제거만 넉넉한 예산으로
     * 맡기고 길이는 여기서 자른다.
     */
    private static String sanitizeReason(String reason) {
        String sanitized = LogSanitizer
                .sanitize(reason, ManualStageSkip.REASON_MAX_LENGTH * 2)
                .trim();
        if (sanitized.length() > ManualStageSkip.REASON_MAX_LENGTH) {
            sanitized = sanitized.substring(0, ManualStageSkip.REASON_MAX_LENGTH);
        }
        if (sanitized.isEmpty() || "(null)".equals(sanitized)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "스킵 사유는 필수입니다.");
        }
        return sanitized;
    }

    /**
     * 행위자(토큰 subject) — 감사의 "누가". 인증 컨텍스트가 없으면 {@code unknown}.
     *
     * <p>토큰 subject 는 외부 입력이므로 {@link LogSanitizer} 로 제어문자를 제거한다(CWE-117).
     *
     * <p>⚠ <b>정제 후 다시 한 번 하드 절단</b>한다 — {@code LogSanitizer} 는 상한에 걸리면
     * {@code "...(truncated)"}(14자)를 <b>덧붙이므로</b> 결과가 상한을 넘는다. 이 값은 로그가 아니라
     * {@code REG_ID VARCHAR(30)} 컬럼에 들어가므로, 그대로 두면 긴 subject 를 가진 토큰에서
     * INSERT 가 DB 오류(500)로 터진다.
     */
    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TokenClaims claims) {
            return Optional.ofNullable(claims.sub())
                    .map(sub -> LogSanitizer.sanitize(sub, ACTOR_MAX_LENGTH))
                    .map(BatchStageSkipService::clampToActorColumn)
                    .orElse("unknown");
        }
        return "unknown";
    }

    /** {@code REG_ID} 컬럼 폭으로 하드 절단 — 접미사가 붙어도 컬럼을 넘지 않게 한다. */
    private static String clampToActorColumn(String actor) {
        return actor.length() <= ACTOR_MAX_LENGTH ? actor : actor.substring(0, ACTOR_MAX_LENGTH);
    }
}

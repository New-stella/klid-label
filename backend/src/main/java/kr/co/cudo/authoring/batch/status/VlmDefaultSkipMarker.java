package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <b>전체 설정</b>으로 시계열 위탁을 건너뛰는 입구 — 배치가 그 단계에 진입하기 <b>직전</b> 표식을
 * 세운다. [@design ADR-050] [@design DFEAT-045]
 *
 * <h3>왜 «표식만» 세우는가 (게이트를 새로 만들지 않는다)</h3>
 * <p>건너뛰기 판정은 이미 {@link BatchStatusService#isBundleManuallySkipped} 한 곳이 소유한다. 여기서
 * 두 번째 판정을 만들면 "설정으로 껐는데 어떤 경로에서는 돈다"가 생긴다. 그래서 이 컴포넌트는
 * <b>기존 표식 축에 한 행을 세울 뿐</b>이고, 그 뒤는 오케스트레이터 루프와 위탁 메서드 안의 기존
 * 게이트가 그대로 처리한다 — 외부 호출은 <b>한 번도 일어나지 않는다</b>(위탁했다가 실패시키는 것이
 * 아니다).
 *
 * <h3>★ 사람이 남긴 표식을 덮지 않는다 (Critical)</h3>
 * <p>그 (영상 × 시계열 묶음)에 표식이 이미 있으면 — 건너뜀이든 <b>해제</b>든 — 아무것도 하지 않는다.
 * 특히 해제 표식을 덮으면 <b>사람이 되살린 영상이 조용히 다시 건너뛰어진다</b>. 판정은
 * {@link BatchStatusService#latestManualSkipMarker}("(영상 × 묶음) 의 마지막 표식 행")를 그대로
 * 재사용하며 새 규칙을 만들지 않는다. 이 성질이 곧 <b>멱등</b>이기도 하다 — 배치가 몇 번 진입해도
 * 표식은 한 번만 적재된다.
 *
 * <h3>대상은 시계열 묶음 하나뿐</h3>
 * <p>오토라벨은 이 스위치의 대상이 <b>아니다</b> — 라벨을 다시 만드는 축이라 성질이 다르다.
 *
 * <h3>★ 호출 지점은 둘이고 둘 다 필요하다 (Critical)</h3>
 * <ol>
 *   <li><b>오케스트레이터 루프</b> — 단계에 진입하기 직전. 여기서 표식이 서야 루프의 기존 게이트가 그
 *       단계를 <b>실행하지 않고</b> 넘어간다(진행 행도 남기지 않는다).</li>
 *   <li><b>위탁 직전({@code VlmTimeseriesStep.doSubmit})</b> — 외부 전송과 <b>같은 메서드</b>. 시계열
 *       위탁은 오케스트레이터만 부르는 것이 아니다: {@code VlmWithheldResumeRunner} 가
 *       {@code run}/{@code runWithMarking} 을 <b>직접</b> 부르고, 그 러너는 비식별 신고 해소 이벤트와
 *       <b>주기 미결 스위퍼</b>(사람 개입 0)에서 도달한다. 1 번만 두면 그 경로에는 표식을 세우는 자가
 *       없어 게이트가 항상 통과하고, 스위치가 켜져 있는데도 <b>외부 벤더가 호출된다</b>.</li>
 * </ol>
 * <p>중복 적재는 일어나지 않는다 — 아래 「사람이 남긴 표식을 덮지 않는다」가 곧 멱등이라, 1 이 세운
 * 표식을 2 가 다시 보고 조기 반환한다.
 *
 * <h3>이것은 파이프라인 제어 장치가 아니다</h3>
 * <p>위탁 실패는 원래 파이프라인을 멈추지 않는다. 이 표식도 마찬가지로 "이 영상은 시계열 없이
 * 확정한다"는 <b>결정</b>을 남길 뿐이며, 프레임 추출·오토라벨링은 그대로 진행된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VlmDefaultSkipMarker {

    /** 이 스위치가 건드리는 유일한 묶음. */
    private static final BatchStageBundle TARGET_BUNDLE = BatchStageBundle.VLM;

    /**
     * 표식 행의 {@code REG_ID} 고정값 — 사람이 아니라 설정이 세운 행임을 감사에서 구분한다.
     * 컬럼 폭 {@code VARCHAR(30)} 이내.
     */
    static final String REG_ID = "batch-vlm-default-skip";

    /**
     * 설정 사유가 비어 있을 때 대신 싣는 본문.
     *
     * <p>스위치를 켜는 저장이 사유를 강제하므로 정상 경로에서는 도달하지 않는다. 다만 그 행이 나중에
     * 사라지더라도 <b>스위치의 결정을 뒤집지는 않는다</b> — 사유가 없다고 위탁을 재개하면 운영자가 켜 둔
     * 설정을 무시하고 외부로 영상을 내보내게 된다.
     */
    static final String REASON_WHEN_UNSET = "사유 미입력";

    private final BatchStatusService batchStatusService;
    private final SystemConfigService systemConfigService;

    /**
     * 이 단계에 진입하기 직전 호출된다 — 대상 묶음이 아니면 아무것도 하지 않는다.
     *
     * @param rawSn 대상 영상
     * @param stage 지금 진입하려는 단계
     */
    public void applyBeforeStage(Long rawSn, BatchStage stage) {
        if (rawSn == null || stage == null) {
            return;
        }
        if (!TARGET_BUNDLE.contains(stage)) {
            return;
        }
        if (!isSkipByDefaultEnabled()) {
            return;
        }
        // 사람의 결정이 우선 — 건너뜀이든 해제든 표식이 있으면 손대지 않는다(멱등도 여기서 성립).
        if (batchStatusService.latestManualSkipMarker(rawSn, TARGET_BUNDLE).isPresent()) {
            return;
        }
        // ★ 독립 트랜잭션으로 적재한다 — 이 메서드는 readOnly 트랜잭션(VlmTimeseriesStep.run) 안에서도
        //   불린다. REQUIRED 로 참여하면 그 INSERT 가 read-only 커넥션에서 거부돼 표식이 서지 못하고,
        //   직후 게이트가 행을 찾지 못해 외부 호출이 그대로 나간다(기전·실측은
        //   recordManualStageSkipInNewTx javadoc — 구 서술 「flush 되지 않아 조용히 사라진다」 폐기).
        batchStatusService.recordManualStageSkipInNewTx(rawSn, TARGET_BUNDLE, reasonText(), REG_ID);
        log.info("[Batch][VlmDefaultSkip] marked by system-wide setting rawSn={} bundle={}",
                rawSn, TARGET_BUNDLE);
    }

    /**
     * 길이 하드 절단 — 정제기의 상한 인자를 그대로 쓰지 않는다.
     *
     * <p>{@link LogSanitizer} 는 상한에 <b>도달</b>하면 {@code "...(truncated)"} 를 덧붙이므로, 정확히
     * 상한 길이인 정상 사유에도 그 꼬리가 붙는다. 제어문자 제거만 넉넉한 예산으로 맡기고 길이는
     * 여기서 자른다({@code BatchStageSkipService.sanitizeReason} 과 같은 이유).
     */
    private static String clamp(String value) {
        return value.length() <= ManualStageSkip.REASON_MAX_LENGTH
                ? value
                : value.substring(0, ManualStageSkip.REASON_MAX_LENGTH);
    }

    /** 행이 없는 것이 정상 상태이며 그때는 «꺼짐»이다(fail-closed 가 아니라 «종전 동작 보존»). */
    private boolean isSkipByDefaultEnabled() {
        return systemConfigService.findString(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT)
                .map(String::trim)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    /**
     * 표식 사유 — 접두(설정에서 비롯했다는 사실) + 설정에 저장된 사유 문구.
     *
     * <p>설정 사유는 사람이 자유 입력하는 값이라 제어문자를 제거한다(CWE-117) — 이 문자열은 감사 행과
     * 로그 양쪽에 실린다.
     */
    private String reasonText() {
        String configured = systemConfigService
                .findString(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT_REASON)
                .orElse(null);
        String body = configured == null
                ? ""
                : clamp(LogSanitizer.sanitize(configured, ManualStageSkip.REASON_MAX_LENGTH * 2).trim());
        if (body.isEmpty()) {
            body = REASON_WHEN_UNSET;
        }
        return ManualStageSkip.DEFAULT_SKIP_REASON_PREFIX + body;
    }
}

package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.service.MarkPlan;
import kr.co.cudo.authoring.marking.service.MarkingChannel;
import kr.co.cudo.authoring.marking.service.MarkingTarget;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.event.PortalMarkingCompletedEvent;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 포털 업로드 영상 채널의 마킹 판정기.
 *
 * <table>
 *   <caption>이 채널이 답하는 두 물음</caption>
 *   <tr><th>물음</th><th>상태 원천</th></tr>
 *   <tr><td>이 사람이 이 영상을 마킹할 수 있는가</td><td><b>본인 자산</b>(출처 판별자 + 소유자)</td></tr>
 *   <tr><td>지금 마킹할 수 있는 단계인가</td><td><b>포털 업로드 상태</b>(메타 원장)</td></tr>
 * </table>
 *
 * <p>관제 가드가 요구하는 세 조건(비식별 완료 · 배치 단계 표식 · 관제 인입 이벤트 유형)은 이 경로에
 * <b>애초에 없다</b> — 본인 데이터라 비식별을 하지 않고, 배치 단계 컬럼을 쓰지 않으며, 검증 이벤트
 * 유형이 관제 인입에서 오지 않는다. 그래서 관제 가드를 무르게 하는 대신 이 판정기를 따로 세운다.
 *
 * <h3>남의 자산과 없는 자산을 <b>같은 코드</b>로 거절한다</h3>
 * <p>두 경우를 상태코드로 가르면 남의 자산이 있는지 없는지가 응답으로 드러난다. 포털 자산 창구가
 * 이미 쓰고 있는 규칙과 같다(CWE-639/209).
 *
 * <h3>재마킹을 제공하지 않는다</h3>
 * <p>마킹을 기다리는 자산만 받는다. 이미 추출이 시작·완료됐거나 실패한 자산은 거절하며, 회복 경로는
 * <b>자산을 지우고 다시 올리는 것</b>뿐이다. 상태가 잘못된 것은 요청 본문이 아니라 대상이므로
 * 입력 오류가 아니라 충돌로 돌려준다.
 *
 * @design API-240
 * @design ADR-013
 * @design ADR-058
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalUploadMarkingChannel implements MarkingChannel {

    /** 자동 마킹 식별자. */
    private static final String MODE_AUTO = "AUTO";

    /** 프레임률 미상 시 폴백 — 프레임 원장·추출이 쓰는 값과 같다. */
    private static final double DEFAULT_FPS = 30.0;

    private final PortalUploadAssetRepository assetRepository;
    private final PortalUploadProperties properties;

    // ==================================================================
    // ① 접근 — 본인 자산인가
    // ==================================================================

    @Override
    public void requireAccess(Long uldSn, TokenClaims actor) {
        loadOwned(uldSn, actor);
    }

    // ==================================================================
    // ② 단계 — 지금 마킹할 수 있는가
    // ==================================================================

    @Override
    public MarkingTarget requireMarkable(Long uldSn, TokenClaims actor) {
        PortalUploadAsset asset = loadOwned(uldSn, actor);
        if (!PortalUploadLedger.TYPE_VIDEO.equals(asset.uldTypeCd())) {
            // 기다려도 달라지지 않는 영구 조건이라 충돌이 아니라 입력 오류로 돌려준다.
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 자산만 이벤트 구간을 마킹할 수 있습니다.");
        }
        String status = asset.uldSttsCd();
        if (PortalUploadLedger.STATUS_UPLOADED.equals(status)) {
            // 조달값이 없다 — 관제 인입 이벤트 유형이 이 경로에 오지 않고, 저장 경로는 외부 채널
            // 응답에 실을 값이 아니다(CWE-209).
            return MarkingTarget.EMPTY;
        }
        if (PortalUploadLedger.STATUS_FAILED.equals(status)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "처리에 실패한 영상입니다. 다시 마킹하려면 이 자산을 지우고 다시 올려 주세요.");
        }
        throw new CustomException(ErrorCode.CONFLICT,
                "이미 마킹을 저장한 영상입니다. 다시 마킹하려면 이 자산을 지우고 다시 올려 주세요.");
    }

    // ==================================================================
    // 조달
    // ==================================================================

    /**
     * 길이는 <b>업로드 확정 시점에 적재된 값</b>만 쓴다 — 대화형 저장에 프로브 서브프로세스를 태우지
     * 않는다. 자동인데 길이를 모르면 지점을 산출할 수 없어 충돌로 거절한다(기다리면 풀리는 조건이라
     * 입력 오류가 아니다). 수동은 길이를 몰라도 저장할 수 있고 상한 검증만 건너뛴다.
     */
    @Override
    public Integer resolveDurationSec(Long uldSn, String mode) {
        Double stored = assetRepository.findPortalAsset(uldSn)
                .map(PortalUploadAsset::vdoLenSec)
                .orElse(null);
        Integer durationSec = (stored != null && stored > 0d) ? (int) Math.round(stored) : null;
        if (durationSec == null && MODE_AUTO.equals(mode)) {
            log.info("[PortalMarking] duration unknown — auto marking refused uldSn={}", uldSn);
            throw new CustomException(ErrorCode.CONFLICT,
                    "영상 길이를 아직 확인하지 못해 자동 마킹을 만들 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        return durationSec;
    }

    @Override
    public double resolveFps(Long uldSn) {
        Double fps = assetRepository.findPortalAsset(uldSn)
                .map(PortalUploadAsset::fps)
                .orElse(null);
        return (fps != null && fps > 0d) ? fps : DEFAULT_FPS;
    }

    /**
     * 이 경로에는 관제 인입 검증 이벤트 유형이 오지 않는다 — 고를 축 자체가 없으므로 비워 둔다
     * (지어내지 않는다).
     */
    @Override
    public Long resolveQuestionSn(Long uldSn, Long requestedQstnSn) {
        return null;
    }

    @Override
    public MarkPlan capMarks(String mode, List<MarkItem> marks) {
        return PortalMarkCap.apply(mode, marks, properties.maxFrames());
    }

    /**
     * 마킹 저장이 곧 추출의 시작이다 — 같은 트랜잭션에서 「마킹 대기 → 추출 중」으로 원자 전이한다.
     *
     * <p>0행이면 동시 저장이 먼저 지나갔거나 그 사이 자산이 사라진 것이라 충돌로 거절하고 마킹까지
     * 함께 롤백시킨다. 단계 판정과 이 전이가 <b>둘 다</b> 필요한 이유는 판정~저장 사이의 창을 닫는
     * 것은 조건부 UPDATE 뿐이기 때문이다.
     */
    @Override
    public void onSaved(Long uldSn, Long markingSn) {
        if (assetRepository.transitionToProcessing(uldSn) != 1) {
            log.warn("[PortalMarking] processing transition lost uldSn={} markingSn={}", uldSn, markingSn);
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 마킹을 저장한 영상입니다. 다시 마킹하려면 이 자산을 지우고 다시 올려 주세요.");
        }
    }

    /**
     * ★ 포털 전용 완료 이벤트 — 관제 배치 브리지는 이 타입을 구독하지 않는다. 포털에는 외부 시계열
     * 위탁도 오토라벨링도 없으므로 마킹이 그것들을 깨울 경로가 <b>타입 수준에서</b> 없다.
     */
    @Override
    public Object completionEvent(Long uldSn, Long markingSn) {
        return new PortalMarkingCompletedEvent(uldSn, markingSn);
    }

    // ==================================================================
    // 내부
    // ==================================================================

    /** 소유자 스코프 단건 조회 — 미인증은 401, 남의 자산·부재는 <b>같은</b> 403. */
    private PortalUploadAsset loadOwned(Long uldSn, TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return assetRepository.findByOwner(uldSn, actor.sub())
                .orElseThrow(() -> new CustomException(ErrorCode.FORBIDDEN,
                        "본인 자산이 아니거나 존재하지 않습니다."));
    }
}

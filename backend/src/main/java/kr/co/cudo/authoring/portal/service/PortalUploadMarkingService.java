package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.marking.service.MarkingOutcome;
import kr.co.cudo.authoring.marking.service.MarkingService;
import kr.co.cudo.authoring.portal.dto.PortalMarkingListResponse;
import kr.co.cudo.authoring.portal.dto.PortalMarkingRequest;
import kr.co.cudo.authoring.portal.dto.PortalMarkingSaveResponse;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포털 업로드 영상 마킹 — 저장·조회.
 *
 * <h3>마킹 로직을 복제하지 않는다</h3>
 * <p>저장은 채널 공통 {@link MarkingService#createOn} 을 그대로 부르고, 이 채널만의 판정
 * ({@link PortalUploadMarkingChannel})을 함께 넘긴다. 지점 산출·중복/상한 검증·직렬화·영속·fps 고정은
 * 모두 그쪽 한 벌이며 여기에 사본이 없다.
 *
 * <h3>저장은 되돌릴 수 없다</h3>
 * <p>받아들여지는 순간 그 지점으로 추출이 시작되고 재마킹을 제공하지 않는다. 회복 경로는 자산을
 * 지우고 다시 올리는 것뿐이며, 그 안내는 거절 메시지가 함께 알린다.
 *
 * @design API-240
 * @design API-241
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalUploadMarkingService {

    private final MarkingService markingService;
    private final PortalUploadMarkingChannel channel;
    private final PortalUploadAssetRepository assetRepository;
    private final LsMarkingRepository markingRepository;
    private final ObjectMapper objectMapper;

    /**
     * 마킹 저장 — 그 지점으로 프레임 추출이 시작된다.
     *
     * <p>자동은 간격으로 지점을 산출하고 수동은 요청에 담긴 지점을 그대로 쓴다. 간격을 바꾸지 않고
     * 저장하면 고정 간격으로 프레임을 뽑던 구 동작과 같은 결과가 된다.
     */
    public PortalMarkingSaveResponse save(Long uldSn, PortalMarkingRequest req, TokenClaims actor) {
        MarkingRequest inner = new MarkingRequest(req.mode(), req.effectiveInterval(), req.marks());
        MarkingOutcome outcome = markingService.createOn(uldSn, inner, actor, channel);
        log.info("[PortalMarking] saved uldSn={} markingSn={} mode={} marks={} truncated={}",
                uldSn, outcome.response().markingSn(), req.mode(),
                outcome.marks().size(), outcome.truncated());
        return PortalMarkingSaveResponse.of(uldSn, outcome);
    }

    /**
     * 마킹 조회 — <b>상태와 자산 종류를 묻지 않는다</b>.
     *
     * <p>저장은 「마킹 대기」 자산만 받지만 조회는 걸지 않는다. 두 축을 같게 두면 <b>다시 저장할 수
     * 없는 자산에서 무엇이 저장돼 있는지조차 볼 수 없게</b> 되는데, 다시 저장할 수 없는 자산일수록
     * 확인할 필요가 크다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public PortalMarkingListResponse list(Long uldSn, TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        // 남의 자산과 없는 자산을 같은 코드로 거절한다(존재 오라클 차단).
        assetRepository.findByOwner(uldSn, actor.sub())
                .orElseThrow(() -> new CustomException(ErrorCode.FORBIDDEN,
                        "본인 자산이 아니거나 존재하지 않습니다."));
        return PortalMarkingListResponse.of(uldSn,
                markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(uldSn), objectMapper);
    }
}

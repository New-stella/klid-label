package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.AiCallCancellationRegistry;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AiCancelResponse;
import kr.co.cudo.authoring.label.dto.AutolabelRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.label.service.AiFrameAccess;
import kr.co.cudo.authoring.label.service.AutolabelOnlineService;
import kr.co.cudo.authoring.label.service.Sam2SegmentService;
import kr.co.cudo.authoring.label.service.YoloTrackService;
import kr.co.cudo.authoring.sysconfig.dto.AiDefaultsResponse;
import kr.co.cudo.authoring.sysconfig.service.AiDefaultsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 포털 라벨링 화면의 AI 보조(AI 탐지 · AI 분할 · AI 자동 추적) 창구 서비스.
 *
 * <p><b>추론 본체는 내부 서비스를 그대로 재사용한다</b> — 검출 클래스 서버측 재구성 · 추론 서버 호출 ·
 * 동시 호출 상한 · 취소 · 좌표 정규화 · 폴리곤 상한 · 추적 시간 예산 절단 · mock 차단 · 응답 조립은 복제하지
 * 않는다. 이 서비스가 갈아끼우는 것은 입력 경계({@link PortalAiFrameAccess}) 하나다 — 포털 작업 대상 인가 ·
 * 포털 서빙과 같은 입력 이미지 · 비식별 판정 없음.
 *
 * <p>트랜잭션을 두지 않는다 — 추론 대기 동안 커넥션을 붙잡지 않게 한다(판정 조회는 각 협력자가 짧게 연다).
 *
 * <p>선택 객체 추적(sam2-track)의 포털 창구는 없다(2026-09-15 확정 — 자동 추적과 기능 중복).
 */
@Service
@RequiredArgsConstructor
public class PortalAiAssistService {

    private final PortalAiFrameAccessFactory accessFactory;
    private final AutolabelOnlineService autolabelOnlineService;
    private final Sam2SegmentService sam2SegmentService;
    private final YoloTrackService yoloTrackService;
    private final AiDefaultsService aiDefaultsService;
    private final AiCallCancellationRegistry cancellationRegistry;

    /** AI 탐지 — 한 프레임. 응답 안내 문구 규약은 창구가 적용한다. @design API-255 */
    public AutolabelOnlineService.AutolabelOutcome autolabel(Long srcSn, AutolabelRequest request, TokenClaims actor) {
        return autolabelOnlineService.autolabelWithAccess(accessFactory.forActor(actor), srcSn, actor,
                request == null ? null : request.classesOrNull(),
                request == null ? null : request.shape(),
                request == null ? null : request.confThreshold(),
                request == null ? null : request.simplifyTolerance());
    }

    /** AI 분할 — path/body 일치 판정은 창구 몫. @design API-257 */
    public Sam2SegmentResponse segment(Sam2SegmentRequest request, TokenClaims actor) {
        return sam2SegmentService.segmentWithAccess(accessFactory.forActor(actor), request);
    }

    /**
     * AI 자동 추적 — path/body 일치 판정은 창구 몫. @design API-254
     *
     * <p>★ <b>교차 영상 400 을 요청 전체에 대해 선판정한다.</b> 본체는 프레임 루프 안에서 영상 불일치를 거부하는데,
     * 그 판정은 앞 프레임을 추론 서버로 보낸 <b>뒤</b>에 걸린다 — 한 요청으로 여러 영상의 프레임을 섞은 경우 앞
     * 프레임은 이미 전송된 상태가 된다. 그래서 시작 프레임과 후속 프레임 전부를 인가(403/404 순서는 본체와 같다)한
     * 다음, 영상 식별자가 하나라도 다르면 본체 호출 전에 400 으로 끝낸다. 인가 결과는 입력 경계가 메모하므로 본체의
     * 재인가는 조회를 다시 하지 않는다. 본체의 루프 내 판정은 그대로 남는다(방어선 둘).
     */
    public YoloTrackResponseDto track(YoloTrackRequest request, TokenClaims actor) {
        AiFrameAccess access = accessFactory.forActor(actor);
        LsDataSrc start = access.authorize(request.srcSn());
        List<Long> nextSrcSns = request.nextSrcSns() == null ? List.of() : request.nextSrcSns();
        List<LsDataSrc> nextFrames = nextSrcSns.stream().map(access::authorize).toList();
        Long rawSn = start.getRawSn();
        for (LsDataSrc next : nextFrames) {
            if (rawSn == null || !rawSn.equals(next.getRawSn())) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "시퀀스 프레임이 시작 프레임과 다른 영상에 속합니다.");
            }
        }
        return yoloTrackService.trackWithAccess(access, request);
    }

    /** 정밀도 초기값·대기 예산 — 내부 조회 창구와 같은 값·같은 형태. @design API-256 */
    public AiDefaultsResponse aiDefaults() {
        return aiDefaultsService.get();
    }

    /**
     * 포털 AI 취소 — 본인(같은 채널·같은 subject)이 시작한 요청만 끊는다. 소유자 판정은 등록소가 한다.
     * 이미 끝남·남의 요청·다른 노드는 구분 없이 {@code cancelled=false}. @design API-258
     */
    public AiCancelResponse cancel(String requestId, TokenClaims actor) {
        return new AiCancelResponse(
                cancellationRegistry.cancel(requestId, AiCallCancellationRegistry.ownerKey(actor)));
    }
}

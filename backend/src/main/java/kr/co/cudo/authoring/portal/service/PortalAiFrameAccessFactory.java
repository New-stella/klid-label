package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.AiFrameAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 포털 AI 보조 입력 경계({@link PortalAiFrameAccess})를 요청마다 한 행위자에 묶어 만든다.
 *
 * <p>입력 경계는 요청 단위 상태(인가 메모)를 가지므로 빈으로 공유하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PortalAiFrameAccessFactory {

    private final PortalWorkTargetResolver targetResolver;
    private final PortalLabelService portalLabelService;
    private final PortalUploadService portalUploadService;

    /**
     * @param actor 포털 토큰 주체 — subject 가 비면 인가 시점에 401 로 끝난다(작업 대상 판정의 규약)
     */
    public AiFrameAccess forActor(TokenClaims actor) {
        return new PortalAiFrameAccess(targetResolver, portalLabelService, portalUploadService,
                actor == null ? null : actor.sub());
    }
}

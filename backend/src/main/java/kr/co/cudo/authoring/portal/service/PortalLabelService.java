package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalAutolabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalLabelRequest;
import kr.co.cudo.authoring.portal.entity.LsPortalUserVideo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 11 — 포털 간편 라벨링 서비스 (외부 위임 + 본인 영상 검증).
 *
 * 정책 (V1.5):
 *  - 본인 업로드 영상에 한해 라벨링 체험 가능.
 *  - 검수 / 버전관리(Gitea 커밋) / VLM 검증 미제공 → LabelService 의 풀 워크플로우 미사용.
 *  - 본 서비스는 권한 검증 + AiServerClient 위임만 수행.
 *
 * 보안:
 *  - IDOR (CWE-639): PortalUploadService.getMyVideo 로 본인 영상 검증 → 다른 사용자 영상 portalVideoSn 거부.
 *  - 라벨 저장은 본 Phase 범위 외 (체험 결과는 클라이언트 메모리에서만 유지).
 */
@Slf4j
@Service
@Transactional(value = "controlTransactionManager", readOnly = true)
@RequiredArgsConstructor
public class PortalLabelService {

    private final PortalUploadService portalUploadService;
    private final PortalAutolabelService portalAutolabelService;

    /** 본인 영상 한정 — 간편 라벨링 (오토 추론 결과 반환만, 저장 없음). */
    public YoloResponse autolabel(PortalAutolabelRequest req, TokenClaims actor) {
        verifyMyVideo(req.portalVideoSn(), actor);
        return portalAutolabelService.predict(req.imageB64());
    }

    /** 본인 영상 한정 — 수동 라벨 등록(체험). 본 Phase 는 echo only — 정식 라벨 테이블 미연동. */
    public PortalLabelRequest acceptLabel(PortalLabelRequest req, TokenClaims actor) {
        verifyMyVideo(req.portalVideoSn(), actor);
        log.info("[Portal] manual label echoed userId={} portalVideoSn={} count={}",
                actor.sub(), req.portalVideoSn(), req.items() == null ? 0 : req.items().size());
        return req;
    }

    private LsPortalUserVideo verifyMyVideo(Long portalVideoSn, TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return portalUploadService.getMyVideo(portalVideoSn, actor.sub());
    }
}

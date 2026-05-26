package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.DatamartLabelResponse;
import kr.co.cudo.authoring.portal.dto.PortalAutolabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.entity.LsPortalUserVideo;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;

import java.util.List;
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
    private final LsDataLblRepository lblRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsPortalUserLabelRepository userLabelRepository;

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

    /** V2.0 — 데이터마트 라벨 Load. rawSn 에 해당하는 원본 라벨 목록 반환 (페이징). */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<DatamartLabelResponse> loadDatamartLabels(Long rawSn, int page, int size) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        int clampedSize = Math.min(Math.max(size, 1), 100);
        int clampedPage = Math.max(page, 0);
        List<LsDataLbl> all = lblRepository.findAllByRawSn(rawSn);
        int fromIndex = clampedPage * clampedSize;
        if (fromIndex >= all.size()) {
            return List.of();
        }
        int toIndex = Math.min(fromIndex + clampedSize, all.size());
        return all.subList(fromIndex, toIndex).stream()
                .map(DatamartLabelResponse::from)
                .toList();
    }

    /** V2.0 — 사용자 라벨 저장. 원본 미수정 — LS_PORTAL_USER_LABEL 별도 적재. */
    @Transactional("controlTransactionManager")
    public PortalUserLabelResponse saveUserLabel(PortalUserLabelRequest req, TokenClaims actor) {
        requireActor(actor);
        LsPortalUserLabel saved = userLabelRepository.save(
                LsPortalUserLabel.create(actor.sub(), req.sourceRawSn(), req.sourceSrcSn(),
                        req.lblTypeCd(), req.label(), req.points()));
        log.info("[Portal] user label saved userId={} rawSn={} srcSn={}",
                actor.sub(), req.sourceRawSn(), req.sourceSrcSn());
        return PortalUserLabelResponse.from(saved);
    }

    /** V2.0 — 본인 작업 라벨 조회 (IDOR: portalUserNo = token sub). */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<PortalUserLabelResponse> listMyLabels(Long rawSn, TokenClaims actor) {
        requireActor(actor);
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        return userLabelRepository.findByPortalUserNoAndSourceRawSnOrderByCreatedAtDesc(actor.sub(), rawSn)
                .stream().map(PortalUserLabelResponse::from).toList();
    }

    private void requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }

    private LsPortalUserVideo verifyMyVideo(Long portalVideoSn, TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return portalUploadService.getMyVideo(portalVideoSn, actor.sub());
    }
}

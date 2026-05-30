package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.entity.LsPortalUserVideo;
import kr.co.cudo.authoring.portal.repository.PortalUserVideoRepository;
import kr.co.cudo.authoring.portal.tus.TusFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 11 — 포털 업로드 결과 영속화.
 *
 *  - TUS 업로드 완료(offset == size) 시 컨트롤러가 호출 → LS_PORTAL_USER_VIDEO INSERT.
 *  - 본인 영상 목록 조회.
 *
 * 썸네일은 본 Phase 범위 외 (FFmpeg 통합은 후속 Phase).
 */
@Slf4j
@Service
@Transactional(value = "controlTransactionManager", readOnly = true)
@RequiredArgsConstructor
public class PortalUploadService {

    private final PortalUserVideoRepository repository;

    @Transactional("controlTransactionManager")
    public LsPortalUserVideo registerCompleted(String portalUserNo, TusFile file) {
        if (!file.isComplete()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "업로드가 완료되지 않았습니다.");
        }
        LsPortalUserVideo entity = LsPortalUserVideo.create(
                portalUserNo,
                file.fileName(),
                file.dataPath().toString(),
                file.size(),
                file.mimeType()
        );
        LsPortalUserVideo saved = repository.save(entity);
        log.info("[Portal] upload registered userId={} portalVideoSn={} size={}",
                portalUserNo, saved.getPortalVideoSn(), saved.getFileSz());
        return saved;
    }

    public List<LsPortalUserVideo> listMyUploads(String portalUserNo) {
        return repository.findByPortalUserNoOrderByRegDtDesc(portalUserNo);
    }

    /** 본인 영상만 조회 (IDOR 차단). */
    public LsPortalUserVideo getMyVideo(Long portalVideoSn, String portalUserNo) {
        return repository.findByPortalVideoSnAndPortalUserNo(portalVideoSn, portalUserNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "본인 업로드 영상이 아닙니다."));
    }
}

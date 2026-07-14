package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.dto.FrameDescriptionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * blocker#2 — 프레임 설명(NIA image.description) 저장/조회 서비스.
 *
 * <p>인가는 {@link LabelAccessGuard#verifyAndGet}(REVIEWER 통과 / WORKER 본인 LABELER 배정만 / 그 외 403)를
 * 재사용해 IDOR(CWE-639)를 일차 차단한다. 검수 완료(APPROVED) 후 수정 시에만 라벨/메타 경로와 동일하게
 * {@code TASK_MODIFIED}(META_UPDATED) 를 발행한다(CLAUDE.md 작업 단위 통지 정책).
 *
 * <p>개인정보 가능성이 있어 로그에 설명 본문은 남기지 않고 srcSn·길이만 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class FrameDescriptionService {

    private final LabelAccessGuard guard;
    private final LsDataSrcRepository srcRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final LsRawDataStatusRepository rawDataStatusRepository;

    public FrameDescriptionResponse get(Long srcSn, TokenClaims actor) {
        LsDataSrc src = guard.verifyAndGet(srcSn, actor);
        return FrameDescriptionResponse.from(src);
    }

    @Transactional("controlTransactionManager")
    public FrameDescriptionResponse update(Long srcSn, String description, TokenClaims actor) {
        LsDataSrc src = guard.verifyAndGet(srcSn, actor);
        Long rawSn = src.getRawSn();

        src.updateDescription(description);
        srcRepository.save(src);
        // 본문 미출력 — srcSn·길이만 (개인정보 노출 방지)
        log.info("[FrameDescription] updated srcSn={} rawSn={} length={}",
                srcSn, rawSn, description == null ? 0 : description.length());

        // 검수 완료(APPROVED) 후 수정 시에만 통지 — 검수 전 저장은 일반 작업이므로 미발행(라벨/메타 경로와 동일 가드).
        if (isReviewApproved(rawSn)) {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, srcSn, ChangeType.META_UPDATED, guard.parseUserNo(actor.sub())));
        }
        return FrameDescriptionResponse.from(src);
    }

    /**
     * 영상(rawSn) 의 검수 상태가 APPROVED(검수 완료) 인지 판정. 상태 row 가 없으면 미검수(false).
     * 매직스트링 금지 — {@link LsRawDataStatus#STTS_APPROVED} 상수 비교.
     */
    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }
}

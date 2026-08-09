package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
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
    private final ReviewApprovalGate approvalGate;

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
        // HIGH-B(Phase 5C) — 프레임 설명(frmExpln)은 export JSON 의 image.description 으로 나가므로 승인 후
        //   수정 시 export 폴더를 새 버전으로 전량 재생성해야 데이터마트 라벨링 정보가 동기화된다.
        //   exportRegenerated=true 로 발행(구 4-arg=false 는 재생성을 트리거하지 못해 옛 설명이 파일에 고착).
        // Phase 7a-1 — needsRecheck=true (사람이 콘텐츠를 고치는 경로): 재검토 표시만 세운다.
        if (approvalGate.isApproved(rawSn)) {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, srcSn, ChangeType.META_UPDATED, guard.parseUserNo(actor.sub()), true, true));
        }
        return FrameDescriptionResponse.from(src);
    }
}

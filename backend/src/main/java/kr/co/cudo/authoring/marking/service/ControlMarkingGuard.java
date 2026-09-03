package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;

/**
 * 관제(내부) 채널의 마킹 가드 — 기존 판정을 <b>그대로</b> 옮겨 담은 것이다.
 *
 * <p>접근은 배정 보유(검수자 이상은 전체), 단계는 비식별 완료 → 배치 단계 표식 → 이벤트 유형 보유
 * 순이며, 규칙 본문은 여전히 {@link MarkingGuards} 한 곳에 있다 — 여기서 복제하지 않는다.
 * 이 클래스는 <b>배선</b>일 뿐이라 관제 동작은 소스 동치로 무변경이다.
 *
 * @design ADR-058
 */
class ControlMarkingGuard implements MarkingChannelGuard {

    private final VideoRepository videoRepository;
    private final LsTaskAssignmentRepository assignmentRepository;

    ControlMarkingGuard(VideoRepository videoRepository, LsTaskAssignmentRepository assignmentRepository) {
        this.videoRepository = videoRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Override
    public void requireAccess(Long rawSn, TokenClaims actor) {
        MarkingGuards.requireAssignedOrReviewer(rawSn, actor, assignmentRepository);
    }

    @Override
    public MarkingTarget requireMarkable(Long rawSn, TokenClaims actor) {
        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        MarkingGuards.requirePreconditions(raw);
        return new MarkingTarget(raw.getEvntTypeCd(), raw.getRawFilePathNm());
    }
}

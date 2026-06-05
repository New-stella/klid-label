package kr.co.cudo.authoring.notice.dto;

import kr.co.cudo.authoring.notice.entity.LsNotice;
import kr.co.cudo.authoring.notice.entity.LsNoticeAttach;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공지 상세 응답 DTO (내용 + 첨부 목록 포함).
 *
 * <p>{@code attachments} 는 Phase 2 에서 추가된 필드 — 기존 응답과 하위호환(필드 추가만).
 * 첨부 항목에는 서버 저장 경로·UUID 가 포함되지 않는다(CWE-209, {@link NoticeAttachResponse}).
 */
public record NoticeResponse(
        Long id,
        String title,
        String content,
        boolean pinned,
        String pubStatus,
        LocalDateTime pubDt,
        String regId,
        LocalDateTime regDt,
        LocalDateTime mdfcnDt,
        List<NoticeAttachResponse> attachments
) {
    public static NoticeResponse from(LsNotice notice) {
        return from(notice, List.of());
    }

    public static NoticeResponse from(LsNotice notice, List<LsNoticeAttach> attaches) {
        List<NoticeAttachResponse> attachments = attaches.stream()
                .map(NoticeAttachResponse::from)
                .toList();
        return new NoticeResponse(
                notice.getNoticeSn(),
                notice.getTitle(),
                notice.getContent(),
                notice.isPinned(),
                notice.getPubStatus().name(),
                notice.getPubDt(),
                notice.getRegId(),
                notice.getRegDt(),
                notice.getMdfcnDt(),
                attachments
        );
    }
}

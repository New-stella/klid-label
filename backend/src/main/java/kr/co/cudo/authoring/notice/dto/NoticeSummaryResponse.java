package kr.co.cudo.authoring.notice.dto;

import kr.co.cudo.authoring.notice.entity.LsNotice;

import java.time.LocalDateTime;

/**
 * 공지 목록 요약 응답 DTO — 본문(content) 제외.
 */
public record NoticeSummaryResponse(
        Long id,
        String title,
        boolean pinned,
        String pubStatus,
        LocalDateTime pubDt,
        LocalDateTime regDt
) {
    public static NoticeSummaryResponse from(LsNotice notice) {
        return new NoticeSummaryResponse(
                notice.getNoticeSn(),
                notice.getTitle(),
                notice.isPinned(),
                notice.getPubStatus().name(),
                notice.getPubDt(),
                notice.getRegDt()
        );
    }
}

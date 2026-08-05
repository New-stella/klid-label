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
 *
 * <p><b>작성자 표시 축이 둘인 이유</b> — {@code regId} 는 {@code LS_NOTICE.REG_ID} 원값(=JWT sub =
 * 내부 사용자 번호 {@code USER_NO} 의 문자열)이고, {@code writerName} 은 그 번호로 조회한
 * {@code LS_ACNT_USER.USER_NM}(V169 로 저작도구 소유 마스터로 이관) 이다. 화면은
 * <b>{@code writerName} 을 표시</b>하고 없을 때만
 * 폴백을 쓴다 — 내부 번호를 사람 이름 자리에 그대로 찍지 않기 위해서다.
 * {@code regId} 는 기존 소비자 하위호환을 위해 유지한다(필드 추가만, 제거 아님).
 *
 * <p>{@code writerName} 이 {@code null} 인 경우: {@code REG_ID} 가 없거나(레거시 행) 숫자가 아니거나
 * 사용자 마스터에 없을 때(탈퇴·관제 계정 삭제). 조회 실패를 예외로 올리지 않는 이유는 공지 조회 자체가
 * 계정 마스터 상태에 종속되면 안 되기 때문이다({@code IssueThreadService} 의 이름 조회와 동일 정책).
 */
public record NoticeResponse(
        Long id,
        String title,
        String content,
        boolean pinned,
        String pubStatus,
        LocalDateTime pubDt,
        String regId,
        String writerName,
        LocalDateTime regDt,
        LocalDateTime mdfcnDt,
        List<NoticeAttachResponse> attachments
) {
    public static NoticeResponse from(LsNotice notice, String writerName) {
        return from(notice, List.of(), writerName);
    }

    public static NoticeResponse from(LsNotice notice, List<LsNoticeAttach> attaches, String writerName) {
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
                writerName,
                notice.getRegDt(),
                notice.getMdfcnDt(),
                attachments
        );
    }
}

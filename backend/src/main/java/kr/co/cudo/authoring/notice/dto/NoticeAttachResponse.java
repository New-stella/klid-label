package kr.co.cudo.authoring.notice.dto;

import kr.co.cudo.authoring.notice.entity.LsNoticeAttach;

import java.time.LocalDateTime;

/**
 * 공지 첨부파일 응답 DTO.
 *
 * <p>보안(CWE-209): 서버 저장 경로(FILE_PATH)·저장 파일명(UUID)은 절대 노출하지 않는다.
 * 외부에는 attachSn, 원본 파일명, 크기, 등록일시만 제공한다.
 */
public record NoticeAttachResponse(
        Long attachSn,
        String fileName,
        long fileSize,
        LocalDateTime regDt
) {
    public static NoticeAttachResponse from(LsNoticeAttach attach) {
        return new NoticeAttachResponse(
                attach.getAttachSn(),
                attach.getOrgnlFileNm(),
                attach.getFileSize(),
                attach.getRegDt()
        );
    }
}

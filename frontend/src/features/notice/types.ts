// 게시판(공지) 도메인 타입 — BE notice DTO 와 1:1 정합.
//
// SoT: backend/src/main/java/kr/co/cudo/authoring/notice/dto/
// - NoticeSummaryResponse: {id, title, pinned, pubStatus, pubDt, regDt}
// - NoticeResponse: {id, title, content, pinned, pubStatus, pubDt, regId, writerName, regDt, mdfcnDt, attachments[]}
// - NoticeAttachResponse: {attachSn, fileName, fileSize, regDt}
//   (보안 — 서버 저장 경로/UUID 는 응답에 절대 포함되지 않음, CWE-209)

/** 발행 상태 — BE PublishStatus enum 미러. */
export const NoticePubStatus = {
  DRAFT: 'DRAFT',
  PUBLISHED: 'PUBLISHED',
} as const;
export type NoticePubStatus = (typeof NoticePubStatus)[keyof typeof NoticePubStatus];

/** 검색 필드 — BE LsNoticeQueryRepository.SearchField 미러. */
export const NoticeSearchField = {
  TITLE: 'TITLE',
  CONTENT: 'CONTENT',
  ALL: 'ALL',
} as const;
export type NoticeSearchField = (typeof NoticeSearchField)[keyof typeof NoticeSearchField];

/** 목록 요약 (본문 제외) — BE NoticeSummaryResponse. */
export interface NoticeSummary {
  id: number;
  title: string;
  pinned: boolean;
  pubStatus: NoticePubStatus;
  /** 발행 일시 (미발행이면 null). */
  pubDt: string | null;
  regDt: string;
}

/** 첨부파일 — BE NoticeAttachResponse. */
export interface NoticeAttach {
  attachSn: number;
  fileName: string;
  fileSize: number;
  regDt: string;
}

/** 상세 (본문 + 첨부 목록) — BE NoticeResponse. */
export interface Notice {
  id: number;
  title: string;
  content: string;
  pinned: boolean;
  pubStatus: NoticePubStatus;
  pubDt: string | null;
  /** 작성자 원값 — `LS_NOTICE.REG_ID`(=JWT sub=내부 사용자 번호 문자열). 표시는 writerName 우선. */
  regId: string | null;
  /**
   * 작성자 표시명 — `MNG_ACCT_USER.USER_NM`.
   * `REG_ID` 가 없거나 숫자가 아니거나(레거시 행) 사용자 마스터에 없으면(탈퇴·관제 계정 삭제) null.
   * 화면은 이 값을 우선 표시하고 없을 때만 regId 로 폴백한다.
   */
  writerName: string | null;
  regDt: string;
  mdfcnDt: string | null;
  attachments: NoticeAttach[];
}

/** 작성/수정 폼 — BE NoticeCreateRequest/NoticeUpdateRequest 와 동일 필드. */
export interface NoticeForm {
  title: string;
  content: string;
  pinned: boolean;
}

/** 목록 조회 파라미터. */
export interface NoticeListParams {
  page: number;
  size: number;
  /** 검색 필드 — 미지정 시 검색 미적용. */
  field?: NoticeSearchField;
  keyword?: string;
}

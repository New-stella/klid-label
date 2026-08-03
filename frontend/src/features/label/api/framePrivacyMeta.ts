// Phase 4 — 프레임 단위 개인정보(익명·가명·개인정보 포함여부) 메타 API 클라이언트.
//
// BE 계약(Phase 3 확정):
//   GET /v1/frames/{srcSn}/privacy-meta → ApiResponse<FramePrivacyMetaResponse>
//   PUT /v1/frames/{srcSn}/privacy-meta  body { srcSn, anonymity, pseudonymity, privacyIncluded }
//     → 전체 교체(full replace). null 필드는 수동값 삭제 → 파생 프리필로 폴백.
// 응답은 apiClient interceptor 가 ApiResponse.data 만 언랩해 반환한다.
//
// 값(BE @Pattern("\\A[YN]\\z") 정합): 각 필드 'Y' | 'N' | null. 체크박스로만 토글 — 자유입력 차단.
//   (^…$ 가 아닌 이유: Java 정규식의 $ 는 후행 개행 앞에서도 매치돼 "Y\n" 이 통과한다 — CRLF 표면)
// 보안: srcSn 은 number 로 강제(path 조작 불가). 응답 본문은 로그 미출력.

import { apiClient } from '@/lib/api/client';

/** Y/N/미판정(null) — BE @Pattern("\\A[YN]\\z") 정합. */
export type YnFlag = 'Y' | 'N' | null;

export interface FramePrivacyMeta {
  srcSn: number;
  /** 익명여부(Y/N) — 비식별 export 의 image 블록에 반영. */
  anonymity: YnFlag;
  /** 가명여부(Y/N) — 비식별 export 의 image 블록에 반영. */
  pseudonymity: YnFlag;
  /** 개인정보 포함여부(Y/N) — 비식별 export 의 image 블록에 반영. */
  privacyIncluded: YnFlag;
}

/** PUT 요청 바디 — 3필드 전체 교체(파생 유지는 null). */
export interface FramePrivacyMetaUpdate {
  anonymity: YnFlag;
  pseudonymity: YnFlag;
  privacyIncluded: YnFlag;
}

/** 프레임 개인정보 메타 조회. */
export function getFramePrivacyMeta(srcSn: number): Promise<FramePrivacyMeta> {
  return apiClient
    .get<FramePrivacyMeta>(`/frames/${srcSn}/privacy-meta`)
    .then((r) => normalize(r.data, srcSn));
}

/** 프레임 개인정보 메타 저장(전체 교체). srcSn 은 body 에도 포함(BE path 일치 검증). */
export function putFramePrivacyMeta(
  srcSn: number,
  body: FramePrivacyMetaUpdate,
): Promise<FramePrivacyMeta> {
  return apiClient
    .put<FramePrivacyMeta>(`/frames/${srcSn}/privacy-meta`, { srcSn, ...body })
    .then((r) => normalize(r.data, srcSn));
}

function normalize(data: FramePrivacyMeta | undefined, srcSn: number): FramePrivacyMeta {
  return {
    srcSn: Number(data?.srcSn ?? srcSn),
    anonymity: data?.anonymity ?? null,
    pseudonymity: data?.pseudonymity ?? null,
    privacyIncluded: data?.privacyIncluded ?? null,
  };
}

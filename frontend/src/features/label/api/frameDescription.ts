// blocker#2 Phase 3 — 프레임 설명(NIA image.description) API 클라이언트.
//
// BE(Phase 1 확정 계약):
//   GET /v1/frames/{srcSn}/description → ApiResponse<{ srcSn, description }>
//   PUT /v1/frames/{srcSn}/description  body { srcSn, description } → ApiResponse<{ srcSn, description }>
// 응답은 apiClient interceptor 가 ApiResponse.data 만 언랩해 반환한다.
//
// 보안: srcSn 은 number 로 강제 — path/query 조작 불가. description 은 서버가 @Size(1000) 검증.
//   설명 본문은 로그에 출력하지 않는다(개인정보 가능).

import { apiClient } from '@/lib/api/client';

export interface FrameDescription {
  srcSn: number;
  /** 프레임 자연어 설명 — 미입력 시 null */
  description: string | null;
}

/** 프레임 설명 조회. */
export function getFrameDescription(srcSn: number): Promise<FrameDescription> {
  return apiClient
    .get<FrameDescription>(`/frames/${srcSn}/description`)
    .then((r) => normalize(r.data, srcSn));
}

/**
 * 프레임 설명 저장/수정.
 * @param description null·빈 문자열이면 설명 삭제(서버 정책)
 */
export function putFrameDescription(
  srcSn: number,
  description: string | null,
): Promise<FrameDescription> {
  return apiClient
    .put<FrameDescription>(`/frames/${srcSn}/description`, { srcSn, description })
    .then((r) => normalize(r.data, srcSn));
}

function normalize(data: FrameDescription | undefined, srcSn: number): FrameDescription {
  return {
    srcSn: Number(data?.srcSn ?? srcSn),
    description: data?.description ?? null,
  };
}

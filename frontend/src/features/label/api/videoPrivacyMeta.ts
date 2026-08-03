// 영상 단위 개인정보(익명·가명·개인정보 포함여부) 메타 API 클라이언트.
//
// BE 계약:
//   GET /v1/videos/{rawSn}/privacy-meta → ApiResponse<VideoPrivacyMetaResponse>
//   PUT /v1/videos/{rawSn}/privacy-meta  body { anonymity, pseudonymity, privacyIncluded }
//     → 전체 교체(full replace). null 필드는 수동값 삭제 → 비식별 기본상수 프리필로 폴백.
// 응답은 apiClient interceptor 가 ApiResponse.data 만 언랩해 반환한다.
//
// 값(BE @Pattern("\\A[YN]\\z") 정합): 각 필드 'Y' | 'N' | null. 체크박스로만 토글 — 자유입력 차단.
//   (^…$ 가 아닌 이유: Java 정규식의 $ 는 후행 개행 앞에서도 매치돼 "Y\n" 이 통과한다 — CRLF 표면)
// ★기본상수(익명 Y / 가명 N / 개인정보포함 N)는 <BE 가 프리필해 내려준다> — FE 가 하드코딩하지 않는다.
//   소비자가 생산자의 판정을 재유도하면 정책 변경 시 조용히 드리프트하기 때문이다.
// 보안: rawSn 은 number 로 강제(path 조작 불가). 응답 본문은 로그 미출력.

import { apiClient } from '@/lib/api/client';

/** Y/N/미판정(null) — BE @Pattern("\\A[YN]\\z") 정합. */
export type YnFlag = 'Y' | 'N' | null;

/** 항목별 출처 — 사람이 저장한 판정(MANUAL) vs BE 기본상수 프리필(DERIVED). */
export type PrivacyMetaSource = 'MANUAL' | 'DERIVED' | null;

export interface VideoPrivacyMeta {
  rawSn: number;
  anonymity: YnFlag;
  pseudonymity: YnFlag;
  privacyIncluded: YnFlag;
  anonymitySource: PrivacyMetaSource;
  pseudonymitySource: PrivacyMetaSource;
  privacyIncludedSource: PrivacyMetaSource;
}

/** PUT 요청 바디 — 3필드 전체 교체(프리필 유지는 null). */
export interface VideoPrivacyMetaUpdate {
  anonymity: YnFlag;
  pseudonymity: YnFlag;
  privacyIncluded: YnFlag;
}

/** 영상 개인정보 메타 조회. */
export function getVideoPrivacyMeta(rawSn: number): Promise<VideoPrivacyMeta> {
  return apiClient
    .get<VideoPrivacyMeta>(`/videos/${rawSn}/privacy-meta`)
    .then((r) => normalize(r.data, rawSn));
}

/** 영상 개인정보 메타 저장(전체 교체). */
export function putVideoPrivacyMeta(
  rawSn: number,
  body: VideoPrivacyMetaUpdate,
): Promise<VideoPrivacyMeta> {
  return apiClient
    .put<VideoPrivacyMeta>(`/videos/${rawSn}/privacy-meta`, body)
    .then((r) => normalize(r.data, rawSn));
}

function normalize(
  data: VideoPrivacyMeta | undefined,
  rawSn: number,
): VideoPrivacyMeta {
  return {
    rawSn: Number(data?.rawSn ?? rawSn),
    anonymity: data?.anonymity ?? null,
    pseudonymity: data?.pseudonymity ?? null,
    privacyIncluded: data?.privacyIncluded ?? null,
    anonymitySource: data?.anonymitySource ?? null,
    pseudonymitySource: data?.pseudonymitySource ?? null,
    privacyIncludedSource: data?.privacyIncludedSource ?? null,
  };
}

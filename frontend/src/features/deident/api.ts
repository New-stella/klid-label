// 비식별화 재처리 API.
//
// 비식별 결과 조회(목록/상세) API 는 외부 비식별 솔루션으로 이관되어 제거됨.
// reprocessDeident 는 진입점(조회 화면)이 사라져 현재 미참조이나, 추후 신고→재처리
// 연계용으로 유지한다.
//
// 보안: 사용자 입력은 path로만 전달 (axios 자동 인코딩, XSS/Injection 방지).
// IDOR 방어는 BE 책임.

import { apiClient } from '@/lib/api/client';

export function reprocessDeident(videoId: number): Promise<void> {
  return apiClient.post(`/deident/${videoId}/reprocess`).then(() => undefined);
}

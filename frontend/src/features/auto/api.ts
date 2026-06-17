// 오토라벨링/시계열 메타 도메인 API.
//
// 보안: srcSn는 number 타입 (path param). axios 자동 URL 인코딩.
// IDOR/Mass Assignment 방어는 BE 책임 (FE는 분기만).

import { apiClient } from '@/lib/api/client';

import type { FrameMeta, FrameMetaUpdateRequest, MetaItem } from './types';

/** BE 실제 응답: {@code { items: [{metaSn, metaKey, metaVal}] }} (영상 단위 K/V 목록, 0건 가능). */
interface MetaApiResponse {
  items?: MetaItem[] | null;
}

/**
 * R7-2: BE {@code MetaResponse {items:[...]}} → 화면 표시 모델로 변환하는 어댑터.
 *
 * BE 가 SoT 이므로 items 목록을 원본으로 보존하고, vlmText 는 metaKey 오름차순으로 결합한다.
 * items 가 0건/누락이어도 vlmText=''·stateChanges=[] 안전 기본값을 채워 화면 크래시를 방지한다.
 */
function toFrameMeta(res: MetaApiResponse | null | undefined): FrameMeta {
  const items: MetaItem[] = Array.isArray(res?.items) ? res!.items! : [];
  const vlmText = [...items]
    .sort((a, b) => String(a.metaKey).localeCompare(String(b.metaKey)))
    .map((it) => it.metaVal ?? '')
    .join('\n');
  return { items, vlmText, stateChanges: [] };
}

export function getMeta(srcSn: number): Promise<FrameMeta> {
  return apiClient
    .get<MetaApiResponse>(`/frames/${srcSn}/meta`)
    .then((r) => toFrameMeta(r.data));
}

export function updateMeta(
  srcSn: number,
  body: FrameMetaUpdateRequest,
): Promise<FrameMeta> {
  return apiClient
    .put<MetaApiResponse>(`/frames/${srcSn}/meta`, body)
    .then((r) => toFrameMeta(r.data));
}

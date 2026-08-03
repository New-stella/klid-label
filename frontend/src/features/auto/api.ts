// 오토라벨링/시계열 메타 도메인 API.
//
// 보안: srcSn는 number 타입 (path param). axios 자동 URL 인코딩.
// IDOR/Mass Assignment 방어는 BE 책임 (FE는 분기만).

import { apiClient } from '@/lib/api/client';

import type { FrameMeta, FrameMetaUpdateRequest, MetaItem } from './types';

/**
 * BE 실제 응답:
 * {@code { items: [...], technicalMeta: [...] }} — 각 항목은
 * {@code {metaSn, metaKey, metaVal, dataMetaReviewSn, reviewStatus}} (영상 단위 K/V, 0건 가능).
 *
 * {@code items}=시계열 메타, {@code technicalMeta}=영상 기술메타({@code video.*}).
 */
interface MetaApiResponse {
  items?: MetaItem[] | null;
  technicalMeta?: MetaItem[] | null;
}

/**
 * 영상 기술메타 키 접두 — BE {@code VideoMetaService.KEY_PREFIX} 미러.
 *
 * 분류의 진실원은 BE 이며 FE 는 배포 스큐(구 BE 가 {@code items} 에 {@code video.*} 를 섞어 보내는
 * 창) 동안만 방어적으로 쓴다. FE 내에서는 이 상수 한 곳만 참조한다(접두 문자열 산재 금지).
 */
const TECHNICAL_META_KEY_PREFIX = 'video.';

function isTechnicalMetaKey(metaKey: unknown): boolean {
  return typeof metaKey === 'string' && metaKey.startsWith(TECHNICAL_META_KEY_PREFIX);
}

/**
 * R7-2: BE {@code MetaResponse} → 화면 표시 모델로 변환하는 어댑터.
 *
 * BE 가 SoT 이므로 items 목록을 원본으로 보존하고, vlmText 는 metaKey 오름차순으로 결합한다.
 * items 가 0건/누락이어도 vlmText=''·stateChanges=[] 안전 기본값을 채워 화면 크래시를 방지한다.
 *
 * 2026-08-03: 기술메타({@code video.*})를 시계열에서 분리한다. 신 BE 는 {@code technicalMeta} 로
 * 내려주고, 구 BE 는 {@code items} 에 섞어 보내므로 양쪽을 합쳐 기술메타 목록을 만든다
 * (한쪽은 항상 비어 있어 중복되지 않는다). vlmText 는 시계열 항목만으로 결합해 기술 수치가
 * 산문 편집 텍스트에 섞이지 않게 한다.
 */
function toFrameMeta(res: MetaApiResponse | null | undefined): FrameMeta {
  const rawItems: MetaItem[] = Array.isArray(res?.items) ? res!.items! : [];
  const beTechnical: MetaItem[] = Array.isArray(res?.technicalMeta)
    ? res!.technicalMeta!
    : [];

  const items = rawItems.filter((it) => !isTechnicalMetaKey(it.metaKey));
  const technicalMeta = [
    ...beTechnical,
    ...rawItems.filter((it) => isTechnicalMetaKey(it.metaKey)),
  ];

  const vlmText = [...items]
    .sort((a, b) => String(a.metaKey).localeCompare(String(b.metaKey)))
    .map((it) => it.metaVal ?? '')
    .join('\n');
  return { items, technicalMeta, vlmText, stateChanges: [] };
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

// 시계열 메타 검토 승인/반려(POST /v1/meta/{metaReviewSn}/approve|reject) 클라이언트는
// 제거했다(2026-08-03 사용자 확정) — 라벨링 화면에서 승인/반려 UI 를 제공하지 않으며,
// 검토 상태 확정은 영상 검수 승인 시 BE 자동 동결이 담당한다. BE 엔드포인트는 존치.

// 포털 채널 API — BE: /api/v1/portal/*
// 보안: axios가 자동 URL 인코딩. IDOR 방어는 BE 책임 (PORTAL_USER 본인 데이터만 노출, CWE-639).
//
// BE 엔드포인트 (kr.co.cudo.authoring.portal.controller.PortalLabelController):
//   GET  /v1/portal/frames/{srcSn}/labels  → 프레임 단위 라벨 Load (datamart 원본 + 본인 user-label 병합)
//   GET  /v1/portal/frames/{srcSn}/image   → 프레임 비식별 이미지 바이너리 (PORTAL_USER 전용)
//   GET  /v1/portal/datamart/labels        → 데이터마트 원본 라벨 Load (rawSn)
//   GET  /v1/portal/user-labels            → 본인 작업 라벨 조회 (rawSn)
//   POST /v1/portal/user-labels            → 본인 작업 라벨 저장 (원본 미수정 — LS_PORTAL_USER_LABEL)
//
// ADR-013: 포털은 데이터마트 영상 선택·간편 라벨링 전용. 오토라벨링·업로드(TUS)·검수·버전관리 미제공.

import { apiClient } from '@/lib/api/client';

// 내부 라벨 api 의 normalizeLabel 과 동일 변환을 재사용 (중복 제거 — 단일 export).
import { normalizeLabel as normalizeLabelShared } from '@/features/label/api';
import type { LabelsResponse, SiblingFrame } from '@/features/label/types';

/**
 * R16 — 포털 프레임 라벨 Load.
 * BE: GET /v1/portal/frames/{srcSn}/labels
 *  - datamart 원본 라벨 + 본인 user-label 병합 (user-label 있으면 우선)
 *  - 응답 shape 은 내부 LabelsResponse 와 동일 (videoId/siblings/labels)
 *
 * 내부 getLabels 의 normalize 를 재사용하기 위해 동일 정규화 로직을 거친다.
 */
export function getPortalLabels(srcSn: number): Promise<LabelsResponse> {
  return apiClient
    .get<LabelsResponse | { items: unknown[] }>(`/portal/frames/${srcSn}/labels`)
    .then((r) => normalizePortalLabelsResponse(srcSn, r.data));
}

/** BE PortalLabelsResponse → FE LabelsResponse 정규화 (내부 normalizeLabel 재사용). */
function normalizePortalLabelsResponse(
  srcSn: number,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  d: any,
): LabelsResponse {
  const rawList0 = Array.isArray(d?.labels) ? d.labels : Array.isArray(d?.items) ? d.items : [];
  // R17 이슈2 — points 가 비어있는(검증 우회로 생성된 stale) 라벨은 캔버스 렌더 크래시 → navigate(-1)
  // 튕김을 유발한다. 빈 좌표 라벨은 안전 스킵 (BE 도 로드/저장 단에서 차단하지만 FE 도 방어).
  // LabelsLayer 등 캔버스 레이어는 다른 에이전트 작업 영역이라 건드리지 않고 normalize 단계에서 거른다.
  const rawList = rawList0.filter(
    (l: unknown) => Array.isArray((l as { points?: unknown })?.points) &&
      ((l as { points: unknown[] }).points.length ?? 0) > 0,
  );
  const siblings: SiblingFrame[] = Array.isArray(d?.siblings)
    ? d.siblings.map((s: { srcSn: number; frameNo: number }) => ({
        srcSn: Number(s.srcSn),
        frameNo: Number(s.frameNo),
      }))
    : [];
  return {
    frameNo: d?.frameNo ?? 0,
    srcSn,
    videoId: d?.videoId !== undefined && d?.videoId !== null ? Number(d.videoId) : undefined,
    // 포털은 비식별 프레임 고정 — RAW 토글/잠금/재처리 없음
    frameImageType: 'DEID',
    lockSttsCd: null,
    siblings,
    labels: rawList.map(normalizeLabelShared),
  };
}

/** R16 — 포털 프레임 이미지 경로 (useImageBlob 가 portalMode 일 때 사용). */
export function portalFrameImagePath(srcSn: number): string {
  return `/portal/frames/${srcSn}/image`;
}

/**
 * R16 — 포털 사용자 라벨 저장 요청 (BE PortalUserLabelRequest 와 1:1).
 * 원본 미수정 — LS_PORTAL_USER_LABEL 별도 적재.
 */
export interface PortalUserLabelRequest {
  sourceRawSn: number;
  sourceSrcSn: number;
  lblTypeCd: string;
  label: string;
  points: string; // JSON 직렬화된 좌표 ([[x,y],...])
}

export interface PortalUserLabelResponse {
  userLblSn: number;
  sourceRawSn: number;
  sourceSrcSn: number;
  lblTypeCd: string;
  label: string;
  points: string;
  createdAt: string;
}

/**
 * R16 — 포털 사용자 라벨 단건 저장.
 * BE: POST /v1/portal/user-labels  — body: PortalUserLabelRequest
 */
export function savePortalUserLabel(
  req: PortalUserLabelRequest,
): Promise<PortalUserLabelResponse> {
  return apiClient
    .post<PortalUserLabelResponse>('/portal/user-labels', req)
    .then((r) => r.data);
}

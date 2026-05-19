// 포털 채널 API — BE: /api/v1/portal/*
// 보안: axios가 자동 URL 인코딩. IDOR 방어는 BE 책임 (PORTAL_USER 본인 데이터만 노출).
//
// BE 시그니처 (kr.co.cudo.authoring.portal.controller.PortalLabelController):
//   GET  /v1/portal/uploads     → List<PortalUploadResponse>
//   POST /v1/portal/autolabel   body: PortalAutolabelRequest { portalVideoSn, imageB64 }
//   POST /v1/portal/labels      body: PortalLabelRequest     { portalVideoSn, items[] }
//
// hotfix(W-2): savePortalLabels 는 이전에 PUT `/portal/labels/{srcSn}` + `{labels}` 로
// 호출해 405/404 가 발생했다. BE 는 POST `/portal/labels` + body `{portalVideoSn, items}` 이므로 정합.
// hotfix(W-3): requestAutolabel 은 이전에 `{srcSn}` 만 보내 BE @NotBlank imageB64 검증에 의해
// 400 이 발생했다. BE DTO 필수 필드 (`portalVideoSn`, `imageB64`) 를 모두 전달하도록 수정.

import { apiClient } from '@/lib/api/client';

import type { PortalAutolabelResponse, PortalUpload } from './types';

/**
 * 본인이 업로드한 영상 목록 조회.
 * BE: GET /api/v1/portal/uploads — 인증 사용자 본인 데이터만 반환.
 */
export function listMyUploads(): Promise<PortalUpload[]> {
  return apiClient.get<PortalUpload[]>('/portal/uploads').then((r) => r.data);
}

/**
 * 오토라벨링 요청 (체험형 — YOLO).
 * BE: POST /api/v1/portal/autolabel — 본인 업로드 portalVideoSn 한정 (IDOR 방어, BE 책임).
 *
 * @param portalVideoSn LS_PORTAL_USER_VIDEO PK
 * @param imageB64      단일 프레임 base64 (BE 최대 20MB)
 */
export function requestAutolabel(
  portalVideoSn: number,
  imageB64: string,
): Promise<PortalAutolabelResponse> {
  return apiClient
    .post<PortalAutolabelResponse>('/portal/autolabel', { portalVideoSn, imageB64 })
    .then((r) => r.data);
}

/**
 * 포털 라벨링 결과 저장 (간편 라벨링 — 버전관리 미제공, echo only).
 * BE: POST /api/v1/portal/labels — body: { portalVideoSn, items[] }.
 *
 * @param portalVideoSn LS_PORTAL_USER_VIDEO PK
 * @param items         BE PortalLabelRequest.Item[] 호환 — { label, lblTypeCd, points[][] }
 */
export interface PortalLabelItem {
  label?: string;
  lblTypeCd?: string;
  points?: number[][];
}

export function savePortalLabels(
  portalVideoSn: number,
  items: PortalLabelItem[],
): Promise<{ portalVideoSn: number; items: PortalLabelItem[] }> {
  return apiClient
    .post<{ portalVideoSn: number; items: PortalLabelItem[] }>('/portal/labels', {
      portalVideoSn,
      items,
    })
    .then((r) => r.data);
}

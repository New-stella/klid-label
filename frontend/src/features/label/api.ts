// 라벨 도메인 API — BE: /api/v1/frames/{srcSn}/labels, /commit
//
// 보안: 사용자 입력은 path/body 파라미터로만 전달 (axios 자동 URL 인코딩, XSS 방지).
// IDOR/Mass Assignment 방어는 BE 책임.

import { apiClient } from '@/lib/api/client';

import type {
  FrameImageType,
  Label,
  LabelsResponse,
  LabelSrcCd,
  LockSttsCd,
  Shape,
  SiblingFrame,
} from './types';

export interface CommitResponse {
  commitSha: string;
  committedAt: string;
}

/**
 * BE → FE 라벨 정규화.
 * BE 응답: { id: number, lblTypeCd: 'BBOX'|'POLYGON'|'MASK', label: string,
 *           points: [[x,y],...], autoLblYn: 'Y'|'N', confScore: number, ... }
 * FE 타입(Label): { id: string, className, source, confidence, shape: {...} }
 *
 * 이미 FE 형태로 들어오는 응답(테스트/구버전)은 그대로 통과.
 */
function normalizeLabel(raw: any): Label {
  const id = raw?.id !== undefined && raw?.id !== null ? String(raw.id) : '';

  // 이미 정규화된 형태면 id만 string 캐스팅하여 반환
  if (raw?.shape && typeof raw.shape === 'object' && raw.shape.type) {
    return { ...raw, id } as Label;
  }

  const lblType: string = raw?.lblTypeCd ?? raw?.shape?.type ?? 'BBOX';
  const points = raw?.points;
  const shape: Shape = (() => {
    if (lblType === 'BBOX') {
      // BE points: [[left, top], [right, bottom]] 또는 flat [l,t,r,b]
      if (Array.isArray(points) && points.length >= 2) {
        const p0 = points[0];
        const p1 = points[1];
        if (Array.isArray(p0) && Array.isArray(p1)) {
          return {
            type: 'BBOX',
            left: Number(p0[0]) || 0,
            top: Number(p0[1]) || 0,
            right: Number(p1[0]) || 0,
            bottom: Number(p1[1]) || 0,
          };
        }
        if (typeof p0 === 'number') {
          return {
            type: 'BBOX',
            left: Number(points[0]) || 0,
            top: Number(points[1]) || 0,
            right: Number(points[2]) || 0,
            bottom: Number(points[3]) || 0,
          };
        }
      }
      return { type: 'BBOX', left: 0, top: 0, right: 0, bottom: 0 };
    }
    if (lblType === 'POLYGON') {
      const flat: number[] = Array.isArray(points)
        ? points.flatMap((p: any) =>
            Array.isArray(p) ? [Number(p[0]) || 0, Number(p[1]) || 0] : [Number(p) || 0],
          )
        : [];
      return { type: 'POLYGON', points: flat };
    }
    return { type: 'MASK' };
  })();

  const autoYn = raw?.autoLblYn;
  const source: Label['source'] =
    autoYn === 'Y'
      ? raw?.lblTypeCd === 'POLYGON' || raw?.algorithm === 'SAM2'
        ? 'AUTO_SAM2'
        : 'AUTO_YOLO'
      : 'MANUAL';

  // BE LabelResponse.Item.trackId 는 VARCHAR(64) 로 String 또는 null 로 들어온다.
  // Phase 5: 정수 형태로 전달되는 케이스(legacy/SAM2 등)도 문자열로 정규화한다.
  const rawTrackId = raw?.trackId;
  const trackId: string | null =
    rawTrackId === null || rawTrackId === undefined || rawTrackId === ''
      ? null
      : String(rawTrackId);

  // Phase 4: LBL_SRC_CD — 보간 row 식별. 누락/공백/타 값은 null 정규화.
  const rawSrcCd = raw?.lblSrcCd;
  const lblSrcCd: LabelSrcCd | null =
    rawSrcCd === 'INTERPOLATED' ? 'INTERPOLATED' : null;

  return {
    id,
    serverId: typeof raw?.id === 'number' ? raw.id : undefined,
    frameNo: Number(raw?.frameNo ?? 0),
    classId: Number(raw?.classId ?? raw?.classCd ?? 0),
    className: String(raw?.label ?? raw?.className ?? ''),
    source,
    confidence: raw?.confScore !== undefined ? Number(raw.confScore) : raw?.confidence,
    shape,
    trackId,
    lblSrcCd,
  };
}

export interface GetLabelsOptions {
  /**
   * REVIEWER 가 비식별이 아닌 원본 프레임을 보고 싶을 때 true.
   * - WORKER 가 true 로 호출해도 BE 가 강제로 DEID 응답.
   * - 기본 false (DEID 우선).
   */
  raw?: boolean;
}

/**
 * 프레임의 라벨 목록 조회.
 *
 * BE 응답에 다음 필드가 함께 포함된다:
 *   - videoId        : LS_DATA_RAW.RAW_SN (해당 프레임이 속한 영상)
 *   - siblings       : 동일 영상의 모든 프레임 (FRAME_NO ASC)
 *   - frameImageType : 'DEID' | 'RAW' (Phase 3 신규)
 *   - lockSttsCd     : 'LOCKED_FOR_REDEIDENT' | null (Phase 3 신규)
 * FE 는 siblings 로 프레임 타임라인을 구성하고 클릭 시 해당 프레임 URL 로 이동.
 */
export function getLabels(
  srcSn: number,
  opts?: GetLabelsOptions,
): Promise<LabelsResponse> {
  const params = opts?.raw ? { raw: true } : undefined;
  return apiClient
    .get<LabelsResponse | { items: Label[] }>(`/frames/${srcSn}/labels`, { params })
    .then((r) => {
      const d = r.data as LabelsResponse & { items?: Label[] };
      const rawList = Array.isArray(d.labels)
        ? d.labels
        : Array.isArray(d.items)
          ? d.items
          : [];
      const siblings: SiblingFrame[] = Array.isArray(d.siblings)
        ? d.siblings.map((s) => ({
            srcSn: Number(s.srcSn),
            frameNo: Number(s.frameNo),
          }))
        : [];
      // frameImageType — 화이트리스트 검증 (BE 응답 신뢰하되, 알 수 없는 값은 undefined)
      const fit = d.frameImageType;
      const frameImageType: FrameImageType | undefined =
        fit === 'RAW' || fit === 'DEID' ? fit : undefined;
      // lockSttsCd — null/undefined 정규화, 그 외 코드는 그대로 통과 (확장 대비 string 허용)
      const rawLock = (d as LabelsResponse).lockSttsCd;
      const lockSttsCd: LockSttsCd | string | null =
        rawLock === null || rawLock === undefined || rawLock === '' ? null : rawLock;
      return {
        frameNo: d.frameNo ?? 0,
        srcSn,
        videoId: d.videoId !== undefined && d.videoId !== null ? Number(d.videoId) : undefined,
        frameImageType,
        lockSttsCd,
        siblings,
        labels: rawList.map(normalizeLabel),
      };
    });
}

/**
 * 프레임 라벨 일괄 저장 (전체 교체).
 * BE: PUT /frames/{srcSn}/labels
 */
export function putLabels(srcSn: number, labels: Label[]): Promise<LabelsResponse> {
  return apiClient
    .put<LabelsResponse>(`/frames/${srcSn}/labels`, { labels })
    .then((r) => r.data);
}

/**
 * Gitea 커밋 트리거 (저장 후 버전관리 반영).
 * BE: POST /frames/{srcSn}/commit
 */
export function commitLabels(srcSn: number, message?: string): Promise<CommitResponse> {
  return apiClient
    .post<CommitResponse>(`/frames/${srcSn}/commit`, { message: message ?? null })
    .then((r) => r.data);
}

/**
 * 비식별 누락 신고 — Phase 3 (라벨러 안전장치).
 *
 * BE: POST /v1/labels/{srcSn}/deident-report
 *   - Body: { reason: string (1~1000자) }
 *   - 응답: 201 { success: true, data: <reportId> }
 *   - 에러: 400 (검증 실패), 403 (본인 배정 아님), 404 (영상 없음),
 *           409 (이미 잠금 — LOCKED_FOR_REDEIDENT)
 *
 * 보안:
 *  - srcSn: number (axios path 자동 인코딩)
 *  - reason: body 로 전달 (HTML escape 는 표시 단에서 React 가 자동 처리)
 *  - IDOR/권한 검증은 BE 책임
 */
export function reportDeidentMiss(srcSn: number, reason: string): Promise<number> {
  return apiClient
    .post<number>(`/labels/${srcSn}/deident-report`, { reason })
    .then((r) => r.data as unknown as number);
}

export interface Sam2TrackRequest {
  /** seed BBox: [left, top, right, bottom] (image px) */
  bbox: [number, number, number, number];
  classId: number;
  /** 다음 N개 프레임까지 자동 추적 (BE 정책상 상한 적용) */
  targetFrameCount: number;
  /** 트랙 ID (이어붙일 기존 트랙 — 없으면 BE가 새로 발급) */
  trackId?: number;
}

export interface Sam2TrackPropagatedFrame {
  frameNo: number;
  bbox: [number, number, number, number];
  confidence?: number;
}

export interface Sam2TrackResponse {
  trackId: number;
  propagatedFrames: Sam2TrackPropagatedFrame[];
}

/**
 * SAM2 자동 추적 요청.
 * BE: POST /frames/{srcSn}/sam2-track
 *
 * 보안: srcSn/bbox/classId 입력 검증 + IDOR 방어는 BE 책임.
 */
export function requestSam2Track(
  srcSn: number,
  payload: Sam2TrackRequest,
): Promise<Sam2TrackResponse> {
  return apiClient
    .post<Sam2TrackResponse>(`/frames/${srcSn}/sam2-track`, payload)
    .then((r) => r.data);
}

// 라벨 도메인 API — BE: /api/v1/frames/{srcSn}/labels
//
// 라벨 저장(PUT)은 작업본 임시저장만 수행한다. 버전 스냅샷은 검수 승인(APPROVED) 시점에 BE 가
// 생성하므로 FE 에서 별도 커밋 호출은 하지 않는다(수동 commit 엔드포인트 폐기).
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

/**
 * 라벨 스냅샷 커밋 결과 (BE 확정 계약).
 * - commitSha: versionHash — 라벨 스냅샷 SHA-256 hex(64자). 멱등(동일 스냅샷 재커밋 시 동일 해시).
 * - committedAt: ISO-8601 커밋 시각
 */
export interface CommitResponse {
  commitSha: string; // = versionHash (SHA-256 hex 64)
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
export function normalizeLabel(raw: any): Label {
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
    // KEYPOINT(BE lblTypeCd='SKELETON') — points 는 17×[x,y,v] 삼중값.
    // FE KeypointShape.keypoints({x,y,v}[]) 로 복원. v 는 {0,1,2} 로 클램프(범위 밖 → 0).
    if (lblType === 'SKELETON' || lblType === 'KEYPOINT') {
      const keypoints = Array.isArray(points)
        ? points.map((p: any) => {
            const arr = Array.isArray(p) ? p : [];
            const vNum = Number(arr[2]);
            const v = vNum === 2 ? 2 : vNum === 1 ? 1 : 0;
            return { x: Number(arr[0]) || 0, y: Number(arr[1]) || 0, v };
          })
        : [];
      return { type: 'KEYPOINT', keypoints };
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

  // Phase 3 (속성 도메인 직후) — LS_LABEL.color enrichment 보존.
  // BE LabelResponse.Item.color 는 '#RRGGBB' 또는 null (매칭 실패).
  // FE 캔버스에서 BBox/Polygon 외곽선 색상으로 사용한다.
  const rawColor = raw?.color;
  const color: string | null =
    typeof rawColor === 'string' && /^#[0-9A-Fa-f]{6}$/.test(rawColor) ? rawColor : null;

  // BE LabelResponse.Item.labelId — LS_LABEL.LABEL_ID 와 1:1.
  // FE 는 labelMasters lookup 의 키로 사용 (color 누락 시 fallback).
  const rawLabelId = raw?.labelId;
  const labelId: number | null =
    rawLabelId === null || rawLabelId === undefined || rawLabelId === '' || Number.isNaN(Number(rawLabelId))
      ? null
      : Number(rawLabelId);

  // Hotfix: BE 가 수동 라벨에 대해 confScore: null 을 응답 (LS_DATA_LBL_AI_INFO 행 없음).
  // 기존 코드는 `null !== undefined → Number(null) === 0` 으로 confidence=0 을 설정해
  // ObjectAttributePanel 이 "낮은 신뢰도" 배지 + 신뢰도 바 0% 를 잘못 표시.
  // null/undefined 모두 undefined 로 정규화하여 표시 자체를 막는다.
  const rawConfScore = raw?.confScore;
  const rawConfidence = raw?.confidence;
  const confidence: number | undefined =
    rawConfScore !== undefined && rawConfScore !== null
      ? Number(rawConfScore)
      : rawConfidence !== undefined && rawConfidence !== null
        ? Number(rawConfidence)
        : undefined;

  return {
    id,
    serverId: typeof raw?.id === 'number' ? raw.id : undefined,
    frameNo: Number(raw?.frameNo ?? 0),
    classId: Number(raw?.classId ?? raw?.classCd ?? 0),
    labelId,
    color,
    className: String(raw?.label ?? raw?.className ?? ''),
    source,
    confidence,
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
            // R5 — 라벨 저장된 프레임 여부(SAVED 연두 판정). BE 미주입 시 false.
            hasLabel: s.hasLabel === true,
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
 * FE Label → BE LabelItemDto 변환.
 * BE PUT 요청 body: { items: LabelItemDto[] }
 */
function serializeLabel(lbl: Label): object {
  const id: number | null =
    lbl.serverId != null
      ? lbl.serverId
      : lbl.id && !isNaN(Number(lbl.id))
        ? Number(lbl.id)
        : null;

  const lblTypeCd =
    lbl.shape.type === 'MASK'
      ? 'SEGMENT'
      : lbl.shape.type === 'KEYPOINT'
        ? 'SKELETON'
        : lbl.shape.type;

  let points: number[][];
  if (lbl.shape.type === 'BBOX') {
    points = [
      [lbl.shape.left, lbl.shape.top],
      [lbl.shape.right, lbl.shape.bottom],
    ];
  } else if (lbl.shape.type === 'POLYGON') {
    const flat = lbl.shape.points;
    points = [];
    for (let i = 0; i + 1 < flat.length; i += 2) {
      points.push([flat[i], flat[i + 1]]);
    }
  } else if (lbl.shape.type === 'KEYPOINT') {
    // 17×[x,y,v] 삼중값 — BE POINT_CN SKELETON 포맷.
    points = lbl.shape.keypoints.map((k) => [k.x, k.y, k.v]);
  } else {
    points = [];
  }

  return { id, lblTypeCd, labelId: lbl.labelId ?? null, label: lbl.className, points };
}

/**
 * 프레임 라벨 일괄 저장 (전체 교체).
 * BE: PUT /frames/{srcSn}/labels  — body: { items: LabelItemDto[] }
 */
export function putLabels(srcSn: number, labels: Label[]): Promise<LabelsResponse> {
  return apiClient
    .put<LabelsResponse>(`/frames/${srcSn}/labels`, { items: labels.map(serializeLabel) })
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

/**
 * SAM2 Track 요청 — BE 계약(SoT)에 정합.
 * BE record Sam2TrackRequest: { srcSn, trackId, prevPolygon, label, nextSrcSns }
 *  - srcSn       : 시작 프레임 SRC_SN (path 와 동일)
 *  - trackId     : 트랙 식별자 (이어붙일 기존 트랙 또는 신규 클라이언트 발급 — NotBlank)
 *  - prevPolygon : 시작 프레임 폴리곤 [[x,y],...] (최소 3점, 박스는 4점으로 변환)
 *  - label       : 객체 라벨명 (NotBlank)
 *  - nextSrcSns  : 트래킹 대상 후속 프레임 SRC_SN 리스트 (1~50)
 */
export interface Sam2TrackRequest {
  trackId: string;
  prevPolygon: number[][];
  label: string;
  nextSrcSns: number[];
}

/** BE Sam2TrackResponseDto.TrackedItem 와 1:1. */
export interface Sam2TrackedItem {
  srcSn: number;
  trackId: string;
  label: string;
  points: number[][];
  score: number;
}

/** BE Sam2TrackResponseDto. */
export interface Sam2TrackResponse {
  tracked: Sam2TrackedItem[];
}

/**
 * SAM2 자동 추적 요청.
 * BE: POST /frames/{srcSn}/sam2-track  — body: { srcSn, trackId, prevPolygon, label, nextSrcSns }
 *
 * 보안: srcSn/prevPolygon/nextSrcSns 입력 검증 + IDOR 방어는 BE 책임.
 */
export function requestSam2Track(
  srcSn: number,
  payload: Sam2TrackRequest,
  // Phase 9 — portalMode=true 면 포털 전용 /portal/frames/{id}/sam2-track 로 분기(persist 없이 좌표만).
  // 내부 /frames/{id}/sam2-track 은 서버에서 LS_DATA_LBL 에 persist 하며 PORTAL 채널 403 이므로 호출 금지.
  portalMode = false,
): Promise<Sam2TrackResponse> {
  const base = portalMode ? '/portal/frames' : '/frames';
  return apiClient
    .post<Sam2TrackResponse>(`${base}/${srcSn}/sam2-track`, { srcSn, ...payload })
    .then((r) => r.data);
}

/**
 * SAM2 Track 청크 크기 — BE Sam2TrackRequest `@Size(max = 50)` 안전상한(CWE-770 방어)과 정합.
 * 한 번의 요청에 실을 수 있는 nextSrcSns 최대 개수. 이 값을 넘기면 BE 가 400 을 반환하므로
 * FE 는 반드시 이 크기 이하로 분할해 순차 호출한다.
 */
export const SAM2_TRACK_CHUNK_SIZE = 50;

/**
 * 청크 순차 추적 중 특정 청크에서 실패했음을 나타내는 에러.
 * 이미 성공한 청크의 추적 결과(`partial`)를 보존해 부분 성공을 유지할 수 있게 한다(롤백 금지).
 */
export class Sam2TrackChunkError extends Error {
  /** 실패 시점까지 누적된(=성공 확정된) 추적 결과. */
  readonly partial: Sam2TrackedItem[];
  /** 정상 완료된 청크 수. */
  readonly completedChunks: number;
  /** 전체 청크 수. */
  readonly totalChunks: number;

  constructor(
    cause: unknown,
    partial: Sam2TrackedItem[],
    completedChunks: number,
    totalChunks: number,
  ) {
    super('SAM2 자동추적 일부 청크 실패', { cause });
    this.name = 'Sam2TrackChunkError';
    this.partial = partial;
    this.completedChunks = completedChunks;
    this.totalChunks = totalChunks;
  }
}

/**
 * nextSrcSns 를 {@link SAM2_TRACK_CHUNK_SIZE} 이하 청크로 분할해 순차 추적한다(폴리곤 전파 체인).
 *
 * - 청크 1: startSrcSn = 초기 시작 프레임, prevPolygon = 초기 폴리곤.
 * - 청크 N(>1): startSrcSn = 직전 청크 마지막 프레임의 srcSn, prevPolygon = 직전 청크 마지막
 *   추적 폴리곤(points). 이렇게 마지막 추적 결과를 다음 청크의 시작 프롬프트로 이어붙인다.
 * - 모든 청크의 tracked 를 누적해 하나의 응답으로 합산 반환한다.
 *
 * 부분 실패 시 이미 성공한 청크 결과를 담은 {@link Sam2TrackChunkError} 를 throw 한다(롤백하지 않음).
 * 각 청크는 50개 이하이므로 BE `@Size(max=50)` 상한을 항상 만족한다(안전상한 유지).
 *
 * @param startSrcSn 최초 시작 프레임 SRC_SN
 * @param payload    trackId/label/prevPolygon + 전체 nextSrcSns
 * @param onProgress (누적 추적 프레임 수, 전체 대상 수) 진행률 콜백 — 청크 완료마다 호출
 */
export async function sam2TrackAllChunks(
  startSrcSn: number,
  payload: Sam2TrackRequest,
  onProgress?: (done: number, total: number) => void,
  // Phase 9 — 포털 모드면 모든 청크를 포털 전용 경로로 호출(내부 persist 경로 미사용).
  portalMode = false,
): Promise<Sam2TrackResponse> {
  const { trackId, label, nextSrcSns } = payload;
  const total = nextSrcSns.length;
  const accumulated: Sam2TrackedItem[] = [];

  if (total === 0) {
    return { tracked: [] };
  }

  // 50개 이하 청크로 분할. slice(step) 는 음수/과대 인덱스가 발생하지 않아 안전(CWE-20).
  const chunks: number[][] = [];
  for (let i = 0; i < total; i += SAM2_TRACK_CHUNK_SIZE) {
    chunks.push(nextSrcSns.slice(i, i + SAM2_TRACK_CHUNK_SIZE));
  }

  let curStartSrcSn = startSrcSn;
  let curPrevPolygon = payload.prevPolygon;

  for (let c = 0; c < chunks.length; c += 1) {
    const chunk = chunks[c];
    let res: Sam2TrackResponse;
    try {
      res = await requestSam2Track(
        curStartSrcSn,
        {
          trackId,
          prevPolygon: curPrevPolygon,
          label,
          nextSrcSns: chunk,
        },
        portalMode,
      );
    } catch (err) {
      // 부분 실패: 지금까지 성공한 청크 결과를 보존해 에러로 표면화(전부 롤백하지 않음).
      throw new Sam2TrackChunkError(err, accumulated, c, chunks.length);
    }

    // 불변성 유지 — 새 배열로 누적하지 않고 push 는 로컬 누적기에만 적용(외부 인자 미변경).
    accumulated.push(...res.tracked);
    onProgress?.(accumulated.length, total);

    const isLastChunk = c === chunks.length - 1;
    if (!isLastChunk) {
      const last = res.tracked[res.tracked.length - 1];
      if (!last) {
        // 다음 청크로 이어갈 폴리곤이 없음 — 이어붙이기 불가로 부분 실패 처리.
        throw new Sam2TrackChunkError(
          new Error('빈 추적 응답으로 다음 청크를 이어갈 수 없습니다'),
          accumulated,
          c + 1,
          chunks.length,
        );
      }
      curStartSrcSn = last.srcSn;
      curPrevPolygon = last.points;
    }
  }

  return { tracked: accumulated };
}

/**
 * SAM2 클릭/박스 분할 요청 페이로드.
 * points 또는 box 중 정확히 하나만 제공 (BE 가 배타 검증 — 400).
 */
export interface Sam2SegmentRequest {
  srcSn: number;
  /** 클릭 좌표 [[x, y], ...] (image px). 박스 미사용 시. */
  points?: number[][];
  /** 드래그 박스 [x1, y1, x2, y2] (image px). 포인트 미사용 시. */
  box?: [number, number, number, number];
}

export interface Sam2SegmentResponse {
  /** 폐곡선 폴리곤 [[x, y], ...] (image px). */
  polygon: number[][];
  /** 신뢰도 0.0 ~ 1.0. */
  score: number;
  /** ai-server mock 응답(모델 미로드/AI_MOCK_MODE) 여부 — true 면 FE 가 경고 + 자동 적용 차단. */
  mock: boolean;
}

/**
 * SAM2 클릭/박스 분할 요청.
 * BE: POST /frames/{srcSn}/sam2-segment
 *
 * 보안: srcSn/points/box 입력 검증·IDOR·좌표 상한은 BE 책임.
 */
export function requestSam2Segment(
  srcSn: number,
  payload: Omit<Sam2SegmentRequest, 'srcSn'>,
  // Phase 9 — portalMode=true 면 포털 전용 /portal/frames/{id}/sam2-segment 로 분기(persist 없이 좌표만).
  // 내부 /frames/{id}/sam2-segment 는 PORTAL 채널 403 이므로 포털에서 호출 금지.
  portalMode = false,
): Promise<Sam2SegmentResponse> {
  const base = portalMode ? '/portal/frames' : '/frames';
  return apiClient
    .post<Sam2SegmentResponse>(`${base}/${srcSn}/sam2-segment`, { srcSn, ...payload })
    .then((r) => r.data);
}

/** YOLO 오토라벨 수동 트리거로 저장된 라벨 요약. */
export interface AutolabelItem {
  lblSn: number;
  /** LS_LABEL FK — 미매칭 시 null. */
  labelId: number | null;
  label: string;
  /** BBOX 평탄 좌표 [x1, y1, x2, y2] (image px). */
  points: number[];
  /** 신뢰도 0.0 ~ 1.0 (null 가능). */
  score: number | null;
  /** 트래커 객체 ID — 단일 프레임 트리거이므로 연속성 미보장(null 가능). */
  trackId: number | null;
}

export interface AutolabelResponse {
  srcSn: number;
  /** 저장된 BBOX 라벨 개수 (mock 응답이면 0). */
  savedCount: number;
  /** ai-server mock 응답 여부 — true 면 저장하지 않으며 FE 는 자동 적용을 차단해야 한다. */
  mock: boolean;
  labels: AutolabelItem[];
}

/**
 * YOLO 오토라벨 수동 실행 요청.
 * BE: POST /frames/{srcSn}/autolabel
 *
 * 보안: srcSn 은 path 파라미터(axios 자동 인코딩). IDOR·작업락·좌표검증·포털 차단은 BE 책임(ADR-013).
 */
export function requestAutolabel(srcSn: number): Promise<AutolabelResponse> {
  return apiClient.post<AutolabelResponse>(`/frames/${srcSn}/autolabel`).then((r) => r.data);
}

/** BE TrackMergeResponse 와 1:1. */
export interface TrackMergeResponse {
  rawSn: number;
  fromTrackId: string;
  toTrackId: string;
  reassignedLabelCount: number;
  /** 재보간이 실제 자동 트랙에 적용됐는지(수동/SEGMENT/SKELETON 이면 false). */
  interpolationApplied: boolean;
  interpolatedRowCount: number;
}

/**
 * 트랙 병합/이름변경(Phase 4).
 * BE: POST /v1/videos/{rawSn}/tracks/merge  — body: { fromTrackId, toTrackId }
 *
 * <p>트랙 번호 변경(rename)은 미사용 번호로의 병합과 동일하므로 이 엔드포인트를 재사용한다.
 * BE 가 원 키프레임 trackId 재지정 + 재보간 + APPROVED 통지를 원자적으로 처리한다.
 *
 * 보안: rawSn 은 path(axios 자동 인코딩), from/to 는 body. IDOR·배타 락·겹침(409)·포털 차단은 BE 책임.
 */
export function mergeTracks(
  rawSn: number,
  fromTrackId: string,
  toTrackId: string,
): Promise<TrackMergeResponse> {
  return apiClient
    .post<TrackMergeResponse>(`/videos/${rawSn}/tracks/merge`, { fromTrackId, toTrackId })
    .then((r) => r.data);
}

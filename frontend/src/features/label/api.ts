// 라벨 도메인 API — BE: /api/v1/frames/{srcSn}/labels
//
// 라벨 저장(PUT)은 작업본 임시저장만 수행한다. 버전 스냅샷은 검수 승인(APPROVED) 시점에 BE 가
// 생성하므로 FE 에서 별도 커밋 호출은 하지 않는다(수동 commit 엔드포인트 폐기).
//
// 보안: 사용자 입력은 path/body 파라미터로만 전달 (axios 자동 URL 인코딩, XSS 방지).
// IDOR/Mass Assignment 방어는 BE 책임.

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

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

  // classId — 캔버스 편집/드롭다운/속성정의 조회의 라벨 마스터 식별자.
  // BE LabelResponse.Item 은 로드 응답에 classId/classCd 를 내려주지 않고 labelId(LS_LABEL.LABEL_ID)만
  // 제공한다. classId 폴백이 없으면 로드된 라벨이 항상 classId=0 이 되어 ① 속성 섹션(classId>0 게이트)이
  // 통째로 숨겨지고 ② 라벨 드롭다운(value=classId)이 엉뚱한 값으로 표시된다. labelId 로 폴백해
  // 두 소비처를 동시에 정상화한다(labelId 는 위에서 이미 null 정규화됨 — null 이면 0 유지).
  return {
    id,
    serverId: typeof raw?.id === 'number' ? raw.id : undefined,
    frameNo: Number(raw?.frameNo ?? 0),
    classId: Number(raw?.classId ?? raw?.classCd ?? labelId ?? 0),
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
      // labelVersion(C-ISSUE-21 / H-ISSUE-41) — 저장 PUT 에 되돌려 보낼 낙관적 동시성 토큰.
      //   BE 는 값이 오면 stale 여부를 검증(409)하고 없으면 검사를 skip 한다. 따라서 이 매핑이
      //   빠지면 동시 편집 보호가 통째로 꺼진 채 full-replace 저장이 나가, 내 화면에 없던 남의
      //   라벨이 조용히 삭제된다.
      //   0 은 유효한 최초 버전이라 falsy 판정으로 버리면 안 되고, 반대로 음수·NaN·문자열 같은
      //   이상값이 캐시에 박히면 이후 저장이 영구 409 루프가 되므로 null(=검사 skip)로 낙인다.
      const rawVersion: unknown = (d as LabelsResponse).labelVersion;
      const labelVersion: number | null =
        typeof rawVersion === 'number' && Number.isInteger(rawVersion) && rawVersion >= 0
          ? rawVersion
          : null;
      return {
        frameNo: d.frameNo ?? 0,
        srcSn,
        videoId: d.videoId !== undefined && d.videoId !== null ? Number(d.videoId) : undefined,
        frameImageType,
        lockSttsCd,
        labelVersion,
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

  const base = { id, lblTypeCd, labelId: lbl.labelId ?? null, label: lbl.className, points };

  // R9 — 온라인 오토라벨(AI 탐지/추적) 출처 보존.
  // 신규 삽입(id === null, 즉 BE INSERT 경로)이고 source 가 오토(≠MANUAL)일 때만 provenance 를 전송한다.
  // 기존 저장 라벨(serverId → id !== null)은 BE 가 UPDATE 로 AUTO_LBL_YN 을 유지하므로 미전송,
  // 수동(MANUAL) 라벨도 미전송(BE 가 AUTO_LBL_YN='N' 수동 저장). 민감 필드는 담지 않는다(Mass Assignment).
  if (id === null && lbl.source && lbl.source !== 'MANUAL') {
    return {
      ...base,
      source: lbl.source,
      ...(lbl.confidence != null ? { confScore: lbl.confidence } : {}),
      algorithm: lbl.source === 'AUTO_SAM2' ? 'SAM2' : 'YOLO',
    };
  }
  return base;
}

/**
 * 프레임 라벨 일괄 저장 (전체 교체).
 * BE: PUT /frames/{srcSn}/labels  — body: { items: LabelItemDto[], labelVersion?: number }
 *
 * labelVersion(C-ISSUE-21): 조회 응답이 준 라벨셋 버전을 그대로 되돌려 보낸다. 그사이 다른 사용자가
 * 같은 프레임을 저장했으면 BE 가 409(CONFLICT)로 거부한다 — 저장이 full-replace 계약이라 버전을
 * 보내지 않으면 내 화면에 없던 남의 라벨이 조용히 삭제된다(실측된 lost update). 값이 없으면
 * (undefined/null) 필드를 생략해 BE 의 하위호환 경로(검사 skip)를 그대로 탄다.
 */
export function putLabels(
  srcSn: number,
  labels: Label[],
  labelVersion?: number | null,
): Promise<LabelsResponse> {
  return apiClient
    .put<LabelsResponse>(`/frames/${srcSn}/labels`, {
      items: labels.map(serializeLabel),
      ...(labelVersion != null ? { labelVersion } : {}),
    })
    .then((r) => r.data);
}

/**
 * 라벨 변경종류 — BE LabelChangeKind 와 1:1.
 * V114 이후 LS_DATA_LBL_HSTRY.CHG_KIND_CD 컬럼은 제거되었고, 이 값은
 * 저장 이벤트 diff 페이로드(LS_DATA_LBL_HSTRY.CHG_DTL_CN) 내 각 변경 항목의
 * changeKind 로만 존재한다.
 */
export type LabelChangeKind = 'ADDED' | 'UPDATED' | 'DELETED';

/**
 * 라벨 스냅샷(변경 전/후 값) — BE LabelSnapshotView 와 1:1.
 * - lblTypeCd : 라벨 형태 코드(BBOX/POLYGON/SEGMENT/SKELETON …). 누락 시 null.
 * - labelId   : LS_LABEL.LABEL_ID(라벨 마스터 FK). 미매칭 시 null.
 * - labelNm   : 라벨명. 삭제/누락 시 null.
 * - pointCn   : 좌표 원문(JSON 문자열). 표시 단에서 요약한다.
 */
export interface LabelSnapshotView {
  lblTypeCd: string | null;
  labelId: number | null;
  labelNm: string | null;
  pointCn: string | null;
}

/**
 * 라벨 단위 변경 항목 — BE LabelChangeView 와 1:1.
 * - ADDED   : before=null, after=값
 * - DELETED : before=값, after=null
 * - UPDATED : before·after 모두 값(무변경 필드 포함)
 * 무변경 라벨은 이 배열에 포함되지 않는다.
 */
export interface LabelChangeView {
  /**
   * 대상 라벨 LS_DATA_LBL.LBL_SN — BE LabelChange 계약상 삭제(DELETED) 후에도 원 PK 보존.
   * (타입은 방어적으로 null 허용하나, BE 는 삭제 경로에서도 lblSn 을 채워 내려준다.)
   */
  lblSn: number | null;
  changeKind: LabelChangeKind;
  /** 라벨명(생존/삭제 공통 라벨 식별). 누락 시 null → 표시 단에서 폴백. */
  labelName: string | null;
  before: LabelSnapshotView | null;
  after: LabelSnapshotView | null;
}

/**
 * 저장 이벤트 단위 라벨 변경 이력 항목 (Phase 2 BE LabelHistoryResponse 와 1:1).
 * 하나의 저장(PUT) 이벤트에서 발생한 라벨 추가/수정/삭제를 묶어 표현한다.
 * - lblHstrySn : 이벤트 대표 PK (2차 정렬 tiebreaker)
 * - srcSn      : 프레임 PK (LS_DATA_SRC.SRC_SN)
 * - regDt      : 저장 일시 (ISO-8601)
 * - actor      : 작업자 식별자(사번, REG_ID). 시스템 경로는 null
 * - actorName  : 작업자 표시명(LS_ACNT_USER.USER_NM). BE 이름 해석 실패 시 null →
 *                화면은 `resolveDisplayName(actorName, actor)` 로 사번 폴백한다(빈칸 금지)
 * - addCnt/mdfcnCnt/delCnt : 이벤트 내 추가/수정/삭제 라벨 수 요약
 * - changes    : 라벨 단위 변경 상세(무변경 라벨은 제외)
 */
export interface LabelHistoryItem {
  lblHstrySn: number;
  srcSn: number;
  regDt: string;
  actor: string | null;
  actorName: string | null;
  addCnt: number;
  mdfcnCnt: number;
  delCnt: number;
  changes: LabelChangeView[];
}

/** 변경종류 화이트리스트 정규화 — 알 수 없는 값은 UPDATED 로 폴백(방어). */
function normalizeChangeKind(raw: unknown): LabelChangeKind {
  return raw === 'ADDED' || raw === 'DELETED' ? raw : 'UPDATED';
}

/** 0 이상 정수 카운트 정규화 — 누락/음수/비수치는 0. */
function normalizeCount(raw: unknown): number {
  const n = Number(raw ?? 0);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
}

/** 스냅샷 정규화 — 객체가 아니면 null, 필드 누락/타입 방어. */
function normalizeSnapshot(raw: unknown): LabelSnapshotView | null {
  if (raw === null || raw === undefined || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  const labelId = r.labelId;
  return {
    lblTypeCd: typeof r.lblTypeCd === 'string' && r.lblTypeCd.length > 0 ? r.lblTypeCd : null,
    labelId:
      labelId === null || labelId === undefined || labelId === '' || Number.isNaN(Number(labelId))
        ? null
        : Number(labelId),
    labelNm: typeof r.labelNm === 'string' && r.labelNm.length > 0 ? r.labelNm : null,
    pointCn: typeof r.pointCn === 'string' && r.pointCn.length > 0 ? r.pointCn : null,
  };
}

/** 라벨 단위 변경 항목 정규화 — 필드 누락/타입 방어. */
function normalizeChangeView(raw: unknown): LabelChangeView {
  const r = (raw ?? {}) as Record<string, unknown>;
  const lblSn = r.lblSn;
  return {
    lblSn: lblSn === null || lblSn === undefined ? null : Number(lblSn),
    changeKind: normalizeChangeKind(r.changeKind),
    labelName: typeof r.labelName === 'string' && r.labelName.length > 0 ? r.labelName : null,
    before: normalizeSnapshot(r.before),
    after: normalizeSnapshot(r.after),
  };
}

/** BE 이력 응답 정규화 — 필드 누락/타입/배열 방어. */
function normalizeHistoryItem(raw: unknown): LabelHistoryItem {
  const r = (raw ?? {}) as Record<string, unknown>;
  const changes = Array.isArray(r.changes) ? r.changes.map(normalizeChangeView) : [];
  return {
    lblHstrySn: Number(r.lblHstrySn ?? 0),
    srcSn: Number(r.srcSn ?? 0),
    regDt: typeof r.regDt === 'string' ? r.regDt : '',
    actor: typeof r.actor === 'string' && r.actor.length > 0 ? r.actor : null,
    actorName: typeof r.actorName === 'string' && r.actorName.length > 0 ? r.actorName : null,
    addCnt: normalizeCount(r.addCnt),
    mdfcnCnt: normalizeCount(r.mdfcnCnt),
    delCnt: normalizeCount(r.delCnt),
    changes,
  };
}

/**
 * 프레임(srcSn) 라벨 변경 이력 조회 (최신순 페이징).
 * BE: GET /api/v1/frames/{srcSn}/label-history?page=&size=
 *   → ApiResponse<Page<LabelHistoryResponse>> (서버 고정 정렬: REG_DT DESC, LBL_HSTRY_SN DESC)
 *
 * 보안:
 *  - srcSn 은 path(axios 자동 인코딩), page/size 는 query 파라미터.
 *  - size 상한(100)은 BE 가 클램프하나 FE 도 기본 20 으로 합리적 상한을 둔다(CWE-770).
 *  - IDOR/인가(WORKER 본인 배정 프레임)는 BE 책임.
 */
export function getLabelHistory(
  srcSn: number,
  page = 0,
  size = 20,
): Promise<PageResponse<LabelHistoryItem>> {
  return apiClient
    .get<PageResponse<LabelHistoryItem>>(`/frames/${srcSn}/label-history`, {
      params: { page, size },
    })
    .then((r) => {
      const d = (r.data ?? {}) as Partial<PageResponse<LabelHistoryItem>>;
      const content = Array.isArray(d.content) ? d.content.map(normalizeHistoryItem) : [];
      return {
        content,
        totalElements: Number(d.totalElements ?? content.length),
        totalPages: Number(d.totalPages ?? (content.length > 0 ? 1 : 0)),
        number: Number(d.number ?? page),
        size: Number(d.size ?? size),
      };
    });
}

/**
 * 라벨 변경 이력 스냅샷(before/after) → FE Label 복원 — "이 저장 되돌리기" 역적용용.
 *
 * pointCn(좌표 JSON 문자열)을 파싱해 {@link normalizeLabel} 로 shape(BBOX/POLYGON/SKELETON)를
 * 복원한다. 좌표를 되살릴 수 없는 경우는 null 을 반환해 호출측(revertSaveEvent)이 안전하게 스킵한다:
 *  - SEGMENT(MASK): 좌표(pointCn)만으로 마스크 복원 불가 → null
 *  - pointCn 누락/빈값 또는 JSON 파싱 실패 → null
 *
 * 복원 라벨의 source 는 알 수 없으므로 normalizeLabel 기본(MANUAL)로 둔다(신규 저장 시 수동 취급).
 * 순수 함수 — 네트워크 호출 없음(스토어/테스트에서 그대로 사용 가능).
 */
export function snapshotToLabel(snap: LabelSnapshotView | null, frameNo: number): Label | null {
  if (!snap) return null;
  const lblTypeCd = snap.lblTypeCd;
  // SEGMENT(MASK)는 좌표로 복원 불가 — 스킵 신호.
  if (lblTypeCd === 'SEGMENT' || lblTypeCd === 'MASK') return null;
  if (!snap.pointCn) return null;
  let points: unknown;
  try {
    points = JSON.parse(snap.pointCn);
  } catch {
    return null;
  }
  if (!Array.isArray(points)) return null;
  return normalizeLabel({
    id: null,
    frameNo,
    lblTypeCd: lblTypeCd ?? 'BBOX',
    labelId: snap.labelId,
    label: snap.labelNm ?? '',
    points,
  });
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
 * 비식별 누락 신고 — <b>마킹 단계</b>(영상 단위).
 *
 * BE: POST /v1/videos/{rawSn}/deident-report
 *   - Body: { reason: string (1~1000자) }
 *   - 응답: 201 { success: true, data: <reportId> }
 *   - 에러: 400(검증 실패), 403(본인 배정 아님), 404(영상 없음), 409(이미 재비식별 진행 중),
 *           412(파생영상 / 마킹 단계가 아님)
 *
 * 라벨링 단계(프레임 단위) 신고와 <b>진입점만</b> 다르고 부수효과는 동일하다. 두 신고는 저장 시
 * 서로 다른 "신고 단계"로 기록되어, 재처리 완료 후 <b>다시 시작하는 지점</b>이 갈린다
 * (마킹 단계 → 마킹부터 / 라벨링 단계 → 프레임 이미지만 다시 만들고 라벨링 계속).
 *
 * 보안: rawSn 은 number(axios path 자동 인코딩), 권한/IDOR 검증은 BE 책임.
 */
export function reportDeidentMissByVideo(rawSn: number, reason: string): Promise<number> {
  return apiClient
    .post<number>(`/videos/${rawSn}/deident-report`, { reason })
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
  /** (Phase 4) 추적 결과 형태 'BBOX'|'POLYGON'. 미지정이면 BE 기본 POLYGON. */
  shape?: DetectShapeType;
}

/** BE Sam2TrackResponseDto.TrackedItem 와 1:1. */
export interface Sam2TrackedItem {
  srcSn: number;
  trackId: string;
  label: string;
  points: number[][];
  score: number;
  /** (Phase 4) 추적 결과 형태 — 미지정이면 POLYGON 로 간주. */
  shapeType?: DetectShapeType;
}

/**
 * 추적 item → FE Label 변환(작업본 병합용). points 는 [[x,y],...] 폴리곤(또는 박스 4점).
 * shapeType='BBOX' 면 외접 박스로, 그 외(기본)는 POLYGON 으로 정규화한다.
 *
 * @param labelId 라벨 마스터 PK. BE `TrackedItem` 은 라벨명(`label`)만 돌려주므로 호출측이
 *   `utils/labelMasterLookup.resolveLabelIdByName` 으로 해석해 넘긴다.
 *   ⚠ 생략하면 마스터 연결이 끊긴 채(labelId=null) 저장돼, 재조회 시 BE 가 `LS_LABEL` 을
 *   조인하지 못해 색·라벨명·속성 정의가 전부 깨진다(= "저장하면 색이 바뀐다").
 */
export function trackedItemToLabel(
  item: Sam2TrackedItem,
  frameNo: number,
  labelId?: number | null,
): Label {
  const isBbox = item.shapeType === 'BBOX';
  // BBOX 형태면 추적 폴리곤의 외접 박스([[minX,minY],[maxX,maxY]])로 환원한다.
  let points: number[][] = item.points;
  if (isBbox && Array.isArray(item.points) && item.points.length > 0) {
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const p of item.points) {
      const x = Number(p?.[0]) || 0;
      const y = Number(p?.[1]) || 0;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
    points = [
      [minX, minY],
      [maxX, maxY],
    ];
  }
  const raw = {
    id: null,
    frameNo,
    labelId: labelId ?? null,
    label: item.label,
    lblTypeCd: isBbox ? 'BBOX' : 'POLYGON',
    points,
    autoLblYn: 'Y',
    algorithm: 'SAM2',
    confScore: item.score,
    trackId: item.trackId,
  };
  return normalizeLabel(raw);
}

/** BE Sam2TrackResponseDto. */
export interface Sam2TrackResponse {
  /** 자동 적용 가능한 추적 결과. mock(모델 미로드) 프레임은 BE 가 제외하므로 여기 담기지 않는다. */
  tracked: Sam2TrackedItem[];
  /**
   * BE ApiResponse.message — mock(모델 미로드) 프레임이 제외됐을 때 "AI 모델 미로드 …"(전량) 또는
   * "일부 결과의 신뢰도를 보장할 수 없습니다."(부분) 안내가 실린다. SAM2 분할/오토라벨과 동일 규약이며
   * FE 는 이 message 로 경고를 표시한다(별도 mock 플래그 없음).
   */
  message?: string | null;
}

/**
 * SAM2 자동 추적 요청.
 * BE: POST /frames/{srcSn}/sam2-track  — body: { srcSn, trackId, prevPolygon, label, nextSrcSns }
 *
 * <p>내부(INTERNAL) 채널 전용이다 — SAM2 는 SFR-08-01(VOS) 핵심 기능이고 포털(외부 채널)에는
 * 제공하지 않는다(ADR-013). 구 포털 전용 경로 `/portal/frames/{id}/sam2-track` 는 서버에서
 * 제거됐으므로 채널 분기를 두지 않는다.
 *
 * 보안: srcSn/prevPolygon/nextSrcSns 입력 검증 + IDOR 방어는 BE 책임.
 */
export function requestSam2Track(
  srcSn: number,
  payload: Sam2TrackRequest,
): Promise<Sam2TrackResponse> {
  return apiClient
    .post<Sam2TrackResponse>(`/frames/${srcSn}/sam2-track`, { srcSn, ...payload })
    // message 보존: mock(모델 미로드) 안내를 FE 가 읽어 경고로 분기하기 위함(오토라벨과 동일).
    .then((r) => ({ ...r.data, message: r.message ?? null }));
}

/**
 * SAM2 Track 청크 크기 — BE Sam2TrackRequest `@Size(max = 50)` 안전상한(CWE-770 방어)과 정합.
 * 한 번의 요청에 실을 수 있는 nextSrcSns 최대 개수. 이 값을 넘기면 BE 가 400 을 반환하므로
 * FE 는 반드시 이 크기 이하로 분할해 순차 호출한다.
 */
export const SAM2_TRACK_CHUNK_SIZE = 50;

/**
 * 청크 체이닝 시드 보정 — BBOX 추적은 tracked.points 가 2점 외접박스([[minX,minY],[maxX,maxY]])로
 * 반환된다(BE 계약). 이 2점을 그대로 다음 청크의 prevPolygon 으로 이어붙이면 BE
 * `Sam2TrackRequest.prevPolygon @Size(min=3)` 검증에 걸려 50프레임 초과 추적의 2번째 청크가 400 이 된다.
 * 2점 박스를 외접박스 4모서리 폐곡선으로 확장해 반환한다. 3점 이상(폴리곤)은 그대로 통과(무영향).
 */
function toSeedPolygon(points: number[][]): number[][] {
  if (!Array.isArray(points) || points.length !== 2) return points;
  const [p0, p1] = points;
  const raw = [p0?.[0], p0?.[1], p1?.[0], p1?.[1]];
  // 좌표 결측/비유한(undefined·NaN) 감지 시 경고를 남긴다 — 폴백(0 치환)은 그대로 유지하되
  // 침묵 실패(비정상 좌표가 (0,0) 등으로 조용히 치환)를 가시화하기 위한 것.
  if (raw.some((v) => !Number.isFinite(Number(v)))) {
    console.warn('[label] toSeedPolygon: 외접박스 좌표 결측/비유한 감지, 0 폴백 적용', points);
  }
  const minX = Number(p0?.[0]) || 0;
  const minY = Number(p0?.[1]) || 0;
  const maxX = Number(p1?.[0]) || 0;
  const maxY = Number(p1?.[1]) || 0;
  return [
    [minX, minY],
    [maxX, minY],
    [maxX, maxY],
    [minX, maxY],
  ];
}

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
    super('AI 추적 일부 청크 실패', { cause });
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
): Promise<Sam2TrackResponse> {
  const { trackId, label, nextSrcSns, shape } = payload;
  const total = nextSrcSns.length;
  const accumulated: Sam2TrackedItem[] = [];

  if (total === 0) {
    return { tracked: [], message: null };
  }

  // 50개 이하 청크로 분할. slice(step) 는 음수/과대 인덱스가 발생하지 않아 안전(CWE-20).
  const chunks: number[][] = [];
  for (let i = 0; i < total; i += SAM2_TRACK_CHUNK_SIZE) {
    chunks.push(nextSrcSns.slice(i, i + SAM2_TRACK_CHUNK_SIZE));
  }

  let curStartSrcSn = startSrcSn;
  let curPrevPolygon = payload.prevPolygon;
  // mock(모델 미로드) 안내 — 청크 중 하나라도 mock 제외가 있었으면 첫 안내를 보존해 최종 반환한다.
  let mockMessage: string | null = null;

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
          // (R12) 추적 결과 형태(BBOX/POLYGON)를 모든 청크에 전파 — 누락 시 BE 기본(POLYGON) 고정.
          ...(shape ? { shape } : {}),
        },
      );
    } catch (err) {
      // 부분 실패: 지금까지 성공한 청크 결과를 보존해 에러로 표면화(전부 롤백하지 않음).
      throw new Sam2TrackChunkError(err, accumulated, c, chunks.length);
    }

    // mock 제외 안내는 첫 발생분을 보존(전량/부분 문구 모두 BE 가 결정).
    if (res.message && mockMessage === null) mockMessage = res.message;
    // 불변성 유지 — 새 배열로 누적하지 않고 push 는 로컬 누적기에만 적용(외부 인자 미변경).
    accumulated.push(...res.tracked);
    onProgress?.(accumulated.length, total);

    const isLastChunk = c === chunks.length - 1;
    if (!isLastChunk) {
      const last = res.tracked[res.tracked.length - 1];
      if (!last && mockMessage !== null) {
        // mock(모델 미로드) 로 이 청크 결과가 통째로 제외돼 이어붙일 시드가 없다. 일반 실패로
        // 오인시키지 않고 지금까지의 성공분 + mock 안내를 반환한다(자동 적용 차단은 유지).
        return { tracked: accumulated, message: mockMessage };
      }
      if (!last) {
        // 다음 청크로 이어갈 폴리곤이 없음 — 이어붙이기 불가로 부분 실패 처리.
        // BE 계약상 성공 응답의 tracked 는 요청 nextSrcSns 개수만큼 채워지므로 도달 불가하나,
        // 향후 계약 변경(빈 tracked 허용) 대비 방어. completedChunks 는 실패 catch 분기와
        // 동일하게 '결과를 낸 완료 청크 수(c)' 로 통일 — 빈 결과 청크는 완료로 세지 않아
        // 실패구간 안내가 실제 완료/실패 청크와 일치한다.
        throw new Sam2TrackChunkError(
          new Error('빈 추적 응답으로 다음 청크를 이어갈 수 없습니다'),
          accumulated,
          c,
          chunks.length,
        );
      }
      curStartSrcSn = last.srcSn;
      // BBOX 추적은 last.points 가 2점 외접박스라 다음 청크 prevPolygon(@Size(min=3))을 위반한다.
      // 4점 폐곡선으로 확장해 이어붙인다(폴리곤은 무영향).
      curPrevPolygon = toSeedPolygon(last.points);
    }
  }

  return { tracked: accumulated, message: mockMessage };
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
  /**
   * (Phase 2 FE) 경계 세밀함 — 폴리곤 단순화 tolerance 0~50(px). 미지정이면 body 에 미포함 →
   * BE 가 시스템 설정 기본값(POLYGON_SIMPLIFY_TOLERANCE)을 사용한다(무회귀).
   */
  simplifyTolerance?: number;
}

export interface Sam2SegmentResponse {
  /** 폐곡선 폴리곤 [[x, y], ...] (image px). mock(모델 미로드) 이면 빈 배열 → FE 자동 적용 차단 신호. */
  polygon: number[][];
  /** 신뢰도 0.0 ~ 1.0. */
  score: number;
  /**
   * BE ApiResponse.message — mock(모델 미로드) 시 "AI 모델 미로드 …" 안내가 실린다.
   * 빈 폴리곤과 함께 자동 적용 차단 + 경고 표시 신호로 사용(별도 mock 플래그 없음).
   */
  message?: string | null;
}

/**
 * SAM2 클릭/박스 분할 요청.
 * BE: POST /frames/{srcSn}/sam2-segment
 *
 * <p>내부(INTERNAL) 채널 전용이다 — 포털(외부 채널)에는 SAM2 를 제공하지 않으며(ADR-013) 구 포털
 * 전용 경로 `/portal/frames/{id}/sam2-segment` 는 서버에서 제거됐다. 채널 분기를 두지 않는다.
 *
 * 보안: srcSn/points/box 입력 검증·IDOR·좌표 상한은 BE 책임.
 */
export function requestSam2Segment(
  srcSn: number,
  payload: Omit<Sam2SegmentRequest, 'srcSn'>,
): Promise<Sam2SegmentResponse> {
  // body 를 명시 조립 — simplifyTolerance 는 숫자일 때만 포함(undefined 는 생략 → BE 기본값, 무회귀).
  const body: Record<string, unknown> = { srcSn };
  if (payload.points !== undefined) body.points = payload.points;
  if (payload.box !== undefined) body.box = payload.box;
  if (typeof payload.simplifyTolerance === 'number') body.simplifyTolerance = payload.simplifyTolerance;
  return apiClient
    .post<Sam2SegmentResponse>(`/frames/${srcSn}/sam2-segment`, body)
    // message 보존: 인터셉터가 unwrap 한 ApiResponse.message 를 data 에 병합해 FE 가 mock 안내를 읽을 수 있게 한다.
    .then((r) => ({ ...r.data, message: r.message ?? null }));
}

/** BE→FE 검출 형태 코드(오토라벨/추적 공통). */
export type DetectShapeType = 'BBOX' | 'POLYGON';

/** YOLO/SAM 오토라벨 수동 트리거 검출 요약(Phase 3 전환 — 미저장, lblSn 항상 null). */
export interface AutolabelItem {
  /** BE PK — Phase 3 전환으로 미저장이라 항상 null. */
  lblSn: number | null;
  /** LS_LABEL FK — 미매칭 시 null. */
  labelId: number | null;
  label: string;
  /** BBOX 평탄 좌표 [x1, y1, x2, y2] (image px). POLYGON 검출이면 생략 가능. */
  points?: number[];
  /** 신뢰도 0.0 ~ 1.0 (null 가능). */
  score: number | null;
  /** 트래커 객체 ID — 단일 프레임 트리거이므로 연속성 미보장(null 가능). */
  trackId: number | null;
  /** 검출 형태 — 미지정이면 BBOX 로 간주. */
  shapeType?: DetectShapeType;
  /** POLYGON 검출 좌표 [[x,y],...] (image px). shapeType='POLYGON' 일 때 사용. */
  polygon?: number[][];
}

export interface AutolabelResponse {
  srcSn: number;
  /** 검출 개수 (mock 응답이면 0). BE detectedCount 미러 — 저장하지 않으므로 savedCount 는 deprecated. */
  savedCount: number;
  /**
   * BE ApiResponse.message — 내부 mock(모델 미로드) 시 안내가 실린다.
   * message 가 있으면 FE 는 자동 적용을 차단하고 경고를 표시한다(정상 "0건 검출"과 구분).
   */
  message?: string | null;
  labels: AutolabelItem[];
}

/**
 * YOLO/SAM 오토라벨 수동 실행 요청.
 * BE: POST /frames/{srcSn}/autolabel  — body {classes?, shape?}
 *
 * @param classIds (Phase 4 — R3) 검출 대상 클래스(COCO 영문명) 화이트리스트. 미지정/빈 배열이면
 *                 classes 없이 호출(전체 검출, 하위호환). 지정 시 body {classes:[...]} 로 필터.
 * @param shape    (Phase 4 — R? B) 검출 형태 'BBOX'|'POLYGON'. 미지정이면 body 에 shape 미포함(BE 기본 BBOX).
 * @param opts     (Phase 2 FE) 조절된 정밀도 옵션. 각 값은 숫자일 때만 body 에 포함(미조절이면 생략 →
 *                 BE 가 시스템 설정 기본값 사용, 무회귀).
 *                 - confThreshold     : 인식 민감도 0.25~0.80
 *                 - simplifyTolerance : 경계 세밀함 0~50 (폴리곤 검출)
 *
 * 보안: srcSn 은 path 파라미터(axios 자동 인코딩). shape 는 화이트리스트('BBOX'|'POLYGON')만 전달.
 *       IDOR·작업락·좌표검증·포털 차단은 BE 책임(ADR-013). classes/범위 검증은 BE @Valid.
 */
export function requestAutolabel(
  srcSn: number,
  classIds?: string[],
  shape?: DetectShapeType,
  opts?: { confThreshold?: number; simplifyTolerance?: number },
): Promise<AutolabelResponse> {
  const body: {
    classes?: string[];
    shape?: DetectShapeType;
    confThreshold?: number;
    simplifyTolerance?: number;
  } = {};
  if (classIds && classIds.length > 0) body.classes = classIds;
  // 입력검증 — 화이트리스트 외 값은 무시(방어).
  if (shape === 'BBOX' || shape === 'POLYGON') body.shape = shape;
  // 정밀도 옵션 — 숫자일 때만 포함(미조절/NaN 은 생략 → BE 기본값).
  if (typeof opts?.confThreshold === 'number') body.confThreshold = opts.confThreshold;
  if (typeof opts?.simplifyTolerance === 'number') body.simplifyTolerance = opts.simplifyTolerance;
  // body 가 비면 인자 없이 호출 — 기존 호출 형태 유지(무회귀, api.test 정합).
  const post =
    Object.keys(body).length > 0
      ? apiClient.post<AutolabelResponse>(`/frames/${srcSn}/autolabel`, body)
      : apiClient.post<AutolabelResponse>(`/frames/${srcSn}/autolabel`);
  // message 보존: mock(모델 미로드) 안내를 FE 가 읽어 경고 토스트로 분기하기 위함.
  return post.then((r) => ({ ...r.data, message: r.message ?? null }));
}

/**
 * 오토라벨 검출 item → FE Label 변환(작업본 병합용). 미저장이므로 id='' → 병합 시 클라 id 부여.
 * shapeType/polygon 을 normalizeLabel 이 이해하는 raw 형태로 매핑한다.
 */
export function autolabelItemToLabel(item: AutolabelItem, frameNo: number): Label {
  const isPolygon = item.shapeType === 'POLYGON';
  const raw = {
    id: null, // 미저장 — 병합 액션이 클라 id 부여
    frameNo,
    labelId: item.labelId,
    label: item.label,
    lblTypeCd: isPolygon ? 'POLYGON' : 'BBOX',
    points: isPolygon ? item.polygon : item.points,
    autoLblYn: 'Y',
    algorithm: isPolygon ? 'SAM2' : undefined,
    confScore: item.score,
    trackId: item.trackId,
  };
  return normalizeLabel(raw);
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

/** BE TrackDeleteResponse 와 1:1 (R4 트랙 삭제). */
export interface TrackDeleteResponse {
  rawSn: number;
  trackId: string;
  fromFrameNo: number;
  /** 실제 삭제된 라벨 수(범위 밖이면 0). */
  deletedCount: number;
}

/**
 * 트랙 삭제 — 지정 프레임(fromFrameNo) 이후(포함) 프레임의 해당 트랙 라벨을 전부 삭제(R4).
 * BE: DELETE /v1/videos/{rawSn}/tracks/{trackId}?fromFrameNo={n}
 *
 * <p>fromFrameNo 는 현재 보고 있는 프레임 번호를 전달한다(현재 프레임 이후 궤적 삭제).
 * 보안: rawSn/trackId 는 path(axios 자동 인코딩), fromFrameNo 는 query. IDOR·배타 락·FK 고아 방지·
 * 포털 차단은 BE 책임(ADR-013).
 */
export function deleteTrack(
  rawSn: number,
  trackId: string,
  fromFrameNo: number,
): Promise<TrackDeleteResponse> {
  return apiClient
    .delete<TrackDeleteResponse>(`/videos/${rawSn}/tracks/${encodeURIComponent(trackId)}`, {
      params: { fromFrameNo },
    })
    .then((r) => r.data);
}

/** BE TrackSplitResponse 와 1:1 (R5 트랙 분할). */
export interface TrackSplitResponse {
  rawSn: number;
  originalTrackId: string;
  /** 분할로 새로 부여된 트랙 ID(영상 내 유니크 = max 정수 트랙ID + 1). */
  newTrackId: string;
  atFrameNo: number;
  /** 새 트랙으로 이동된 원 키프레임 라벨 수(경계 밖이면 0). */
  movedCount: number;
}

/**
 * 트랙 분할(split) — atFrameNo 이후(포함) 프레임의 트랙 키프레임을 새 트랙 ID 로 분리(R5).
 * BE: POST /v1/videos/{rawSn}/tracks/{trackId}/split  — body: { atFrameNo }
 *
 * <p>atFrameNo 는 현재 보고 있는 프레임 번호를 전달한다(현재 프레임 기준 분할). 좌표는 불변.
 * 보안: rawSn/trackId 는 path(axios 자동 인코딩), atFrameNo 는 body. IDOR·배타 락·유니크 채번·
 * 포털 차단은 BE 책임(ADR-013).
 */
export function splitTrack(
  rawSn: number,
  trackId: string,
  atFrameNo: number,
): Promise<TrackSplitResponse> {
  return apiClient
    .post<TrackSplitResponse>(`/videos/${rawSn}/tracks/${encodeURIComponent(trackId)}/split`, {
      atFrameNo,
    })
    .then((r) => r.data);
}

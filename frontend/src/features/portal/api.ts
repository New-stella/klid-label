// 포털 채널 API — BE: /api/v1/portal/*
// 보안: axios가 자동 URL 인코딩. IDOR 방어는 BE 책임 (PORTAL_USER 본인 데이터만 노출, CWE-639).
//
// BE 엔드포인트 (kr.co.cudo.authoring.portal.controller.PortalLabelController):
//   GET  /v1/portal/frames/{srcSn}/labels  → 프레임 단위 라벨 Load (datamart 원본 + 본인 user-label 병합)
//   GET  /v1/portal/frames/{srcSn}/image   → 프레임 비식별 이미지 바이너리 (PORTAL_USER 전용)
//   GET  /v1/portal/datamart/labels        → 데이터마트 원본 라벨 Load (rawSn)
//   GET  /v1/portal/datamart/videos/{rawSn}/download → 본인 작업 데이터 묶음 파일 다운로드
//   GET  /v1/portal/user-labels            → 본인 작업 라벨 조회 (rawSn)
//   POST /v1/portal/user-labels            → 본인 작업 라벨 저장 (원본 미수정 — LS_PORTAL_USER_LABEL)
//
// ADR-013: 포털은 데이터마트 영상 선택·간편 라벨링 전용. 오토라벨링·업로드(TUS)·검수·버전관리 미제공.

import { apiClient } from '@/lib/api/client';
import {
  parseContentDispositionFilename,
  toDownloadBlob,
  triggerBrowserDownload,
} from '@/lib/api/download';
import type { PageResponse } from '@/lib/api/types';
// 내부 라벨 api 의 normalizeLabel 과 동일 변환을 재사용 (중복 제거 — 단일 export).
import { normalizeLabel as normalizeLabelShared } from '@/features/label/api';
import type { LabelsResponse, SiblingFrame } from '@/features/label/types';

/**
 * Phase B — 포털 홈 데이터마트 영상 목록 1행 (BE DatamartVideoResponse 와 1:1).
 *
 * 데이터마트 노출(검수 완료=APPROVED) 영상만 포함. firstSrcSn 은 라벨링 진입
 * (/portal/label/{firstSrcSn}) 용 첫 프레임 SRC_SN — BE 가 프레임 0건 영상을 제외하므로 항상 존재.
 *
 * MED-3: lastUpdatedAt 은 LS_RAW_DATA_STATUS.UPD_DT(마지막 상태 변경 일시)이다. 정확한 승인 시각
 * 컬럼이 없어 'approvedAt' 으로 명명하면 재승인 전 상태 전이 시 오해를 일으키므로 의미에 맞춰 명명.
 */
export interface DatamartVideo {
  rawSn: number;
  title: string;
  eventName: string | null;
  frameCount: number;
  firstSrcSn: number;
  lastUpdatedAt: string | null;
  /**
   * 본인 저장 라벨의 보존기간 만료 예정 시각. 저장 라벨이 없으면 `null`.
   *
   * ★ 서버가 **조회 시점에 계산하는 파생값**이다(저장되지 않는다). 보존기간 설정이 바뀌면 다음
   * 조회부터 값이 달라지므로 화면이 따로 보관해 두고 쓰지 않는다 — 받은 값을 그대로 표시한다.
   */
  myLabelExpiresAt: string | null;
}

/**
 * Phase B — 데이터마트 영상 목록 조회.
 * BE: GET /v1/portal/datamart/videos?page=&size=  (PORTAL_USER 전용, APPROVED 게이트는 BE 책임)
 * 보안: page/size 는 axios params 로만 전달 — 문자열 직접 연결 금지.
 */
export function listDatamartVideos(params: { page?: number; size?: number } = {}) {
  return apiClient
    .get<PageResponse<DatamartVideo>>('/portal/datamart/videos', { params })
    .then((r) => r.data);
}

/**
 * 작업 데이터 다운로드 응답의 제한시간(ms). @design SCREEN-028, API-203
 *
 * ★ 공용 기본값(30초)을 그대로 쓰면 **구조적으로 끊긴다.** 이 응답은 비식별 영상이 포함되면
 *   GB 급이고 서버가 그래서 메모리에 담지 않고 스트리밍으로 내려보낸다
 *   (`PortalDatamartDownloadService` — "영상이 포함되면 응답이 GB 급"). 브라우저 XHR 의 timeout 은
 *   **응답이 끝날 때까지의 총 경과 시간**이라 전송 시간이 그대로 여기에 잡힌다. 즉 회선이 아무리
 *   좋아도 GB 급 전송은 30초 안에 끝나지 않고, 끊긴 시점엔 서버가 이미 전량을 읽어 흘려보낸 뒤라
 *   **재시도할수록 서버 부하만 늘어난다**(사용자에게는 그저 실패로 보인다).
 *
 * ★ 왜 30분인가 — 실효 대역 5Mbps(내부망 기준으로 넉넉히 보수적으로 잡은 값)에서 약 1.1GB 를
 *   받아낼 수 있는 시간이다. 요청량 제한(분당 3회)이 별도로 걸려 있어 이 상한을 크게 잡는 것이
 *   무제한 동시 다운로드로 번지지 않는다.
 *
 * ★ 왜 0(무제한)이 아닌가 — 무제한으로 두면 연결이 조용히 멈췄을 때 버튼이 '내려받는 중…' 에
 *   영구히 갇혀 새로고침 말고는 빠져나올 길이 없다. **유한한 상한이 그 상태를 끝내 주는 최후 장치**다.
 *   ⚠ 구 근거였던 *"이 화면에는 취소할 수단이 없다"* 는 **더 이상 사실이 아니다**(취소 조작이 생겼다).
 *   그렇다고 상한을 걷어내지 말 것 — 취소는 **사용자가 화면을 보고 있을 때만** 동작하는 수동 장치라,
 *   자리를 비운 사이 멈춘 전송을 끝내 주지는 못한다. 두 장치는 서로를 대체하지 않는다.
 */
export const DATAMART_DOWNLOAD_TIMEOUT_MS = 30 * 60 * 1000;

/**
 * 데이터마트 영상 1건의 **본인 작업 데이터** 묶음 파일 다운로드. @design SCREEN-028
 * BE: GET /v1/portal/datamart/videos/{rawSn}/download
 *
 * 보안: apiClient(blob) 경유 — Authorization 헤더가 자동 첨부된다. 이 응답은 인증을 요구하므로
 * `<a href>` 직링크는 401 이 되어 쓸 수 없다(형제 경로인 업로드 자산 다운로드와 동일 규약).
 * 파일명은 BE 가 서버 생성 고정명을 Content-Disposition 으로 내려주며 그 값을 신뢰한다.
 *
 * 실패 응답은 `ApiError` 로 올라온다 — 사유가 갈리므로(요청량 초과 / 비식별 재처리 / 저장 라벨 없음 /
 * 접근 권한) 호출측이 상태코드로 구분해 안내한다. **응답이 아예 오지 않는 실패**(전송 중단·네트워크
 * 단절·제한시간 초과)도 같은 자리에서 갈라 안내한다(`datamartDownloadErrorMessage`).
 *
 * @param signal 사용자 취소용 중단 신호(선택). 넘기면 전송이 실제로 중단된다 — 신호를 받아만 두고
 *   요청에 싣지 않으면 화면의 '취소' 는 버튼만 있고 GB 급 전송은 계속되는 거짓 조작이 된다.
 *   ⚠ 중단되면 응답이 없어 `ApiError(status 0)` 으로 올라오는데, 그 값만으로는 **사용자 취소와
 *   회선 단절이 구분되지 않는다**. 그 구분은 신호를 쥐고 있는 호출측(화면)이 한다.
 */
export function downloadDatamartVideoData(rawSn: number, signal?: AbortSignal): Promise<void> {
  return apiClient
    .get(`/portal/datamart/videos/${rawSn}/download`, {
      responseType: 'blob',
      transformResponse: (raw) => raw,
      // 공용 기본값(30초)을 덮어쓴다 — 위 상수 주석 참조. 값을 빼면 GB 급 전송이 30초에 끊긴다.
      timeout: DATAMART_DOWNLOAD_TIMEOUT_MS,
      signal,
    })
    .then((res) => {
      const fileName =
        parseContentDispositionFilename(
          (res.headers?.['content-disposition'] as string | undefined) ?? '',
        ) ?? `portal-video-${rawSn}.zip`;
      triggerBrowserDownload(toDownloadBlob(res.data as unknown), fileName);
    });
}

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

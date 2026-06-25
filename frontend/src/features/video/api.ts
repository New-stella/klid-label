// 영상 도메인 API — BE: /api/v1/videos, /videos/{id}

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  FrameLabels,
  RedeidentResult,
  ResolutionExportResult,
  ResolutionPreset,
  Video,
  VideoDetail,
  VideoListParams,
} from './types';

/**
 * 보안: axios가 자동 URL 인코딩 (XSS/Injection 방지).
 * 사용자 입력은 params로만 전달 — 문자열 직접 연결 금지.
 *
 * BE 응답이 신/구 필드 혼재 가능성을 가정해 normalize 단계를 둔다:
 *  - id      ← rawSn fallback
 *  - cctvName ← vmsCctvId fallback
 *  - eventTypeCd ← evntTypeCd fallback (camelCase 정정 이전 응답 호환)
 *  - capturedAt ← regDt fallback
 *  - frameCount ← 0 fallback
 *  - status ← dataSttsCd fallback ('PENDING' 최종 fallback)
 */
type RawVideo = Partial<Video> & {
  rawSn?: number;
  vmsCctvId?: string;
  evntTypeCd?: string;
  dataSttsCd?: string;
  regDt?: string;
  prvcTypeCd?: string;
  deIdntfYn?: string;
};

function normalizeVideo(v: RawVideo): Video {
  return {
    id: (v.id ?? v.rawSn) as number,
    cctvName: (v.cctvName ?? v.vmsCctvId ?? '') as string,
    vmsClipId: v.vmsClipId ?? '',
    eventName: v.eventName,
    eventTypeCd: v.eventTypeCd ?? v.evntTypeCd,
    localGov: v.localGov,
    frameCount: v.frameCount ?? 0,
    status: (v.status ?? v.dataSttsCd ?? 'PENDING') as Video['status'],
    capturedAt: (v.capturedAt ?? v.regDt ?? '') as string,
    thumbnailUrl: v.thumbnailUrl,
    privacyTypeCd: (v.privacyTypeCd ?? v.prvcTypeCd) as string | undefined,
    durationSec: v.durationSec,
    updatedAt: v.updatedAt ?? null,
    reviewCompletedAt: v.reviewCompletedAt ?? null,
    // BE 실제 응답 키는 deIdntfYn (deIdentYn 아님). SC-009 재비식별 버튼 노출 조건.
    // BE 가 코드값('Y'|'N'|'F')만 내려주므로 리터럴 유니온으로 캐스팅.
    deIdntfYn: v.deIdntfYn as Video['deIdntfYn'],
    // LABELER 배정 정보 (BE VideoSummaryResponse) — 미배정 영상은 모두 undefined.
    // TaskListPage 정합: 배정/재배정 버튼 분기 + 재배정 모달 사전선택에 사용된다.
    assignmentId: v.assignmentId,
    workerId: v.workerId,
    workerName: v.workerName,
    assignedAt: v.assignedAt,
    assignStatus: v.assignStatus,
  };
}

export function listVideos(params: VideoListParams) {
  return apiClient
    .get<PageResponse<RawVideo>>('/videos', { params })
    .then((r) => ({
      ...r.data,
      content: (r.data.content ?? []).map(normalizeVideo),
    }));
}

export function getVideo(id: number) {
  return apiClient
    .get<
      RawVideo &
        Partial<VideoDetail> & {
          durationSec?: number;
          regDt?: string;
          updDt?: string;
        }
    >(`/videos/${id}`)
    .then((r) => {
      const base = normalizeVideo(r.data);
      const d = r.data;
      return {
        ...base,
        // deIdntfYn(SC-009 재비식별 노출 조건)은 normalizeVideo 가 매핑하므로 별도 스프레드 제거.
        duration: d.duration ?? d.durationSec ?? 0,
        fileSizeMb: d.fileSizeMb ?? 0,
        resolution: d.resolution ?? '',
        framePreviews: (d.framePreviews ?? []).map((fp) => ({
          srcSn: fp.srcSn,
          frameNo: fp.frameNo,
          thumbnailUrl: fp.thumbnailUrl,
        })),
        stages: d.stages ?? [],
        createdAt:
          d.createdAt ??
          ((d as Record<string, unknown>)['regDt'] as string | undefined),
        updatedAt:
          d.updatedAt ??
          ((d as Record<string, unknown>)['updDt'] as string | undefined),
      } as VideoDetail;
    });
}

/**
 * 영상 스트림 단기 서명 URL 발급 — BE: GET /api/v1/videos/{rawSn}/stream-url.
 *
 * <p><video> 엘리먼트는 Authorization 헤더를 못 붙여 인증 스트림(/stream)을 직접 재생하지 못한다.
 * 따라서 인증된 axios 호출로 짧은 TTL HMAC 서명 URL 을 받아 <video src> 로 사용한다.
 * 반환 url 은 BE 가 만든 절대 경로(`/api/v1/videos/{rawSn}/stream?exp=...&sig=...`)다.
 *
 * 보안: rawSn 은 숫자 path 파라미터로만 전달 — 문자열 직접 연결/사용자 입력 삽입 없음.
 */
export interface StreamUrl {
  url: string;
  expiresAt: number;
  ttlSeconds: number;
}

export function getStreamUrl(rawSn: number) {
  return apiClient
    .get<StreamUrl>(`/videos/${rawSn}/stream-url`)
    .then((r) => r.data);
}

export function getVideoLabels(videoId: number | string) {
  return apiClient
    .get<FrameLabels>(`/videos/${videoId}/labels/auto`)
    .then((r) => r.data);
}

/**
 * 해상도 export (SFR-06-03) — 검수 완료 원본의 프레임 이미지셋을 표준 하위 해상도로 다운스케일.
 * BE: POST /api/v1/videos/{rawSn}/resolution (REVIEWER).
 *
 * 보안: preset 은 화이트리스트 타입(ResolutionPreset)으로 강제 — 자유 해상도 입력 차단.
 * 업스케일/증강본/미검수/중복은 BE 가 400/409 로 거부 → ApiError 로 전파.
 */
export function changeResolution(rawSn: number, preset: ResolutionPreset) {
  return apiClient
    .post<ResolutionExportResult>(`/videos/${rawSn}/resolution`, { preset })
    .then((r) => r.data);
}

/**
 * 영상 재비식별 요청 (SC-009) — BE: POST /api/v1/videos/{rawSn}/redeident (REVIEWER).
 *
 * <p>검수완료(APPROVED)됐으나 비식별 미완인 영상을 다시 비식별 처리한다.
 * BE 가 비동기로 접수 → 200/202 + status='ACCEPTED'. 라벨·검수상태는 보존.
 *
 * 보안: rawSn 은 숫자 path 파라미터로만 전달 — 문자열 직접 연결/사용자 입력 삽입 없음.
 * 권한(REVIEWER)·상태(APPROVED·미비식별)·작업락은 BE 가 403/404/409 로 강제한다.
 */
export function requestRedeident(rawSn: number) {
  return apiClient
    .post<RedeidentResult>(`/videos/${rawSn}/redeident`)
    .then((r) => r.data);
}

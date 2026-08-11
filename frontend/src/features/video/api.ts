// 영상 도메인 API — BE: /api/v1/videos, /videos/{id}

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  FrameLabels,
  RedeidentResult,
  ResolutionChangeResult,
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
 *  - capturedAt = 촬영 시각(BE SHT_DT). **regDt(수신 시각) 폴백 없음** — 화면 컬럼('녹화일')·
 *    정렬 키(capturedAt→shtDt)·기간 필터가 모두 촬영 시각 축인데 표시값만 수신 시각이던 드리프트를
 *    정정했다. BE 가 null 을 주면 '' 로 두고 화면이 '-' 를 그린다(수신 시각으로 몰래 채우지 않는다).
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
  deidentStatus?: string;
  reviewSttsCd?: string;
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
    capturedAt: (v.capturedAt ?? '') as string,
    thumbnailUrl: v.thumbnailUrl,
    privacyTypeCd: (v.privacyTypeCd ?? v.prvcTypeCd) as string | undefined,
    durationSec: v.durationSec,
    updatedAt: v.updatedAt ?? null,
    reviewCompletedAt: v.reviewCompletedAt ?? null,
    // BE 실제 응답 키는 deIdntfYn (deIdentYn 아님). SC-009 재비식별 버튼 노출 조건.
    // BE 가 코드값('Y'|'N'|'F')만 내려주므로 리터럴 유니온으로 캐스팅.
    deIdntfYn: v.deIdntfYn as Video['deIdntfYn'],
    // 비식별 처리 상태(Phase 2 응답) — 목록 배지 우선표시 + 마킹 진입 차단 판정.
    deidentStatus: v.deidentStatus as Video['deidentStatus'],
    // 검수 상태(LS_RAW_DATA_STATUS.DATA_STTS_CD) — SC-009 재비식별 버튼 노출 판정 필드.
    // status(배치단계)와 별개. 상태 row 없으면 BE 가 null → undefined.
    reviewSttsCd: v.reviewSttsCd ?? undefined,
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
        // 파생영상 여부 — BE 가 boolean 으로만 내려준다(원본 rawSn 은 내려주지 않는다: 원본을 신고해도
        // 파생본은 달라지지 않아 유도 자체가 잘못된 안내). 구 응답은 undefined 로 남긴다.
        derivative: d.derivative === true,
        // P2b — 한번이라도 검수 완료된 적이 있는가. reviewSttsCd(현재 상태)와 다른 축이라 재검수
        //   재제출로 상태가 내려간 구간에도 true 다. 구 응답(필드 부재)은 false 로 떨어지고 판정이
        //   현재 상태로 폴백한다(fail-closed 는 판정 쪽이 담당).
        everApproved: d.everApproved === true,
        // 비식별 이력 — BE 가 최신순으로 내려준다(정렬을 FE 에서 다시 유도하지 않는다).
        //   값을 못 내리는 구 응답은 빈 배열로 정규화해 화면 분기를 하나로 유지한다.
        deidentHistory: d.deidentHistory ?? [],
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
 * 해상도 변경(SFR-06-03) — 검수 완료 원본에서 목표 해상도별 새 파생영상(RAW_SN)을 만들어
 * 검수 파이프라인(PENDING)에 넣는다. 구 "export 프레임셋" 의미 폐기.
 * BE: POST /api/v1/videos/{rawSn}/resolution (REVIEWER).
 *
 * <p>presets 는 선택적이다. 미지정/빈 목록이면 BE 가 표준 3종(RESL_1080P/RESL_720P/RESL_480P)
 * 전체를 생성(원본과 동일 해상도만 스킵)한다. 지정 시 그 목록만 생성한다.
 *
 * 상태코드: 1건 이상 CREATED → 201, 전부 FAILED → 500, 적용 프리셋 0(전부 스킵) → 400.
 *
 * 보안: presets 는 화이트리스트 타입(ResolutionPreset[])으로 강제 — 자유 해상도 입력 차단.
 * rawSn 은 숫자 path 파라미터로만 전달. 업스케일/증강본/미검수는 BE 가 400/409 로 거부.
 */
export function changeResolution(rawSn: number, presets?: ResolutionPreset[]) {
  const body = presets && presets.length > 0 ? { presets } : {};
  return apiClient
    .post<ResolutionChangeResult>(`/videos/${rawSn}/resolution`, body)
    .then((r) => r.data);
}

/**
 * 해상도 파생영상 **확정 상태 조회** — BE: GET /api/v1/videos/{rawSn}/resolution (REVIEWER).
 *
 * <p>생성(POST)의 201 `CREATED` 는 "예약 성공"만 뜻하고 실제 확정(파일 산출·라벨 복사)은 비동기다.
 * 그래서 확정 실패가 어느 화면에도 보이지 않았다 — 파생 RAW 는 영상 목록에서 제외되고, 실패 시
 * 예약 행도 삭제되어 증강 이력에도 남지 않는다. 이 조회는 파생 RAW 자체를 원천으로 확정 결과
 * (COMPLETED/IN_PROGRESS/FAILED)를 돌려준다.
 *
 * <p>응답 스키마는 생성 API 와 동일한 {@link ResolutionChangeResult} 다(BE 가 같은 record 를 쓴다).
 * 해상도 파생이 아닌 파생(외부 증강 WINTER/NIGHT/RAIN)은 BE 가 목록에서 제외한다.
 *
 * 보안: rawSn 은 숫자 path 파라미터로만 전달 — 문자열 직접 연결/사용자 입력 삽입 없음.
 * 권한(REVIEWER)·존재 여부는 BE 가 403/404 로 강제한다.
 */
export function listResolutionDerivatives(rawSn: number) {
  return apiClient
    .get<ResolutionChangeResult>(`/videos/${rawSn}/resolution`)
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

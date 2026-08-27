// 영상 도메인 API — BE: /api/v1/videos, /videos/{id}

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  BatchBulkRetryResult,
  BatchRetryResult,
  BatchStageRerunResult,
  BatchStageSkipResult,
  FrameLabels,
  ResolutionChangeResult,
  ResolutionPreset,
  StageBundle,
  Video,
  VideoDetail,
  VideoListParams,
  VrfcEvntQuestion,
} from './types';
import { BULK_RETRY_MAX, BULK_STAGE_BUNDLE, isBulkStageBundle, isStageBundle } from './types';

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

/**
 * 검증 이벤트 질문 1건이 <b>고를 수 있는 값</b>인지 판정한다. [@design API-043]
 *
 * 일련번호가 없으면 마킹 등록 요청에 실을 것이 없고, 문구가 비어 있으면 드롭다운에 빈 줄이
 * 그려져 무엇을 고르는지 알 수 없다. 둘 다 화면에서 되돌릴 수 없는 상태라 경계에서 걸러낸다.
 */
function isVrfcEvntQuestion(q: unknown): q is VrfcEvntQuestion {
  if (typeof q !== 'object' || q === null) return false;
  const { vrfcEvntQstnSn, qstnCn } = q as Partial<VrfcEvntQuestion>;
  return typeof vrfcEvntQstnSn === 'number' && typeof qstnCn === 'string' && qstnCn.trim() !== '';
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
        // [@design API-043] 해상도 — 기술메타에 적재된 형식 그대로다. **빈 문자열로 접지 않고
        //   null 을 그대로 남긴다**: 서버가 "미상"을 null 로 말하므로 화면도 같은 축을 본다
        //   (표시 두 곳의 `|| '-'` 폴백이 null·빈 문자열을 같게 다뤄 표시 결과는 동일하다).
        resolution: d.resolution ?? null,
        // [@design API-043] 실제 CCTV 식별자 — 기본정보의 'CCTV ID' 가 이 값을 그대로 쓴다.
        //   구 화면은 rawSn 으로 `video-0001` 을 조립해, 같은 화면 상단 제목의 진짜 식별자와
        //   서로 다른 두 값이 동시에 떴다. 없으면 조립값으로 되돌아가지 않고 그대로 비운다.
        vmsCctvId: d.vmsCctvId ?? null,
        // [@design API-043] [@design SCREEN-009] 프레임 미리보기 — 기존 세 필드(식별자·번호·썸네일
        //   주소)의 이름·타입·의미는 불변이고 두 값을 더한다.
        //   ⚠ `timestampMs` 는 **`null` 이 실려 온다**(키 부재가 아니다 — 이 프로젝트는 직렬화에서
        //     null 을 생략하지 않는다). 여기서 `?? null` 로 접어 「키 없음(구 응답)」과 「값 없음」을
        //     한 형태로 만든다 — 두 형태가 섞이면 소비처가 `undefined` 만 거르다 null 을 통과시킨다.
        //   ⚠ `hasIssue` 는 BE 가 원시 boolean 이라 null 이 오지 않지만, 값을 못 내리는 구 응답을
        //     위해 `=== true` 로 좁힌다(없으면 「이슈 없음」이지 「미상」이 아니다).
        framePreviews: (d.framePreviews ?? []).map((fp) => ({
          srcSn: fp.srcSn,
          frameNo: fp.frameNo,
          thumbnailUrl: fp.thumbnailUrl,
          timestampMs: fp.timestampMs ?? null,
          hasIssue: fp.hasIssue === true,
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
        // [@design API-043] 배치 실패 사유 — 서버가 이미 사용자 문구로 변환한 값. 빈 문자열은
        //   "사유 없음"과 같으므로 null 로 접어 화면 분기를 하나로 만든다(트림 후 판정).
        batchFailureReason: d.batchFailureReason?.trim() ? d.batchFailureReason : null,
        // [@design API-043] 건너뛴 작업 묶음 — 화이트리스트 교집합만 남긴다.
        //   미지의 코드(신 BE 가 대상을 넓힌 경우)를 그대로 두면 ① 화면에 기술 코드가 그대로
        //   새고 ② 건너뛰기 해제 요청이 그 값을 경로 세그먼트로 쓰게 된다(CWE-22).
        //   ⚠ 구 값 `YOLO`·`SAM2` 도 여기서 걸러진다 — 값 공간이 묶음(`AUTOLABEL`)으로 바뀌었고,
        //     개별 단계를 그대로 통과시키면 그 코드가 다시 경로 세그먼트가 된다(서버가 400 으로 막는다).
        skippedStages: (d.skippedStages ?? []).filter(isStageBundle),
        // [@design API-043] [@design ADR-050] 건너뛰기가 해제된 작업 묶음 — **영구** 상태다.
        //   재수행 버튼의 노출 근거이며, 이 값이 없던 시절 화면이 세션 로컬로 기억하던 것을 대체한다.
        //   화이트리스트 교집합만 남기는 이유는 `skippedStages` 와 같다(경로 세그먼트가 된다 — CWE-22).
        clearedStages: (d.clearedStages ?? []).filter(isStageBundle),
        // [@design ADR-050] 지금 실패한 상태인 작업 묶음 — 「건너뛰기를 허용할지」의 서버 판정 결과다.
        //   ★ 화면이 `stages`·사유 문자열에서 실패를 재유도하지 않게 하는 유일한 근거이며, 시계열
        //     위탁 실패처럼 **파이프라인을 멈추지 않는 실패**는 이 값으로만 드러난다.
        //   화이트리스트 교집합만 남기는 이유는 위 두 목록과 같다(경로 세그먼트가 된다 — CWE-22).
        failedStages: (d.failedStages ?? []).filter(isStageBundle),
        // [@design API-043] 그 영상의 검증 이벤트 유형 코드 — 관제 이벤트유형(evntTypeCd)과 다른
        //   코드 체계다. 미수신이면 null 이고 그러면 고를 질문도 없다.
        vrfcEvntTypeCd: d.vrfcEvntTypeCd ?? null,
        // [@design API-043] [@design SCREEN-006] 검증 이벤트 질문 목록 — BE 가 정렬순서 오름차순으로
        //   내려주므로 **다시 정렬하지 않는다**(첫 번째가 곧 기본 질문이며 서버 교정도 같은 축이다).
        //   값을 못 내리는 구 응답은 빈 배열로 정규화해 화면 분기를 하나로 유지한다.
        //   ⚠ 항목 형태 검증을 여기서 한 번만 한다 — 일련번호가 없거나 문구가 빈 항목은 고를 수도
        //     보낼 수도 없는 값이라 그대로 두면 화면이 빈 옵션을 그리고 요청에 null 이 실린다.
        vrfcEvntQuestions: (d.vrfcEvntQuestions ?? []).filter(isVrfcEvntQuestion),
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

// [@design SCREEN-009] 영상 재비식별 요청 클라이언트(`requestRedeident`)는 두지 않는다 —
//   영상 상세 화면에서 '재비식별 요청' 진입점이 제거되면서 호출부가 0이 됐다(확정 사양).
//   BE 엔드포인트(POST /api/v1/videos/{rawSn}/redeident)는 그대로 살아 있다.

/**
 * 배치 재실행 — BE: POST /api/v1/videos/{rawSn}/batch/retry (REVIEWER). [@design API-167]
 *
 * <p>실패로 고착된 영상을 검수자가 수동으로 재기동한다. 파이프라인을 처음부터 순회하되 이미
 * 성공한 단계는 산출물이 실재하면 다시 수행하지 않으므로 실질 작업은 실패한 단계부터 이어진다
 * (같은 영상을 여러 번 눌러도 프레임·자동 라벨·외부 위탁이 중복되지 않는다).
 *
 * 보안: rawSn 은 숫자 path 파라미터로만 전달 — 문자열 직접 연결/사용자 입력 삽입 없음.
 * 권한(REVIEWER)·상태(실패 아님)는 BE 가 403/409 로 강제한다.
 */
export function retryBatch(rawSn: number) {
  return apiClient
    .post<BatchRetryResult>(`/videos/${rawSn}/batch/retry`)
    .then((r) => r.data);
}

/**
 * 작업 묶음 수동 스킵 — BE: POST /api/v1/videos/{rawSn}/batch/stages/{stage}/skip (REVIEWER).
 * [@design API-198]
 *
 * <p>단위는 개별 단계가 아니라 **작업 묶음**이다 — 시계열(VLM) · 오토라벨(AI 탐지·AI 분할·보간).
 *
 * <p>사유는 **필수**다. 스킵은 그 영상의 시계열 서술·자동 라벨을 비우는 결정이라, 나중에 "왜 이
 * 영상만 비어 있나"를 되짚을 근거가 남아야 한다. 서버도 `@NotBlank` 로 같은 제약을 건다.
 *
 * 보안:
 * - 경로 조작(CWE-22): `bundle` 은 화이트리스트(STAGE_BUNDLES) 교집합만 통과시킨다. 타입만으로는
 *   런타임 유입(서버 응답 유래 값)을 막지 못하므로 호출 직전에 다시 판정한다.
 * - 입력 검증(CWE-20): 사유는 호출부 폼(zod)과 서버가 이중으로 검증한다. 여기서는 그대로 전달한다.
 */
export function skipBatchStage(rawSn: number, bundle: StageBundle, reason: string) {
  assertStageBundle(bundle);
  return apiClient
    .post<BatchStageSkipResult>(`/videos/${rawSn}/batch/stages/${bundle}/skip`, { reason })
    .then((r) => r.data);
}

/**
 * 작업 묶음 스킵 해제 — BE: DELETE /api/v1/videos/{rawSn}/batch/stages/{stage}/skip (REVIEWER).
 * [@design API-200]
 *
 * <p>스킵 표식만 해제하고 실행하지는 않는다 — 실제 실행은 재수행({@link rerunBatchStage})이 담당한다.
 * **204 No Content** 라 응답 본문이 없다(반환값 없음).
 *
 * <p>⚠ **어떤 화면도 이 함수를 부르지 않는다**(ADR-050). 재수행이 건너뛴 상태를 직접 수락하고 해제
 * 표식까지 함께 남기므로 화면의 해제 동선은 폐지됐다. 서버 계약은 그대로라 클라이언트도 남겨 두지만,
 * 이것을 근거로 화면에 해제 버튼을 다시 만들지 말 것.
 *
 * 보안: 화이트리스트 검증은 {@link skipBatchStage} 와 동일하다.
 */
export function unskipBatchStage(rawSn: number, bundle: StageBundle): Promise<void> {
  assertStageBundle(bundle);
  return apiClient
    .delete(`/videos/${rawSn}/batch/stages/${bundle}/skip`)
    .then(() => undefined);
}

/**
 * 건너뛰기를 해제한 작업 묶음 재수행 — BE: POST /api/v1/videos/{rawSn}/batch/stages/{stage}/rerun (REVIEWER).
 * [@design API-201]
 *
 * <p>「문제가 생긴 곳부터 재시도한다」 — 전체 재기동(API-167)과 <b>분리된 요청</b>이다. 완주 영상에
 * 전체 재기동을 쓰면 파이프라인이 통째로 돌아 트랙 보간이 함께 수행되고, 사람이 손댄 보간 라벨이
 * 전량 지워진다(복구 지점 없음). 그래서 해제한 그 묶음만 지목한다.
 *
 * <p>★<b>범위를 고르지 않는다 — 묶음이 곧 범위다.</b> 구 요청 본문 `scope`(ONLY/FROM)는 **폐지**됐다.
 * 되살리면 보간을 뺀 부분 수행이 다시 가능해져 산출물끼리 어긋난다(그 갈래가 폐지된 이유다).
 * 대신 오토라벨 묶음은 보간까지 다시 만들므로, <b>고르는 시점에</b> 호출부가 그 사실을 알린다.
 *
 * <p>★ 대상을 요청이 자유롭게 지정하지 못한다 — 서버는 <b>그 영상에서 건너뛴 적이 있는 묶음</b>
 * (지금 건너뛴 상태이거나 이미 해제된 묶음)만 수락하고 그 외에는 400 이다(임의 지정을 허용하면 앞
 * 작업을 건너뛴 산출물이 전제 없이 만들어진다).
 *
 * <p>★ <b>건너뛴 상태를 직접 수락한다</b>(ADR-050) — 해제를 먼저 부를 필요가 없으며, 재수행이 해제
 * 표식까지 함께 남긴다. 그래서 화면의 해제 동선은 폐지됐다.
 *
 * <p>200 은 <b>접수</b>다 — 상태 선점까지만 요청 안에서 처리하고 파이프라인은 뒤에서 이어 돈다.
 *
 * 보안: 화이트리스트 검증은 {@link skipBatchStage} 와 **같은 판정기**({@link assertStageBundle})를
 * 쓴다(CWE-22 — 복제하면 한쪽만 갱신되어 갈린다). 권한(REVIEWER)·건너뛴 적이 있는 묶음인지·선점 충돌은
 * BE 가 403/400/409 로 강제한다.
 */
export function rerunBatchStage(rawSn: number, bundle: StageBundle) {
  assertStageBundle(bundle);
  return apiClient
    .post<BatchStageRerunResult>(`/videos/${rawSn}/batch/stages/${bundle}/rerun`)
    .then((r) => r.data);
}

/**
 * 배치 일괄 재시작 — BE: POST /api/v1/videos/batch/retry (REVIEWER). [@design API-199]
 *
 * <p>★**부분 성공**이다. 한 건도 성공하지 못해도 200 이므로 호출부는 상태코드가 아니라
 * `results`(건별 성패 + 사유)로 판정해야 한다.
 *
 * 보안: 목록 상한(CWE-770)은 서버가 400 으로 강제하고, 화면은 같은 값을 미리 안내한다.
 * 여기서는 요청 전 중복을 제거해 "자기 자신이 만든 진행 상태에 막혀 실패로 보고되는" 잡음을 없앤다
 * (서버도 같은 정규화를 한다 — 두 곳이 갈리면 건수 표시가 어긋나므로 규칙을 맞춘다).
 */
export function retryBatchBulk(rawSns: number[]) {
  const unique = Array.from(new Set(rawSns));
  return apiClient
    .post<BatchBulkRetryResult>('/videos/batch/retry', { rawSns: unique })
    .then((r) => r.data);
}

/**
 * 작업 묶음 일괄 건너뛰기 — BE: POST /api/v1/videos/batch/stages/{stage}/skip (REVIEWER).
 * [@design API-212]
 *
 * <p>단건 건너뛰기({@link skipBatchStage})와 판정·기록 규칙이 같고 대상만 목록으로 받는다.
 * 대상 묶음은 <b>시계열({@link BULK_STAGE_BUNDLE}) 하나</b>다 — 오토라벨은 산출물이 라벨이라 대량으로
 * 건너뛸 수 있게 열지 않았다(서버도 400).
 *
 * <p><b>사유는 요청당 하나</b>이며 대상 전건에 같은 값으로 기록된다. 영상마다 다른 사유를 받으면
 * 일괄로 처리할 이유가 사라지기 때문이다. 사유 자체는 서버가 정제·검증하므로 여기서는 그대로 전달한다
 * (상한은 {@code SKIP_REASON_MAX} — 화면이 미리 안내한다).
 *
 * <p>★<b>부분 성공</b>이다. 한 건도 처리되지 못해도 200 이므로 호출부는 상태코드가 아니라
 * `results`(건별 성패 + 사유)로 판정해야 한다 — "요청 성공"만 보고 전부 건너뛴 것처럼 알리면
 * 사용자는 무엇이 안 됐는지 영영 모른다.
 *
 * 보안:
 * - 경로 조작(CWE-22): 경로 세그먼트는 상수 하나뿐이며 호출 직전에 다시 판정한다({@link assertBulkStageBundle}).
 * - 자원 소모(CWE-770): 1회 상한(BULK_RETRY_MAX)은 서버가 400 으로 강제하고 화면이 같은 값을 미리 안내한다.
 * - 중복 제거는 서버와 <b>같은 규칙</b>이다 — 두 곳이 갈리면 건수 표시가 어긋난다.
 */
export function skipBatchStageBulk(rawSns: number[], reason: string) {
  return apiClient
    .post<BatchBulkRetryResult>(bulkStagePath('skip'), {
      rawSns: distinctRawSns(rawSns),
      reason,
    })
    .then((r) => r.data);
}

/**
 * 작업 묶음 일괄 <b>건너뛰기 해제</b> — BE: DELETE /api/v1/videos/batch/stages/{stage}/skip (REVIEWER).
 * [@design API-213]
 *
 * <p>건너뜀 표식이라는 하위 리소스를 지우는 요청이라 DELETE 다(같은 URL 에 쿼리 파라미터로 행위를
 * 분기하지 않는다는 이 저장소 규약). ★<b>대상 목록을 본문으로 받는 DELETE</b> 이므로 axios 는
 * {@code delete(url, { data })} 형태로 보낸다 — 중간 경로가 본문을 버리는 구성에서는 목록이 전달되지
 * 않으며, 그 경우 서버는 "전건 해제"로 흐르지 않고 400 이다(본문 필수).
 *
 * <p>해제는 표식을 지우는 것이 아니라 <b>해제 표식을 덧붙이는 것</b>이라 누가 언제 풀었는지가 함께
 * 남는다(단건과 같다). <b>작업을 실행하지는 않는다</b> — 실제 실행은 {@link rerunBatchStageBulk} 가 담당한다.
 *
 * <p>★ 단건 해제({@link unskipBatchStage})와 달리 <b>본문이 있는 200</b> 이다(단건은 204 무본문).
 * 부분 성공을 건별로 돌려줘야 하기 때문이며, 판정은 상태코드가 아니라 `results` 로 한다.
 *
 * <p>⚠ **어떤 화면도 이 함수를 부르지 않는다**(ADR-050) — 목록 화면의 「일괄 건너뛰기 해제」 버튼은
 * 폐기됐다. 회수는 {@link rerunBatchStageBulk} 하나로 끝난다(재수행이 건너뛴 상태를 직접 수락한다).
 * 서버 계약은 그대로라 클라이언트도 남겨 두지만, 이것을 근거로 버튼을 다시 만들지 말 것.
 *
 * 보안: 경로·중복·상한 규칙은 {@link skipBatchStageBulk} 와 동일하다.
 */
export function clearBatchStageSkipBulk(rawSns: number[]) {
  return apiClient
    .delete<BatchBulkRetryResult>(bulkStagePath('skip'), {
      data: { rawSns: distinctRawSns(rawSns) },
    })
    .then((r) => r.data);
}

/**
 * 건너뛴 적이 있는 작업 묶음 일괄 재수행 — BE: POST /api/v1/videos/batch/stages/{stage}/rerun (REVIEWER).
 * [@design API-214] [@design ADR-050]
 *
 * <p>수락 조건은 단건({@link rerunBatchStage})과 같다 — <b>그 영상에서 건너뛴 적이 있는 묶음인가</b>
 * (건너뜀·해제 모두) 하나이며, 그 외는 건별 실패다(임의 지정을 허용하면 앞 작업을 건너뛴 산출물이
 * 전제 없이 만들어진다). 해제를 먼저 부를 필요는 없다 — 이 요청이 해제 표식까지 함께 남긴다.
 *
 * <p>★ <b>검수 승인 이력이 있는 영상도 이 경로에서는 대상이 된다.</b> 시계열 재수행은 확정된 라벨을
 * 되돌리지 않고 메타만 더하며, 들어온 서술이 실제로 달라졌을 때만 재검수·산출물 재생성·관제 재통지가
 * 걸린다(같은 값이면 no-op). ⚠ 오토라벨은 이 예외 대상이 <b>아니다</b> — 라벨을 다시 만들어 승인
 * 스냅샷과 어긋나기 때문이며, 그래서 일괄 축이 시계열 하나로 좁혀져 있다.
 *
 * <p>★ 건별 `success` 는 <b>접수</b>이지 파이프라인 완료가 아니다(실행은 비동기). 진행은 목록·상세의
 * 처리 단계 표시로 확인한다.
 *
 * <p>★ <b>범위를 고르지 않는다 — 묶음이 곧 범위다</b>(단건과 같다). 구 `scope` 본문을 되살리지 말 것.
 *
 * 보안: 경로·중복·상한 규칙은 {@link skipBatchStageBulk} 와 동일하다.
 */
export function rerunBatchStageBulk(rawSns: number[]) {
  return apiClient
    .post<BatchBulkRetryResult>(bulkStagePath('rerun'), {
      rawSns: distinctRawSns(rawSns),
    })
    .then((r) => r.data);
}

/**
 * 일괄 축 경로 조립 — 세그먼트 검증의 <b>단일 지점</b>. [@design API-212] [@design API-213] [@design API-214]
 *
 * <p>세 함수가 각자 문자열을 잇지 않게 한 곳으로 모은다(복제하면 한쪽만 갱신되어 갈린다).
 * 단건 축은 {@code /videos/{rawSn}/batch/stages/…}(7 세그먼트), 일괄 축은 {@code /videos/batch/stages/…}
 * (6 세그먼트)라 <b>세그먼트 수가 달라</b> 서로 매칭되지 않는다.
 */
function bulkStagePath(action: 'skip' | 'rerun'): string {
  assertBulkStageBundle(BULK_STAGE_BUNDLE);
  return `/videos/batch/stages/${BULK_STAGE_BUNDLE}/${action}`;
}

/**
 * 요청 전 중복 제거 — 서버와 <b>같은 규칙</b>(중복은 1건 취급, 요청 순서 보존).
 *
 * 두 곳이 갈리면 건수 표시가 어긋나고, "자기 자신이 만든 진행 상태에 막혀 실패로 보고되는" 잡음이 난다.
 */
function distinctRawSns(rawSns: number[]): number[] {
  return Array.from(new Set(rawSns));
}

/**
 * 일괄 축 경로 세그먼트 검증(CWE-22) — 화이트리스트 <b>2단</b>.
 *
 * 1단({@link assertStageBundle})은 조작 대상 묶음인지, 2단은 그중 <b>일괄로 열어 둔 것</b>인지 본다.
 * 두 판정을 합치면 단건 축이 함께 좁아지므로 합치지 않는다.
 */
function assertBulkStageBundle(bundle: string): void {
  assertStageBundle(bundle);
  if (!isBulkStageBundle(bundle)) {
    throw new Error('일괄 처리는 시계열 작업 묶음만 지원합니다.');
  }
}

/** 경로 세그먼트로 쓰기 전 작업 묶음 코드 화이트리스트 검증(CWE-22). */
function assertStageBundle(bundle: string): asserts bundle is StageBundle {
  if (!isStageBundle(bundle)) {
    throw new Error('지원하지 않는 배치 작업입니다.');
  }
}

/** 상한 초과 여부 — 화면과 API 가 같은 기준을 쓰도록 여기서 노출한다. [@design API-199] */
export function exceedsBulkRetryLimit(count: number): boolean {
  return count > BULK_RETRY_MAX;
}

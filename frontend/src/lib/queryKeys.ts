// Query Key Factory — 도메인별 골격 (Phase 1+에서 도메인별로 확장)

import type { NoticeListParams } from '@/features/notice/types';

export const USER_KEYS = {
  all: ['users'] as const,
  me: () => [...USER_KEYS.all, 'me'] as const,
  list: (params: Record<string, unknown>) => [...USER_KEYS.all, 'list', params] as const,
  detail: (id: number) => [...USER_KEYS.all, 'detail', id] as const,
};

export const ASSIGNMENT_KEYS = {
  all: ['assignments'] as const,
  list: (params: Record<string, unknown>) => [...ASSIGNMENT_KEYS.all, 'list', params] as const,
  /** 이벤트유형 옵션 — 목록 필터에 의존하지 않는다(near-immutable). */
  eventTypes: (params: Record<string, unknown>) =>
    [...ASSIGNMENT_KEYS.all, 'event-types', params] as const,
  history: (id: number) => [...ASSIGNMENT_KEYS.all, 'history', id] as const,
};

/**
 * SCR-TASK-001 REVIEWER 통합 작업 목록 — BE /v1/tasks/board.
 * 페이지/상태별로 키를 분리하여 React Query 가 캐시를 분리 관리한다.
 */
export const TASK_BOARD_KEYS = {
  all: ['taskBoard'] as const,
  list: (params: Record<string, unknown>) => [...TASK_BOARD_KEYS.all, 'list', params] as const,
  /** KPI 집계 — 목록과 필터가 다르므로(workStatus 제외) 키를 분리한다. */
  summary: (params: Record<string, unknown>) =>
    [...TASK_BOARD_KEYS.all, 'summary', params] as const,
  /** 이벤트유형 옵션 — status 축에만 의존(near-immutable). */
  eventTypes: (params: Record<string, unknown>) =>
    [...TASK_BOARD_KEYS.all, 'event-types', params] as const,
};

export const VIDEO_KEYS = {
  all: ['videos'] as const,
  list: (params: Record<string, unknown>) => [...VIDEO_KEYS.all, 'list', params] as const,
  detail: (id: number) => [...VIDEO_KEYS.all, 'detail', id] as const,
  streamUrl: (id: number) => [...VIDEO_KEYS.all, 'stream-url', id] as const,
};

export const LABEL_KEYS = {
  all: ['labels'] as const,
  byVideo: (videoId: number) => [...LABEL_KEYS.all, 'video', videoId] as const,
  byFrame: (videoId: number, frameNo: number) =>
    [...LABEL_KEYS.all, 'video', videoId, 'frame', frameNo] as const,
  /** 프레임(srcSn) 라벨 변경 이력 — 전체 페이지 prefix (저장 후 일괄 invalidate 용). */
  historyByFrame: (srcSn: number) => [...LABEL_KEYS.all, 'history', srcSn] as const,
  /** 프레임(srcSn) 라벨 변경 이력 — 페이지별 캐시 키. */
  history: (srcSn: number, page: number) =>
    [...LABEL_KEYS.historyByFrame(srcSn), page] as const,
};

export const REVIEW_KEYS = {
  all: ['reviews'] as const,
  pending: (params: Record<string, unknown>) => [...REVIEW_KEYS.all, 'pending', params] as const,
  /**
   * 검수목록 KPI 집계 — 목록과 파라미터가 다르므로(status 제외) 키를 분리한다.
   * `REVIEW_KEYS.all` 하위라 승인/반려 mutation 의 broad invalidate 한 번에 함께 갱신된다.
   */
  summary: (params: Record<string, unknown>) =>
    [...REVIEW_KEYS.all, 'summary', params] as const,
  detail: (id: number) => [...REVIEW_KEYS.all, 'detail', id] as const,
  frames: (videoId: number) => [...REVIEW_KEYS.all, 'frames', videoId] as const,
  issueThreads: (rawSn: number) =>
    [...REVIEW_KEYS.all, 'issueThreads', rawSn] as const,
};

export const VERSION_KEYS = {
  all: ['versions'] as const,
  history: (videoId: number) => [...VERSION_KEYS.all, 'history', videoId] as const,
  diff: (videoId: number, fromSha: string, toSha: string) =>
    [...VERSION_KEYS.all, 'diff', videoId, fromSha, toSha] as const,
  /**
   * 버전 스냅샷 ↔ 현재 작업본 diff.
   * `VERSION_KEYS.all` 하위라 라벨 저장(useUpdateLabels)·롤백(useRollback)의 broad invalidate 로
   * 별도 배선 없이 함께 갱신된다. 두 버전 비교(`diff`)와는 키가 분리돼야 한다 — 같은 키를 쓰면
   * 버전 간 diff 결과가 작업본 diff 자리에 표시된다.
   */
  workingDiff: (videoId: number, hash: string) =>
    [...VERSION_KEYS.all, 'working-diff', videoId, hash] as const,
  /**
   * R6/D4 — 영상(rawSn) 단위 산출 버전 목록(「시작 버전 선택」 선택지).
   *
   * `history(srcSn)`(프레임 축)와 <b>키가 분리돼야 한다</b> — 두 축은 식별자 의미가 다르고
   * (프레임 PK vs 영상 PK) 응답 스키마도 다르다. 같은 키를 쓰면 한쪽 결과가 다른 쪽 자리에 뜬다.
   * `VERSION_KEYS.all` 하위라 라벨 저장·롤백·시작버전 적용의 broad invalidate 로 함께 갱신된다.
   */
  videoVersions: (rawSn: number) => [...VERSION_KEYS.all, 'video', rawSn] as const,
};

export const AUGMENT_KEYS = {
  all: ['augments'] as const,
  list: (params: Record<string, unknown>) => [...AUGMENT_KEYS.all, 'list', params] as const,
  /**
   * 결과 조회 전체 prefix — **진행상태 키(`progress`)를 포함하지 않는다**.
   * 결과만 다시 받고 싶을 때 `all` 을 무효화하면 폴링 쿼리까지 함께 깨워 서버 요청이 증폭된다
   * (서버에 속도 제한이 없다). 결과 갱신은 이 prefix 로만 한다.
   */
  details: () => [...AUGMENT_KEYS.all, 'detail'] as const,
  // 프레임 쌍(page/size) + 결과 항목(itemPage/itemSize) 두 축을 모두 키에 포함해야
  // 페이지 전환이 캐시에 반영된다(축이 둘이라 한쪽만 넣으면 다른 축이 캐시에 갇힌다).
  detail: (id: number, params: Record<string, unknown> = {}) =>
    [...AUGMENT_KEYS.details(), id, params] as const,
  /** 항목별 진행상태 폴링 — 결과 조회와 갱신 주기가 달라 키를 분리한다. */
  progress: (id: number) => [...AUGMENT_KEYS.all, 'progress', id] as const,
};

export const EXPORT_KEYS = {
  all: ['exports'] as const,
  list: (params: Record<string, unknown>) => [...EXPORT_KEYS.all, 'list', params] as const,
  detail: (id: number) => [...EXPORT_KEYS.all, 'detail', id] as const,
};

/**
 * 비식별 신고 관리(REVIEWER) — BE GET /v1/deident-reports.
 * 상태/페이지별로 키를 분리하여 React Query 가 캐시를 분리 관리한다.
 */
export const DEIDENT_REPORT_KEYS = {
  all: ['deidentReports'] as const,
  list: (params: Record<string, unknown>) =>
    [...DEIDENT_REPORT_KEYS.all, 'list', params] as const,
  /**
   * 재비식별 산출물 후보 목록 — `all` 하위에 두어 해소 성공 시 기존 무효화로 함께 갱신된다
   * (외부 솔루션이 파일을 더 만들었을 수 있으므로 재조회가 맞다).
   */
  candidates: (rprtSn: number) =>
    [...DEIDENT_REPORT_KEYS.all, 'candidates', rprtSn] as const,
};

export const PORTAL_KEYS = {
  all: ['portal'] as const,
  uploads: (params: Record<string, unknown>) => [...PORTAL_KEYS.all, 'uploads', params] as const,
  uploadDetail: (uldSn: number) => [...PORTAL_KEYS.all, 'upload-detail', uldSn] as const,
  uploadFrameLabels: (uldFrmeSn: number) =>
    [...PORTAL_KEYS.all, 'upload-frame-labels', uldFrmeSn] as const,
  datamartVideos: (params: Record<string, unknown>) =>
    [...PORTAL_KEYS.all, 'datamart-videos', params] as const,
};

export const SYSCONFIG_KEYS = {
  all: ['sysconfig'] as const,
  presets: () => [...SYSCONFIG_KEYS.all, 'presets'] as const,
  /**
   * AI 정밀도 기본값(GET /v1/ai-defaults) — 라벨링 화면 전용 읽기.
   *
   * `all` 하위에 두어 검수자의 설정 수정(useUpdateConfig)이 이 조회도 함께 무효화하게 한다.
   */
  aiDefaults: () => [...SYSCONFIG_KEYS.all, 'ai-defaults'] as const,
};

/**
 * AI 장비 노드 원장(GET /v1/manage/ai-servers) — 관리자 전용 목록.
 *
 * 유형은 **조건 축**이라 키에 넣는다 — 넣지 않으면 추론 탭에서 받은 목록이 시계열 탭에 그대로
 * 나온다(두 유형은 부하를 세는 축까지 달라 섞이면 수치가 거짓이 된다).
 *
 * ⚠ `SYSCONFIG_KEYS` 아래에 두지 않는다 — 설정값 저장이 이 목록을 무효화할 이유가 없고,
 *   원장은 설정 캐시와 다른 진실원이다(`ADR-046`).
 */
export const AI_SERVER_KEYS = {
  all: ['aiServers'] as const,
  list: (srvrTypeCd?: string) => [...AI_SERVER_KEYS.all, 'list', srvrTypeCd ?? 'ALL'] as const,
};

export const NOTICE_KEYS = {
  all: ['notices'] as const,
  lists: () => [...NOTICE_KEYS.all, 'list'] as const,
  list: (params: NoticeListParams) => [...NOTICE_KEYS.lists(), params] as const,
  details: () => [...NOTICE_KEYS.all, 'detail'] as const,
  detail: (id: number) => [...NOTICE_KEYS.details(), id] as const,
};

export const META_KEYS = {
  all: ['meta'] as const,
  byVideo: (videoId: number) => [...META_KEYS.all, 'video', videoId] as const,
};

/**
 * event_annotation(외부 VLM VQA/CoT) — BE GET/PUT /v1/videos/{rawSn}/event-annotation.
 * 영상(rawSn) 단위로 캐시를 분리 관리한다.
 */
export const EVENT_ANNOTATION_KEYS = {
  all: ['eventAnnotation'] as const,
  byVideo: (rawSn: number) => [...EVENT_ANNOTATION_KEYS.all, 'video', rawSn] as const,
};

export const AUTOLABEL_KEYS = {
  all: ['autolabel'] as const,
  byVideo: (videoId: number) => [...AUTOLABEL_KEYS.all, 'video', videoId] as const,
};

export const MARKING_KEYS = {
  all: ['markings'] as const,
};

export const STAT_KEYS = {
  all: ['stats'] as const,
  worker: (userId: number | string) => [...STAT_KEYS.all, 'worker', userId] as const,
  overall: () => [...STAT_KEYS.all, 'overall'] as const,
};

/**
 * 이벤트 타입 — BE GET /v1/event-types (필터 옵션), /v1/event-types/labels (코드→라벨 맵).
 * near-immutable 이라 한 세션 1회만 페치한다 (hooks staleTime: Infinity).
 */
export const EVENT_TYPE_KEYS = {
  all: ['eventTypes'] as const,
  options: () => [...EVENT_TYPE_KEYS.all, 'options'] as const,
  labels: () => [...EVENT_TYPE_KEYS.all, 'labels'] as const,
};

/**
 * 검증 이벤트 유형·질문(DOMAIN-014) — BE `/v1/manage/verification-event-types`.
 * [@design API-219] [@design API-220]
 *
 * ★ `EVENT_TYPE_KEYS` 하위에 두지 않는다 — 관제 이벤트유형(`EV…`)과 검증 이벤트 유형(`fire`…)은
 * <b>코드 체계가 다른 별개 축</b>이다. 한 뿌리에 두면 한쪽의 무효화가 접두 일치로 다른 쪽까지
 * 걸어, 관계없는 재조회가 일어나거나 「응답값으로 갱신하고 재조회하지 않는다」는 계약이 무너진다.
 */
export const VERIFICATION_EVENT_TYPE_KEYS = {
  all: ['verificationEventTypes'] as const,
  list: () => [...VERIFICATION_EVENT_TYPE_KEYS.all, 'list'] as const,
};

/**
 * 외부 산출물 이관(DOMAIN-017) — BE `/v1/imports`, `/v1/import-mappings`.
 *
 * 이력·대응 두 축을 한 뿌리(`all`) 아래 둔다 — 적재가 성공하면 이력이 늘고, 대응을 확정하면
 * 다음 검사 결과가 달라지므로 두 축이 서로의 갱신 대상이 된다.
 */
export const IMPORT_KEYS = {
  all: ['imports'] as const,
  historyLists: () => [...IMPORT_KEYS.all, 'history'] as const,
  historyList: (params: Record<string, unknown>) =>
    [...IMPORT_KEYS.historyLists(), params] as const,
  historyDetail: (trnsfSn: number) => [...IMPORT_KEYS.all, 'history', 'detail', trnsfSn] as const,
  mappingLists: () => [...IMPORT_KEYS.all, 'mappings'] as const,
  mappingList: (params: Record<string, unknown>) =>
    [...IMPORT_KEYS.mappingLists(), params] as const,
  /**
   * 위치 탐색(API-221·API-222) — **자리(`path`)로만** 캐시를 나눈다.
   *
   * 루트 목록은 기준 위치가 없어 키에 `null` 을 그대로 쓴다(`undefined` 는 키에서 소실된다).
   * 폴더 축과 파일 축은 같은 경로여도 담는 것이 달라 키를 분리한다.
   *
   * ★<b>이어받을 자리(`cursor`)를 키에 넣지 않는다.</b> 이 두 축은 무한 조회
   * (`useInfiniteQuery`)로 받으며, 그 규약상 커서는 키가 아니라 **쪽 인자**(`pageParam`)로
   * 흐르고 받아온 쪽들이 <b>한 키 아래에 쌓인다</b>. 커서를 키에 넣으면 쪽마다 별개의 캐시
   * 자리가 생겨 <b>이어붙이기가 성립하지 않는다</b> — 「더 보기」를 누를 때마다 앞서 받은
   * 목록이 사라지고 그 쪽 하나만 남는다.
   *
   * ⚠ 이것은 앞선 「찾을 이름」 축과 반대 방향이다. 그때는 이름이 키에 <b>반드시</b> 들어가야
   *   했다(같은 자리에서 조건만 바뀌면 다른 결과라 캐시를 갈라야 했다). 커서는 조건이 아니라
   *   <b>같은 목록의 이어지는 부분</b>이라 갈라서는 안 된다. 두 축을 같은 규칙으로 다루지 말 것.
   */
  browseFolders: (path: string | null) =>
    [...IMPORT_KEYS.all, 'browse', 'folders', path] as const,
  browseFiles: (path: string | null) => [...IMPORT_KEYS.all, 'browse', 'files', path] as const,
  /**
   * 탐색 캐시 전체. 창을 다시 열 때 **쌓인 쪽들을 버리는** 데 쓴다.
   *
   * 이어받기는 창을 여는 시점부터 다시 시작한다(SCREEN-039). 버리지 않으면 앞 회차에 쌓아 둔
   * 쪽들이 그대로 남아, 창을 다시 열었는데 이미 여러 번 이어받은 상태로 열린다.
   */
  browses: () => [...IMPORT_KEYS.all, 'browse'] as const,
};

// 버전관리 도메인 API — BE: /api/v1/frames/{srcSn}/versions, /versions/{toHash}/diff, /versions/{versionHash}/rollback
//
// 버전 식별자 의미 (Phase 6 DB 스냅샷 전환):
// - 모든 버전 식별자(commitSha)는 versionHash = 라벨 스냅샷 SHA-256 hex(64자). 와이어 호환을 위해
//   응답 필드명만 commitSha/shortHash 로 유지한다. 외부 VCS 의존 없음(versionHash=스냅샷 SHA-256).
// - srcSn 은 LS_DATA_SRC.SRC_SN (프레임 단위 PK). 영상(LS_DATA_RAW.RAW_SN)이 아니다.
//
// 보안 (security.md 정합):
// - 사용자 입력 versionHash 는 BE 에서 hex(64자) 검증 후 사용 — FE 는 단순 전달.
// - axios가 path/query 자동 URL 인코딩 (XSS/CRLF 방어).
// - IDOR 방어 + 롤백 권한(REVIEWER) 검증은 BE 책임.

import { apiClient } from '@/lib/api/client';

import type {
  LabelDiff,
  RollbackResponse,
  StartVersionApplyResult,
  Version,
  VideoVersion,
} from './types';

/**
 * 프레임(srcSn)의 라벨 커밋 이력 조회 (최신순).
 * BE: GET /api/v1/frames/{srcSn}/versions
 *
 * @param srcSn LS_DATA_SRC.SRC_SN — 프레임 단위 PK (LabelingPage 에서는 data.srcSn 그대로 전달)
 */
export function listVersions(srcSn: number): Promise<Version[]> {
  return apiClient.get<Version[]>(`/frames/${srcSn}/versions`).then((r) => r.data);
}

/**
 * 두 버전 간 라벨 diff 조회.
 * BE: GET /api/v1/versions/{toHash}/diff?compareWith={fromHash}
 *
 * - path(commit) = 비교 대상 to 버전(versionHash)
 * - query(compareWith) = 기준 from 버전(versionHash)
 * compareWith가 없으면 BE는 직전 버전(부모 스냅샷)과 비교.
 */
export function getDiff(commit: string, compareWith?: string): Promise<LabelDiff[]> {
  return apiClient
    .get<LabelDiff[]>(`/versions/${commit}/diff`, {
      params: compareWith ? { compareWith } : undefined,
    })
    .then((r) => r.data);
}

/**
 * 버전 스냅샷 ↔ 현재 작업본(LS_DATA_LBL) 라벨 diff 조회.
 * BE: GET /api/v1/versions/{commit}/diff-with-working
 *
 * - path(commit) = 기준 from 버전(versionHash)
 * - to 는 항상 현재 작업본이라 별도 파라미터가 없다.
 * 승인 버전이 1건뿐이라 두 버전 비교가 불가능한 프레임에서도 "승인 이후 지금까지"를 볼 수 있다.
 * 응답 스키마는 getDiff 와 동일(LabelDiff[])하여 DiffViewer 를 그대로 재사용한다.
 *
 * [req: R1]
 */
export function getWorkingDiff(commit: string): Promise<LabelDiff[]> {
  return apiClient
    .get<LabelDiff[]>(`/versions/${commit}/diff-with-working`)
    .then((r) => r.data);
}

/**
 * 지정한 versionHash 의 라벨 스냅샷으로 롤백 (현재 버전 위에 신규 이력 1건 생성).
 * BE: POST /api/v1/versions/{versionHash}/rollback  body: { srcSn }
 *
 * 보안: 권한(REVIEWER) 검증 + versionHash hex 검증은 BE에서 수행.
 */
export function rollback(commit: string, srcSn: number): Promise<RollbackResponse> {
  return apiClient
    .post<RollbackResponse>(`/versions/${commit}/rollback`, { srcSn })
    .then((r) => r.data);
}

/**
 * 영상(rawSn)의 산출 버전 목록 — 「시작 버전 선택」 선택지.
 * BE: GET /api/v1/videos/{rawSn}/versions
 *
 * 프레임 단위 목록({@link listVersions})과 <b>별개 리소스</b>다 — 이쪽은 영상 축이며 번호는 관제가
 * 픽업하는 산출 폴더 `v{n}` 과 같다. 서버가 내림차순(최신 먼저)으로 내려주며 <b>FE 는 순서를 다시
 * 정하지 않는다</b>(정렬 축이 두 곳으로 갈리면 화면과 서버가 어긋난다).
 *
 * @design D4
 * @req R6
 */
export function listVideoVersions(rawSn: number): Promise<VideoVersion[]> {
  return apiClient
    .get<VideoVersion[]>(`/videos/${rawSn}/versions`)
    .then((r) => (Array.isArray(r.data) ? r.data : []));
}

/**
 * 영상 단위 시작 버전 적용 — 선택한 산출 버전 상태(라벨 본문 + 프레임 폐기 상태)로 되돌린다.
 * BE: PUT /api/v1/videos/{rawSn}/start-version  body: { versionNo }
 *
 * ⚠ <b>이 호출은 서버 작업본을 실제로 바꾼다</b>(조회가 아니다). 화면은 실행 전에 그 사실과 미저장
 * 편집이 사라진다는 것을 알리고 확인을 받아야 한다.
 *
 * 거부 코드: 인가 401/403 → 없는 회차 404 → 비식별 신고 412 → 작업락·중복 실행 409 →
 * 프레임 수 상한 초과 400. 판정은 모두 BE 가 하며 FE 는 단순 전달이다.
 *
 * @design D4
 * @design D5
 * @req R6
 */
export function applyStartVersion(
  rawSn: number,
  versionNo: number,
): Promise<StartVersionApplyResult> {
  return apiClient
    .put<StartVersionApplyResult>(`/videos/${rawSn}/start-version`, { versionNo })
    .then((r) => r.data);
}

/**
 * E2E 테스트 데이터 fixture.
 *
 * 보안: 실제 시크릿/계정 사용 금지. BE {@code /v1/dev/tokens} 가 발급하는 테스트 JWT 만 사용.
 * (운영 prd 환경에서는 endpoint 자체가 비활성화되어 본 fixture 가 동작하지 않는다 — 안전 기본값.)
 *
 * userNo 는 LS_ACNT_USER / LS_TASK_ASSIGNMENT 시드 기준이다.
 * ⚠ **영상(rawSn)·프레임(srcSn) 은 하드코딩하지 않는다** — 시드가 재적재되면 PK 가 통째로 바뀌어
 * 스펙 전체가 404 로 죽는다(H-ISSUE-143 실사고: rawSn=9035 / srcSn=241 는 현 스택에 존재하지 않았고
 * describe.serial 이라 이후 3건이 연쇄 스킵됐다). 실행 시점에 API 로 해석한다
 * ({@link resolveWorkflowFixture}).
 */

import type { APIRequestContext } from '@playwright/test';

import { beCall, withApiContext, issueDevToken } from './be-client';

export const TEST_USERS = {
  reviewer: {
    userNo: '1001',
    sub: '1001',
    name: '검수자김',
    role: 'REVIEWER' as const,
    channel: 'INTERNAL' as const,
  },
  worker: {
    userNo: '1003',
    sub: '1003',
    name: '작업자이',
    role: 'WORKER' as const,
    channel: 'INTERNAL' as const,
  },
  /**
   * LS_TASK_ASSIGNMENT 에 LABELER 로 실제 배정이 존재하는 작업자(userNo=2001).
   * 전체 워크플로우 E2E(labeling-review-full-flow.spec.ts)에서 WORKER 역할로 사용.
   */
  labeler: {
    userNo: '2001',
    sub: '2001',
    name: '라벨작업자',
    role: 'WORKER' as const,
    channel: 'INTERNAL' as const,
  },
  portalUser: {
    userNo: '2001',
    sub: '2001',
    name: '포털사용자',
    role: 'PORTAL_USER' as const,
    channel: 'PORTAL' as const,
  },
};

/** 시드 데이터의 srcSn 1~5 에 라벨 존재 — 라벨링 진입 테스트는 1 사용. */
export const TEST_VIDEO_WITH_LABEL = 1;

// ---------------------------------------------------------------------------
// 전체 워크플로우(TC-E2E-016~019) 픽스처 — 실행 시점 동적 해석
// ---------------------------------------------------------------------------

/** 해석된 워크플로우 대상. videoId=LS_DATA_RAW.RAW_SN, srcSn=LS_DATA_SRC.SRC_SN(첫 프레임). */
export interface WorkflowFixture {
  videoId: number;
  srcSn: number;
}

/** 검수 상태 머신상 WORKER 가 제출(→PENDING)까지 도달할 수 있는 출발 상태. */
const RESOLVABLE_STATUSES = ['ASSIGNED', 'REJECTED', 'APPROVED', 'PENDING', 'IN_REVIEW'] as const;

/** 정규화 비용이 적은 상태를 앞에 둔다 (ASSIGNED 는 그대로 사용 가능). */
const STATUS_RANK: Record<string, number> = {
  ASSIGNED: 0,
  REJECTED: 1,
  PENDING: 2,
  IN_REVIEW: 3,
  APPROVED: 4,
};

/** 롤백 단계가 요구하는 최소 커밋(LS_LABEL_VERSION) 수 — 최신 1건 + 롤백 대상 1건. */
const REQUIRED_VERSIONS = 2;

interface AssignmentItem {
  videoId: number;
  taskTypeCd: string;
  firstSrcSn: number | null;
}

interface VersionItem {
  commitSha: string;
}

interface Tokens {
  worker: string;
  reviewer: string;
}

/**
 * 전체 워크플로우 E2E 가 사용할 (영상, 프레임) 을 실행 시점에 해석하고, 워크플로우가 완주할 수 있는
 * 사전조건까지 API 로 갖춘다.
 *
 * <p>왜 동적인가: 대상은 "특정 PK" 가 아니라 **조건을 만족하는 아무 영상 1건**이다. 시드가 바뀌어도
 * 조건만 만족하면 스펙이 그대로 통과해야 한다.
 *
 * <p>해석 절차 (모두 공개 API — 테스트 전용 백도어 없음):
 * <ol>
 *   <li>작업자 본인 배정 목록(`GET /v1/assignments`)에서 LABELER 배정 + 프레임 보유(firstSrcSn) 후보 수집</li>
 *   <li>후보별로 검수 상태(`GET /v1/reviews/{videoId}`)와 라벨 조회 가능 여부
 *       (`GET /v1/frames/{srcSn}/labels`)를 확인 — 비식별 신고 구간(412)·미보유 영상은 탈락</li>
 *   <li>커밋 이력이 이미 충분한 후보를 우선 선택 (부수효과 최소화)</li>
 *   <li>선택 영상을 제출 가능한 상태로 정규화 (PENDING→취소, IN_REVIEW→반려)</li>
 *   <li>롤백 단계용 커밋이 부족하면 승인 사이클(저장→제출→시작→승인)을 돌려 스냅샷을 적층</li>
 * </ol>
 *
 * <p>버전 스냅샷은 **검수 승인 시점에만** 생성되므로(SFR-08 / CLAUDE.md 2계층), 롤백을 검증하려면
 * 승인 사이클로 커밋을 쌓는 것 외에 방법이 없다. 이미 충분하면 아무 것도 하지 않는다(멱등).
 */
export async function resolveWorkflowFixture(): Promise<WorkflowFixture> {
  const tokens: Tokens = {
    worker: await issueDevToken(TEST_USERS.labeler),
    reviewer: await issueDevToken(TEST_USERS.reviewer),
  };

  return withApiContext(async (ctx) => {
    const chosen = await pickCandidate(ctx, tokens);
    await normalizeToSubmittable(ctx, tokens, chosen);
    await ensureRollbackableVersions(ctx, tokens, chosen);
    return chosen;
  });
}

/** 조건을 만족하는 첫 후보를 고른다. 하나도 없으면 원인을 알 수 있는 메시지로 실패시킨다. */
async function pickCandidate(ctx: APIRequestContext, tokens: Tokens): Promise<WorkflowFixture> {
  const res = await beCall<{ content?: AssignmentItem[] }>(
    ctx,
    'get',
    '/v1/assignments?page=0&size=100',
    tokens.worker,
  );
  if (res.status !== 200) {
    throw new Error(`배정 목록 조회 실패 (status=${res.status})`);
  }
  const rows = (res.data?.content ?? []).filter(
    (i) => i.taskTypeCd === 'LABELER' && i.firstSrcSn != null && i.videoId != null,
  );

  const scored: Array<{ fixture: WorkflowFixture; rank: number; versions: number }> = [];
  for (const row of rows) {
    const fixture: WorkflowFixture = { videoId: row.videoId, srcSn: row.firstSrcSn as number };

    const review = await beCall<{ dataSttsCd?: string }>(
      ctx,
      'get',
      `/v1/reviews/${fixture.videoId}`,
      tokens.worker,
    );
    const status = review.data?.dataSttsCd;
    if (review.status !== 200 || !status) continue;
    if (!RESOLVABLE_STATUSES.includes(status as (typeof RESOLVABLE_STATUSES)[number])) continue;

    // 비식별 신고 구간(412)·권한 문제(403)·프레임 부재(404) 는 여기서 걸러진다.
    const labels = await beCall(ctx, 'get', `/v1/frames/${fixture.srcSn}/labels`, tokens.worker);
    if (labels.status !== 200) continue;

    scored.push({
      fixture,
      rank: STATUS_RANK[status] ?? 99,
      versions: await countVersions(ctx, tokens, fixture),
    });
  }

  if (scored.length === 0) {
    throw new Error(
      '전체 워크플로우 E2E 조건을 만족하는 영상이 없습니다 — ' +
        `작업자(userNo=${TEST_USERS.labeler.userNo}) 에게 프레임을 보유한 LABELER 배정이 필요합니다.`,
    );
  }

  // 커밋이 이미 충분한 후보 우선(승인 사이클 부수효과 회피) → 그다음 정규화 비용이 낮은 상태 순.
  scored.sort((a, b) => {
    const aReady = a.versions >= REQUIRED_VERSIONS ? 0 : 1;
    const bReady = b.versions >= REQUIRED_VERSIONS ? 0 : 1;
    return aReady - bReady || a.rank - b.rank || a.fixture.videoId - b.fixture.videoId;
  });
  return scored[0].fixture;
}

async function currentStatus(
  ctx: APIRequestContext,
  tokens: Tokens,
  f: WorkflowFixture,
): Promise<string> {
  const res = await beCall<{ dataSttsCd?: string }>(
    ctx,
    'get',
    `/v1/reviews/${f.videoId}`,
    tokens.worker,
  );
  const status = res.data?.dataSttsCd;
  if (res.status !== 200 || !status) {
    throw new Error(`검수 상태 조회 실패 videoId=${f.videoId} status=${res.status}`);
  }
  return status;
}

async function countVersions(
  ctx: APIRequestContext,
  tokens: Tokens,
  f: WorkflowFixture,
): Promise<number> {
  const res = await beCall<VersionItem[]>(
    ctx,
    'get',
    `/v1/frames/${f.srcSn}/versions`,
    tokens.worker,
  );
  return res.status === 200 && Array.isArray(res.data) ? res.data.length : 0;
}

/**
 * 상태 머신을 따라 "WORKER 가 검수 제출할 수 있는 상태"로 되돌린다.
 * (ASSIGNED / REJECTED / APPROVED 는 모두 → PENDING 제출이 허용되므로 그대로 둔다.)
 */
async function normalizeToSubmittable(
  ctx: APIRequestContext,
  tokens: Tokens,
  f: WorkflowFixture,
): Promise<void> {
  for (let i = 0; i < 6; i++) {
    const status = await currentStatus(ctx, tokens, f);
    if (status === 'ASSIGNED' || status === 'REJECTED' || status === 'APPROVED') return;
    if (status === 'PENDING') {
      await expectOk(
        beCall(ctx, 'post', `/v1/reviews/${f.videoId}/cancel-submit`, tokens.worker),
        '검수 제출 취소',
      );
    } else if (status === 'IN_REVIEW') {
      await expectOk(
        beCall(ctx, 'post', `/v1/reviews/${f.videoId}/reject`, tokens.reviewer, {
          reason: 'E2E 픽스처 정규화 — 워크플로 시작 상태로 되돌립니다.',
        }),
        '검수 반려',
      );
    } else {
      throw new Error(`정규화 불가 상태 videoId=${f.videoId} status=${status}`);
    }
  }
  throw new Error(`검수 상태 정규화 실패 videoId=${f.videoId}`);
}

/**
 * 롤백 단계가 성립하려면 프레임에 커밋이 2건 이상 있어야 한다(최신 + 롤백 대상).
 * 부족하면 저장→제출→검수시작→승인 사이클로 스냅샷을 적층한다.
 *
 * <p>동일 페이로드는 같은 VERSION_HASH 라 새 커밋이 만들어지지 않으므로 매 사이클 좌표를 바꾼다.
 */
async function ensureRollbackableVersions(
  ctx: APIRequestContext,
  tokens: Tokens,
  f: WorkflowFixture,
): Promise<void> {
  for (let attempt = 0; attempt < 3; attempt++) {
    if ((await countVersions(ctx, tokens, f)) >= REQUIRED_VERSIONS) return;

    // 좌표는 프레임 경계 안쪽 작은 박스. 실행마다 달라야 새 스냅샷이 적층된다.
    const edge = 100 + ((Date.now() + attempt * 13) % 60);
    await expectOk(
      beCall(ctx, 'put', `/v1/frames/${f.srcSn}/labels`, tokens.worker, {
        items: [
          {
            id: null,
            lblTypeCd: 'BBOX',
            label: 'e2e-fixture',
            points: [
              [10, 10],
              [edge, edge],
            ],
          },
        ],
      }),
      '라벨 저장',
    );
    await expectOk(
      beCall(ctx, 'post', `/v1/reviews/${f.videoId}/submit`, tokens.worker),
      '검수 제출',
    );
    await expectOk(
      beCall(ctx, 'post', `/v1/reviews/${f.videoId}/start`, tokens.reviewer),
      '검수 시작',
    );
    await expectOk(
      beCall(ctx, 'post', `/v1/reviews/${f.videoId}/approve`, tokens.reviewer, {}),
      '검수 승인',
    );
    await normalizeToSubmittable(ctx, tokens, f);
  }

  if ((await countVersions(ctx, tokens, f)) < REQUIRED_VERSIONS) {
    throw new Error(`롤백 대상 커밋 확보 실패 srcSn=${f.srcSn}`);
  }
}

/** 실패 시 상태코드/errorCode 만 노출한다 — 응답 본문(라벨·PII)은 로그에 남기지 않는다. */
async function expectOk(p: Promise<{ status: number; errorCode?: string }>, what: string) {
  const res = await p;
  if (res.status < 200 || res.status >= 300) {
    throw new Error(`${what} 실패 (status=${res.status}, errorCode=${res.errorCode ?? '-'})`);
  }
  return res;
}

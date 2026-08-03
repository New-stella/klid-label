import { request as pwRequest, type APIRequestContext } from '@playwright/test';

/**
 * E2E 전용 BE API 클라이언트.
 *
 * <p>{@link auth.fixture} 의 dev 토큰 발급과 {@link test-data} 의 워크플로 픽스처 해석이
 * 같은 BE 주소/토큰 발급 경로를 공유하므로 한 곳으로 모은다.
 *
 * <p>보안:
 * <ul>
 *   <li>실제 시크릿/계정을 쓰지 않는다 — BE {@code /v1/dev/tokens} 는 prd 에서 비활성화된다.</li>
 *   <li>토큰은 매 호출마다 신규 발급한다. 발급·호출 코드 자체는 토큰을 파일/로그에 기록하지 않지만,
 *       {@link auth.fixture} 의 ingress 진입이 쿼리스트링(`/ingress?token=...`)이라 테스트 실패·재시도
 *       시 Playwright 트레이스/스크린샷 아티팩트에 URL 형태로 남을 수 있다
 *       (만료 1h · dev 전용 엔드포인트 · 테스트 계정 → 잔여 위험 낮음).</li>
 *   <li>대상 BE 는 루프백만 허용한다 — 아래 {@link assertLocalBeTarget} 참조.</li>
 * </ul>
 */

/** 루프백만 허용한다. WHATWG URL 은 IPv6 호스트를 대괄호째 돌려주므로 그 형태도 함께 둔다. */
const LOOPBACK_HOSTS = ['127.0.0.1', 'localhost', '::1', '[::1]'];

/**
 * 픽스처가 대상 BE 를 파괴적으로 변경하므로(제출 취소·반려·라벨 덮어쓰기·검수 승인 →
 * 관제서버 `TASK_COMPLETED` outbound 통지) 대상이 로컬인지 먼저 확인한다.
 * `E2E_BE_URL` 오설정으로 stg/prd 를 가리키면 실데이터 오염 + 허위 완료 통지가 나간다.
 *
 * <p>원격 실행이 의도된 경우에만 `E2E_ALLOW_REMOTE=1` 로 명시 해제한다(빈값은 해제로 보지 않는다).
 *
 * @throws 대상이 루프백이 아니거나 URL 형식이 아니면 예외 (fail-closed)
 */
export function assertLocalBeTarget(base: string): void {
  let host: string;
  try {
    host = new URL(base).hostname.toLowerCase();
  } catch {
    throw new Error('E2E_BE_URL 이 올바른 URL 이 아닙니다 (예: http://127.0.0.1:8080)');
  }
  if (LOOPBACK_HOSTS.includes(host)) return;
  if (process.env.E2E_ALLOW_REMOTE) return;
  throw new Error(
    `E2E 픽스처는 대상 BE 의 실데이터를 변경한다 — 원격 대상 금지: host=${host} ` +
      `(원격 실행이 의도된 경우 E2E_ALLOW_REMOTE=1 설정)`,
  );
}

export const BE_BASE = process.env.E2E_BE_URL || 'http://127.0.0.1:8080';

// 모듈 로드 시점에 fail-closed — 토큰 발급을 포함한 모든 호출 경로보다 앞선다.
assertLocalBeTarget(BE_BASE);

export interface DevTokenClaims {
  role: 'REVIEWER' | 'WORKER' | 'PORTAL_USER';
  channel: 'INTERNAL' | 'PORTAL';
  userNo: string;
  name?: string;
}

/** BE 가 서명한 테스트 JWT 를 발급받는다. */
export async function issueDevToken(claims: DevTokenClaims): Promise<string> {
  const ctx = await pwRequest.newContext();
  try {
    const res = await ctx.post(`${BE_BASE}/api/v1/dev/tokens`, {
      data: {
        role: claims.role,
        channel: claims.channel,
        userNo: claims.userNo,
        name: claims.name,
        expSeconds: 3600,
      },
    });
    if (!res.ok()) {
      throw new Error(`dev token 발급 실패: ${res.status()} ${await res.text()}`);
    }
    const body = (await res.json()) as { data?: { token?: string } };
    if (!body.data?.token) {
      throw new Error('dev token 응답에 token 없음');
    }
    return body.data.token;
  } finally {
    await ctx.dispose();
  }
}

export interface BeResult<T = unknown> {
  status: number;
  /** ApiResponse<T> 의 data. 응답이 JSON 이 아니거나 실패면 undefined. */
  data?: T;
  errorCode?: string;
}

/**
 * BE 호출 (context-path `/api` 자동 부착). 상태코드를 예외로 바꾸지 않는다 —
 * 픽스처 해석은 404/403/412 를 "후보 부적합" 신호로 그대로 활용한다.
 */
export async function beCall<T = unknown>(
  ctx: APIRequestContext,
  method: 'get' | 'post' | 'put',
  path: string,
  token: string,
  body?: unknown,
): Promise<BeResult<T>> {
  const res = await ctx[method](`${BE_BASE}/api${path}`, {
    headers: { Authorization: `Bearer ${token}` },
    ...(body === undefined ? {} : { data: body }),
  });
  let parsed: { data?: T; errorCode?: string } | undefined;
  try {
    parsed = (await res.json()) as { data?: T; errorCode?: string };
  } catch {
    parsed = undefined;
  }
  return { status: res.status(), data: parsed?.data, errorCode: parsed?.errorCode };
}

/** API 전용 컨텍스트를 만들어 콜백에 넘기고 반드시 dispose 한다. */
export async function withApiContext<T>(
  fn: (ctx: APIRequestContext) => Promise<T>,
): Promise<T> {
  const ctx = await pwRequest.newContext();
  try {
    return await fn(ctx);
  } finally {
    await ctx.dispose();
  }
}

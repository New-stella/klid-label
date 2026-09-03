import { readFileSync } from 'node:fs';
import path from 'node:path';

import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RouteObject } from 'react-router-dom';

// [@design INT-013]
/**
 * 라우트를 채널로 가른다 — **반대 채널 화면이 산출물에 섞이지 않는다**의 라우팅 축 회귀 가드.
 *
 * 설계는 채널을 가르는 지점을 「진입점 · 라우팅 · 셸」 셋으로 규정한다. 진입점(기준 경로)과
 * 셸(자체 머리 영역)은 이미 갈려 있었고 **라우팅만 남아 있었다** — 두 채널 빌드가 서로의 화면을
 * 전부 싣고 있었다.
 *
 * ## 이 파일이 전용인 이유
 *
 * 채널은 **모듈이 처음 평가될 때 굳는 상수**로 갈린다(`IS_PORTAL_CHANNEL_BUILD`). 한 파일에서
 * 값을 바꿔 가며 재평가하면 라우트 표가 회차에 따라 달라져 케이스끼리 오염된다. 그래서
 * **케이스마다 `vi.resetModules()` 후 동적 import** 로 프로덕션 라우트 표를 다시 만든다.
 *
 * ★ **프로덕션 라우트 표를 실제로 읽는다.** 시험이 자체 라우터 트리를 새로 조립하면 프로덕션에서
 *   그 경로를 다시 열어도 통과한다 — 지키려는 것이 배선인데 배선을 안 읽는 꼴이다.
 *
 * ⚠ 이 가드는 **라우팅(이동 가능 여부)** 축이다. 「번들에서 빠졌는가」는 소스 배치로 지킬 수 없고
 *   운영 빌드를 떠서 재는 수밖에 없다 — 그쪽 근거는 회수 보고의 실측표다. 둘은 서로를 대체하지
 *   않는다: 라우트만 가르고 접기가 안 되면 이동은 막혀도 코드는 남는다.
 */

/** 라우트 트리를 전체 경로 문자열 집합으로 편다 — 중첩 자식까지 본다. */
function flattenPaths(routes: readonly RouteObject[], parent = ''): string[] {
  const out: string[] = [];
  for (const route of routes) {
    const raw = route.path ?? '';
    const full = raw.startsWith('/')
      ? raw
      : raw === ''
        ? parent
        : `${parent.replace(/\/$/, '')}/${raw}`;
    if (route.path !== undefined) out.push(full);
    if (route.children) out.push(...flattenPaths(route.children, full));
  }
  return out;
}

async function loadRoutes(channel: string): Promise<string[]> {
  vi.resetModules();
  vi.stubEnv('VITE_BUILD_CHANNEL', channel);
  const mod = await import('@/router');
  return flattenPaths(mod.router.routes);
}

/**
 * 포털 채널 산출물이 가져야 할 경로.
 *
 * ⚠ 맨 아래 것은 **화면이 아니다** — 폐기된 업로드 자산 라벨링 주소이고, 통합 라벨링 화면
 *   (`/portal/label/:id`)으로 갈아타는 호환 조각이 그 자리를 지킨다. 이미 나가 있는 주소로
 *   들어온 사용자가 막히지 않게 남긴 것이므로 여기서 **빠지면 안 되지만**, 이 목록에 있다는
 *   이유로 그 자리에 화면을 되살리지 말 것(포털의 라벨링 화면은 하나뿐이다).
 */
const PORTAL_SCREENS = [
  '/portal',
  '/portal/label/:id',
  '/portal/uploads',
  // 증강 — 본문 상단 이동 탭의 목적지다. 여기가 비면 탭을 누른 사용자가 못 찾은 주소로 떨어진다.
  '/portal/augment',
  // 업로드 영상 마킹 — 목적지가 아니라 목록 행에서 들어가는 화면이지만, 주소로 직접 들어올 수
  // 있어야 하므로 산출물에 반드시 있어야 한다(이동 탭에 없는 것과는 다른 축이다).
  '/portal/uploads/:uldSn/marking',
  '/portal/uploads/:uldSn/label',
];

/** 내부(관제) 채널 산출물이 가져야 할 화면 — 전수가 아니라 축마다 하나씩 고른 표본이다. */
const INTERNAL_SCREENS = [
  '/label/:id',
  '/role-claim',
  '/dashboard',
  '/video/status',
  '/task',
  '/marking/:rawSn',
  '/review',
  '/stat/worker',
  '/augment/request',
  '/manage/settings',
  '/notice',
  '/admin',
  '/admin/users',
  '/admin/endpoints',
];

/** 두 채널 모두에 있어야 하는 공용 진입 경로. */
const SHARED_ENTRIES = ['/ingress', '/forbidden', '/dev/login'];

describe('라우트 채널 분기', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  // 스캐너 자신에 대한 가드가 먼저다 — 경로를 못 펴면 그 뒤 「없다」 단언이 전부 공짜로 통과한다.
  it('라우트_표를_실제로_읽고_중첩_자식까지_편다', async () => {
    const control = await loadRoutes('control');

    expect(control.length).toBeGreaterThan(30);
    // 중첩 자식이 부모 경로와 이어져야 한다(평탄화가 깨지면 아래 단언이 의미를 잃는다).
    expect(control).toContain('/admin/endpoints');
    expect(control).toContain('/manage/settings');
  });

  describe('관제 채널 산출물', () => {
    it('★포털_화면이_하나도_없다', async () => {
      const paths = await loadRoutes('control');

      expect(paths.filter((p) => PORTAL_SCREENS.includes(p))).toEqual([]);
      // 「없다」만 보면 다른 이름으로 되살아나도 통과한다 — 접두 자체를 함께 막는다.
      expect(paths.filter((p) => p.startsWith('/portal'))).toEqual([]);
    });

    it('★내부_화면은_그대로_있다_제거_축과_존치_축을_짝으로_본다', async () => {
      const paths = await loadRoutes('control');

      for (const screen of INTERNAL_SCREENS) expect(paths).toContain(screen);
    });

    it('채널_미지정_빌드도_관제와_같다_기존_산출물_무영향', async () => {
      const paths = await loadRoutes('');

      expect(paths.filter((p) => p.startsWith('/portal'))).toEqual([]);
      for (const screen of INTERNAL_SCREENS) expect(paths).toContain(screen);
    });
  });

  describe('포털 채널 산출물', () => {
    it('★포털_화면이_모두_있다', async () => {
      const paths = await loadRoutes('portal');

      for (const screen of PORTAL_SCREENS) expect(paths).toContain(screen);
    });

    it('★내부_화면이_하나도_없다', async () => {
      const paths = await loadRoutes('portal');

      const leaked = paths.filter((p) => INTERNAL_SCREENS.includes(p));
      expect(leaked).toEqual([]);
      // 관리자·관리 화면은 접두째 없어야 한다(개별 열거는 새 화면이 늘면 눈이 먼다).
      expect(paths.filter((p) => p.startsWith('/admin'))).toEqual([]);
      expect(paths.filter((p) => p.startsWith('/manage'))).toEqual([]);
    });

    /**
     * ★ `/role-claim` 이 내부 전용인 근거는 **이동 지점이 하나뿐이고 그것이 내부 채널 전용**이라는
     *   것이다 — `router/guards.tsx` 의 `if (!claims.role && claims.channel === 'INTERNAL')`.
     *   포털 채널은 그 자리에서 이동 대신 제자리 안내를 그리고, 포털 사용자는 발급 시점에 역할이
     *   있어 이 흐름 자체를 타지 않는다. 추정이 아니라 확인한 근거다.
     */
    it('역할_부여_화면은_내부_채널_전용이다', async () => {
      expect(await loadRoutes('portal')).not.toContain('/role-claim');
      expect(await loadRoutes('control')).toContain('/role-claim');
    });

    it('★뿌리와_못찾은_주소를_받을_자리가_있다_내부_트리가_받던_몫이다', async () => {
      const paths = await loadRoutes('portal');

      // Host 는 마운트 경로의 뿌리에 우리를 얹는다 — 그 자리가 비면 뿌리 진입이 아무 데도 안 걸린다.
      expect(paths).toContain('/');
      // 못 찾은 주소는 관제 채널에서 내부 트리 안의 `*` 가 받았다. 그 트리가 없으니 여기 있어야 한다.
      // (평탄화 결과의 최상위 splat 표기는 `/*` 다.)
      expect(paths).toContain('/*');
    });
  });

  describe('공용 진입 경로 — 양쪽에 남는다', () => {
    it('★두_채널_모두_진입_페이지와_오류_화면과_개발용_로그인을_갖는다', async () => {
      const control = await loadRoutes('control');
      const portal = await loadRoutes('portal');

      for (const shared of SHARED_ENTRIES) {
        expect(control).toContain(shared);
        expect(portal).toContain(shared);
      }
    });

    /**
     * ★ 진입 페이지는 포털 채널 복구 동선의 경유지다 — 뿌리 진입 보정이 여기로 보내고, 이 화면이
     *   채널 클레임으로 갈라 포털 홈 또는 개발용 로그인으로 보낸다. 여기서 빠지면 그 동선이
     *   통째로 끊긴다(단독 구동에서 화면에 닿을 방법이 사라진다).
     */
    it('★포털_복구_동선의_경유지_두_곳이_포털_산출물에_함께_있다', async () => {
      const portal = await loadRoutes('portal');

      expect(portal).toContain('/ingress');
      expect(portal).toContain('/dev/login');
      expect(portal).toContain('/portal');
    });
  });

  // ───────────────────────────────────────────────────────────────────────────
  // 접기 축 — 라우팅 시험만으로는 지켜지지 않는 것들
  //
  // 아래 둘은 **라우트 표에 드러나지 않는다.** 앞의 것은 판정 형태를 실행 중 형태로 바꿔도 라우트
  // 표가 똑같이 갈려 전건 초록이고(그런데 번들에는 반대 채널 코드가 그대로 남는다), 뒤의 것은 그
  // 화면이 어차피 내부 트리 **안**에 있어 포털 표에 안 나타난다(그런데 화면 청크는 실려 나갔다 —
  // 실측으로 확인한 실제 누출이다). 그래서 소스 배치를 값 축으로 못 박는다.
  // ───────────────────────────────────────────────────────────────────────────
  describe('산출 시점에 접히는 형태를 쓴다', () => {
    /**
     * 주석을 걷어낸 코드 본문만 본다.
     *
     * 이 저장소는 주석에 결정 근거를 싣는다 — 라우터의 문서 주석은 「왜 실행 중 형태를 쓰지
     * 않는가」를 설명하느라 그 이름을 반드시 포함한다. 원문 그대로 훑으면 그 설명이 위반으로
     * 잡혀 결국 설명을 지우는 쪽으로 압력이 간다(`remote/__tests__/remoteEntrypointGuard` 가
     * 같은 이유로 같은 처리를 한다).
     */
    function codeOf(file: string): string {
      return readFileSync(file, 'utf-8')
        .split('\n')
        .filter((line) => {
          const t = line.trim();
          return !(t.startsWith('//') || t.startsWith('*') || t.startsWith('/*'));
        })
        .join('\n');
    }

    const ROUTER_SOURCE = codeOf(path.resolve(__dirname, '../index.tsx'));

    it('주석에_적힌_설명은_위반으로_잡지_않는다_스캐너_자기_가드', () => {
      // 스캐너가 조용히 눈이 멀면 아래 두 가드는 공짜로 통과한다.
      expect(ROUTER_SOURCE.length).toBeGreaterThan(1000);
      expect(ROUTER_SOURCE).toContain('createBrowserRouter');
      expect(ROUTER_SOURCE).not.toContain('설명한다면 여기 걸려야 한다');
    });

    it('★라우트를_가르는_판정은_실행_중_형태가_아니라_산출_시점_형태다', () => {
      // `isPortalEmbedChannel()` 은 함수 호출이라 번들러가 접지 못한다 — 그것으로 가르면
      // 이동은 막히지만 반대 채널 화면 코드가 산출물에 그대로 남는다.
      expect(ROUTER_SOURCE).toContain('IS_PORTAL_CHANNEL_BUILD');
      expect(ROUTER_SOURCE).not.toContain('isPortalEmbedChannel(');
    });

    it('★내부_채널_전용_개발_화면도_채널_축을_앞에_둔다', () => {
      // `isDevUploadEnabled()` 는 실행 중 판정이라 접히지 않는다. 채널 축을 앞에 두지 않으면
      // 그 블록의 지연 로드가 살아남아 관리자 화면 청크가 포털 산출물에 실린다.
      expect(ROUTER_SOURCE).toContain('if (!IS_PORTAL_CHANNEL_BUILD && isDevUploadEnabled())');
    });
  });

  describe('기준 경로와 라우트 분기는 같은 축으로 움직인다', () => {
    it('★관제는_기준경로_없음_+_포털화면_없음_이_짝이다', async () => {
      vi.resetModules();
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
      const { resolveRouterBasename } = await import('@/lib/remoteMount');
      const paths = flattenPaths((await import('@/router')).router.routes);

      expect(resolveRouterBasename()).toBeUndefined();
      expect(paths.filter((p) => p.startsWith('/portal'))).toEqual([]);
    });

    it('★포털은_기준경로_있음_+_내부화면_없음_이_짝이다', async () => {
      vi.resetModules();
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      const { resolveRouterBasename, PORTAL_MOUNT_BASENAME } = await import('@/lib/remoteMount');
      const paths = flattenPaths((await import('@/router')).router.routes);

      expect(resolveRouterBasename()).toBe(PORTAL_MOUNT_BASENAME);
      expect(paths.filter((p) => p.startsWith('/admin'))).toEqual([]);
    });
  });
});

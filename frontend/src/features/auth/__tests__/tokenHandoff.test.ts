import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  BEARER_PREFIX,
  HOST_HANDOFF_METHOD_NAMES,
  INTERNAL_AUTH_HEADER,
  PORTAL_ACCESS_TOKEN_HEADER,
  buildAuthHeader,
  clearHostTokenHandoff,
  getAccessToken,
  internalTokenHandoff,
  registerHostTokenHandoff,
  resolveAuthHeaderName,
  resolveTokenHandoff,
  portalTokenHandoff,
  type TokenHandoffGateway,
} from '@/features/auth/tokenHandoff';
import { useAuthStore } from '@/stores/useAuthStore';

const CONTROL_JWT = 'control-jwt-1';
const LEGACY_JWT = 'legacy-jwt-1';
const HOST_JWT = 'host-jwt-1';
const HOST_JWT_RENEWED = 'host-jwt-2';
const STALE_STORE_JWT = 'stale-store-jwt';

/** Host 가 주입할 창구의 최소 구현 — 토큰 값을 바깥에서 갈아끼울 수 있게 둔다. */
function makeHostGateway(initial: string | null) {
  const state = { value: initial };
  const gateway: TokenHandoffGateway = {
    getAccessToken: () => state.value,
    refresh: async () => state.value,
    onUnauthorized: vi.fn(),
    notifyActivity: vi.fn(),
  };
  return { gateway, state };
}

describe('tokenHandoff — 토큰 인계 창구 어댑터', () => {
  beforeEach(() => {
    clearHostTokenHandoff();
    useAuthStore.setState({ token: null, claims: null });
  });

  afterEach(() => {
    clearHostTokenHandoff();
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  describe('계약 이름 — 값 축으로 고정한다', () => {
    /**
     * ★ 형식 검사(「함수가 네 개 있다」)로는 이 축을 지키지 못한다. 이름 자체가 Host 가 주입하는
     *   객체의 키라, 이름이 바뀌면 **실행 시점에** 창구를 못 찾는다(빌드는 통과한다).
     *   그래서 순서까지 포함해 값으로 못박는다.
     */
    it('창구_이름_4종이_포털이_제시한_계약_그대로다', () => {
      expect([...HOST_HANDOFF_METHOD_NAMES]).toEqual([
        'getAccessToken',
        'refresh',
        'onUnauthorized',
        'notifyActivity',
      ]);
    });

    it('내부_채널_창구가_계약_이름_4종을_빠짐없이_구현한다', () => {
      for (const name of HOST_HANDOFF_METHOD_NAMES) {
        expect(typeof (internalTokenHandoff as unknown as Record<string, unknown>)[name]).toBe(
          'function',
        );
      }
      // 계약 밖의 키를 덧붙이지 않는다 — 덧붙이면 Host 가 그것을 계약으로 오해한다.
      expect(Object.keys(internalTokenHandoff).sort()).toEqual(
        [...HOST_HANDOFF_METHOD_NAMES].sort(),
      );
    });

    it('포털_채널_창구도_같은_이름_4종을_구현한다', () => {
      expect(Object.keys(portalTokenHandoff).sort()).toEqual([...HOST_HANDOFF_METHOD_NAMES].sort());
    });
  });

  describe('내부(관제) 채널 — 지금 동작 그대로', () => {
    it('스토어_값을_그대로_돌려준다', async () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
      useAuthStore.setState({ token: CONTROL_JWT, claims: null });

      expect(resolveTokenHandoff()).toBe(internalTokenHandoff);
      expect(await getAccessToken()).toBe(CONTROL_JWT);
    });

    it('채널_미설정이면_내부_채널로_떨어진다', async () => {
      // 기존 빌드(`VITE_BUILD_CHANNEL` 미설정)가 지금과 똑같이 동작해야 한다.
      vi.stubEnv('VITE_BUILD_CHANNEL', '');
      useAuthStore.setState({ token: LEGACY_JWT, claims: null });

      expect(resolveTokenHandoff()).toBe(internalTokenHandoff);
      expect(await getAccessToken()).toBe(LEGACY_JWT);
    });

    it('Host_창구가_주입돼_있어도_내부_채널은_그것을_쓰지_않는다', async () => {
      // 내부 채널의 진실원은 스토어다. Host 창구가 어쩌다 등록돼도 조달처가 바뀌면 안 된다.
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
      const { gateway } = makeHostGateway(HOST_JWT);
      registerHostTokenHandoff(gateway);
      useAuthStore.setState({ token: CONTROL_JWT, claims: null });

      expect(await getAccessToken()).toBe(CONTROL_JWT);
    });
  });

  describe('포털 채널 — Host 에 매번 다시 묻는다', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    });

    it('Host_창구에서_토큰을_얻는다', async () => {
      const { gateway } = makeHostGateway(HOST_JWT);
      expect(registerHostTokenHandoff(gateway)).toBe(true);

      expect(resolveTokenHandoff()).toBe(portalTokenHandoff);
      expect(await getAccessToken()).toBe(HOST_JWT);
    });

    /**
     * ★★ 이 어댑터의 존재 이유 그 자체다.
     *
     * Host 가 세션을 갱신하면 우리가 앞서 본 값은 **죽은 토큰**이 된다. 값을 스냅샷으로
     * 잡아 두면(모듈 상수·메모이제이션·스토어 복사) 갱신 이후 전 API 가 401 로 떨어지고,
     * 그 파손은 **갱신이 일어날 만큼 오래 머문 뒤에만** 드러나 개발 중에는 조용하다.
     */
    it('★Host가_갱신하면_다음_조회부터_새_값이_나온다_스냅샷을_잡지_않는다', async () => {
      const { gateway, state } = makeHostGateway(HOST_JWT);
      registerHostTokenHandoff(gateway);
      expect(await getAccessToken()).toBe(HOST_JWT);

      state.value = HOST_JWT_RENEWED;

      expect(await getAccessToken()).toBe(HOST_JWT_RENEWED);
    });

    /**
     * ★ 폴백을 두면 「창구가 아직 없다」가 「옛 값으로 조용히 돌아간다」가 되어, 막으려던
     *   결함이 장애 상황에서만 되살아난다. 없으면 없는 것이다(fail-closed).
     */
    it('★Host_창구가_없으면_null이며_스토어_값으로_폴백하지_않는다', async () => {
      useAuthStore.setState({ token: STALE_STORE_JWT, claims: null });

      expect(await getAccessToken()).toBeNull();
    });

    it('창구를_해제하면_다시_null로_돌아간다', async () => {
      const { gateway } = makeHostGateway(HOST_JWT);
      registerHostTokenHandoff(gateway);
      expect(await getAccessToken()).toBe(HOST_JWT);

      clearHostTokenHandoff();

      expect(await getAccessToken()).toBeNull();
    });

    it('갱신_요청은_Host_창구로_위임된다', async () => {
      const { gateway, state } = makeHostGateway(HOST_JWT);
      registerHostTokenHandoff(gateway);
      state.value = HOST_JWT_RENEWED;

      await expect(portalTokenHandoff.refresh()).resolves.toBe(HOST_JWT_RENEWED);
    });

    it('창구가_없을_때_통지_호출은_조용히_무시된다_예외를_던지지_않는다', async () => {
      // 통지는 부가 채널이라, 창구가 없다는 이유로 화면 동작을 깨뜨리면 안 된다.
      expect(() => portalTokenHandoff.onUnauthorized()).not.toThrow();
      expect(() => portalTokenHandoff.notifyActivity()).not.toThrow();
      await expect(portalTokenHandoff.refresh()).resolves.toBeNull();
    });

    it('통지_창구는_Host로_위임된다', () => {
      const { gateway } = makeHostGateway(HOST_JWT);
      registerHostTokenHandoff(gateway);

      portalTokenHandoff.onUnauthorized();
      portalTokenHandoff.notifyActivity();

      expect(gateway.onUnauthorized).toHaveBeenCalledTimes(1);
      expect(gateway.notifyActivity).toHaveBeenCalledTimes(1);
    });
  });

  /**
   * [@design INT-013] 「토큰을 어느 헤더에 어떤 모양으로 싣는가」 축.
   *
   * 조달 경로(위)와 **짝**이다 — 조달이 옳아도 싣는 자리가 틀리면 서버가 토큰을 못 찾는다.
   * 그리고 그 실패는 빌드가 아니라 런타임 401 로만 드러난다.
   */
  describe('인증 헤더 계약 — 값 축으로 고정한다', () => {
    const HEADER_JWT = 'jwt-for-header';

    it('계약값이_포털이_정한_이름_그대로다', () => {
      // 형식 검사(「문자열이다」)로는 이 축을 못 지킨다 — 이름 자체가 계약이다.
      expect(PORTAL_ACCESS_TOKEN_HEADER).toBe('x-access-token');
      expect(INTERNAL_AUTH_HEADER).toBe('Authorization');
      expect(BEARER_PREFIX).toBe('Bearer ');
    });

    it('내부_채널은_Authorization_Bearer_그대로다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');

      expect(buildAuthHeader(HEADER_JWT)).toEqual({
        name: 'Authorization',
        value: `Bearer ${HEADER_JWT}`,
      });
    });

    it('채널_미설정도_내부_채널과_같다_기존_빌드_동작_보존', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', '');

      expect(buildAuthHeader(HEADER_JWT)).toEqual({
        name: 'Authorization',
        value: `Bearer ${HEADER_JWT}`,
      });
    });

    it('포털_채널은_전용_헤더에_토큰_값만_싣는다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');

      expect(buildAuthHeader(HEADER_JWT)).toEqual({
        name: 'x-access-token',
        value: HEADER_JWT,
      });
    });

    /**
     * ★ 서버가 **거부로 강제**하는 축이다. `Authorization` 값을 그대로 복사해 넣는 구현이
     *   정확히 이 변이이며, 모양이 비슷해 눈으로는 지나간다.
     *
     * ⚠ 거부 사유는 둘로 갈린다 — 전용 헤더 **단독**에 접두가 붙으면 그 문자열 **전체가 토큰**이
     *   되어 **서명 파싱에서 거부**(401), `Authorization` 과 **함께** 오면 **값 충돌**로 거부(401).
     *   둘 다 401 이지만 사유가 다르므로 「하나만 보내면 접두는 무해하다」로 읽지 말 것.
     *   근거: 백엔드 `JwtFilterPortalHeaderIngressTest` 의 두 케이스.
     */
    it('★포털_채널_전용_헤더_값에_Bearer_접두가_붙지_않는다', async () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');

      const { value } = buildAuthHeader(HEADER_JWT);

      expect(value.startsWith(BEARER_PREFIX)).toBe(false);
      expect(value).not.toContain('Bearer');
      expect(value).toBe(HEADER_JWT);
    });

    /**
     * ★ 이름을 두 곳에 따로 적으면 채널 전환 시 한쪽만 갱신돼도 컴파일·타입이 통과한다.
     *   그 어긋남은 401 재시도 판정이 영영 발동하지 않는 형태로만 드러난다.
     */
    it.each(['control', 'portal', ''])(
      '★되읽기용_이름과_조립된_헤더_이름이_같다_채널(%s)',
      (channel) => {
        vi.stubEnv('VITE_BUILD_CHANNEL', channel);

        expect(resolveAuthHeaderName()).toBe(buildAuthHeader(HEADER_JWT).name);
      },
    );

    it.each(['control', 'portal', ''])('한_채널이_고르는_헤더는_하나뿐이다_채널(%s)', (channel) => {
      vi.stubEnv('VITE_BUILD_CHANNEL', channel);

      // 두 헤더를 함께 실으면 서버가 값 충돌로 401 을 낸다 — 조립기는 한 벌만 돌려준다.
      const header = buildAuthHeader(HEADER_JWT);
      expect([PORTAL_ACCESS_TOKEN_HEADER, INTERNAL_AUTH_HEADER]).toContain(header.name);
      expect(Object.keys(header).sort()).toEqual(['name', 'value']);
    });
  });

  describe('반환 형태 — Host 가 동기로 주든 Promise 로 주든 같은 모양이 된다', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    });

    /**
     * ★ **포털 Host 의 실제 구현이 이 모양이다.** 갱신이 오가는 «동안» 옛 토큰을 넘기지
     *   않으려면 갱신 큐에 붙어 기다려야 하므로 동기로는 만들 수 없다(포털 인계문서 §3-1).
     *   기다리지 않으면 `Promise` 객체가 토큰 자리에 들어가 JWT 해독의 `token.split('.')` 이
     *   `e.split is not a function` 으로 죽는다 — 2026-09-10 실측으로 확인된 형태다.
     */
    it('★Host가_Promise를_돌려줘도_문자열_토큰이_나온다', async () => {
      registerHostTokenHandoff({
        getAccessToken: async () => HOST_JWT,
        refresh: async () => HOST_JWT,
        onUnauthorized: vi.fn(),
        notifyActivity: vi.fn(),
      });

      const token = await getAccessToken();
      expect(typeof token).toBe('string');
      expect(token).toBe(HOST_JWT);
    });

    it('Host가_동기로_돌려줘도_그대로_통한다', async () => {
      const { gateway } = makeHostGateway(HOST_JWT);
      registerHostTokenHandoff(gateway);

      await expect(getAccessToken()).resolves.toBe(HOST_JWT);
    });

    /**
     * ★ 문자열도 `null` 도 아닌 값이 오면 **토큰 없음으로 접는다**(fail-closed). 그대로
     *   흘려보내면 훨씬 뒤에서 엉뚱한 모습으로 터져 원인이 남지 않는다.
     */
    it('★문자열도_null도_아닌_값은_토큰_없음으로_접는다', async () => {
      vi.spyOn(console, 'error').mockImplementation(() => {});
      registerHostTokenHandoff({
        getAccessToken: () => ({ not: 'a token' }) as unknown as string,
        refresh: async () => null,
        onUnauthorized: vi.fn(),
        notifyActivity: vi.fn(),
      });

      await expect(getAccessToken()).resolves.toBeNull();
      expect(console.error).toHaveBeenCalled();
    });

    /**
     * ★ 창구는 **Host 가 구현하는 남의 코드**다. 거기서 던진 예외가 새어 나가면 부팅·요청이
     *   통째로 멈추는데, 그건 「토큰이 없다」보다 나쁜 결말이다.
     */
    it('★창구가_예외를_던져도_토큰_없음으로_접고_계속_간다', async () => {
      vi.spyOn(console, 'error').mockImplementation(() => {});
      registerHostTokenHandoff({
        getAccessToken: () => {
          throw new Error('host exploded');
        },
        refresh: async () => null,
        onUnauthorized: vi.fn(),
        notifyActivity: vi.fn(),
      });

      await expect(getAccessToken()).resolves.toBeNull();
    });

    it('빈_문자열은_토큰_없음으로_본다', async () => {
      registerHostTokenHandoff({
        getAccessToken: () => '   ',
        refresh: async () => null,
        onUnauthorized: vi.fn(),
        notifyActivity: vi.fn(),
      });

      await expect(getAccessToken()).resolves.toBeNull();
    });
  });

  describe('등록 시 모양 검사 — 어긋난 창구는 받지 않는다', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      vi.spyOn(console, 'error').mockImplementation(() => {});
    });

    it.each([...HOST_HANDOFF_METHOD_NAMES])('%s_가_빠진_창구는_거부한다', async (missing) => {
      const { gateway } = makeHostGateway(HOST_JWT);
      const broken = { ...gateway } as Record<string, unknown>;
      delete broken[missing];

      expect(registerHostTokenHandoff(broken)).toBe(false);
      // 거부했으면 반쯤 등록된 상태로 남지 않는다.
      expect(await getAccessToken()).toBeNull();
    });

    it('이름이_다른_창구는_거부한다_개념만_맞추고_새로_지으면_안_된다', async () => {
      // 개념은 같지만 이름을 새로 지은 경우 — 실행 시점에 창구를 못 찾는 바로 그 상황.
      const renamed = {
        getJwt: () => HOST_JWT,
        renew: async () => HOST_JWT,
        onAuthFailure: () => {},
        touch: () => {},
      };

      expect(registerHostTokenHandoff(renamed)).toBe(false);
      expect(await getAccessToken()).toBeNull();
    });

    it.each([null, undefined, 'a-string', 42])('객체가_아닌_값(%s)은_거부한다', (value) => {
      expect(registerHostTokenHandoff(value)).toBe(false);
    });
  });
});

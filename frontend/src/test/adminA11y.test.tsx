// 관리 영역 화면 자동 접근성 감사 — 위반 0. [@design NFR-022]
//
// 적용 범위는 **관리 영역 네 화면**이다 — 사용자 관리 · 연동 서버 주소 · 패스워드 교체 ·
//   위험 액션. 되돌리기 어려운 조작을 다루는 자리라, 보조기술 사용자가 지금 무엇을 조작하는지
//   확인하지 못하면 그 결과가 크다.
//
// ⚠ **나머지 내부 화면으로 넓히지 않는다** — 별도 결정이다. 지금 전 화면에 한 번에 걸면 기준만
//   서고 지켜지지 않는 상태가 된다.
//
// ★**자동 감사는 사람의 확인을 대체하지 않는다.** 자동으로 잡히는 것은 규칙으로 판정 가능한
//   부분뿐이고, 초점 순서가 화면의 논리와 맞는지나 안내 문구가 실제로 뜻이 통하는지는 그 밖에
//   있다. 위반 0 은 **최소선**이지 접근성이 확보됐다는 뜻이 아니다.
//
// ★색상만으로 의미를 전달하지 않는다는 것도 이 기준 안에 있다 — 다만 대비 계산은 jsdom 이
//   실제 렌더 색을 갖지 않아 자동 감사로 판정되지 않는다. 팔레트 대비는 `categoryPalette` 가
//   값에서 직접 계산하고, 색 외 단서(글자·아이콘 병기)는 각 컴포넌트 시험이 본다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { axe } from 'vitest-axe';
import * as axeMatchers from 'vitest-axe/matchers';

import { useAdminSessionStore } from '@/features/adminSession/store';
import { apiClient } from '@/lib/api/client';
import { AdminEndpointsPage } from '@/pages/admin/AdminEndpointsPage';
import { AdminMaintenancePage } from '@/pages/admin/AdminMaintenancePage';
import { AdminPasswordPage } from '@/pages/admin/AdminPasswordPage';
import { UserManagePage } from '@/pages/manage/UserManagePage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

// ⚠ 매처는 **등록과 타입 확장을 따로** 해야 한다 — 등록만 하면 vitest 는 통과하는데 `tsc` 가
//   `toHaveNoViolations` 를 몰라 빌드가 빨개진다(타입 확장은 `src/test/vitestAxe.d.ts`).
expect.extend(axeMatchers);

/**
 * 판정 기준은 **WCAG 2.1 AA** 다 — 기준이 그렇게 적혀 있으므로 감사도 그 규칙 집합으로 돌린다.
 *
 * ⚠ 이것은 위반을 덮는 예외가 아니라 **기준의 범위**다. axe 의 best-practice 규칙(예: 모든
 *   콘텐츠가 랜드마크 안에 있어야 한다)은 WCAG 요건이 아니고, 화면 전체가 아니라 조각을
 *   렌더하는 시험에서는 셸이 없다는 이유만으로 걸린다 — 그것을 기준에 넣으면 이 감사가
 *   가리키는 대상이 화면이 아니라 시험 형태가 된다.
 */
const WCAG_21_AA = {
  runOnly: { type: 'tag' as const, values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
};

/** 인증 상태 자리 — 화면 판정에 쓰이는 것은 `claims` 다. */
const AUTH_STUB = 'dummy-value';
const WINDOW_STUB = 'dummy-window';

function signInAsAdmin() {
  useAuthStore.setState({
    token: AUTH_STUB,
    claims: { sub: '1001', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999 },
    isHydrated: true,
  });
}

/** 관리자 단기 유효창 — 잠긴 화면이 아니라 **실제 조작면**을 감사하기 위해 열어 둔다. */
function openAdminWindow() {
  useAdminSessionStore.getState().open({
    token: WINDOW_STUB,
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
  });
}

const USERS_PAGE = {
  success: true,
  data: {
    content: [
      {
        id: 7,
        loginId: 'hong',
        name: '홍길동',
        role: 'WORKER',
        active: true,
        createdAt: '2026-05-01T00:00:00Z',
        lastLoginAt: '2026-05-20T00:00:00Z',
      },
      // 역할 미배정 행 — 경고 아이콘이 붙는 배지가 감사 대상에 들어가게 한다.
      {
        id: 8,
        loginId: 'nobody',
        name: '아무개',
        role: null,
        active: false,
        createdAt: '2026-05-02T00:00:00Z',
        lastLoginAt: null,
      },
    ],
    totalElements: 2,
    totalPages: 1,
    number: 0,
    size: 20,
  },
  message: null,
  errorCode: null,
};

const CONFIGS = {
  success: true,
  data: [
    { configKey: 'kpst.deid.base-url', configVl: 'https://deid.invalid', configTypeCd: 'STRING' },
    { configKey: 'vlm.client.url', configVl: '', configTypeCd: 'STRING' },
  ],
  message: null,
  errorCode: null,
};

/**
 * 장비 목록(SCREEN-042 「추론·외부 시계열 분석 장비 목록」 영역) — 연동 서버 주소 화면이 함께 그린다.
 *
 * ⚠ 이 응답을 배선하지 않으면 그 영역이 오류 상태로만 렌더되어 <b>표·배지·행 액션이 통째로
 *   감사 대상에서 빠진다</b>. 감사기가 아무것도 못 보고 초록이 되는 형태라 반드시 채워 둔다.
 */
const AI_SERVERS = {
  success: true,
  data: [
    {
      srvrId: 'gpu01',
      srvrNm: 'klid-ai-gpu-01',
      srvrAddr: 'http://10.0.0.11:9300',
      srvrTypeCd: 'INFERENCE',
      srvrSttsCd: 'AVAILABLE',
      chckDt: '2026-09-01T00:00:00Z',
      chckFailNocs: 0,
      chckScsNocs: 12,
      regDt: '2026-08-01T00:00:00Z',
      mdfrId: '1001',
      mdfcnDt: null,
      loads: [
        {
          usgTypeCd: 'BATCH',
          prcsNocs: 1,
          wtngNocs: 2,
          effectiveLoad: 3,
          chckDt: '2026-09-01T00:00:00Z',
        },
      ],
    },
    {
      srvrId: 'gpu02',
      srvrNm: null,
      srvrAddr: 'http://10.0.0.12:9300',
      srvrTypeCd: 'INFERENCE',
      srvrSttsCd: 'UNAVAILABLE',
      chckDt: null,
      chckFailNocs: 3,
      chckScsNocs: 0,
      regDt: '2026-08-02T00:00:00Z',
      mdfrId: null,
      mdfcnDt: null,
      loads: [],
    },
  ],
  message: null,
  errorCode: null,
};

describe('자동 감사기 자체 확인', () => {
  // ★「위반 0」은 감사기가 실제로 무언가를 볼 때만 의미가 있다. 규칙 집합을 좁히거나 도구 배선이
  //   끊기면 아무것도 검사하지 않은 채 초록이 되는데, 그건 통과가 아니라 가드 무력화다.
  //   그래서 **알려진 위반이 실제로 잡히는지**를 같은 설정으로 함께 확인한다.
  it('명백한_위반은_같은_설정에서_잡힌다', async () => {
    // ⚠ JSX 로 쓰지 않는다 — 이 저장소의 정적 검사(jsx-a11y)가 대체 텍스트 없는 이미지를
    //   **소스 단계에서** 막는다. 그건 올바른 규칙이므로 끄지 않고, 검사 대상이 아닌 DOM 조립
    //   경로로 표본을 만든다(시험 안에서만 쓰는 고정 조각이라 외부 입력이 섞이지 않는다).
    const probe = document.createElement('div');
    // 대체 텍스트 없는 이미지 + 이름 없는 입력 — 둘 다 WCAG A 위반이다.
    const img = document.createElement('img');
    img.setAttribute('src', '/none.png');
    const input = document.createElement('input');
    input.setAttribute('type', 'text');
    probe.append(img, input);
    document.body.append(probe);

    try {
      const results = await axe(probe, WCAG_21_AA);
      expect(results.violations.map((v) => v.id).sort()).toEqual(['image-alt', 'label']);
    } finally {
      probe.remove();
    }
  });
});

describe('관리 영역 화면 접근성 (WCAG 2.1 AA 자동 감사)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/users').reply(200, USERS_PAGE);
    mock.onGet('/manage/configs').reply(200, CONFIGS);
    mock.onGet('/manage/ai-servers').reply(200, AI_SERVERS);
    signInAsAdmin();
    openAdminWindow();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  it('사용자_관리_화면에_위반이_없다', async () => {
    const { container } = renderWithProviders(<UserManagePage />, {
      initialEntries: ['/admin/users'],
    });
    await waitFor(() => expect(screen.getByText('홍길동')).toBeInTheDocument());

    expect(await axe(container, WCAG_21_AA)).toHaveNoViolations();
  });

  it('연동_서버_주소_화면에_위반이_없다', async () => {
    const { container } = renderWithProviders(<AdminEndpointsPage />, {
      initialEntries: ['/admin/endpoints'],
    });
    await waitFor(() => expect(screen.getByLabelText('비식별 서버')).toBeInTheDocument());
    // 장비 목록이 실제로 그려진 뒤에 감사한다 — 비어 있는 상태를 감사하면 표·배지·행 액션이
    // 대상에서 빠진 채 초록이 된다.
    await waitFor(() => expect(screen.getByTestId('ai-server-table')).toBeInTheDocument());

    expect(await axe(container, WCAG_21_AA)).toHaveNoViolations();
  });

  it('패스워드_교체_화면에_위반이_없다', async () => {
    const { container } = renderWithProviders(<AdminPasswordPage />, {
      initialEntries: ['/admin/password'],
    });
    await waitFor(() => expect(screen.getByLabelText('현재 패스워드')).toBeInTheDocument());

    expect(await axe(container, WCAG_21_AA)).toHaveNoViolations();
  });

  it('위험_액션_화면에_위반이_없다', async () => {
    const { container } = renderWithProviders(<AdminMaintenancePage />, {
      initialEntries: ['/admin/maintenance'],
    });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /시스템 초기화/ })).toBeInTheDocument(),
    );

    expect(await axe(container, WCAG_21_AA)).toHaveNoViolations();
  });
});

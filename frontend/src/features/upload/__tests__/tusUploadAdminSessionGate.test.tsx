// 회귀 가드 — 파일 업로드 화면의 **두 적재 경로가 유효창 만료를 똑같이 처리한다**.
// [@design SCREEN-027] [@design API-158] [@design API-194] [@design ADR-046]
//
// ★왜 이 가드가 필요한가
//   같은 화면(`/admin/uploads`)의 두 경로가 갈려 있었다. 「파이프라인 즉시 실행」은 전송 **전에**
//   잠김을 보고 확인 창을 열었는데, 「관제 인입 재현」은 곧바로 세션 생성을 보내 **서버 원문 403**
//   이 그대로 화면에 떴다. 사용자에게는 「권한이 없습니다」로 읽혀 역할 문제로 오인된다.
//   확정 사양은 «만료를 상태코드로만 알리고 끝내지 않는다» 이므로 그 동작은 미충족이었다.
//
// ★단언을 문구로 하지 않는다
//   「잠김 선처리로 막혔다」와 「보냈는데 서버가 403 을 줬다」는 화면 문구로 구분되지 않는다.
//   그래서 **관측된 요청 수 0** 으로 단언한다. 문구 단언은 두 상태를 같은 것으로 통과시킨다.
//
// ★가짜 통과 방지 — 양성 대조군
//   「요청 0건」만 단언하면 버튼이 통째로 고장 나도 통과한다. 그래서 «열려 있을 때는 실제로 나간다»
//   를 같은 파일에서 함께 본다.
//
// ⚠ 이 가드가 지키는 반대 축 — 이어보내기(재개)에는 요구하지 않는다
//   대용량 영상은 유효창(기본 10분)을 넘기기 마련이라, 청크까지 요구하면 **구조적으로 올릴 수
//   없게 된다.** 그래서 잠긴 뒤에도 재개가 나가는 것을 함께 못 박는다. 헤더가 어디에 실리는가는
//   `features/adminSession/__tests__/adminSessionHeaderScope.test.ts` 가 따로 지킨다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { DevAutolabelTestPage } from '@/pages/dev/DevAutolabelTestPage';
import { DEFAULT_CHUNK_SIZE } from '@/features/upload/api/tusClient';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAdminSessionStore } from '@/features/adminSession/store';
import { useAuthStore } from '@/stores/useAuthStore';

const EVENT_CATEGORIES = [
  { categoryKey: '020002', label: '쓰러짐', memberCodes: ['EV02000201'] },
];

/** 관측된 요청 — 이벤트유형 옵션 조회는 화면이 뜨기만 해도 나가므로 세지 않는다. */
interface SeenRequest {
  method: string;
  url: string;
}

function fileInput(): HTMLInputElement {
  return document.getElementById('dev-upload-file') as HTMLInputElement;
}

/** 실제로 N 바이트를 만들지 않고 크기만 큰 파일 — 청크 경계를 넘기는 데만 쓴다. */
function makeSizedFile(size: number, name = 'clip.mp4'): File {
  const f = new File([new Uint8Array(1)], name, { type: 'video/mp4' });
  Object.defineProperty(f, 'size', { value: size });
  return f;
}

function openAdminSessionWindow(): void {
  useAdminSessionStore.getState().open({
    token: 'dummy-window',
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
  });
}

/** 유효창을 잠근다 — 만료됐거나 아직 열지 않은 상태. */
function lockAdminSessionWindow(): void {
  useAdminSessionStore.getState().clear();
}

const startButton = () => screen.getByRole('button', { name: '업로드 시작' });

/**
 * 지금 나갔어야 할 요청이 관측될 때까지 이벤트 루프를 한 바퀴 비운다.
 *
 * 「요청 0건」을 화면 문구보다 **먼저** 단언하기 위한 것이다. 문구를 먼저 단언하면 그 대기가
 * 시간을 벌어 주어, 정작 이 가드의 핵심인 요청 수 단언이 무엇 때문에 통과했는지 흐려진다.
 */
async function flushPending(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

describe('파일 업로드 — 관리자 유효창이 잠겼을 때', () => {
  let mock: MockAdapter;
  let requests: SeenRequest[];
  /**
   * 청크(PATCH) 응답이 돌려줄 누적 offset.
   *
   * ⚠ **파일 크기 이상이어야 업로드 루프가 끝난다.** 작은 값을 계속 돌려주면 `uploadFile` 의
   * `while (offset < file.size)` 가 영원히 돌아 워커가 힙을 소진한다(실측으로 한 번 겪었다).
   * 기본값은 「한 번에 다 올라갔다」이고, 중간 정지를 만드는 테스트만 이 값을 낮춘다.
   */
  let patchOffset: string;
  /** 진행위치 조회(HEAD)가 돌려줄 offset — 재개가 어디서부터 이어 보낼지 정한다. */
  let headOffset: string;
  /** 청크 응답을 붙잡아 두는 문 — 「전송 중」 상태를 결정적으로 고정할 때만 건다. */
  let patchGate: Promise<void> | null;

  const HUGE_OFFSET = String(Number.MAX_SAFE_INTEGER);

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    requests = [];
    patchOffset = HUGE_OFFSET;
    headOffset = '0';
    patchGate = null;
    // 옵션 조회를 제외한 **모든** 요청을 센다. 경로를 열거하면 새 창구가 생겼을 때 조용히 새므로
    // 「그 밖의 전부」를 세는 쪽으로 잡는다.
    mock.onAny().reply(async (config) => {
      const url = String(config.url ?? '');
      if (url.includes('/event-types')) {
        return [200, { success: true, data: EVENT_CATEGORIES, message: null, errorCode: null }];
      }
      const method = String(config.method ?? '').toLowerCase();
      requests.push({ method, url });
      if (method === 'patch') {
        if (patchGate) await patchGate;
        return [204, null, { 'tus-resumable': '1.0.0', 'upload-offset': patchOffset }];
      }
      if (method === 'head') {
        return [200, null, { 'tus-resumable': '1.0.0', 'upload-offset': headOffset }];
      }
      return [
        200,
        { success: true, data: {}, message: null, errorCode: null },
        {
          location: '/v1/uploads/u-1',
          'tus-resumable': '1.0.0',
          'x-ingest-status': 'PENDING',
          'upload-offset': '0',
        },
      ];
    });
    useAuthStore.setState({
      token: 'dummy-test-token',
      claims: { sub: '1001', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
    });
    lockAdminSessionWindow();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    lockAdminSessionWindow();
  });

  it('★관제_인입_재현_시작이_요청을_한_건도_보내지_않고_확인_창을_연다', async () => {
    // given — 유효창이 잠긴 채로 인입 재현 경로에 파일을 고른다
    const user = userEvent.setup();
    renderWithProviders(<DevAutolabelTestPage />);
    await user.click(screen.getByRole('radio', { name: /관제 인입 재현/ }));
    await user.upload(fileInput(), makeSizedFile(1024));
    await waitFor(() => expect(startButton()).toBeEnabled());

    // when — 시작을 누른다
    await user.click(startButton());

    // then — ★요청이 한 건도 나가지 않았다. 문구가 아니라 관측된 요청 수로 단언한다 —
    // 「막혀서 안 보냈다」와 「보냈다가 403 을 받았다」는 화면 문구로 구분되지 않는다.
    // 나갔다면 이 시점에 이미 관측된다(핸들러가 동기로 보내고 어댑터가 그 뒤 태스크에서 답한다).
    await flushPending();
    expect(requests, `보내지 말았어야 할 요청: ${JSON.stringify(requests)}`).toHaveLength(0);
    // then — 그리고 무슨 일인지 알린다(만료를 상태코드로만 알리고 끝내지 않는다)
    expect(await screen.findByRole('dialog', { name: '관리자 인증' })).toBeInTheDocument();
  });

  it('파이프라인_즉시_실행_시작도_같은_선처리를_거친다', async () => {
    // given — 두 경로가 같은 화면이므로 만료 처리도 같아야 한다
    const user = userEvent.setup();
    renderWithProviders(<DevAutolabelTestPage />);
    await user.upload(fileInput(), makeSizedFile(1024));
    await waitFor(() => expect(startButton()).toBeEnabled());

    // when
    await user.click(startButton());

    // then
    await flushPending();
    expect(requests, `보내지 말았어야 할 요청: ${JSON.stringify(requests)}`).toHaveLength(0);
    expect(await screen.findByRole('dialog', { name: '관리자 인증' })).toBeInTheDocument();
  });

  it('양성_대조군 — 유효창이_열려_있으면_시작이_실제로_요청을_보낸다', async () => {
    // given — 위 두 케이스의 「0건」이 버튼 고장으로도 성립하지 않게 하는 대조군
    const user = userEvent.setup();
    openAdminSessionWindow();
    renderWithProviders(<DevAutolabelTestPage />);
    await user.click(screen.getByRole('radio', { name: /관제 인입 재현/ }));
    await user.upload(fileInput(), makeSizedFile(1024));
    await waitFor(() => expect(startButton()).toBeEnabled());

    // when
    await user.click(startButton());

    // then — 세션 생성이 실제로 나갔고, 확인 창은 뜨지 않는다
    await waitFor(() => expect(requests.some((r) => r.method === 'post')).toBe(true));
    expect(screen.queryByRole('dialog', { name: '관리자 인증' })).toBeNull();
    // 업로드가 끝날 때까지 기다린다 — 테스트가 먼저 끝나면 뒤늦은 상태 갱신이 act 경고로 샌다.
    await screen.findByTestId('tus-completed');
  });

  it('★잠긴_뒤에도_재개는_막지_않는다 — 대용량_업로드가_유효창을_넘겨도_끊기지_않아야_한다', async () => {
    // given — 청크 1개로 끝나지 않는 크기 + 첫 청크 응답을 붙잡아 «전송 중 일시정지» 를 만든다
    const user = userEvent.setup();
    let releaseFirstChunk!: () => void;
    patchGate = new Promise<void>((resolve) => {
      releaseFirstChunk = resolve;
    });
    // 첫 청크는 파일보다 작은 offset 을 돌려준다 — 아직 남았으므로 루프가 일시정지 신호를 본다.
    patchOffset = String(DEFAULT_CHUNK_SIZE);
    headOffset = String(DEFAULT_CHUNK_SIZE);

    openAdminSessionWindow();
    renderWithProviders(<DevAutolabelTestPage />);
    await user.click(screen.getByRole('radio', { name: /관제 인입 재현/ }));
    await user.upload(fileInput(), makeSizedFile(DEFAULT_CHUNK_SIZE + 1024));
    await waitFor(() => expect(startButton()).toBeEnabled());
    await user.click(startButton());

    await user.click(await screen.findByRole('button', { name: '일시정지' }));
    releaseFirstChunk();
    const resume = await screen.findByRole('button', { name: '재개' });
    // 이어보내기가 남은 조각을 한 번에 끝내게 둔다 — 아니면 루프가 끝나지 않는다.
    patchGate = null;
    patchOffset = HUGE_OFFSET;

    // when — 여기서 유효창이 끝난다(대용량 업로드에서는 정상 상황이다)
    act(() => lockAdminSessionWindow());
    const patchesBefore = requests.filter((r) => r.method === 'patch').length;
    await user.click(resume);

    // then — 이어보내기는 그대로 나간다. 확인 창으로 막으면 이 업로드는 영영 끝나지 않는다.
    await waitFor(() =>
      expect(requests.filter((r) => r.method === 'patch').length).toBeGreaterThan(patchesBefore),
    );
    expect(screen.queryByRole('dialog', { name: '관리자 인증' })).toBeNull();
  });
});

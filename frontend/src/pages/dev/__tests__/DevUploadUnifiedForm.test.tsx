import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { DevAutolabelTestPage } from '@/pages/dev/DevAutolabelTestPage';
import { MULTIPART_MAX_BYTES } from '@/features/dev/components/unifiedUploadForm';
import { DEFAULT_CHUNK_SIZE } from '@/features/upload/api/tusClient';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAdminSessionStore } from '@/features/adminSession/store';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 파일 업로드 화면(`/admin/uploads`) — **입력 폼 통합** 회귀 가드. [@design SCREEN-027]
 *
 * 이 화면은 필드 구성이 다른 별개 카드 2개였다가 «적재 경로 라디오 + 두 경로가 완전히 같은 입력
 * 폼» 한 벌로 합쳐졌다. 라디오는 **보내는 곳과 그 뒤 흐름만** 바꾼다.
 *
 * 여기서 지키는 것:
 * - 폼이 다시 갈라지지 않는다(경로 전환이 필드 집합을 바꾸지 않는다)
 * - 경로를 바꿔도 입력한 값이 살아 있다(폼이 한 벌이라는 사실의 관측 가능한 결과)
 * - 필수는 식별 정보 네 항목뿐이고 나머지 묶음은 처음에 접혀 있다
 * - 통째 전송 한도(500MB)는 즉시 실행 경로에만 걸린다
 *
 * ⚠ jsdom 사각: CSS 로 감춘 요소는 조회에서 걸러지지 않는다. 그래서 접힘은 클래스가 아니라
 * `hidden` 속성과 `aria-expanded` 로 판정한다(브라우저에서도 실제로 감춰지는 축).
 */
const EVENT_CATEGORIES = [
  { categoryKey: '020002', label: '쓰러짐', memberCodes: ['EV02000201'] },
  { categoryKey: '050001', label: '싸움', memberCodes: ['EV05000101'] },
];

/** 폼의 입력 칸 구성을 «라벨 문구 집합» 으로 지문화한다 — 경로 전환 전후 비교용. */
function formFieldSignature(): string[] {
  const form = document.querySelector('form');
  if (!form) throw new Error('업로드 폼을 찾지 못했습니다.');
  return Array.from(form.querySelectorAll('label'))
    .map((l) => (l.textContent ?? '').trim())
    .filter((t) => t !== '')
    .sort();
}

/** 접이식 묶음 토글 — 접근성 이름에 묶음 이름이 들어 있다. */
function groupToggle(titlePart: string): HTMLElement {
  return screen.getByRole('button', { name: new RegExp(titlePart) });
}

function fileInput(): HTMLInputElement {
  return document.getElementById('dev-upload-file') as HTMLInputElement;
}

function makeFile(size: number, name = 'clip.mp4'): File {
  return new File([new Uint8Array(size)], name, { type: 'video/mp4' });
}

/** 실제로 N 바이트를 만들지 않고 크기만 큰 파일 — 500MB 초과 케이스는 할당하면 안 된다. */
function makeHugeFile(size: number): File {
  const f = makeFile(1);
  Object.defineProperty(f, 'size', { value: size });
  return f;
}

async function chooseIngestRoute(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(screen.getByRole('radio', { name: /관제 인입 재현/ }));
}

/**
 * 관리자 유효창을 열어 둔다 — 이 화면은 관리자 페이지 소속이라 **진입 게이트를 통과한 상태**에서만
 * 열린다(`AdminSessionGuard`). 창을 안 열면 업로드가 요청 전에 확인 창으로 막혀, 이 파일이 지키려는
 * 폼·전송 축이 아니라 유효창 축을 검증하게 된다. 유효창 자체의 회귀 가드는 별도 파일이 갖는다.
 */
function openAdminSessionWindow(): void {
  useAdminSessionStore.getState().open({
    token: 'dummy-window',
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
  });
}

describe('파일 업로드 — 적재 경로와 단일 입력 폼', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/event-types').reply(200, {
      success: true,
      data: EVENT_CATEGORIES,
      message: null,
      errorCode: null,
    });
    useAuthStore.setState({
      token: 'dummy-test-token',
      claims: { sub: '1001', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
    });
    openAdminSessionWindow();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('적재_경로_라디오로_두_경로를_고를_수_있다', async () => {
    // given
    const user = userEvent.setup();
    renderWithProviders(<DevAutolabelTestPage />);

    // then — 두 선택지가 있고 기본은 파이프라인 즉시 실행이다
    const immediate = screen.getByRole('radio', { name: /파이프라인 즉시 실행/ });
    const ingest = screen.getByRole('radio', { name: /관제 인입 재현/ });
    expect(immediate).toBeChecked();
    expect(ingest).not.toBeChecked();

    // when — 인입 재현으로 바꾼다
    await user.click(ingest);

    // then
    expect(ingest).toBeChecked();
    expect(immediate).not.toBeChecked();
  });

  it('두_경로가_같은_입력_폼을_쓴다', async () => {
    // given — 즉시 실행(기본) 상태의 입력 칸 구성
    const user = userEvent.setup();
    renderWithProviders(<DevAutolabelTestPage />);
    const before = formFieldSignature();

    // when — 경로만 바꾼다
    await chooseIngestRoute(user);

    // then — 입력 칸 구성이 한 칸도 달라지지 않는다. 이 화면의 핵심 계약이다 —
    // 경로는 «보내는 곳» 만 바꾸고 «무엇을 입력하는지» 는 바꾸지 않는다.
    expect(formFieldSignature()).toEqual(before);
    // 지문이 비어 있으면 위 비교가 무의미해진다(가짜 통과 방지)
    expect(before.length).toBeGreaterThan(20);
  });

  it('경로를_바꿔도_입력한_값이_유지된다', async () => {
    // given — 두 경로 모두 쓰는 필드(클립 ID)와 한쪽만 쓰는 필드(지자체명)를 채운다
    const user = userEvent.setup();
    renderWithProviders(<DevAutolabelTestPage />);
    const clipId = screen.getByLabelText('영상 클립 ID *') as HTMLInputElement;
    await user.clear(clipId);
    await user.type(clipId, 'my-clip-777');

    await user.click(groupToggle('위치 · CCTV 제원'));
    const govName = screen.getByLabelText('지자체명') as HTMLInputElement;
    await user.type(govName, '강남구');

    // when — 경로 전환
    await chooseIngestRoute(user);

    // then — 폼이 한 벌이므로 값이 초기화되지 않는다(별개 폼 2개였다면 사라졌다)
    expect((screen.getByLabelText('영상 클립 ID *') as HTMLInputElement).value).toBe('my-clip-777');
    expect((screen.getByLabelText('지자체명') as HTMLInputElement).value).toBe('강남구');
  });

  it('필수는_식별_정보_네_항목뿐이고_나머지_묶음은_접혀_있다', () => {
    // given/when
    renderWithProviders(<DevAutolabelTestPage />);

    // then — 식별 정보 묶음 안의 필수 표시(` *`)는 정확히 4개다
    const identity = screen.getByRole('group', { name: '식별 정보' });
    const requiredLabels = Array.from(identity.querySelectorAll('label'))
      .map((l) => (l.textContent ?? '').trim())
      .filter((t) => t.endsWith('*'));
    expect(requiredLabels).toEqual([
      '영상 클립 ID *',
      'CCTV ID *',
      '출처유형 *',
      '지자체코드 *',
    ]);
    // 촬영일시는 같은 묶음에 있지만 선택이다
    expect(within(identity).getByLabelText('촬영일시')).toBeInTheDocument();

    // then — 나머지 묶음 3종은 처음에 접혀 있다(입력 부담 완화). 클래스가 아니라
    // aria-expanded + hidden 으로 판정한다(jsdom 은 CSS 로 감춘 것을 걸러내지 못한다).
    for (const title of ['위치 · CCTV 제원', '이벤트 · 관제일지', '영상 기술메타']) {
      const toggle = groupToggle(title);
      expect(toggle, title).toHaveAttribute('aria-expanded', 'false');
      const panel = document.getElementById(toggle.getAttribute('aria-controls') ?? '');
      expect(panel, title).not.toBeNull();
      expect(panel, title).toHaveAttribute('hidden');
    }
  });

  it('접기_묶음을_펼치면_내용이_드러난다', async () => {
    // given — 위 테스트의 대칭(항상 접혀 있으면 입력 자체가 불가능하다)
    const user = userEvent.setup();
    renderWithProviders(<DevAutolabelTestPage />);
    const toggle = groupToggle('영상 기술메타');

    // when
    await user.click(toggle);

    // then
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const panel = document.getElementById(toggle.getAttribute('aria-controls') ?? '');
    expect(panel).not.toHaveAttribute('hidden');
  });

  it('영상_길이는_입력할_수_없고_서버가_채운다는_안내가_보인다', () => {
    // given/when
    renderWithProviders(<DevAutolabelTestPage />);

    // then — 안내 박스만 있고 «영상 길이» 라는 입력 칸은 없다
    expect(screen.getByTestId('dev-upload-duration-notice')).toHaveTextContent(
      '올린 영상 파일에서 서버가 직접 읽어 채웁니다',
    );
    expect(screen.queryByLabelText('영상 길이')).toBeNull();
  });

  it('클립_ID_와_CCTV_ID_와_지자체코드에_기본값이_미리_채워진다', () => {
    // given/when — 화면 진입 직후
    renderWithProviders(<DevAutolabelTestPage />);

    // then — 사용자가 아무것도 채우지 않아도 되는 상태로 시작한다
    expect((screen.getByLabelText('영상 클립 ID *') as HTMLInputElement).value).not.toBe('');
    expect((screen.getByLabelText('CCTV ID *') as HTMLInputElement).value).toBe('CCTV-001');
    expect((screen.getByLabelText('지자체코드 *') as HTMLInputElement).value).toBe('11680');
    // 프로토콜명 같은 기술 용어가 기본값으로 노출되지 않는다
    expect((screen.getByLabelText('영상 클립 ID *') as HTMLInputElement).value).not.toMatch(/tus/i);
  });

  it('파일과_필수값이_채워지기_전에는_업로드_시작이_눌리지_않는다', async () => {
    // given — 기본값은 채워져 있고 파일만 없는 상태
    const user = userEvent.setup();
    renderWithProviders(<DevAutolabelTestPage />);
    const start = () => screen.getByRole('button', { name: '업로드 시작' });
    expect(start()).toBeDisabled();

    // when — 파일을 고르면 눌린다
    await user.upload(fileInput(), makeFile(4));
    await waitFor(() => expect(start()).toBeEnabled());

    // when — 필수 한 칸(지자체코드)을 비우면 다시 잠긴다
    await user.clear(screen.getByLabelText('지자체코드 *'));
    await waitFor(() => expect(start()).toBeDisabled());
  });

  it('즉시_실행_경로에만_500MB_한도가_적용된다', async () => {
    // given — 한도를 1바이트 넘긴 파일
    const user = userEvent.setup();
    renderWithProviders(<DevAutolabelTestPage />);
    await user.upload(fileInput(), makeHugeFile(MULTIPART_MAX_BYTES + 1));

    // then — 통째로 보내는 즉시 실행 경로는 사유를 먼저 알리고 시작을 막는다
    expect(await screen.findByTestId('dev-upload-size-limit')).toHaveTextContent('500MB');
    expect(screen.getByRole('button', { name: '업로드 시작' })).toBeDisabled();

    // when — 청크로 나눠 보내는 인입 재현 경로로 바꾸면
    await chooseIngestRoute(user);

    // then — 그 한도를 받지 않는다(같은 파일, 같은 폼)
    expect(screen.queryByTestId('dev-upload-size-limit')).toBeNull();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '업로드 시작' })).toBeEnabled(),
    );
  });

  it('전송_중에는_폼_전체가_잠긴다', async () => {
    // given — 응답이 오지 않는 요청으로 «전송 중» 을 고정한다
    const user = userEvent.setup();
    mock.onPost('/dev/upload').reply(() => new Promise(() => {}));
    renderWithProviders(<DevAutolabelTestPage />);
    await user.upload(fileInput(), makeFile(4));

    // when
    await user.click(screen.getByRole('button', { name: '업로드 시작' }));

    // then — 필수·선택·경로 라디오·파일까지 전부 잠긴다(전송 도중 값이 바뀌면 무엇을 보냈는지
    // 알 수 없게 된다)
    await waitFor(() => expect(screen.getByLabelText('영상 클립 ID *')).toBeDisabled());
    expect(screen.getByLabelText('CCTV ID *')).toBeDisabled();
    expect(screen.getByLabelText('지자체코드 *')).toBeDisabled();
    expect(fileInput()).toBeDisabled();
    expect(screen.getByRole('radio', { name: /관제 인입 재현/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: '초기화' })).toBeDisabled();
  });

  it('인입_재현_경로는_일시정지와_재개가_노출된다', async () => {
    // given — 청크 1개로 끝나지 않는 크기 + 첫 청크 응답을 우리가 붙잡아 둔다.
    // 그래야 «전송 중 일시정지 → 남은 청크 전에 멈춤» 순서가 결정적으로 재현된다.
    const user = userEvent.setup();
    let releaseFirstChunk!: () => void;
    const gate = new Promise<void>((resolve) => {
      releaseFirstChunk = resolve;
    });
    mock.onPost('/uploads').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: '/v1/uploads/u-pause',
      'x-ingest-status': 'PENDING',
    });
    mock.onPatch('/uploads/u-pause').reply(async () => {
      await gate;
      // 파일보다 작은 offset — 아직 남았으므로 루프가 일시정지 신호를 확인한다
      return [204, null, { 'tus-resumable': '1.0.0', 'upload-offset': String(DEFAULT_CHUNK_SIZE) }];
    });

    renderWithProviders(<DevAutolabelTestPage />);
    await chooseIngestRoute(user);
    await user.upload(fileInput(), makeHugeFile(DEFAULT_CHUNK_SIZE + 1024));
    await user.click(screen.getByRole('button', { name: '업로드 시작' }));

    // when — 전송 중에는 일시정지가 뜨고 재개는 없다
    const pause = await screen.findByRole('button', { name: '일시정지' });
    expect(screen.queryByRole('button', { name: '재개' })).toBeNull();
    await user.click(pause);
    releaseFirstChunk();

    // then — 멈춘 뒤에는 재개가 뜨고 일시정지는 사라진다(기존 세션을 이어받는다)
    expect(await screen.findByRole('button', { name: '재개' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '일시정지' })).toBeNull();
    expect(screen.getByRole('button', { name: '취소' })).toBeInTheDocument();
  });

  it('즉시_실행_경로에는_일시정지와_재개가_없다', async () => {
    // given — 통째 전송은 중간에 멈출 지점이 없다
    const user = userEvent.setup();
    mock.onPost('/dev/upload').reply(() => new Promise(() => {}));
    renderWithProviders(<DevAutolabelTestPage />);
    await user.upload(fileInput(), makeFile(4));

    // when
    await user.click(screen.getByRole('button', { name: '업로드 시작' }));

    // then
    await waitFor(() => expect(fileInput()).toBeDisabled());
    expect(screen.queryByRole('button', { name: '일시정지' })).toBeNull();
    expect(screen.queryByRole('button', { name: '재개' })).toBeNull();
  });

  it('초기화는_입력값과_파일과_결과를_모두_비운다', async () => {
    // given — 즉시 실행으로 한 건 올려 결과 패널까지 띄운다
    const user = userEvent.setup();
    mock.onPost('/dev/upload').reply(200, {
      success: true,
      data: {
        rawSn: 4242,
        savedFilePath: 'dev-upload/reset.mp4',
        pipelineStatus: 'PROCESSING',
        startedAt: 1715520000000,
      },
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/videos\/\d+$/).reply(200, {
      success: true,
      data: {
        id: 4242,
        rawSn: 4242,
        cctvName: 'CCTV-001',
        vmsClipId: 'reset',
        frameCount: 0,
        status: 'MARKING_READY',
        capturedAt: '2026-05-12T10:00:00Z',
        duration: 60,
        fileSizeMb: 10,
        resolution: '1920x1080',
        framePreviews: [],
        stages: [],
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<DevAutolabelTestPage />);
    const clipId = screen.getByLabelText('영상 클립 ID *') as HTMLInputElement;
    await user.clear(clipId);
    await user.type(clipId, 'reset-me');
    await user.upload(fileInput(), makeFile(4, 'reset.mp4'));
    await user.click(screen.getByRole('button', { name: '업로드 시작' }));
    expect(await screen.findByTestId('autolabel-raw-sn')).toHaveTextContent('4242');
    expect(screen.getByTestId('dev-upload-selected-file')).toBeInTheDocument();

    // when
    await user.click(screen.getByRole('button', { name: '초기화' }));

    // then — 입력값·고른 파일·결과가 모두 비워진다
    await waitFor(() => expect(screen.queryByTestId('autolabel-raw-sn')).toBeNull());
    expect((screen.getByLabelText('영상 클립 ID *') as HTMLInputElement).value).not.toBe('reset-me');
    expect(screen.queryByTestId('dev-upload-selected-file')).toBeNull();
    expect(fileInput().value).toBe('');
  });

  it('경로별로_전송되지_않는_묶음을_화면이_알린다', async () => {
    // given — 조용한 손실 차단: 입력은 받아 놓고 버리지 않고, 버릴 것은 미리 알린다
    const user = userEvent.setup();
    renderWithProviders(<DevAutolabelTestPage />);

    // then — 즉시 실행 경로의 좁은 계약에 없는 묶음들
    expect(screen.getByTestId('unsent-notice-출처유형')).toBeInTheDocument();
    expect(screen.getByTestId('unsent-notice-영상 기술메타')).toBeInTheDocument();
    expect(screen.queryByTestId('unsent-notice-개인정보 유형')).toBeNull();

    // when — 인입 재현으로 바꾸면 안내가 뒤바뀐다
    await chooseIngestRoute(user);

    // then
    expect(screen.getByTestId('unsent-notice-개인정보 유형')).toBeInTheDocument();
    expect(screen.queryByTestId('unsent-notice-출처유형')).toBeNull();
    expect(screen.queryByTestId('unsent-notice-영상 기술메타')).toBeNull();
  });

  it('기술메타_묶음의_안내는_서버가_채운다는_사실을_담는다', () => {
    // given — 즉시 실행 경로. 입력값은 버려지지만 서버가 올린 파일에서 읽어 채운다.
    renderWithProviders(<DevAutolabelTestPage />);

    // then — «전송되지 않습니다» 만 적으면 거짓이 된다(서버가 파일에서 읽어 채우므로).
    const notice = screen.getByTestId('unsent-notice-영상 기술메타');
    expect(notice.textContent).toContain('입력값이 전송되지 않습니다');
    expect(notice.textContent).toContain('서버가 올린 영상 파일에서 읽을 수 있는 항목을 직접 채웁니다');
    // 서버 설정으로 끌 수 있고 화면은 그 값을 모른다 — 켜졌든 꺼졌든 참인 표현이어야 한다.
    expect(notice.textContent).toContain('서버 설정에 따라 생략될 수 있습니다');
  });

  it('다른_묶음의_안내는_전송되지_않는다는_서술을_유지한다', () => {
    // given — 위치·CCTV 제원 등은 파일에서 읽을 수 없어 진짜로 버려진다.
    renderWithProviders(<DevAutolabelTestPage />);

    // then — 전부 같은 문구로 통일하면 이쪽이 거짓이 된다.
    const notice = screen.getByTestId('unsent-notice-위치 · CCTV 제원');
    expect(notice.textContent).toContain('이 항목이 전송되지 않습니다');
    expect(notice.textContent).not.toContain('서버가 올린 영상 파일에서 읽을 수 있는 항목');
  });
});

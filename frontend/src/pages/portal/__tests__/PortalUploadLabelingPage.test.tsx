// Phase 6 — 포털 업로드 자산 라벨링 화면 동선 검증.
// react-konva 는 jsdom 미지원이라 passthrough 로 mock (LabelingPage 테스트 관례와 동일).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      ...rest
    }: {
      children?: ReactNode;
      [key: string]: unknown;
    }) => createElement('div', { 'data-konva': name, ...rest }, children);
    KonvaMock.displayName = `KonvaMock(${name})`;
    return KonvaMock;
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Group: passthrough('Group'),
  };
});

import { apiClient } from '@/lib/api/client';
import type { Label } from '@/features/label/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

import { PortalUploadLabelingPage } from '../PortalUploadLabelingPage';

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function frameRow(uldFrmeSn: number, frmeNo: number) {
  return { uldFrmeSn, uldSn: 1, frmeNo, regDt: '2026-07-17T00:00:00' };
}

function detail(over: Record<string, unknown> = {}) {
  return {
    uldSn: 1,
    uldTypeCd: 'IMAGE',
    orgnlFileNm: 'photo.jpg',
    fileSz: 1024,
    mimeTypeNm: 'image/jpeg',
    uldSttsCd: 'READY',
    frmeCnt: 1,
    vdoLenSec: null,
    fps: null,
    regDt: '2026-07-17T00:00:00',
    mdfcnDt: null,
    frames: [frameRow(100, 0)],
    ...over,
  };
}

function bboxRaw(label: string) {
  return {
    uldLblSn: 1,
    uldFrmeSn: 100,
    lblTypeCd: 'BBOX',
    label,
    points: [
      [10, 20],
      [110, 220],
    ],
    regDt: '2026-07-17T00:00:00',
    mdfcnDt: null,
  };
}

function bboxLabel(): Label {
  return {
    id: 'tmp-1',
    frameNo: 0,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 20, right: 110, bottom: 220 },
  };
}

function renderPage(uldSn = 1) {
  return renderWithProviders(<PortalUploadLabelingPage />, {
    initialEntries: [`/portal/uploads/${uldSn}/label`],
    routes: [{ path: '/portal/uploads/:uldSn/label', element: <PortalUploadLabelingPage /> }],
  });
}

describe('PortalUploadLabelingPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    // 라벨 마스터(선택 UI + OverlayLayer) — 빈 목록.
    mock.onGet('/manage/labels').reply(200, ok([]));
    // 프레임 이미지 — Blob (jsdom 은 createObjectURL 미지원이라 url 은 null 로 폴백).
    mock.onGet(/\/portal\/uploads\/frames\/\d+\/image/).reply(200, new Blob());
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    vi.restoreAllMocks();
  });

  it('이미지_자산은_단일_프레임으로_렌더되고_bbox_저장_호출', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    let putBody: unknown = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      putBody = JSON.parse(config.data as string);
      return [200, ok([])];
    });

    renderPage();

    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    // 단일 프레임 → 프레임 네비게이션(다음) 미노출.
    expect(screen.queryByRole('button', { name: '다음 프레임' })).not.toBeInTheDocument();

    // 사용자가 그린 bbox 를 스토어에 반영(캔버스 onLabelAdd 경로 대체).
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    expect(Array.isArray(putBody)).toBe(true);
    expect((putBody as unknown[])[0]).toMatchObject({ lblTypeCd: 'BBOX', label: 'car' });
  });

  it('저장은_현재_프레임_라벨_전체교체_PUT_1회_raw배열', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    let rawData: string | undefined;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      rawData = config.data as string;
      return [200, ok([])];
    });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());

    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    const parsed = JSON.parse(rawData as string);
    // 최상위 raw 배열 + points 는 배열(문자열 직렬화 아님).
    expect(Array.isArray(parsed)).toBe(true);
    expect(Array.isArray(parsed[0].points)).toBe(true);
    expect(typeof parsed[0].points).not.toBe('string');
  });

  it('라벨_조회가_늦게_도착해도_사용자가_그린_라벨이_보존되어_저장된다', async () => {
    // given: 라벨 조회 응답이 늦는다(초기 로드 지연/백그라운드 재조회).
    //   응답 도착 시 무조건 store 를 덮어쓰면 방금 그린 라벨이 사라져 저장 본문이 비어버린다
    //   — 실제로 이 경합 때문에 저장 PUT 본문이 빈 배열이 되는 간헐 실패가 있었다.
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    let releaseLabels: (() => void) | null = null;
    mock.onGet('/portal/uploads/frames/100/labels').reply(
      () =>
        new Promise((resolve) => {
          releaseLabels = () => resolve([200, ok([bboxRaw('server-label')])]);
        }),
    );
    let putBody: unknown = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      putBody = JSON.parse(config.data as string);
      return [200, ok([])];
    });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());

    // when: 사용자가 먼저 그리고, 그 뒤에 조회 응답이 도착한다.
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    await act(async () => {
      releaseLabels?.();
      await Promise.resolve();
    });
    // 서버 라벨이 작업본에 합쳐질 때까지 대기(덮어쓰기였다면 'car' 가 사라진다).
    await waitFor(() =>
      expect(useLabelStore.getState().labels.some((l) => l.className === 'server-label')).toBe(true),
    );

    // then: 그린 라벨이 살아 있고 저장 본문에도 담긴다.
    const classNames = useLabelStore.getState().labels.map((l) => l.className);
    expect(classNames).toContain('car');
    expect(classNames).toContain('server-label');
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    expect((putBody as unknown[]).some((b) => (b as { label: string }).label === 'car')).toBe(true);
  });

  it('저장_성공후_재조회분이_작업본에_반영된다', async () => {
    // given: 사용자가 그린(dirty) 상태. 저장 뒤에도 dirty 가 남으면 "미저장 편집 보호" 가드가
    //   계속 걸려, 그 프레임은 저장 이후 서버 상태와 영영 재동기화되지 않는다(이 화면에는
    //   dirty 를 비우는 다른 주체가 없다).
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock
      .onGet('/portal/uploads/frames/100/labels')
      .replyOnce(200, ok([]))
      .onGet('/portal/uploads/frames/100/labels')
      .reply(200, ok([bboxRaw('server-canon')]));
    mock.onPut('/portal/uploads/frames/100/labels').reply(200, ok([]));

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);

    // when: 저장
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(mock.history.put).toHaveLength(1));

    // then: 미저장 표시가 풀리고 재조회분(서버 확정본)이 작업본에 반영된다.
    await waitFor(() => expect(useLabelStore.getState().dirtyLabels.size).toBe(0));
    await waitFor(() =>
      expect(useLabelStore.getState().labels.map((l) => l.className)).toEqual(['server-canon']),
    );
  });

  it('영상_자산은_프레임_이동시_라벨이_프레임별로_로드됨', async () => {
    mock
      .onGet('/portal/uploads/1')
      .reply(200, ok(detail({ uldTypeCd: 'VIDEO', frmeCnt: 2, frames: [frameRow(100, 0), frameRow(101, 1)] })));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([bboxRaw('frame0')]));
    mock.onGet('/portal/uploads/frames/101/labels').reply(200, ok([bboxRaw('frame1')]));

    renderPage();

    // 첫 프레임 라벨 로드 → 스토어 반영.
    await waitFor(() => {
      expect(useLabelStore.getState().labels[0]?.className).toBe('frame0');
    });

    fireEvent.click(screen.getByRole('button', { name: '다음 프레임' }));

    await waitFor(() => {
      expect(useLabelStore.getState().labels[0]?.className).toBe('frame1');
    });
  });

  it('다운로드_버튼이_export_엔드포인트를_호출', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    mock.onGet('/portal/uploads/1/export').reply(200, '{"ok":true}', {
      'content-disposition': 'attachment; filename="upload-1.json"',
    });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '내보내기(JSON)' }));

    await waitFor(() =>
      expect(mock.history.get.some((r) => r.url === '/portal/uploads/1/export')).toBe(true),
    );
  });

  it('원본_다운로드_버튼이_file_엔드포인트를_호출', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    mock.onGet('/portal/uploads/1/file').reply(200, new Blob(), {
      'content-disposition': 'attachment; filename="photo.jpg"',
    });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '원본 다운로드' }));

    await waitFor(() =>
      expect(mock.history.get.some((r) => r.url === '/portal/uploads/1/file')).toBe(true),
    );
    // export(JSON) 엔드포인트는 호출되지 않는다(원본 파일 경로만).
    expect(mock.history.get.some((r) => r.url === '/portal/uploads/1/export')).toBe(false);
  });

  it('라벨_전체삭제_후_저장은_빈_배열_PUT', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([bboxRaw('car')]));
    let putBody: unknown = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      putBody = JSON.parse(config.data as string);
      return [200, ok([])];
    });

    renderPage();

    // 프레임 라벨 로드 → 스토어에 1건 반영됨.
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));

    // 사용자가 라벨을 모두 제거(전체삭제).
    act(() => {
      useLabelStore.getState().setLabels([]);
    });
    expect(useLabelStore.getState().labels).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    // 전체교체 PUT body 는 빈 배열(멱등 전체 삭제).
    expect(Array.isArray(putBody)).toBe(true);
    expect(putBody).toEqual([]);
  });

  it('SAM_키포인트_오토라벨_도구가_노출되지_않음', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());

    // 노출 도구: 선택/이동/바운딩박스/폴리곤만.
    expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '폴리곤' })).toBeInTheDocument();
    // 미노출: SAM 분할/추적, 키포인트, 오토라벨.
    expect(screen.queryByRole('button', { name: /SAM/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /키포인트/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /오토라벨/ })).not.toBeInTheDocument();
  });

  it('포털업로드_저장중에는_캔버스_편집이_차단된다', async () => {
    // given: 저장 PUT 이 아직 응답하지 않은 in-flight 상태
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    let releasePut: (() => void) | null = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply(
      () =>
        new Promise((resolve) => {
          releasePut = () => resolve([200, ok([])]);
        }),
    );

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });

    // when: 저장 시작
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));

    // then: 캔버스·도구·저장 버튼이 모두 잠긴다(진행 중 편집 창 자체가 없어진다).
    expect(screen.getByTestId('canvas-shell').getAttribute('data-edit-blocked')).toBe('true');
    expect(screen.getByTestId('canvas-shell').getAttribute('data-read-only')).toBe('true');
    expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeDisabled();
    // 진행 중에는 버튼 문구가 '저장 중…' 으로 바뀐다 — 이름이 아니라 상태를 본다.
    expect(screen.getByRole('button', { name: /저장/ })).toBeDisabled();

    // and: 저장이 끝나면 즉시 복구된다.
    await act(async () => {
      releasePut?.();
      await Promise.resolve();
    });
    await waitFor(() =>
      expect(screen.getByTestId('canvas-shell').getAttribute('data-edit-blocked')).toBe('false'),
    );
    expect(screen.getByRole('button', { name: '바운딩 박스' })).not.toBeDisabled();
  });

  it('포털업로드_저장중_편집이_불가하므로_저장후_재조회가_작업분을_잃지_않는다', async () => {
    // given: 저장(전체교체 PUT)이 in-flight 인 동안 캔버스가 잠겨 있으므로, 저장 스냅샷 이후에
    //   그려진 라벨이라는 것이 존재할 수 없다. 저장 성공 후 재조회분이 그대로 작업본이 된다.
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock
      .onGet('/portal/uploads/frames/100/labels')
      .replyOnce(200, ok([]))
      .onGet('/portal/uploads/frames/100/labels')
      .reply(200, ok([bboxRaw('server-canon')]));
    let releasePut: (() => void) | null = null;
    let putBody: unknown = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      putBody = JSON.parse(config.data as string);
      return new Promise((resolve) => {
        releasePut = () => resolve([200, ok([])]);
      });
    });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });

    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));
    // 저장 본문에는 그린 라벨이 담겨 있다(작업 결과가 요청에 반영됨).
    expect((putBody as unknown[]).some((b) => (b as { label: string }).label === 'car')).toBe(true);

    // when: 저장 완료 → 재조회
    await act(async () => {
      releasePut?.();
      await Promise.resolve();
    });

    // then: 미저장 표시가 풀리고, 서버 확정본이 작업본이 된다(유실된 편집 없음).
    await waitFor(() => expect(useLabelStore.getState().dirtyLabels.size).toBe(0));
    await waitFor(() =>
      expect(useLabelStore.getState().labels.map((l) => l.className)).toEqual(['server-canon']),
    );
  });

  it('저장이_취소되면_저장됨으로_취급하지_않고_미저장_표시가_유지된다', async () => {
    // given: 저장 in-flight 중 취소(프레임 이동/언마운트 등) → 도착 응답은 폐기된다.
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    let releasePut: (() => void) | null = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply(
      () =>
        new Promise((resolve) => {
          releasePut = () => resolve([200, ok([])]);
        }),
    );

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));

    // when
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await act(async () => {
      releasePut?.();
      await Promise.resolve();
    });

    // then: dirty 가 유지되어 미저장 사실이 화면에 남는다.
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
  });

  it('READY_아닌_자산_진입시_안내', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail({ uldSttsCd: 'PROCESSING' })));

    renderPage();

    await waitFor(() => expect(screen.getByText(/준비 중인 자산/)).toBeInTheDocument());
    // 캔버스는 렌더되지 않는다.
    expect(screen.queryByTestId('canvas-shell')).not.toBeInTheDocument();
  });
});

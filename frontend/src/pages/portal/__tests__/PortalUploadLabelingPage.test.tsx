// Phase 6 — 포털 업로드 자산 라벨링 화면 동선 검증.
// react-konva 는 jsdom 미지원이라 passthrough 로 mock (LabelingPage 테스트 관례와 동일).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, ...rest }: any) =>
      // eslint-disable-next-line react/no-children-prop
      React.createElement('div', { 'data-konva': name, ...rest }, children);
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
    expect(screen.getByRole('button', { name: '바운딩박스' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '폴리곤' })).toBeInTheDocument();
    // 미노출: SAM 분할/추적, 키포인트, 오토라벨.
    expect(screen.queryByRole('button', { name: /SAM/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /키포인트/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /오토라벨/ })).not.toBeInTheDocument();
  });

  it('READY_아닌_자산_진입시_안내', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail({ uldSttsCd: 'PROCESSING' })));

    renderPage();

    await waitFor(() => expect(screen.getByText(/준비 중인 자산/)).toBeInTheDocument());
    // 캔버스는 렌더되지 않는다.
    expect(screen.queryByTestId('canvas-shell')).not.toBeInTheDocument();
  });
});

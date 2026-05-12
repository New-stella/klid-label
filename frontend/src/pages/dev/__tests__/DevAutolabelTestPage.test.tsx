import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { DevAutolabelTestPage } from '@/pages/dev/DevAutolabelTestPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/** Blob → string (jsdom 호환). Blob.text() 미지원 환경 대비 FileReader 폴백. */
function readBlobAsText(blob: Blob): Promise<string> {
  if (typeof blob.text === 'function') {
    return blob.text();
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

/**
 * DevAutolabelTestPage 테스트.
 *
 * - 파일 미선택 → 실행 버튼 disabled
 * - 정상 제출 → POST /dev/autolabel-test 호출 + FormData 에 file/meta part 포함
 * - 400/409 BE 에러 → 메시지 표시
 * - 성공 시 rawSn 화면 표시
 *
 * 보안 검증: file 입력은 `accept="video/*"`, BE message 는 자동 이스케이프되어 표시.
 */
describe('DevAutolabelTestPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: {
        sub: '1001',
        role: 'REVIEWER',
        channel: 'INTERNAL',
        exp: 9999999999,
      },
      isHydrated: true,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  it('파일_미선택시_실행_버튼_disabled', () => {
    renderWithProviders(<DevAutolabelTestPage />);
    const button = screen.getByRole('button', { name: '실행' });
    expect(button).toBeDisabled();
  });

  it('정상_제출시_uploadAutolabelTest_호출_FormData에_file_meta_part_포함', async () => {
    const user = userEvent.setup();
    let capturedFormData: FormData | null = null;
    mock.onPost('/dev/autolabel-test').reply((config) => {
      capturedFormData =
        config.data instanceof FormData ? (config.data as FormData) : null;
      return [
        200,
        {
          success: true,
          data: {
            rawSn: 12345,
            savedFilePath: 'autolabel-test/abc-123.mp4',
            pipelineStatus: 'PROCESSING',
            startedAt: 1715520000000,
          },
          message: null,
          errorCode: null,
        },
      ];
    });
    // 영상 상세 polling 응답 (rawSn 받은 후 자동 호출)
    mock.onGet(/\/videos\/\d+$/).reply(200, {
      success: true,
      data: {
        id: 12345,
        rawSn: 12345,
        cctvName: 'CCTV-001',
        vmsClipId: 'test-clip-001',
        frameCount: 0,
        status: 'PENDING',
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

    const file = new File(['dummy'], 'test.mp4', { type: 'video/mp4' });
    const fileInput = document.getElementById(
      'autolabel-test-file',
    ) as HTMLInputElement;
    await user.upload(fileInput, file);

    await user.click(screen.getByRole('button', { name: '실행' }));

    await waitFor(() => {
      expect(capturedFormData).not.toBeNull();
    });

    // FormData 검증: file + meta part
    const fd = capturedFormData as unknown as FormData;
    const filePart = fd.get('file');
    const metaPart = fd.get('meta');
    expect(filePart).toBeInstanceOf(File);
    expect((filePart as File).name).toBe('test.mp4');
    expect(metaPart).toBeInstanceOf(Blob);
    expect((metaPart as Blob).type).toBe('application/json');

    // meta blob 내용 검증 — JSON 으로 직렬화되어 있어야 함
    // jsdom 환경에서 Blob.text() 폴리필이 없을 수 있어 FileReader 폴백 사용.
    const metaText = await readBlobAsText(metaPart as Blob);
    const meta = JSON.parse(metaText);
    expect(meta.vmsClipId).toMatch(/^test-/);
    expect(meta.cctvId).toBe('CCTV-001');
    expect(meta.eventTypeCd).toBe('EVT_FALL');
    expect(meta.localGovCd).toBe('11680');
    expect(meta.prvcTypeCd).toBe('ANONY');
    expect(meta.durationSec).toBe(60);
    // ISO-8601 instant 형식
    expect(typeof meta.capturedAt).toBe('string');
    expect(meta.capturedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  it('성공시_rawSn_화면_표시', async () => {
    const user = userEvent.setup();
    mock.onPost('/dev/autolabel-test').reply(200, {
      success: true,
      data: {
        rawSn: 7777,
        savedFilePath: 'autolabel-test/xyz.mp4',
        pipelineStatus: 'PROCESSING',
        startedAt: 1715520000000,
      },
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/videos\/\d+$/).reply(200, {
      success: true,
      data: {
        id: 7777,
        rawSn: 7777,
        cctvName: 'CCTV-001',
        vmsClipId: 'test-clip-001',
        frameCount: 30,
        status: 'PROGRESS',
        capturedAt: '2026-05-12T10:00:00Z',
        duration: 60,
        fileSizeMb: 10,
        resolution: '1920x1080',
        framePreviews: [],
        stages: [
          { name: 'FRAME_EXTRACT', status: 'DONE', progress: 100 },
          { name: 'YOLO', status: 'PROGRESS', progress: 40 },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<DevAutolabelTestPage />);

    const file = new File(['v'], 'clip.mp4', { type: 'video/mp4' });
    const fileInput = document.getElementById(
      'autolabel-test-file',
    ) as HTMLInputElement;
    await user.upload(fileInput, file);
    await user.click(screen.getByRole('button', { name: '실행' }));

    await waitFor(() => {
      expect(screen.getByTestId('autolabel-raw-sn')).toHaveTextContent(
        'rawSn = 7777',
      );
    });
    // 영상 상세 링크 노출
    expect(
      screen.getByRole('link', { name: /영상 상세 보기/ }),
    ).toHaveAttribute('href', '/video/7777');
  });

  it('BE_400_응답시_메시지_표시', async () => {
    const user = userEvent.setup();
    mock.onPost('/dev/autolabel-test').reply(400, {
      success: false,
      data: null,
      message: '허용되지 않는 확장자입니다.',
      errorCode: 'INVALID_INPUT',
    });

    renderWithProviders(<DevAutolabelTestPage />);
    // user.upload 는 accept 속성 기반으로 파일을 필터링하므로, mp4 mime 으로
    // 통과시키되 BE 가 400 으로 거절하는 시나리오를 검증한다.
    const file = new File(['v'], 'bad.mp4', { type: 'video/mp4' });
    const fileInput = document.getElementById(
      'autolabel-test-file',
    ) as HTMLInputElement;
    await user.upload(fileInput, file);
    await user.click(screen.getByRole('button', { name: '실행' }));

    await waitFor(() => {
      expect(screen.getByTestId('autolabel-error')).toHaveTextContent(
        '허용되지 않는 확장자입니다.',
      );
    });
    // 결과 영역은 노출되지 않음
    expect(screen.queryByTestId('autolabel-raw-sn')).not.toBeInTheDocument();
  });

  it('BE_409_vmsClipId_중복_메시지_표시', async () => {
    const user = userEvent.setup();
    mock.onPost('/dev/autolabel-test').reply(409, {
      success: false,
      data: null,
      message: 'vmsClipId 가 이미 존재합니다.',
      errorCode: 'CONFLICT',
    });

    renderWithProviders(<DevAutolabelTestPage />);
    const file = new File(['v'], 'dup.mp4', { type: 'video/mp4' });
    const fileInput = document.getElementById(
      'autolabel-test-file',
    ) as HTMLInputElement;
    await user.upload(fileInput, file);
    await user.click(screen.getByRole('button', { name: '실행' }));

    await waitFor(() => {
      expect(screen.getByTestId('autolabel-error')).toHaveTextContent(
        'vmsClipId 가 이미 존재합니다.',
      );
    });
  });
});

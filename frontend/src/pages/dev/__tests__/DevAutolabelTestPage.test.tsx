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

  it('신_시나리오_비식별은_선두_무조건이므로_비식별_대상_아님_문구가_없음', () => {
    renderWithProviders(<DevAutolabelTestPage />);
    // 구 시나리오 문구 부재 — 비식별은 적재 직후 선두·무조건 실행
    expect(screen.queryByText(/비식별 대상 아님/)).not.toBeInTheDocument();
  });

  it('ANONY_선택시에도_비식별_단계_토글이_표시됨', () => {
    renderWithProviders(<DevAutolabelTestPage />);
    // ANONY 라디오 라벨이 신 의미로 노출 (prvcTypeCd 는 표시용, 게이팅 미사용)
    expect(screen.getByText(/ANONY \(비식별 미적용\)/)).toBeInTheDocument();
    // 비식별 단계 토글은 prvcTypeCd 와 무관하게 항상 표시
    expect(
      screen.getByTestId('autolabel-stage-toggle-DEIDENTIFY'),
    ).toBeInTheDocument();
  });

  it('DEIDENTIFY가_토글_목록_선두에_표시됨_신_순서', () => {
    renderWithProviders(<DevAutolabelTestPage />);
    const group = screen.getByRole('group', { name: '배치 단계 토글' });
    const toggles = group.querySelectorAll('[data-testid^="autolabel-stage-toggle-"]');
    const keys = Array.from(toggles).map((el) =>
      el.getAttribute('data-testid')?.replace('autolabel-stage-toggle-', ''),
    );
    // 신 순서: DEIDENTIFY → FRAME_EXTRACT → YOLO → SAM2
    expect(keys).toEqual(['DEIDENTIFY', 'FRAME_EXTRACT', 'YOLO', 'SAM2']);
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
    // durationSec 는 BE 가 ffprobe 로 자동 추출 → FE meta 에 포함되지 않음
    expect(meta).not.toHaveProperty('durationSec');
    // ISO-8601 instant 형식
    expect(typeof meta.capturedAt).toBe('string');
    expect(meta.capturedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    // enabledStages 기본값 — 4단계 모두 ON
    expect(meta.enabledStages).toEqual({
      FRAME_EXTRACT: true,
      DEIDENTIFY: true,
      YOLO: true,
      SAM2: true,
    });
  });

  it('단계_토글_OFF시_meta_enabledStages에_false_전송', async () => {
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
            rawSn: 11111,
            savedFilePath: 'autolabel-test/toggle.mp4',
            pipelineStatus: 'PROCESSING',
            startedAt: 1715520000000,
          },
          message: null,
          errorCode: null,
        },
      ];
    });
    mock.onGet(/\/videos\/\d+$/).reply(200, {
      success: true,
      data: {
        id: 11111,
        rawSn: 11111,
        cctvName: 'CCTV-001',
        vmsClipId: 'test-toggle',
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

    const file = new File(['v'], 'toggle.mp4', { type: 'video/mp4' });
    const fileInput = document.getElementById(
      'autolabel-test-file',
    ) as HTMLInputElement;
    await user.upload(fileInput, file);

    // YOLO + SAM2 토글 OFF
    const yoloLabel = screen.getByTestId('autolabel-stage-toggle-YOLO');
    const sam2Label = screen.getByTestId('autolabel-stage-toggle-SAM2');
    await user.click(yoloLabel.querySelector('input[type=checkbox]')!);
    await user.click(sam2Label.querySelector('input[type=checkbox]')!);

    await user.click(screen.getByRole('button', { name: '실행' }));

    await waitFor(() => expect(capturedFormData).not.toBeNull());
    const metaPart = (capturedFormData as unknown as FormData).get('meta') as Blob;
    const meta = JSON.parse(await readBlobAsText(metaPart));
    expect(meta.enabledStages).toEqual({
      FRAME_EXTRACT: true,
      DEIDENTIFY: true,
      YOLO: false,
      SAM2: false,
    });
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

  it('파이프라인_FAILED_상태_감지시_에러_박스_노출', async () => {
    const user = userEvent.setup();
    mock.onPost('/dev/autolabel-test').reply(200, {
      success: true,
      data: {
        rawSn: 8888,
        savedFilePath: 'autolabel-test/fail.mp4',
        pipelineStatus: 'PROCESSING',
        startedAt: 1715520000000,
      },
      message: null,
      errorCode: null,
    });
    // 영상 상세 polling — FAILED 상태로 응답
    mock.onGet(/\/videos\/\d+$/).reply(200, {
      success: true,
      data: {
        id: 8888,
        rawSn: 8888,
        cctvName: 'CCTV-001',
        vmsClipId: 'test-clip-fail',
        frameCount: 0,
        status: 'FAILED',
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

    const file = new File(['v'], 'fail.mp4', { type: 'video/mp4' });
    const fileInput = document.getElementById(
      'autolabel-test-file',
    ) as HTMLInputElement;
    await user.upload(fileInput, file);
    await user.click(screen.getByRole('button', { name: '실행' }));

    await waitFor(() => {
      expect(
        screen.getByTestId('autolabel-pipeline-failed'),
      ).toHaveTextContent('파이프라인 실행에 실패했습니다');
    });
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

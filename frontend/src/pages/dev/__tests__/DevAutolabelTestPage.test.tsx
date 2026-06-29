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
 * DevAutolabelTestPage 테스트 (dev 업로드 단순화 — 운영 시나리오 1:1 고정 플로우).
 *
 * 고정 플로우: 업로드 → 비식별(무조건) → MARKING_READY 정지. 단계 토글/마킹 직접 수행
 * 체크박스는 제거되었으며, meta 에 enabledStages/manualMarking 을 전송하지 않는다.
 * 폴링 terminal = MARKING_READY 또는 FAILED. MARKING_READY 도달 시 마킹 대기 안내 + 진입 링크 노출.
 *
 * 보안 검증: file 입력은 `accept` 화이트리스트, BE message 는 자동 이스케이프되어 표시.
 */
// 관제 이벤트 타입 카테고리 옵션 — useEventTypes() 소스. select value=categoryKey,
// 제출 payload eventTypeCd=memberCodes[0].
const EVENT_CATEGORIES = [
  { categoryKey: '020002', label: '쓰러짐', memberCodes: ['EV02000201'] },
  { categoryKey: '050001', label: '싸움', memberCodes: ['EV05000101'] },
  { categoryKey: '030001', label: '교통사고', memberCodes: ['EV03000101'] },
];

describe('DevAutolabelTestPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // 이벤트 타입 카테고리 옵션 — 화면 select + 제출 EV-코드 변환에 필요.
    mock.onGet('/event-types').reply(200, {
      success: true,
      data: EVENT_CATEGORIES,
      message: null,
      errorCode: null,
    });
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

  it('파일_미선택시_업로드_버튼_disabled', () => {
    renderWithProviders(<DevAutolabelTestPage />);
    const button = screen.getByRole('button', { name: '업로드' });
    expect(button).toBeDisabled();
  });

  it('단계_선택_fieldset과_마킹수동_체크박스가_없음', () => {
    renderWithProviders(<DevAutolabelTestPage />);
    // 단계 토글 fieldset 제거 — 고정 플로우라 단계 선택 UI 없음
    expect(screen.queryByText('실행 단계 선택')).not.toBeInTheDocument();
    expect(
      screen.queryByTestId('autolabel-stage-toggle-DEIDENTIFY'),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId('autolabel-stage-toggle-YOLO'),
    ).not.toBeInTheDocument();
    // 마킹 직접 수행(수동) 체크박스 제거
    expect(
      screen.queryByTestId('autolabel-manual-marking'),
    ).not.toBeInTheDocument();
  });

  it('정상_제출시_uploadAutolabelTest_호출_FormData에_file_meta_part_포함_토글필드_미전송', async () => {
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

    await user.click(screen.getByRole('button', { name: '업로드' }));

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
    const metaText = await readBlobAsText(metaPart as Blob);
    const meta = JSON.parse(metaText);
    expect(meta.vmsClipId).toMatch(/^test-/);
    expect(meta.cctvId).toBe('CCTV-001');
    // 카테고리(첫 옵션 020002) → 대표 EV-코드(memberCodes[0]) 전송 — 구 EVT_* 미전송.
    expect(meta.eventTypeCd).toBe('EV02000201');
    expect(meta.eventTypeCd).not.toMatch(/^EVT_/);
    expect(meta.localGovCd).toBe('11680');
    expect(meta.prvcTypeCd).toBe('ANONY');
    // durationSec 는 BE 가 ffprobe 로 자동 추출 → FE meta 에 포함되지 않음
    expect(meta).not.toHaveProperty('durationSec');
    // ISO-8601 instant 형식
    expect(typeof meta.capturedAt).toBe('string');
    expect(meta.capturedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    // 고정 플로우 — 단계 토글/마킹 분기 필드는 전송하지 않는다
    expect(meta).not.toHaveProperty('enabledStages');
    expect(meta).not.toHaveProperty('manualMarking');
  });

  it('업로드후_MARKING_READY_도달하면_폴링_종료_마킹대기_안내와_마킹화면_링크_표시', async () => {
    const user = userEvent.setup();
    let pollCount = 0;
    mock.onPost('/dev/autolabel-test').reply(200, {
      success: true,
      data: {
        rawSn: 44444,
        savedFilePath: 'autolabel-test/ready.mp4',
        pipelineStatus: 'PROCESSING',
        startedAt: 1715520000000,
      },
      message: null,
      errorCode: null,
    });
    // 고정 플로우 — 비식별 후 파이프라인이 MARKING_READY 에서 정지한다.
    mock.onGet(/\/videos\/\d+$/).reply(() => {
      pollCount += 1;
      return [
        200,
        {
          success: true,
          data: {
            id: 44444,
            rawSn: 44444,
            cctvName: 'CCTV-001',
            vmsClipId: 'test-ready',
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
        },
      ];
    });

    renderWithProviders(<DevAutolabelTestPage />);
    const file = new File(['v'], 'ready.mp4', { type: 'video/mp4' });
    const fileInput = document.getElementById(
      'autolabel-test-file',
    ) as HTMLInputElement;
    await user.upload(fileInput, file);
    await user.click(screen.getByRole('button', { name: '업로드' }));

    // 마킹 대기 안내가 표시되고 (terminal 도달)
    await waitFor(() => {
      expect(
        screen.getByTestId('autolabel-marking-ready'),
      ).toHaveTextContent('마킹 대기');
    });
    // 마킹 화면 진입 링크 노출
    expect(
      screen.getByRole('link', { name: /마킹 화면/ }),
    ).toHaveAttribute('href', '/marking/44444');
    // 영상 목록 링크도 노출 (FE-4 — `/video` 는 index 라우트가 없어 404. `/video/completed` 로 수정)
    expect(
      screen.getByRole('link', { name: /영상 목록/ }),
    ).toHaveAttribute('href', '/video/completed');
    // 진행 스피너는 사라진다 (무한 폴링 방지 — terminal 도달)
    expect(
      screen.queryByLabelText('파이프라인 진행 중'),
    ).not.toBeInTheDocument();

    // 폴링이 멈췄는지 확인 — 추가 시간 경과해도 호출 수가 더 늘지 않음.
    const countAtTerminal = pollCount;
    await new Promise((r) => setTimeout(r, 2200));
    expect(pollCount).toBe(countAtTerminal);
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

    const file = new File(['v'], 'clip.mp4', { type: 'video/mp4' });
    const fileInput = document.getElementById(
      'autolabel-test-file',
    ) as HTMLInputElement;
    await user.upload(fileInput, file);
    await user.click(screen.getByRole('button', { name: '업로드' }));

    await waitFor(() => {
      expect(screen.getByTestId('autolabel-raw-sn')).toHaveTextContent(
        'rawSn = 7777',
      );
    });
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
    const file = new File(['v'], 'bad.mp4', { type: 'video/mp4' });
    const fileInput = document.getElementById(
      'autolabel-test-file',
    ) as HTMLInputElement;
    await user.upload(fileInput, file);
    await user.click(screen.getByRole('button', { name: '업로드' }));

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
    await user.click(screen.getByRole('button', { name: '업로드' }));

    await waitFor(() => {
      expect(
        screen.getByTestId('autolabel-pipeline-failed'),
      ).toHaveTextContent('파이프라인 실행에 실패했습니다');
    });
  });

  it('dev_업로드_이벤트_select가_관제카테고리를_렌더하고_제출시_EV코드를_보낸다', async () => {
    const user = userEvent.setup();
    let capturedMeta: Record<string, unknown> | null = null;
    mock.onPost('/dev/autolabel-test').reply(async (config) => {
      const fd = config.data as FormData;
      const blob = fd.get('meta') as Blob;
      capturedMeta = JSON.parse(await readBlobAsText(blob));
      return [
        200,
        {
          success: true,
          data: {
            rawSn: 33333,
            savedFilePath: 'autolabel-test/cat.mp4',
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
        id: 33333,
        rawSn: 33333,
        cctvName: 'CCTV-001',
        vmsClipId: 'test-cat',
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

    // 메인 폼의 이벤트 select (TusUploadPanel 에도 동일 라벨 select 가 있어 id 로 한정).
    const select = document.getElementById(
      'autolabel-test-event',
    ) as HTMLSelectElement;
    // 관제 카테고리 옵션이 렌더된다 (구 EVT_* 옵션 부재).
    await waitFor(() => {
      const labels = Array.from(select.options).map((o) => o.textContent);
      expect(labels).toEqual(
        expect.arrayContaining(['쓰러짐', '싸움', '교통사고']),
      );
    });
    expect(
      Array.from(select.options).some((o) => /EVT_/.test(o.value)),
    ).toBe(false);

    // 교통사고(030001) 선택 → 제출 시 EV03000101 전송.
    await user.selectOptions(select, '030001');

    const file = new File(['v'], 'cat.mp4', { type: 'video/mp4' });
    const fileInput = document.getElementById(
      'autolabel-test-file',
    ) as HTMLInputElement;
    await user.upload(fileInput, file);
    await user.click(screen.getByRole('button', { name: '업로드' }));

    await waitFor(() => {
      expect(capturedMeta).not.toBeNull();
    });
    const sentMeta = capturedMeta as unknown as Record<string, unknown>;
    expect(sentMeta.eventTypeCd).toBe('EV03000101');
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
    await user.click(screen.getByRole('button', { name: '업로드' }));

    await waitFor(() => {
      expect(screen.getByTestId('autolabel-error')).toHaveTextContent(
        'vmsClipId 가 이미 존재합니다.',
      );
    });
  });
});

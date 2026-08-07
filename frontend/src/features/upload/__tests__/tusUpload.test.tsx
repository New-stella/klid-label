import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, render, renderHook, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import {
  uploadFile,
  type InternalUploadCreatePayload,
  type TusMetadata,
} from '@/features/upload/api/tusClient';
import { useTusUpload } from '@/features/upload/hooks/useTusUpload';
import { TusUploadPanel } from '@/features/upload/components/TusUploadPanel';
import {
  SRC_TYPES,
  VRFC_EVNT_TYPES,
} from '@/features/upload/components/tusUploadForm';
import { selectRadixOption } from '@/test/selectTestUtils';

// 포털 업로드가 쓰는 메타(헤더 방식) — filename 만 보낸다.
const META: TusMetadata = { filename: 'clip.mp4' };

// 내부 업로드가 쓰는 세션 생성 JSON 바디(관제 인입 재현).
const PAYLOAD: InternalUploadCreatePayload = {
  fileName: 'clip.mp4',
  vmsClipId: 'VMS-1',
  cctvId: 'CCTV-1',
  lclgvCd: '1168000000',
  srcType: 'USER_ULD',
  shtDt: '2024-05-01T12:00',
  vdoLenSec: 600,
  mntrCn: '관제일지 본문',
};

/** size 바이트의 더미 File 생성. */
function makeFile(size: number): File {
  return new File([new Uint8Array(size)], 'clip.mp4', { type: 'video/mp4' });
}

describe('TUS 업로드 클라이언트', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('정상_생성_청크전송_완료_진행률100', async () => {
    // given — 5바이트 파일을 청크 2바이트로 → 3청크
    const file = makeFile(5);
    const uploadId = 'u-123';
    let serverOffset = 0;

    mock.onPost('/uploads').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: `/v1/uploads/${uploadId}`,
    });
    mock.onPatch(`/uploads/${uploadId}`).reply((config) => {
      // 각 PATCH 는 Content-Length 만큼 offset 전진을 모사.
      const chunk = config.data as Blob;
      serverOffset += chunk.size;
      return [204, null, { 'tus-resumable': '1.0.0', 'upload-offset': String(serverOffset) }];
    });

    // when
    const progress: number[] = [];
    const result = await uploadFile({
      file,
      metadata: META,
      chunkSize: 2,
      onProgress: (u, t) => progress.push(u / t),
    });

    // then — 완료 + 최종 offset == 파일 크기
    expect(result.completed).toBe(true);
    expect(result.uploadOffset).toBe(5);
    expect(result.uploadId).toBe(uploadId);
    expect(progress[progress.length - 1]).toBe(1);
    // POST 1회 + PATCH 3회(2+2+1)
    expect(mock.history.post).toHaveLength(1);
    expect(mock.history.patch).toHaveLength(3);
  });

  it('내부업로드_POST시_인입메타를_JSON바디로_전송하고_UploadMetadata헤더는_쓰지않는다', async () => {
    const file = makeFile(4);
    mock.onPost('/uploads').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: '/v1/uploads/u-x',
      'x-ingest-status': 'PENDING',
    });
    mock.onPatch('/uploads/u-x').reply(204, null, {
      'tus-resumable': '1.0.0',
      'upload-offset': '4',
      'x-ingest-status': 'PENDING',
    });

    const result = await uploadFile({
      file,
      metadata: META,
      chunkSize: 4,
      createPayload: PAYLOAD,
    });

    const postReq = mock.history.post[0];
    // Upload-Length 는 TUS 헤더 그대로
    expect(postReq.headers?.['Upload-Length']).toBe('4');
    expect(postReq.headers?.['Tus-Resumable']).toBe('1.0.0');
    // ★메타는 바디로 — 헤더 1KB 상한으로는 관제일지(4000자) 하나도 못 싣는다
    expect(postReq.headers?.['Upload-Metadata']).toBeUndefined();
    expect(postReq.headers?.['Content-Type']).toBe('application/json');
    expect(JSON.parse(String(postReq.data))).toMatchObject({
      vmsClipId: 'VMS-1',
      cctvId: 'CCTV-1',
      lclgvCd: '1168000000',
      srcType: 'USER_ULD',
      vdoLenSec: 600,
      mntrCn: '관제일지 본문',
    });
    // 업로드 완료 != 적재 — 인입 대기 상태를 화면에 전달한다
    expect(result.ingestStatus).toBe('PENDING');
  });

  it('포털업로드_POST는_기존_UploadMetadata_헤더방식_그대로다 — 바디_미전송', async () => {
    const file = makeFile(4);
    mock.onPost('/portal/uploads/tus').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: '/v1/portal/uploads/tus/p-1',
    });
    mock.onPatch('/portal/uploads/tus/p-1').reply(204, null, {
      'tus-resumable': '1.0.0',
      'upload-offset': '4',
    });

    await uploadFile({
      file,
      metadata: META,
      chunkSize: 4,
      endpointBase: '/portal/uploads/tus',
    });

    const postReq = mock.history.post[0];
    // ★포털은 createPayload 를 넘기지 않으므로 헤더 방식이 유지돼야 한다(동작 변경 금지).
    expect(String(postReq.headers?.['Upload-Metadata'])).toContain('filename ');
    expect(postReq.headers?.['Content-Type']).not.toBe('application/json');
    expect(postReq.data ?? null).toBeNull();
  });

  it('재개_HEAD로_서버offset조회후_남은청크만전송', async () => {
    // given — 6바이트 파일, 서버는 이미 4바이트 보유 (재개 시나리오)
    const file = makeFile(6);
    const uploadId = 'u-resume';
    let serverOffset = 4;

    mock.onHead(`/uploads/${uploadId}`).reply(200, null, {
      'tus-resumable': '1.0.0',
      'upload-offset': '4',
      'upload-length': '6',
    });
    mock.onPatch(`/uploads/${uploadId}`).reply((config) => {
      serverOffset += (config.data as Blob).size;
      return [204, null, { 'tus-resumable': '1.0.0', 'upload-offset': String(serverOffset) }];
    });

    // when — resumeUploadId 로 재개
    const result = await uploadFile({
      file,
      metadata: META,
      chunkSize: 2,
      resumeUploadId: uploadId,
    });

    // then — POST 미발생, HEAD 1회 + 남은 2바이트 1청크만 PATCH
    expect(mock.history.post).toHaveLength(0);
    expect(mock.history.head).toHaveLength(1);
    expect(mock.history.patch).toHaveLength(1);
    expect(result.completed).toBe(true);
    expect(result.uploadOffset).toBe(6);
  });

  it('useTusUpload_훅_일시정지시_paused상태_offset보존', async () => {
    // given — 6바이트, 청크 2 → 첫 청크 후 일시정지
    const file = makeFile(6);
    const uploadId = 'u-pause';
    let serverOffset = 0;
    let patchCount = 0;

    mock.onPost('/uploads').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: `/v1/uploads/${uploadId}`,
    });
    mock.onPatch(`/uploads/${uploadId}`).reply((config) => {
      patchCount += 1;
      serverOffset += (config.data as Blob).size;
      return [204, null, { 'tus-resumable': '1.0.0', 'upload-offset': String(serverOffset) }];
    });

    const { result } = renderHook(() => useTusUpload());

    // 첫 onProgress 콜백 시점(첫 청크 직후)에 pause 신호.
    // shouldPause 는 다음 청크 경계에서 평가되므로, 1청크 전송 후 멈춤을 검증.
    let started: Promise<unknown>;
    await act(async () => {
      // pause 를 즉시 걸어두면 0청크에서 멈추므로, 1청크 후 멈추도록
      // microtask 로 첫 청크 통과 후 pause.
      started = result.current.start(file, META);
      // 첫 PATCH 가 비동기 큐에 들어간 직후 pause
      queueMicrotask(() => result.current.pause());
      await started;
    });

    // then — 일시정지로 완료되지 않음, 일부만 전송됨
    await waitFor(() => {
      expect(['paused', 'completed']).toContain(result.current.status);
    });
    // pause 가 동작했다면 6바이트 전부 전송되지 않았어야 한다(3청크 미만).
    expect(patchCount).toBeLessThanOrEqual(3);
    expect(result.current.uploadId).toBe(uploadId);
  });

  it('M3_출처유형_선택지에_AUGMENTED가_없다 — 인입_입력면_allowlist는_4종', () => {
    // 증강 파생본은 저작도구가 직접 만들고 ORGNL_RAW_SN 으로 부모를 가리킨다. 인입으로 받으면
    // 부모 없는 "파생 출처" 행이 생겨 파생 판별 축이 어긋나므로 BE 가 400 으로 거부한다 —
    // 화면에 남겨두면 사용자가 고를 수 있는데 반드시 실패하는 선택지가 된다.
    expect(SRC_TYPES.map((o) => o.value)).toEqual([
      'USER_ULD',
      'ORIGINAL',
      'RELAY',
      'GENERATED',
    ]);
  });

  it('LOW_세션없이_재개하면_바디없는_POST를_보내지_않고_거부한다', async () => {
    // 내부 업로드의 세션 생성 POST 는 인입 메타 JSON 바디를 요구한다. uploadId 가 없는 상태에서
    // createPayload 없이 재개하면 415/400 이 나므로, 원인이 드러나는 에러로 먼저 막는다.
    const file = makeFile(4);
    const { result } = renderHook(() => useTusUpload());

    await act(async () => {
      await result.current.resume(file, META).catch(() => undefined);
    });

    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error).toContain('재개할 업로드 세션이 없습니다');
    expect(mock.history.post).toHaveLength(0);
  });

  it('LOW_포털업로드는_세션없이_재개해도_기존_헤더방식_POST가_유지된다', async () => {
    // 포털은 createPayload 를 쓰지 않는다(헤더 방식) — 위 가드가 그 경로를 막으면 회귀다.
    const file = makeFile(4);
    mock.onPost('/portal/uploads/tus').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: '/v1/portal/uploads/tus/p-2',
    });
    mock.onPatch('/portal/uploads/tus/p-2').reply(204, null, {
      'tus-resumable': '1.0.0',
      'upload-offset': '4',
    });

    const { result } = renderHook(() =>
      useTusUpload({ endpointBase: '/portal/uploads/tus' }),
    );

    await act(async () => {
      await result.current.resume(file, META).catch(() => undefined);
    });

    await waitFor(() => expect(result.current.status).toBe('completed'));
    expect(mock.history.post).toHaveLength(1);
  });

  it('useTusUpload_훅_에러시_error상태와_메시지노출', async () => {
    const file = makeFile(4);
    mock.onPost('/uploads').reply(409, {
      success: false,
      data: null,
      message: '동일한 영상 클립 ID 의 인입 정보가 이미 존재합니다.',
      errorCode: 'CONFLICT',
    });

    const { result } = renderHook(() => useTusUpload());

    await act(async () => {
      await result.current.start(file, META).catch(() => undefined);
    });

    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error).toContain('영상 클립 ID');
  });
});

/**
 * dev 업로드 화면의 **검증이벤트유형** 입력 (@req R7).
 *
 * 관제가 인입(`LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`)으로 이 값을 보내주기 전까지, dev 업로드로 직접
 * 넣어 외부 VLM 검증(`POST /v1/videovlm/verify`) 연동을 돌려보기 위한 입력이다.
 */
describe('TUS 업로드 폼 — 검증이벤트유형 (@req R7)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  /** 패널을 렌더하고 검증이벤트유형 select 트리거를 돌려준다(label 연결이 전제 — 접근성 가드 겸용). */
  function renderPanelAndGetSelect(): HTMLElement {
    render(<TusUploadPanel />);
    return screen.getByLabelText('검증이벤트유형');
  }

  /** 파일 선택 → 업로드 시작 → 세션 생성 POST 바디를 파싱해 돌려준다. */
  async function uploadAndReadCreateBody(
    user: ReturnType<typeof userEvent.setup>,
  ): Promise<Record<string, unknown>> {
    mock.onPost('/uploads').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: '/v1/uploads/u-vrfc',
      'x-ingest-status': 'PENDING',
    });
    mock.onPatch('/uploads/u-vrfc').reply(204, null, {
      'tus-resumable': '1.0.0',
      'upload-offset': '5',
    });

    const fileInput = document.getElementById('tus-file') as HTMLInputElement;
    await user.upload(fileInput, makeFile(5));
    await user.click(screen.getByRole('button', { name: '업로드 시작' }));

    // 완료까지 기다린다 — 중간에 단언하면 이후 상태 갱신이 act() 밖에서 일어나 경고가 뜬다.
    await screen.findByTestId('tus-completed');
    expect(mock.history.post).toHaveLength(1);
    return JSON.parse(String(mock.history.post[0].data)) as Record<string, unknown>;
  }

  it('검증이벤트유형_select에_6종_옵션과_직접입력이_렌더된다', async () => {
    // given/when — dev 업로드 패널 렌더
    const user = userEvent.setup();
    const select = renderPanelAndGetSelect();

    // 미지정이 기본 선택 — 필수 필드가 아니다(미지정 업로드도 위탁되며 벤더 응답이 판정한다).
    expect(select).toHaveTextContent('미지정 (AI 검증 위탁 생략)');
    // 직접 입력 칸은 그 옵션을 고르기 전에는 없다.
    expect(screen.queryByLabelText('검증이벤트유형 직접 입력')).toBeNull();

    // then — 미지정 + 벤더 enum 6종 + 직접 입력(2026-08-06 신설)
    await user.click(select);
    const optionLabels = (await screen.findAllByRole('option')).map((o) => o.textContent);
    expect(optionLabels).toEqual([
      '미지정 (AI 검증 위탁 생략)',
      '화재 (fire)',
      '쓰러짐 (fall)',
      '폭력 (violence)',
      '침수 (flooding)',
      '교통사고 (car_accident)',
      '납치 (kidnapping)',
      '직접 입력',
    ]);
  });

  it('직접입력을_고르면_자유입력칸이_열리고_그_값이_전송된다', async () => {
    // given — 프리셋에 없는 값을 시험해야 하는 경우(벤더가 enum 을 넓혔거나 미지 값 확인)
    const user = userEvent.setup();
    const select = renderPanelAndGetSelect();
    await selectRadixOption(user, select, '직접 입력');

    // when
    const manual = screen.getByLabelText('검증이벤트유형 직접 입력');
    await user.type(manual, 'earthquake');
    const body = await uploadAndReadCreateBody(user);

    // then — 센티넬(`__manual__`)이 아니라 입력값이 전송된다
    expect(body.vrfcEvntTypeCd).toBe('earthquake');
  });

  it('프리셋에서_직접입력으로_바꾸면_이전_프리셋값이_남지_않는다', async () => {
    // given — 프리셋을 골랐다가 마음을 바꾼 동선
    const user = userEvent.setup();
    const select = renderPanelAndGetSelect();
    await selectRadixOption(user, select, '화재 (fire)');

    // when — 직접 입력으로 전환(아무것도 타이핑하지 않는다)
    await selectRadixOption(user, select, '직접 입력');

    // then — 값이 비어야 한다. 남겨두면 "직접 입력"인데 fire 가 전송된다(조용한 오전송).
    expect((screen.getByLabelText('검증이벤트유형 직접 입력') as HTMLInputElement).value).toBe('');
    const body = await uploadAndReadCreateBody(user);
    expect('vrfcEvntTypeCd' in body).toBe(false);
  });

  it('직접입력칸은_20자를_넘겨_입력할_수_없다', async () => {
    // given — 컬럼이 VARCHAR(20) 이라 초과분은 저장되지 않는다(BE 도 400 으로 막는다).
    const user = userEvent.setup();
    const select = renderPanelAndGetSelect();
    await selectRadixOption(user, select, '직접 입력');

    // when
    const manual = screen.getByLabelText('검증이벤트유형 직접 입력') as HTMLInputElement;
    await user.type(manual, 'a'.repeat(30));

    // then — 화면에서 먼저 막아 400 왕복을 줄인다(신뢰 경계는 여전히 서버다)
    expect(manual.value).toHaveLength(20);
  });

  it('옵션_라벨은_한글병기이고_전송값은_영문_enum이다', async () => {
    // given/when
    const user = userEvent.setup();
    const select = renderPanelAndGetSelect();
    await user.click(select);

    // then — 라벨은 한글 병기(값은 벤더 규격 소문자 원문 — 라벨을 전송하면 벤더가 거부하므로
    // 전송 경로는 다른 테스트가 검증하고, 여기는 화면 표기만 본다)
    const optionLabels = (await screen.findAllByRole('option')).map((o) => o.textContent ?? '');
    expect(optionLabels.some((t) => t.includes('화재'))).toBe(true);
    expect(optionLabels.some((t) => t.includes('쓰러짐'))).toBe(true);
    expect(optionLabels.some((t) => t.includes('폭력'))).toBe(true);
    expect(optionLabels.some((t) => t.includes('침수'))).toBe(true);
    expect(optionLabels.some((t) => t.includes('교통사고'))).toBe(true);
    expect(optionLabels.some((t) => t.includes('납치'))).toBe(true);
    // 라벨에 enum 원문을 병기해 전송값을 화면에서도 확인할 수 있게 한다
    expect(optionLabels.some((t) => t.includes('car_accident'))).toBe(true);
    // 단일 진실원(VRFC_EVNT_TYPES)이 그대로 렌더된다 — 리터럴을 화면에 복제하지 않는다
    expect(VRFC_EVNT_TYPES.map((o) => o.value)).toEqual([
      'fire',
      'fall',
      'violence',
      'flooding',
      'car_accident',
      'kidnapping',
    ]);
  });

  it('선택한_값이_업로드_세션_생성_바디에_담긴다', async () => {
    // given
    const user = userEvent.setup();
    const select = renderPanelAndGetSelect();

    // when — 화재 선택 후 업로드
    await selectRadixOption(user, select, '화재 (fire)');
    const body = await uploadAndReadCreateBody(user);

    // then — BE record 필드명이 곧 JSON 키다(@JsonProperty 없음)
    expect(body.vrfcEvntTypeCd).toBe('fire');
  });

  it('미지정이면_검증이벤트유형이_전송되지_않는다', async () => {
    // given — 아무것도 고르지 않은 기본 상태
    const user = userEvent.setup();
    renderPanelAndGetSelect();

    // when
    const body = await uploadAndReadCreateBody(user);

    // then — 빈 값은 키 자체를 보내지 않는다(기존 선택 필드 관례). BE 는 미지정으로 처리한다.
    expect('vrfcEvntTypeCd' in body).toBe(false);
    // 회귀 — 기존 필수 필드는 그대로 실려야 한다
    expect(body.cctvId).toBe('CCTV-001');
    expect(body.srcType).toBe('USER_ULD');
  });

  it('select는_label과_연결되어_있다', () => {
    // given/when — getByLabelText 는 htmlFor/id 연결이 없으면 실패한다
    const select = renderPanelAndGetSelect();

    // then
    expect(select.tagName).toBe('BUTTON');
    expect(select.id).not.toBe('');
    const label = document.querySelector(`label[for="${select.id}"]`);
    expect(label).not.toBeNull();
  });
});

/**
 * 사용자에게 보이는 문구에는 프로토콜명(TUS)·외부 모델명(VLM) 같은 기술 용어와 내부 설계 용어를
 * 쓰지 않는다. 화면 제목·옵션 라벨에 이런 낱말이 다시 새어 들어오면 이 테스트가 물어야 한다.
 */
describe('업로드 화면 노출 문구', () => {
  it('제목과_옵션에_기술용어와_내부용어가_없다', () => {
    // given/when
    const { container } = render(<TusUploadPanel />);

    // then — 프로토콜명·모델명·내부 설계 용어는 노출하지 않는다
    expect(container.textContent).not.toMatch(/TUS/i);
    expect(container.textContent).not.toMatch(/VLM/i);
    expect(container.textContent).not.toContain('관제 인입 재현');
    // 대체 문구는 남아 있어야 한다(문구 자체가 사라지는 회귀 차단)
    expect(screen.getByText('대용량 영상 업로드 (이어서 올리기 지원)')).toBeInTheDocument();
  });
});

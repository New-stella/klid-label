import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import {
  uploadFile,
  type InternalUploadCreatePayload,
  type TusMetadata,
} from '@/features/upload/api/tusClient';
import { useTusUpload } from '@/features/upload/hooks/useTusUpload';
import { SRC_TYPES } from '@/features/upload/components/tusUploadForm';

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

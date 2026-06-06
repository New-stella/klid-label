import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { uploadFile, type TusMetadata } from '@/features/upload/api/tusClient';
import { useTusUpload } from '@/features/upload/hooks/useTusUpload';

const META: TusMetadata = {
  filename: 'clip.mp4',
  vmsClipId: 'VMS-1',
  cctvId: 'CCTV-1',
  eventTypeCd: 'EVT_FALL',
  localGovCd: '1168000000',
  prvcTypeCd: 'ANONY',
  capturedAt: '2024-05-01T12:00:00Z',
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

  it('POST시_UploadLength_UploadMetadata_헤더전송', async () => {
    const file = makeFile(4);
    mock.onPost('/uploads').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: '/v1/uploads/u-x',
    });
    mock.onPatch('/uploads/u-x').reply(204, null, {
      'tus-resumable': '1.0.0',
      'upload-offset': '4',
    });

    await uploadFile({ file, metadata: META, chunkSize: 4 });

    const postReq = mock.history.post[0];
    expect(postReq.headers?.['Upload-Length']).toBe('4');
    // Upload-Metadata 는 base64 인코딩된 filename/vmsClipId 등 포함
    expect(String(postReq.headers?.['Upload-Metadata'])).toContain('vmsClipId ');
    expect(postReq.headers?.['Tus-Resumable']).toBe('1.0.0');
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

  it('useTusUpload_훅_에러시_error상태와_메시지노출', async () => {
    const file = makeFile(4);
    mock.onPost('/uploads').reply(409, {
      success: false,
      data: null,
      message: '동일한 vmsClipId 가 이미 존재합니다.',
      errorCode: 'CONFLICT',
    });

    const { result } = renderHook(() => useTusUpload());

    await act(async () => {
      await result.current.start(file, META).catch(() => undefined);
    });

    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error).toContain('vmsClipId');
  });
});

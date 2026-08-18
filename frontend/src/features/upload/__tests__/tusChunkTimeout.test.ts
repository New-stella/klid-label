// TUS 청크 업로드(PATCH) — 요청 제한시간 배선.
//
// 이 파일이 고정하는 계약:
//  - 청크는 기본 **8MB** 인데 이 요청은 공용 기본값(30초)을 그대로 썼다. 8MB(=64Mb)를 30초에
//    올리려면 **2.13Mbps 이상 업링크**가 필요하다. 상향 대역은 하향보다 좁아 흔히 미달한다.
//  - 재개 업로드라 실패해도 이어붙지만 **매 청크가 같은 벽에 부딪히므로 진행이 영구 정체**한다.
//    "재시도되니 괜찮다" 로 넘길 수 없다.
//  - 제한시간은 **실제 보내는 청크 크기**로 계산한다 — 고정값이면 마지막 조각(수 KB)에도
//    큰 상한이 붙고, 청크 크기를 키우면(서버 상한 16MB) 모자란다.
//  - 값 단언은 **기대값과 같다**로 쓴다. 「기본값과 다르다」로 쓰면 값이 우연히 기본값과 겹치는
//    순간 조용히 참이 되어 아무것도 지키지 못한다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  DEFAULT_CHUNK_SIZE,
  TUS_CHUNK_UPLOAD_BASE_MS,
  TUS_CHUNK_UPLOAD_MS_PER_MB,
  tusChunkTimeoutMs,
  uploadFile,
} from '../api/tusClient';

/** size 바이트의 더미 File 생성. */
function makeFile(size: number): File {
  return new File([new Uint8Array(size)], 'clip.mp4', { type: 'video/mp4' });
}

const MB = 1024 * 1024;

describe('TUS 청크 업로드 — 요청 제한시간', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('내부_업로드_청크_PATCH에_공용_기본값이_아닌_전용_제한시간이_실린다', async () => {
    // given — 4바이트 파일을 한 청크로
    const file = makeFile(4);
    const seenTimeouts: (number | undefined)[] = [];

    mock.onPost('/uploads').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: '/v1/uploads/u-1',
    });
    mock.onPatch('/uploads/u-1').reply((config) => {
      seenTimeouts.push(config.timeout);
      const chunk = config.data as Blob;
      return [204, null, { 'tus-resumable': '1.0.0', 'upload-offset': String(chunk.size) }];
    });

    // when
    await uploadFile({ file, metadata: { filename: 'clip.mp4' }, chunkSize: 4 });

    // then: 기본값(30초)이면 8MB 청크가 2.13Mbps 미만 업링크에서 매번 끊긴다
    expect(seenTimeouts).toEqual([tusChunkTimeoutMs(4)]);
  });

  it('포털_업로드_청크_PATCH에도_같은_제한시간이_실린다', async () => {
    // given — 두 경로는 같은 전송 함수를 쓰므로 한쪽만 고쳐지는 일이 없어야 한다
    const file = makeFile(3);
    let seenTimeout: number | undefined;

    mock.onPost('/portal/uploads/tus').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: '/v1/portal/uploads/tus/p-1',
    });
    mock.onPatch('/portal/uploads/tus/p-1').reply((config) => {
      seenTimeout = config.timeout;
      return [204, null, { 'tus-resumable': '1.0.0', 'upload-offset': '3' }];
    });

    // when
    await uploadFile({
      file,
      metadata: { filename: 'clip.mp4' },
      chunkSize: 3,
      endpointBase: '/portal/uploads/tus',
    });

    // then
    expect(seenTimeout).toBe(tusChunkTimeoutMs(3));
  });

  it('청크마다_그_청크의_크기로_계산한_제한시간이_실린다', async () => {
    // given — 5바이트 파일을 2바이트 청크로 → 2 + 2 + 1
    const file = makeFile(5);
    const seenTimeouts: (number | undefined)[] = [];
    let offset = 0;

    mock.onPost('/uploads').reply(201, null, {
      'tus-resumable': '1.0.0',
      location: '/v1/uploads/u-2',
    });
    mock.onPatch('/uploads/u-2').reply((config) => {
      seenTimeouts.push(config.timeout);
      offset += (config.data as Blob).size;
      return [204, null, { 'tus-resumable': '1.0.0', 'upload-offset': String(offset) }];
    });

    // when
    await uploadFile({ file, metadata: { filename: 'clip.mp4' }, chunkSize: 2 });

    // then: 마지막 조각(1바이트)에 앞 청크(2바이트)와 같은 상한을 붙이지 않는다
    expect(seenTimeouts).toEqual([tusChunkTimeoutMs(2), tusChunkTimeoutMs(2), tusChunkTimeoutMs(1)]);
  });

  it('청크가_클수록_제한시간이_길어진다', () => {
    expect(tusChunkTimeoutMs(8 * MB)).toBeGreaterThan(tusChunkTimeoutMs(1 * MB));
    expect(tusChunkTimeoutMs(16 * MB)).toBeGreaterThan(tusChunkTimeoutMs(8 * MB));
  });

  it('기본_청크_8MB_는_전송량만큼_더한_값을_준다', () => {
    // 상향 대역 가정(1Mbps = 1MB 당 8초)이 실제로 곱해져야 한다
    expect(tusChunkTimeoutMs(DEFAULT_CHUNK_SIZE)).toBe(
      TUS_CHUNK_UPLOAD_BASE_MS + TUS_CHUNK_UPLOAD_MS_PER_MB * 8,
    );
    // 공용 기본값(30초)으로는 8MB 청크를 감당하지 못한다
    expect(tusChunkTimeoutMs(DEFAULT_CHUNK_SIZE)).toBeGreaterThan(30_000);
  });

  it('청크_크기가_비정상이면_고정비용만_준다', () => {
    // 0·음수·NaN 이 0 이나 NaN 제한시간(=즉시 끊김/무제한)이 되지 않게 한다
    expect(tusChunkTimeoutMs(0)).toBe(TUS_CHUNK_UPLOAD_BASE_MS);
    expect(tusChunkTimeoutMs(-1)).toBe(TUS_CHUNK_UPLOAD_BASE_MS);
    expect(tusChunkTimeoutMs(Number.NaN)).toBe(TUS_CHUNK_UPLOAD_BASE_MS);
  });

  it('제한시간은_유한하다', () => {
    // 유한해야 한다 — 연결이 조용히 멈췄을 때 업로드가 영구히 매달리는 것을 끝내 주는 최후 장치다
    expect(Number.isFinite(tusChunkTimeoutMs(DEFAULT_CHUNK_SIZE))).toBe(true);
    expect(tusChunkTimeoutMs(DEFAULT_CHUNK_SIZE)).toBeGreaterThan(0);
  });
});

import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { clonePreset, createPreset, listPresets } from '@/features/preset/api';
import type { PresetForm } from '@/features/preset/types';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

describe('preset api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('listPresets_마스터_join_코드_정규화', async () => {
    // given
    mock.onGet('/manage/presets').reply(
      200,
      ok([
        {
          id: 1,
          name: '화재 기본',
          description: null,
          labelCodes: ['화재', '연기'],
          labelCodeOptions: [
            {
              labelId: 5,
              code: null,
              labelName: '화재',
              labelType: 'BBOX',
              linked: true,
              bboxEnabled: true,
              polygonEnabled: false,
            },
            {
              labelId: 6,
              code: null,
              labelName: '연기',
              labelType: 'POLYGON',
              linked: true,
              bboxEnabled: false,
              polygonEnabled: true,
            },
          ],
          createdAt: '2026-05-01T00:00:00Z',
          updatedAt: '2026-05-01T00:00:00Z',
        },
      ]),
    );

    // when
    const list = await listPresets();

    // then
    expect(list).toHaveLength(1);
    expect(list[0]!.name).toBe('화재 기본');
    expect(list[0]!.codes.map((c) => c.labelName)).toEqual(['화재', '연기']);
    expect(list[0]!.codes[0]!.linked).toBe(true);
    expect(list[0]!.codes[0]!.labelId).toBe(5);
    expect(list[0]!.codes[1]!.labelType).toBe('POLYGON');
  });

  it('listPresets_미연결_레거시코드_미연결로_매핑', async () => {
    // given — labelCodeOptions 에 linked=false 항목
    mock.onGet('/manage/presets').reply(
      200,
      ok([
        {
          id: 2,
          name: '레거시',
          description: null,
          labelCodes: ['OLD_CODE'],
          labelCodeOptions: [
            {
              labelId: null,
              code: 'OLD_CODE',
              labelName: 'OLD_CODE',
              labelType: null,
              linked: false,
              bboxEnabled: false,
              polygonEnabled: false,
            },
          ],
          createdAt: '2026-05-01T00:00:00Z',
          updatedAt: '2026-05-01T00:00:00Z',
        },
      ]),
    );

    // when
    const list = await listPresets();

    // then
    expect(list[0]!.codes[0]!.linked).toBe(false);
    expect(list[0]!.codes[0]!.labelName).toBe('OLD_CODE');
    expect(list[0]!.codes[0]!.labelId).toBeNull();
  });

  it('listPresets_labelCodeOptions_없으면_labelCodes로_미연결_폴백', async () => {
    // given — 구 응답(옵션 없음)
    mock.onGet('/manage/presets').reply(
      200,
      ok([
        {
          id: 3,
          name: '구형',
          description: null,
          labelCodes: ['PERSON'],
          createdAt: '2026-05-01T00:00:00Z',
          updatedAt: '2026-05-01T00:00:00Z',
        },
      ]),
    );

    // when
    const list = await listPresets();

    // then
    expect(list[0]!.codes).toHaveLength(1);
    expect(list[0]!.codes[0]!.linked).toBe(false);
    expect(list[0]!.codes[0]!.labelName).toBe('PERSON');
  });

  it('createPreset_요청_body에_labelIds만_전송', async () => {
    // given
    let sentBody: unknown;
    mock.onPost('/manage/presets').reply((config) => {
      sentBody = JSON.parse(config.data as string);
      return [
        200,
        ok({
          id: 9,
          name: '신규',
          description: '',
          labelCodes: ['사람'],
          labelCodeOptions: [
            {
              labelId: 1,
              code: null,
              labelName: '사람',
              labelType: 'BBOX',
              linked: true,
              bboxEnabled: true,
              polygonEnabled: false,
            },
          ],
          eventTypeCd: '',
          createdAt: '2026-05-01T00:00:00Z',
          updatedAt: '2026-05-01T00:00:00Z',
        }),
      ];
    });

    const form: PresetForm = {
      name: '신규',
      description: '',
      labelIds: [1, 2],
      eventTypeCd: '',
    };

    // when
    const created = await createPreset(form);

    // then — 요청 body 는 labelIds 만(형태 필드 미포함)
    expect(sentBody).toMatchObject({ name: '신규', labelIds: [1, 2] });
    expect(sentBody).not.toHaveProperty('labelCodes');
    expect(sentBody).not.toHaveProperty('labelCodeOptions');
    expect(created.codes[0]!.labelName).toBe('사람');
  });

  it('프리셋_복사시_복사본_접미사', async () => {
    // given
    mock.onPost('/manage/presets/1/clone').reply(
      200,
      ok({
        id: 2,
        name: '화재 기본 (복사본)',
        description: null,
        labelCodes: ['화재'],
        labelCodeOptions: [
          {
            labelId: 5,
            code: null,
            labelName: '화재',
            labelType: 'BBOX',
            linked: true,
            bboxEnabled: true,
            polygonEnabled: false,
          },
        ],
        createdAt: '2026-05-10T00:00:00Z',
        updatedAt: '2026-05-10T00:00:00Z',
      }),
    );

    // when
    const cloned = await clonePreset(1);

    // then
    expect(cloned.name).toMatch(/복사본/);
  });
});

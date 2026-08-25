import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ZodError, type ZodIssue } from 'zod';

import { apiClient } from '@/lib/api/client';
import * as presetApi from '@/features/preset/api';
import { createPreset, listPresets, updatePreset } from '@/features/preset/api';
import type { PresetForm } from '@/features/preset/types';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

/** BE 응답 골격 — 이름·설명은 없고 이벤트유형코드·표시명이 실린다(V17). */
const codeOption = (labelId: number, labelName: string, labelType = 'BBOX') => ({
  labelId,
  code: null,
  labelName,
  labelType,
  linked: true,
  bboxEnabled: labelType === 'BBOX',
  polygonEnabled: labelType === 'POLYGON',
});

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
          labelCodes: ['화재', '연기'],
          labelCodeOptions: [codeOption(5, '화재', 'BBOX'), codeOption(6, '연기', 'POLYGON')],
          eventTypeCd: 'EV02000101',
          eventTypeNm: '화재',
          createdAt: '2026-05-01T00:00:00Z',
          updatedAt: '2026-05-01T00:00:00Z',
        },
      ]),
    );

    // when
    const list = await listPresets();

    // then
    expect(list).toHaveLength(1);
    expect(list[0]!.codes.map((c) => c.labelName)).toEqual(['화재', '연기']);
    expect(list[0]!.codes[0]!.linked).toBe(true);
    expect(list[0]!.codes[0]!.labelId).toBe(5);
    expect(list[0]!.codes[1]!.labelType).toBe('POLYGON');
  });

  it('★서버가_준_이벤트_표시명을_그대로_싣는다', async () => {
    // given: 필터 옵션에서 제외되는 대분류(배회)도 서버는 표시명을 채워 준다 —
    //   화면이 이벤트 목록으로 역해석하면 이런 유형이 코드로만 노출된다.
    mock.onGet('/manage/presets').reply(
      200,
      ok([
        {
          id: 7,
          labelCodes: ['사람'],
          labelCodeOptions: [codeOption(1, '사람')],
          eventTypeCd: 'EV08000101',
          eventTypeNm: '배회',
          createdAt: '2026-05-01T00:00:00Z',
          updatedAt: '2026-05-01T00:00:00Z',
        },
      ]),
    );

    // when
    const list = await listPresets();

    // then
    expect(list[0]!.eventTypeCd).toBe('EV08000101');
    expect(list[0]!.eventTypeNm).toBe('배회');
  });

  it('이벤트_표시명이_비어_오면_유형코드로_폴백한다', async () => {
    // given: 표시명 미탑재 응답(구 서버·부분 응답)
    mock.onGet('/manage/presets').reply(
      200,
      ok([
        {
          id: 8,
          labelCodes: ['사람'],
          labelCodeOptions: [codeOption(1, '사람')],
          eventTypeCd: 'EV09000101',
          createdAt: '2026-05-01T00:00:00Z',
          updatedAt: '2026-05-01T00:00:00Z',
        },
      ]),
    );

    // when
    const list = await listPresets();

    // then: 폴백 규칙을 FE 가 재현하지 않고 코드 원문만 쓴다
    expect(list[0]!.eventTypeNm).toBe('EV09000101');
  });

  it('listPresets_미연결_레거시코드_미연결로_매핑', async () => {
    // given — labelCodeOptions 에 linked=false 항목
    mock.onGet('/manage/presets').reply(
      200,
      ok([
        {
          id: 2,
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
          eventTypeCd: 'EV02000101',
          eventTypeNm: '화재',
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
          labelCodes: ['PERSON'],
          eventTypeCd: 'EV02000101',
          eventTypeNm: '화재',
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

  it('★createPreset_요청_body는_eventTypeCd와_labelIds_둘뿐이다', async () => {
    // given
    let sentBody: Record<string, unknown> = {};
    mock.onPost('/manage/presets').reply((config) => {
      sentBody = JSON.parse(config.data as string) as Record<string, unknown>;
      return [
        201,
        ok({
          id: 9,
          labelCodes: ['사람'],
          labelCodeOptions: [codeOption(1, '사람')],
          eventTypeCd: 'EV02000101',
          eventTypeNm: '화재',
          createdAt: '2026-05-01T00:00:00Z',
          updatedAt: '2026-05-01T00:00:00Z',
        }),
      ];
    });

    const form: PresetForm = { eventTypeCd: 'EV02000101', labelIds: [1, 2] };

    // when
    const created = await createPreset(form);

    // then — 키가 정확히 둘이다. 폐기된 name/description 을 보내면 BE 가 무시하긴 하지만
    //   FE 에 구 계약이 남아 있다는 뜻이라 회귀로 잡는다.
    expect(Object.keys(sentBody).sort()).toEqual(['eventTypeCd', 'labelIds']);
    expect(sentBody).toMatchObject({ eventTypeCd: 'EV02000101', labelIds: [1, 2] });
    expect(created.codes[0]!.labelName).toBe('사람');
    expect(created.eventTypeNm).toBe('화재');
  });

  it('★updatePreset_요청_body도_eventTypeCd와_labelIds_둘뿐이다', async () => {
    // given
    let sentBody: Record<string, unknown> = {};
    mock.onPut('/manage/presets/9').reply((config) => {
      sentBody = JSON.parse(config.data as string) as Record<string, unknown>;
      return [
        200,
        ok({
          id: 9,
          labelCodes: ['사람'],
          labelCodeOptions: [codeOption(1, '사람')],
          eventTypeCd: 'EV08000101',
          eventTypeNm: '배회',
          createdAt: '2026-05-01T00:00:00Z',
          updatedAt: '2026-05-02T00:00:00Z',
        }),
      ];
    });

    // when
    await updatePreset(9, { eventTypeCd: 'EV08000101', labelIds: [1] });

    // then
    expect(Object.keys(sentBody).sort()).toEqual(['eventTypeCd', 'labelIds']);
  });

  it('★이벤트유형이_비면_전송_전에_스키마가_막는다', () => {
    // given: 서버 왕복 없이 거부되어야 한다(BE 는 400 으로 되돌린다)
    const form = { eventTypeCd: '', labelIds: [1] } as PresetForm;

    // when / then: `presetSchema.parse` 는 **동기 throw** 다 — Promise 를 만들지 않으므로
    //   `rejects` 로는 잡히지 않고 그대로 터진다.
    expect(() => createPreset(form)).toThrow(ZodError);

    // 어느 필드가 막았는지까지 본다 — "막힌다"만 보면 엉뚱한 필드가 막아도 통과한다.
    // ⚠ 개수는 단언하지 않는다: 빈 문자열은 `too_small`(min 1)과 형식 `custom` 두 이슈를
    //   동시에 만들고, 스키마를 손보면 그 조합이 바뀐다. 경로만 느슨하게 확인한다.
    let issues: ZodIssue[] = [];
    try {
      createPreset(form);
    } catch (e) {
      issues = (e as ZodError).issues;
    }
    expect(issues.some((iss) => iss.path[0] === 'eventTypeCd')).toBe(true);

    // 그리고 그 거부는 네트워크에 나가기 전이다.
    expect(mock.history.post).toHaveLength(0);
  });

  it('★복제_API는_폐기됐다', () => {
    // 프리셋은 이벤트유형 1건에 1건만 대응하므로 복제 대상이 없고, 복제 결과(이벤트 미연결)는
    // 어느 영상에도 매칭되지 않는 죽은 행이 된다. 서버 엔드포인트도 함께 제거됐다.
    expect('clonePreset' in presetApi).toBe(false);
  });
});

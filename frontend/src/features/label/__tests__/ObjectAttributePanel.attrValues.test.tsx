// SCR-LABEL-002 — 라벨 관리에서 정의한 속성을 라벨링 캔버스 객체 속성 패널에 배선한 테스트.
//
// - 정의(GET /manage/labels/{classId}/attrs)를 입력 UI로 렌더
// - 영속 객체(serverId=lblSn)의 값(GET /labels/{lblSn}/attrs) 로드/채움
// - 값 변경 시 PUT /labels/{lblSn}/attrs (변경분 {attrId,value}) 저장
// - 미저장 객체(serverId 없음) / 정의 0건 안내

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { normalizeLabel } from '../api';
import { ObjectAttributePanel } from '../components/ObjectAttributePanel';
import type { Label } from '../types';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

const CLASS_ID = 3;
const LBL_SN = 7001;

const attrDefs = [
  {
    attrId: 10,
    labelId: CLASS_ID,
    name: '색상',
    inputType: 'SELECT',
    valuesJson: '["빨강","파랑"]',
    defaultVal: null,
    mutable: 'Y',
    sortNo: 1,
    useYn: 'Y',
  },
  {
    attrId: 11,
    labelId: CLASS_ID,
    name: '방향',
    inputType: 'TEXT',
    valuesJson: null,
    defaultVal: null,
    mutable: 'N',
    sortNo: 2,
    useYn: 'Y',
  },
];

function baseLabel(overrides: Partial<Label> = {}): Label {
  return {
    id: 'obj-1',
    frameNo: 1,
    classId: CLASS_ID,
    className: 'pedestrian',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 0, top: 0, right: 100, bottom: 50 },
    ...overrides,
  };
}

function mountSelected(label: Label) {
  useLabelStore.getState().reset();
  useLabelStore.getState().setLabels([label]);
  useLabelStore.getState().selectLabel(label.id);
  return renderWithProviders(<ObjectAttributePanel labels={[label]} />);
}

describe('ObjectAttributePanel — 라벨 속성값 배선', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // 라벨 마스터(드롭다운) 잡음 방지.
    mock.onGet('/manage/labels').reply(200, ok([]));
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    mock.restore();
  });

  it('선택객체_라벨에_정의된_속성이_입력필드로_렌더된다', async () => {
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok(attrDefs));
    mock.onGet(`/labels/${LBL_SN}/attrs`).reply(200, ok([]));

    mountSelected(baseLabel({ serverId: LBL_SN }));

    await waitFor(() => {
      expect(screen.getByLabelText('색상')).toBeInTheDocument();
    });
    expect(screen.getByLabelText('방향')).toBeInTheDocument();
  });

  it('SELECT속성은_정의된_선택지_드롭다운으로_렌더된다', async () => {
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok(attrDefs));
    mock.onGet(`/labels/${LBL_SN}/attrs`).reply(200, ok([]));

    mountSelected(baseLabel({ serverId: LBL_SN }));

    const select = (await screen.findByLabelText('색상')) as HTMLSelectElement;
    const optionTexts = Array.from(select.options).map((o) => o.textContent);
    expect(optionTexts).toContain('빨강');
    expect(optionTexts).toContain('파랑');
  });

  it('mutable_N_속성은_비활성으로_렌더된다', async () => {
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok(attrDefs));
    mock.onGet(`/labels/${LBL_SN}/attrs`).reply(200, ok([]));

    mountSelected(baseLabel({ serverId: LBL_SN }));

    const dirInput = (await screen.findByLabelText('방향')) as HTMLInputElement;
    expect(dirInput).toBeDisabled();
  });

  it('영속객체_serverId면_현재값을_로드해_입력에_채운다', async () => {
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok(attrDefs));
    mock
      .onGet(`/labels/${LBL_SN}/attrs`)
      .reply(200, ok([{ attrId: 10, name: '색상', inputType: 'SELECT', value: '파랑' }]));

    mountSelected(baseLabel({ serverId: LBL_SN }));

    await waitFor(() => {
      const select = screen.getByLabelText('색상') as HTMLSelectElement;
      expect(select.value).toBe('파랑');
    });
  });

  it('값_변경시_putLabelAttrValues가_attrId와_value로_호출된다', async () => {
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok(attrDefs));
    mock
      .onGet(`/labels/${LBL_SN}/attrs`)
      .reply(200, ok([{ attrId: 10, name: '색상', inputType: 'SELECT', value: '파랑' }]));
    mock.onPut(`/labels/${LBL_SN}/attrs`).reply(200, ok(null));

    mountSelected(baseLabel({ serverId: LBL_SN }));

    const select = (await screen.findByLabelText('색상')) as HTMLSelectElement;
    await waitFor(() => expect(select.value).toBe('파랑'));

    fireEvent.change(select, { target: { value: '빨강' } });

    await waitFor(() => {
      const puts = mock.history.put.filter((r) => r.url === `/labels/${LBL_SN}/attrs`);
      expect(puts).toHaveLength(1);
      const body = JSON.parse(puts[0].data) as { values: { attrId: number; value: string }[] };
      expect(body.values).toEqual([{ attrId: 10, value: '빨강' }]);
    });
  });

  it('미저장객체_serverId없음이면_저장대신_안내가_표시된다', async () => {
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok(attrDefs));

    mountSelected(baseLabel({ serverId: undefined }));

    await waitFor(() => {
      expect(screen.getByText(/저장 후 속성 입력이 가능합니다/)).toBeInTheDocument();
    });
    // 값 조회 호출 없음 (serverId 없음).
    expect(mock.history.get.some((r) => r.url === `/labels/undefined/attrs`)).toBe(false);
    // 입력은 비활성.
    expect(screen.getByLabelText('색상')).toBeDisabled();
  });

  it('정의0건_라벨은_빈상태_안내가_표시된다', async () => {
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok([]));
    mock.onGet(`/labels/${LBL_SN}/attrs`).reply(200, ok([]));

    mountSelected(baseLabel({ serverId: LBL_SN }));

    await waitFor(() => {
      expect(screen.getByText(/정의된 속성이 없습니다/)).toBeInTheDocument();
    });
  });

  // ── 이슈1 — 로드된 라벨(classId 없음, labelId만)에서도 속성 섹션이 노출된다 ──
  it('로드객체_선택시_속성섹션이_표시된다', async () => {
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok(attrDefs));
    mock.onGet(`/labels/${LBL_SN}/attrs`).reply(200, ok([]));

    // BE 로드 응답 형태(classId/classCd 없음, labelId=CLASS_ID) → normalizeLabel 로 로드 라벨 재현.
    const loaded = normalizeLabel({
      id: LBL_SN,
      lblTypeCd: 'BBOX',
      label: 'pedestrian',
      labelId: CLASS_ID,
      autoLblYn: 'N',
      points: [
        [0, 0],
        [100, 50],
      ],
    });
    // 폴백으로 classId 가 마스터 id(=labelId)로 매핑되어 게이트(classId>0)를 통과해야 한다.
    expect(loaded.classId).toBe(CLASS_ID);
    expect(loaded.serverId).toBe(LBL_SN);

    mountSelected(loaded);

    await waitFor(() => {
      expect(screen.getByLabelText('색상')).toBeInTheDocument();
    });
  });

  // ── 이슈2 — 한 속성 저장→재조회 시 편집 중인 다른 필드 입력이 유지된다 ──
  it('한_속성_저장후_재조회시_편집중인_다른_필드_입력이_유지된다', async () => {
    const defsAB = [
      {
        attrId: 10,
        labelId: CLASS_ID,
        name: '색상',
        inputType: 'SELECT',
        valuesJson: '["빨강","파랑"]',
        defaultVal: null,
        mutable: 'Y',
        sortNo: 1,
        useYn: 'Y',
      },
      {
        attrId: 12,
        labelId: CLASS_ID,
        name: '메모',
        inputType: 'TEXT',
        valuesJson: null,
        defaultVal: null,
        mutable: 'Y',
        sortNo: 2,
        useYn: 'Y',
      },
    ];
    // 서버 저장값을 mutable 변수로 두고, PUT 반영 후 재조회에 반영한다.
    const serverValues: { attrId: number; name: string; inputType: string; value: string }[] = [];
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok(defsAB));
    mock.onGet(`/labels/${LBL_SN}/attrs`).reply(() => [200, ok(serverValues)]);
    mock.onPut(`/labels/${LBL_SN}/attrs`).reply((config) => {
      const body = JSON.parse(config.data) as { values: { attrId: number; value: string }[] };
      for (const v of body.values) {
        const idx = serverValues.findIndex((x) => x.attrId === v.attrId);
        if (idx >= 0) serverValues[idx] = { ...serverValues[idx], value: v.value };
        else serverValues.push({ attrId: v.attrId, name: '', inputType: 'SELECT', value: v.value });
      }
      return [200, ok(null)];
    });

    mountSelected(baseLabel({ serverId: LBL_SN }));

    // TEXT 필드 B 타이핑 (blur 전 — 미커밋).
    const memo = (await screen.findByLabelText('메모')) as HTMLInputElement;
    fireEvent.change(memo, { target: { value: '보행자 그룹' } });
    expect(memo.value).toBe('보행자 그룹');

    // SELECT A 변경 → 즉시 커밋 → PUT → invalidate → 값 재조회.
    const select = screen.getByLabelText('색상') as HTMLSelectElement;
    fireEvent.change(select, { target: { value: '빨강' } });

    await waitFor(() => {
      const puts = mock.history.put.filter((r) => r.url === `/labels/${LBL_SN}/attrs`);
      expect(puts.length).toBeGreaterThanOrEqual(1);
    });
    // 재조회 후 A 는 서버값 반영.
    await waitFor(() => {
      expect((screen.getByLabelText('색상') as HTMLSelectElement).value).toBe('빨강');
    });
    // 핵심: 편집 중이던 B 입력이 재조회 재시드로 덮이지 않아야 한다.
    expect((screen.getByLabelText('메모') as HTMLInputElement).value).toBe('보행자 그룹');
  });

  // ── 이슈(HIGH) — 다른 객체로 전환 시 이전 객체의 편집중 draft 가 남지 않는다 (key 리마운트) ──
  it('다른_객체로_전환하면_이전_객체의_편집중_draft가_남지_않는다', async () => {
    const defsAB = [
      {
        attrId: 10,
        labelId: CLASS_ID,
        name: '색상',
        inputType: 'SELECT',
        valuesJson: '["빨강","파랑"]',
        defaultVal: null,
        mutable: 'Y',
        sortNo: 1,
        useYn: 'Y',
      },
      {
        attrId: 12,
        labelId: CLASS_ID,
        name: '메모',
        inputType: 'TEXT',
        valuesJson: null,
        defaultVal: null,
        mutable: 'Y',
        sortNo: 2,
        useYn: 'Y',
      },
    ];
    const LBL_A = 100;
    const LBL_B = 200;
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok(defsAB));
    // 객체 A: 서버 저장값 색상=파랑, 메모=A메모.
    mock
      .onGet(`/labels/${LBL_A}/attrs`)
      .reply(200, ok([
        { attrId: 10, name: '색상', inputType: 'SELECT', value: '파랑' },
        { attrId: 12, name: '메모', inputType: 'TEXT', value: 'A메모' },
      ]));
    // 객체 B: 서버 저장값 색상=빨강, 메모=B메모 (A와 다른 값).
    mock
      .onGet(`/labels/${LBL_B}/attrs`)
      .reply(200, ok([
        { attrId: 10, name: '색상', inputType: 'SELECT', value: '빨강' },
        { attrId: 12, name: '메모', inputType: 'TEXT', value: 'B메모' },
      ]));

    const labelA = baseLabel({ id: 'obj-A', serverId: LBL_A });
    const labelB = baseLabel({ id: 'obj-B', serverId: LBL_B });

    // 객체 A 선택 마운트.
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([labelA]);
    useLabelStore.getState().selectLabel(labelA.id);
    const { rerender } = renderWithProviders(<ObjectAttributePanel labels={[labelA]} />);

    // A 의 메모를 편집(blur 전 — 미커밋 dirty draft).
    const memoA = (await screen.findByLabelText('메모')) as HTMLInputElement;
    await waitFor(() => expect(memoA.value).toBe('A메모'));
    fireEvent.change(memoA, { target: { value: 'A편집중미저장' } });
    expect(memoA.value).toBe('A편집중미저장');

    // 캔버스에서 같은 클래스의 다른 객체 B 를 바로 클릭 선택.
    useLabelStore.getState().setLabels([labelB]);
    useLabelStore.getState().selectLabel(labelB.id);
    rerender(<ObjectAttributePanel labels={[labelB]} />);

    // key 리마운트로 B 는 자신의 서버값을 표시하고 A 의 편집 잔재가 없어야 한다.
    await waitFor(() => {
      expect((screen.getByLabelText('메모') as HTMLInputElement).value).toBe('B메모');
    });
    expect((screen.getByLabelText('색상') as HTMLSelectElement).value).toBe('빨강');
    // A 의 미커밋 draft('A편집중미저장')가 B 화면에 남지 않는다.
    expect((screen.getByLabelText('메모') as HTMLInputElement).value).not.toBe('A편집중미저장');
  });

  // ── 이슈3 — 조회 실패 fail-open 차단 (에러 안내 + 빈상태 구분) ──
  it('속성_정의_조회_실패시_에러안내가_표시되고_빈상태와_구분된다', async () => {
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(500, {
      success: false,
      data: null,
      message: '서버 오류',
      errorCode: 'INTERNAL_ERROR',
    });
    mock.onGet(`/labels/${LBL_SN}/attrs`).reply(200, ok([]));

    mountSelected(baseLabel({ serverId: LBL_SN }));

    await waitFor(() => {
      expect(screen.getByText(/속성 정의를 불러오지 못했습니다/)).toBeInTheDocument();
    });
    // "정의 없음"(정상 빈 상태)과 혼동되지 않아야 한다.
    expect(screen.queryByText(/정의된 속성이 없습니다/)).not.toBeInTheDocument();
  });

  it('속성값_조회_실패시_에러안내가_표시되고_입력이_비활성된다', async () => {
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok(attrDefs));
    mock.onGet(`/labels/${LBL_SN}/attrs`).reply(500, {
      success: false,
      data: null,
      message: '조회 실패',
      errorCode: 'INTERNAL_ERROR',
    });

    mountSelected(baseLabel({ serverId: LBL_SN }));

    await waitFor(() => {
      expect(screen.getByText(/저장된 속성값을 불러오지 못했습니다/)).toBeInTheDocument();
    });
    // 실제 저장값을 모르므로 편집 차단.
    expect(screen.getByLabelText('색상')).toBeDisabled();
  });
});

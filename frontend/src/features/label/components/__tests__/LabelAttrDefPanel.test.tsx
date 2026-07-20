// Phase 4 — 라벨별 속성 정의 관리 패널(LabelAttrDefPanel) UI 테스트.
//
// 소비 계약(Phase 2 BE / API): /manage/labels/{labelId}/attrs (GET/POST/PUT/DELETE).
// 실제 훅(useLabelAttrs / useCreate·Update·DeleteLabelAttr)을 통해 동작 검증 —
// apiClient 를 MockAdapter 로 가로채는 통합 스타일(다른 컴포넌트 테스트와 동일 패턴).
//
// 보안 초점: valuesJson 에 스크립트 문자열이 있어도 React 기본 escape 로 텍스트 렌더(XSS 방어).

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { LabelAttrDefPanel } from '@/features/label/components/LabelAttrDefPanel';
import { renderWithProviders } from '@/test/renderWithProviders';

function listPayload(rows: Array<Record<string, unknown>>) {
  return { success: true, data: rows, message: null, errorCode: null };
}

const ATTRS = [
  {
    attrId: 10,
    labelId: 3,
    name: '색상',
    inputType: 'SELECT',
    valuesJson: '["빨강","파랑"]',
    defaultVal: '빨강',
    mutable: 'Y',
    sortNo: 1,
    useYn: 'Y',
  },
  {
    attrId: 11,
    labelId: 3,
    name: '메모',
    inputType: 'TEXT',
    valuesJson: null,
    defaultVal: null,
    mutable: 'N',
    sortNo: 2,
    useYn: 'Y',
  },
];

describe('LabelAttrDefPanel — 라벨 속성 정의 관리', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('라벨_선택시_해당_라벨의_속성정의_목록이_조회된다', async () => {
    // given
    mock.onGet('/manage/labels/3/attrs').reply(200, listPayload(ATTRS));

    // when
    renderWithProviders(<LabelAttrDefPanel labelId={3} labelName="사람" />);

    // then — 목록 렌더 + labelId 경로로 조회
    expect(await screen.findByText('색상')).toBeInTheDocument();
    expect(screen.getByText('메모')).toBeInTheDocument();
    expect(mock.history.get[0].url).toBe('/manage/labels/3/attrs');
  });

  it('inputType_RADIO_선택시_선택항목_valuesJson_입력_UI가_노출된다', async () => {
    // given
    mock.onGet('/manage/labels/3/attrs').reply(200, listPayload([]));
    const user = userEvent.setup();
    renderWithProviders(<LabelAttrDefPanel labelId={3} labelName="사람" />);
    await screen.findByRole('button', { name: '속성 추가' });

    // when — 추가 폼 열고 입력 형식을 RADIO 로
    await user.click(screen.getByRole('button', { name: '속성 추가' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('입력 형식'), {
      target: { value: 'RADIO' },
    });

    // then — 선택 항목 편집 UI 노출
    expect(within(dialog).getByText('선택 항목')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '항목 추가' })).toBeInTheDocument();
  });

  it('inputType_TEXT_선택시_선택항목_입력_UI가_숨겨진다', async () => {
    // given
    mock.onGet('/manage/labels/3/attrs').reply(200, listPayload([]));
    const user = userEvent.setup();
    renderWithProviders(<LabelAttrDefPanel labelId={3} labelName="사람" />);
    await screen.findByRole('button', { name: '속성 추가' });

    // when
    await user.click(screen.getByRole('button', { name: '속성 추가' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('입력 형식'), {
      target: { value: 'TEXT' },
    });

    // then — 선택 항목 편집 UI 숨김
    expect(within(dialog).queryByText('선택 항목')).not.toBeInTheDocument();
  });

  it('속성_추가시_createLabelAttr가_labelId와_함께_호출된다', async () => {
    // given
    mock.onGet('/manage/labels/3/attrs').reply(200, listPayload([]));
    let capturedBody: Record<string, unknown> | null = null;
    mock.onPost('/manage/labels/3/attrs').reply((config) => {
      capturedBody = JSON.parse(config.data as string);
      return [
        201,
        {
          success: true,
          data: {
            attrId: 30,
            labelId: 3,
            name: '높이',
            inputType: 'NUMBER',
            valuesJson: null,
            defaultVal: null,
            mutable: 'Y',
            sortNo: 3,
            useYn: 'Y',
          },
          message: null,
          errorCode: null,
        },
      ];
    });
    const user = userEvent.setup();
    renderWithProviders(<LabelAttrDefPanel labelId={3} labelName="사람" />);
    await screen.findByRole('button', { name: '속성 추가' });

    // when — 폼 입력 후 저장
    await user.click(screen.getByRole('button', { name: '속성 추가' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('속성명'), '높이');
    fireEvent.change(within(dialog).getByLabelText('입력 형식'), {
      target: { value: 'NUMBER' },
    });
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    // then — labelId 경로로 POST, 허용 필드만 전송
    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    expect(mock.history.post[0].url).toBe('/manage/labels/3/attrs');
    expect(capturedBody).toMatchObject({ name: '높이', inputType: 'NUMBER', valuesJson: null });
  });

  it('속성_삭제는_확인모달_승인후에만_deleteLabelAttr를_호출한다', async () => {
    // given
    mock.onGet('/manage/labels/3/attrs').reply(200, listPayload(ATTRS));
    mock.onDelete('/manage/labels/3/attrs/10').reply(204);
    const user = userEvent.setup();
    renderWithProviders(<LabelAttrDefPanel labelId={3} labelName="사람" />);
    await screen.findByText('색상');

    // when — 삭제 버튼 클릭만 하면 아직 DELETE 안 됨
    await user.click(screen.getByRole('button', { name: '색상 속성 삭제' }));
    expect(mock.history.delete).toHaveLength(0);

    // 확인 모달 승인
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '삭제' }));

    // then — 이제 DELETE 호출
    await waitFor(() => expect(mock.history.delete).toHaveLength(1));
    expect(mock.history.delete[0].url).toBe('/manage/labels/3/attrs/10');
  });

  it('속성_동일이름_중복시_409응답이_사용자_메시지로_표시된다', async () => {
    // given
    mock.onGet('/manage/labels/3/attrs').reply(200, listPayload([]));
    mock.onPost('/manage/labels/3/attrs').reply(409, {
      success: false,
      data: null,
      message: '이미 존재하는 속성명입니다.',
      errorCode: 'DUPLICATE_ATTR',
    });
    const user = userEvent.setup();
    renderWithProviders(<LabelAttrDefPanel labelId={3} labelName="사람" />);
    await screen.findByRole('button', { name: '속성 추가' });

    // when
    await user.click(screen.getByRole('button', { name: '속성 추가' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('속성명'), '색상');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    // then — 409 userMessage 노출
    expect(await screen.findByText('이미 존재하는 속성명입니다.')).toBeInTheDocument();
  });

  it('valuesJson에_스크립트_문자열이_있어도_이스케이프되어_텍스트로_렌더된다', async () => {
    // given — XSS 시도 페이로드
    const xss = '<script>alert(1)</script>';
    mock.onGet('/manage/labels/3/attrs').reply(
      200,
      listPayload([
        {
          attrId: 12,
          labelId: 3,
          name: '위험값',
          inputType: 'SELECT',
          valuesJson: JSON.stringify([xss, '정상']),
          defaultVal: null,
          mutable: 'Y',
          sortNo: 1,
          useYn: 'Y',
        },
      ]),
    );

    // when
    const { container } = renderWithProviders(
      <LabelAttrDefPanel labelId={3} labelName="사람" />,
    );
    await screen.findByText('위험값');

    // then — 스크립트 문자열이 텍스트로 렌더되고, 실제 <script> 엘리먼트로 주입되지 않음
    expect(screen.getByText(/<script>alert\(1\)<\/script>/)).toBeInTheDocument();
    expect(container.querySelector('script')).toBeNull();
  });

  it('valuesJson이_깨진_JSON이면_크래시없이_안전하게_렌더된다', async () => {
    // given — 파싱 불가 문자열
    mock.onGet('/manage/labels/3/attrs').reply(
      200,
      listPayload([
        {
          attrId: 13,
          labelId: 3,
          name: '깨진값',
          inputType: 'SELECT',
          valuesJson: '["빨강", 파랑',
          defaultVal: null,
          mutable: 'Y',
          sortNo: 1,
          useYn: 'Y',
        },
      ]),
    );

    // when / then — 예외 없이 행이 렌더됨
    renderWithProviders(<LabelAttrDefPanel labelId={3} labelName="사람" />);
    expect(await screen.findByText('깨진값')).toBeInTheDocument();
  });
});

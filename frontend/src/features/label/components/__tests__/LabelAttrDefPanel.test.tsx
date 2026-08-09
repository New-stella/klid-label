// Phase 4 — 라벨별 속성 정의 관리 패널(LabelAttrDefPanel) UI 테스트.
//
// 소비 계약(Phase 2 BE / API): /manage/labels/{labelId}/attrs (GET/POST/PUT/DELETE).
// 실제 훅(useLabelAttrs / useCreate·Update·DeleteLabelAttr)을 통해 동작 검증 —
// apiClient 를 MockAdapter 로 가로채는 통합 스타일(다른 컴포넌트 테스트와 동일 패턴).
//
// 보안 초점: valuesJson 에 스크립트 문자열이 있어도 React 기본 escape 로 텍스트 렌더(XSS 방어).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { LabelAttrDefPanel } from '@/features/label/components/LabelAttrDefPanel';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';

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
    renderWithProviders(<LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />);

    // then — 목록 렌더 + labelId 경로로 조회
    expect(await screen.findByText('색상')).toBeInTheDocument();
    expect(screen.getByText('메모')).toBeInTheDocument();
    expect(mock.history.get[0].url).toBe('/manage/labels/3/attrs');
  });

  it('inputType_RADIO_선택시_선택항목_valuesJson_입력_UI가_노출된다', async () => {
    // given
    mock.onGet('/manage/labels/3/attrs').reply(200, listPayload([]));
    const user = userEvent.setup();
    renderWithProviders(<LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />);
    await screen.findByRole('button', { name: '속성 추가' });

    // when — 추가 폼 열고 입력 형식을 RADIO 로
    await user.click(screen.getByRole('button', { name: '속성 추가' }));
    const dialog = await screen.findByRole('dialog', { name: '속성 추가' });
    await selectRadixOption(user, within(dialog).getByLabelText('입력 형식'), '라디오(단일)');

    // then — 선택 항목 편집 UI 노출
    expect(within(dialog).getByText('선택 항목')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '항목 추가' })).toBeInTheDocument();
  });

  it('inputType_TEXT_선택시_선택항목_입력_UI가_숨겨진다', async () => {
    // given
    mock.onGet('/manage/labels/3/attrs').reply(200, listPayload([]));
    const user = userEvent.setup();
    renderWithProviders(<LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />);
    await screen.findByRole('button', { name: '속성 추가' });

    // when
    await user.click(screen.getByRole('button', { name: '속성 추가' }));
    const dialog = await screen.findByRole('dialog', { name: '속성 추가' });
    await selectRadixOption(user, within(dialog).getByLabelText('입력 형식'), '텍스트');

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
    renderWithProviders(<LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />);
    await screen.findByRole('button', { name: '속성 추가' });

    // when — 폼 입력 후 저장
    await user.click(screen.getByRole('button', { name: '속성 추가' }));
    const dialog = await screen.findByRole('dialog', { name: '속성 추가' });
    await user.type(within(dialog).getByLabelText('속성명'), '높이');
    await selectRadixOption(user, within(dialog).getByLabelText('입력 형식'), '숫자');
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
    renderWithProviders(<LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />);
    await screen.findByText('색상');

    // when — 삭제 버튼 클릭만 하면 아직 DELETE 안 됨
    await user.click(screen.getByRole('button', { name: '색상 속성 삭제' }));
    expect(mock.history.delete).toHaveLength(0);

    // 확인 모달 승인
    const dialog = await screen.findByRole('dialog', { name: '속성 삭제' });
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
    renderWithProviders(<LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />);
    await screen.findByRole('button', { name: '속성 추가' });

    // when
    await user.click(screen.getByRole('button', { name: '속성 추가' }));
    const dialog = await screen.findByRole('dialog', { name: '속성 추가' });
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
    renderWithProviders(<LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />);
    await screen.findByText('위험값');

    // then — 스크립트 문자열이 텍스트로 렌더되고, 실제 <script> 엘리먼트로 주입되지 않음
    expect(screen.getByText(/<script>alert\(1\)<\/script>/)).toBeInTheDocument();
    // ⚠ 시트는 portal(document.body)로 렌더되므로 render 의 container 를 보면 **항상 비어**
    //   가짜 통과가 된다. 주입 여부는 문서 전체에서 확인한다.
    expect(document.body.querySelector('script')).toBeNull();
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
    renderWithProviders(<LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />);
    expect(await screen.findByText('깨진값')).toBeInTheDocument();
  });

  // ── 사양 SCREEN-035 「속성 정의 사이드 시트」 회귀 가드 ────────────────────
  //
  // 구 구현은 테이블 아래 인라인으로 펼쳐지는 <section> 이었다(사양 드리프트).
  // 아래 가드는 ①시트로서의 정체성(오버레이 dialog + 접근가능한 이름) ②키보드 접근성
  // (ESC·포커스 진입) ③넘침 처리 ④작성 중 데이터 보호(바깥 클릭·중첩 ESC)를 고정한다.

  describe('사이드 시트 회귀 가드', () => {
    it('속성_패널은_인라인이_아니라_이름을_가진_오버레이_dialog_다', async () => {
      // given
      mock.onGet('/manage/labels/3/attrs').reply(200, listPayload(ATTRS));

      // when — 페이지 컨테이너에 렌더해도 시트는 그 안에 인라인으로 들어가지 않는다.
      const { container } = renderWithProviders(
        <LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />,
      );
      await screen.findByText('색상');

      // then — 접근가능한 이름을 가진 dialog 로 노출된다.
      const sheet = screen.getByRole('dialog', { name: '사람 속성 정의' });
      expect(sheet).toBeInTheDocument();

      // then — portal 로 document.body 에 붙는다(= 트리거가 있던 자리에 인라인으로 펼쳐지지 않는다).
      expect(container.contains(sheet)).toBe(false);

      // then — 뷰포트 고정 오버레이(백드롭) 위에 얹힌 우측 패널이다.
      const backdrop = screen.getByTestId('drawer-backdrop');
      expect(backdrop.className).toContain('fixed');
      expect(backdrop.contains(sheet)).toBe(true);
      expect(sheet.className).toContain('right-0');
    });

    it('시트가_열리면_포커스가_시트_안으로_들어온다', async () => {
      // given
      mock.onGet('/manage/labels/3/attrs').reply(200, listPayload(ATTRS));

      // when
      renderWithProviders(
        <LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />,
      );
      const sheet = await screen.findByRole('dialog', { name: '사람 속성 정의' });

      // then — 포커스가 문서 body 에 남지 않고 시트 내부로 이동한다.
      expect(document.activeElement).not.toBe(document.body);
      expect(sheet.contains(document.activeElement)).toBe(true);
    });

    it('ESC_로_시트가_닫힌다', async () => {
      // given
      mock.onGet('/manage/labels/3/attrs').reply(200, listPayload(ATTRS));
      const user = userEvent.setup();
      const onClose = vi.fn();
      renderWithProviders(
        <LabelAttrDefPanel open onClose={onClose} labelId={3} labelName="사람" />,
      );
      await screen.findByRole('dialog', { name: '사람 속성 정의' });

      // when
      await user.keyboard('{Escape}');

      // then
      expect(onClose).toHaveBeenCalled();
    });

    it('바깥_클릭으로는_닫히지_않는다_작성중_속성정의_유실_방지', async () => {
      // given — 인라인이던 시절에는 다른 곳을 눌러도 패널이 유지됐다. 시트로 바꾸면서
      // 바깥 클릭 닫기를 켜면 오조작 한 번에 작성 중 입력이 사라지는 회귀가 된다.
      mock.onGet('/manage/labels/3/attrs').reply(200, listPayload(ATTRS));
      const user = userEvent.setup();
      const onClose = vi.fn();
      renderWithProviders(
        <LabelAttrDefPanel open onClose={onClose} labelId={3} labelName="사람" />,
      );
      await screen.findByRole('dialog', { name: '사람 속성 정의' });

      // when — 백드롭(시트 바깥) 클릭
      await user.click(screen.getByTestId('drawer-backdrop'));

      // then — 닫히지 않는다. 닫기는 X 버튼 / ESC 라는 명시적 의도로만.
      expect(onClose).not.toHaveBeenCalled();
      expect(screen.getByRole('dialog', { name: '사람 속성 정의' })).toBeInTheDocument();
    });

    it('추가_폼이_열려있는_동안_ESC_는_폼만_닫고_시트는_남는다', async () => {
      // given — ESC 는 가장 안쪽 다이얼로그 하나만 닫아야 한다. 두 리스너가 같은 document 에
      // 붙어 있어 안쪽의 stopPropagation 으로는 바깥을 막지 못하므로 시트 쪽에서 꺼야 한다.
      // 끄지 않으면 ESC 한 번에 폼과 시트가 함께 닫혀 입력한 값이 사라진다.
      mock.onGet('/manage/labels/3/attrs').reply(200, listPayload([]));
      const user = userEvent.setup();
      const onClose = vi.fn();
      renderWithProviders(
        <LabelAttrDefPanel open onClose={onClose} labelId={3} labelName="사람" />,
      );
      await screen.findByRole('button', { name: '속성 추가' });

      // when — 폼을 열고 값을 입력한 뒤 ESC
      await user.click(screen.getByRole('button', { name: '속성 추가' }));
      const form = await screen.findByRole('dialog', { name: '속성 추가' });
      await user.type(within(form).getByLabelText('속성명'), '높이');
      await user.keyboard('{Escape}');

      // then — 폼만 닫히고 시트는 그대로 남는다.
      await waitFor(() =>
        expect(screen.queryByRole('dialog', { name: '속성 추가' })).not.toBeInTheDocument(),
      );
      expect(onClose).not.toHaveBeenCalled();
      expect(screen.getByRole('dialog', { name: '사람 속성 정의' })).toBeInTheDocument();

      // then — 폼이 닫힌 뒤의 ESC 는 시트를 닫는다(닫기 수단이 사라지지 않는다).
      await user.keyboard('{Escape}');
      expect(onClose).toHaveBeenCalled();
    });

    it('속성이_많아도_시트_본문이_잘리지_않고_스크롤_영역에_담긴다', async () => {
      // given — 속성 40건.
      const many = Array.from({ length: 40 }, (_, i) => ({
        attrId: 100 + i,
        labelId: 3,
        name: `속성${i}`,
        inputType: 'TEXT',
        valuesJson: null,
        defaultVal: null,
        mutable: 'Y',
        sortNo: i + 1,
        useYn: 'Y',
      }));
      mock.onGet('/manage/labels/3/attrs').reply(200, listPayload(many));

      // when
      renderWithProviders(
        <LabelAttrDefPanel open onClose={() => {}} labelId={3} labelName="사람" />,
      );
      await screen.findByText('속성39');
      const sheet = screen.getByRole('dialog', { name: '사람 속성 정의' });

      // then — 시트 자체는 높이가 묶인 세로 flex 컨테이너다(내용만큼 자라지 않는다).
      expect(sheet.className).toContain('h-full');
      expect(sheet.className).toContain('flex-col');

      // then — 목록은 스크롤 컨테이너 안에 있고, 그 컨테이너는 flex-1 + min-h-0 을 갖는다.
      //
      // ⚠ jsdom 은 레이아웃을 계산하지 않아 "실제로 스크롤됐다"를 측정할 수 없다. 이 가드가
      //   고정하는 것은 **스크롤이 성립하는 CSS 계약**이다 — 특히 min-h-0 이 빠지면 flex
      //   아이템의 자동 최소 크기가 콘텐츠 높이가 되어 overflow-y-auto 가 발동하지 않고
      //   내용이 그대로 잘린다(Modal 에서 실측된 결함과 같은 원인). 클래스가 되돌려지면
      //   이 테스트가 깨진다.
      const scroller = screen.getByTestId('label-attr-row-139').closest('.overflow-y-auto');
      expect(scroller).not.toBeNull();
      expect(scroller!.className).toContain('flex-1');
      expect(scroller!.className).toContain('min-h-0');
      expect(sheet.contains(scroller!)).toBe(true);
    });
  });
});

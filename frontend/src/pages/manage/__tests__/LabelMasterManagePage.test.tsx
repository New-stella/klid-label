// Phase 3 — 라벨 마스터 관리 화면 (LabelMasterManagePage) 단위 테스트.
//
// 커버 범위:
//  - 목록 정렬순 렌더
//  - 생성 모달 클라이언트 검증(name 필수) — 검증 실패 시 생성 API 미호출
//  - 수정 저장 → PUT /manage/labels/{labelId}
//  - 삭제 확인 모달 승인 후에만 DELETE 호출 (비가역 방지)
//  - 중복(409) 응답이 사용자 메시지로 표시
//  - 라우트 가드(RoleGuard) — WORKER 는 REVIEWER 전용 라우트에서 /forbidden 으로 차단

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { LabelMasterManagePage } from '@/pages/manage/LabelMasterManagePage';
import { RoleGuard } from '@/router/guards';
import { Role } from '@/lib/api/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

// 정렬순(sortNo) 오름차순 검증을 위해 의도적으로 역순으로 제공.
const LABELS = [
  { labelId: 2, name: '차량', color: '#22C55E', type: 'BBOX', sortNo: 3, useYn: 'Y' },
  { labelId: 1, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y' },
];

describe('LabelMasterManagePage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/labels').reply(200, ok(LABELS));
  });

  afterEach(() => {
    mock.restore();
    useUiStore.setState({ toasts: [] });
  });

  it('라벨_마스터_목록이_정렬순으로_렌더된다', async () => {
    // given / when
    renderWithProviders(<LabelMasterManagePage />);

    // then — sortNo 오름차순: 사람(1) 이 차량(3) 보다 먼저 나온다.
    await screen.findByText('사람');
    const rows = screen.getAllByTestId(/^label-master-row-/);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('사람')).toBeInTheDocument();
    expect(within(rows[1]).getByText('차량')).toBeInTheDocument();
  });

  it('라벨_추가_모달에서_name_없이_저장시_검증에러가_표시되고_생성API가_호출되지_않는다', async () => {
    // given
    const user = userEvent.setup();
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // when — 라벨 추가 모달 열고 name 을 비운 채 저장
    await user.click(screen.getByRole('button', { name: '라벨 추가' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    // then — 검증 에러 노출 + POST 미발생
    expect(await within(dialog).findByText('라벨명을 입력하세요.')).toBeInTheDocument();
    expect(mock.history.post).toHaveLength(0);
  });

  it('수정_저장시_updateLabelMaster가_해당_labelId로_호출된다', async () => {
    // given
    mock.onPut('/manage/labels/1').reply(200, ok(LABELS[1]));
    const user = userEvent.setup();
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // when — "사람" 행 수정 → 정렬순 변경 → 저장
    await user.click(screen.getByRole('button', { name: '사람 수정' }));
    const dialog = await screen.findByRole('dialog');
    const sortInput = within(dialog).getByLabelText('정렬 순서');
    await user.clear(sortInput);
    await user.type(sortInput, '9');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    // then — PUT /manage/labels/1
    await waitFor(() => {
      expect(mock.history.put).toHaveLength(1);
    });
    expect(mock.history.put[0].url).toBe('/manage/labels/1');
  });

  it('삭제는_확인_모달_승인_후에만_deleteLabelMaster를_호출한다', async () => {
    // given
    mock.onDelete('/manage/labels/1').reply(204);
    const user = userEvent.setup();
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // when — 삭제 클릭만으로는 DELETE 가 발생하지 않아야 한다.
    await user.click(screen.getByRole('button', { name: '사람 삭제' }));
    expect(mock.history.delete).toHaveLength(0);

    // then — 확인 모달 승인 후에만 DELETE.
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '삭제' }));
    await waitFor(() => {
      expect(mock.history.delete).toHaveLength(1);
    });
    expect(mock.history.delete[0].url).toBe('/manage/labels/1');
  });

  it('중복_라벨_생성시_409응답이_사용자_메시지로_표시된다', async () => {
    // given — 생성 시 409 CONFLICT
    mock.onPost('/manage/labels').reply(409, {
      success: false,
      data: null,
      message: '이미 존재하는 라벨명입니다',
      errorCode: 'DUPLICATE_LABEL',
    });
    const user = userEvent.setup();
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // when — 유효한 폼으로 생성 시도
    await user.click(screen.getByRole('button', { name: '라벨 추가' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('라벨명'), '사람');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    // then — BE 사용자 메시지가 그대로 노출
    expect(await within(dialog).findByText('이미 존재하는 라벨명입니다')).toBeInTheDocument();
  });

  it('COCO_클래스를_지정해_생성하면_dtctTypeCd가_요청본문에_포함된다', async () => {
    // given — 생성 성공.
    mock.onPost('/manage/labels').reply(201, ok({ ...LABELS[1], labelId: 3, dtctTypeCd: 'person' }));
    const user = userEvent.setup();
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // when — 라벨명 + AI 탐지 클래스(사람=person) 지정 후 저장.
    await user.click(screen.getByRole('button', { name: '라벨 추가' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('라벨명'), '보행자');
    await user.selectOptions(within(dialog).getByLabelText('AI 탐지 클래스 (선택)'), 'person');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    // then — POST 본문에 dtctTypeCd='person' 포함.
    await waitFor(() => {
      expect(mock.history.post).toHaveLength(1);
    });
    expect(JSON.parse(mock.history.post[0].data)).toMatchObject({
      name: '보행자',
      dtctTypeCd: 'person',
    });
  });

  it('COCO_클래스_미지정으로_생성하면_dtctTypeCd가_null로_전송된다(미매핑_허용)', async () => {
    // given
    mock.onPost('/manage/labels').reply(201, ok({ ...LABELS[1], labelId: 3, dtctTypeCd: null }));
    const user = userEvent.setup();
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // when — AI 탐지 클래스 미지정(기본값) 상태로 저장.
    await user.click(screen.getByRole('button', { name: '라벨 추가' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('라벨명'), '기타객체');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    // then — dtctTypeCd=null(미매핑) 로 전송.
    await waitFor(() => {
      expect(mock.history.post).toHaveLength(1);
    });
    expect(JSON.parse(mock.history.post[0].data).dtctTypeCd).toBeNull();
  });

  it('COCO_중복_매핑시_409응답이_사용자_메시지로_표시된다', async () => {
    // given — 다른 활성 라벨이 이미 그 COCO 클래스에 매핑됨(409).
    mock.onPost('/manage/labels').reply(409, {
      success: false,
      data: null,
      message: '이미 다른 라벨에 매핑된 AI 탐지 클래스입니다',
      errorCode: 'DUPLICATE_DETECT_MAPPING',
    });
    const user = userEvent.setup();
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // when — 이미 매핑된 COCO 클래스를 다시 지정해 생성 시도.
    await user.click(screen.getByRole('button', { name: '라벨 추가' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('라벨명'), '사람2');
    await user.selectOptions(within(dialog).getByLabelText('AI 탐지 클래스 (선택)'), 'person');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    // then — BE 사용자 메시지가 그대로 노출(중복 안내).
    expect(
      await within(dialog).findByText('이미 다른 라벨에 매핑된 AI 탐지 클래스입니다'),
    ).toBeInTheDocument();
  });

  it('삭제_실패_409시_서버_메시지가_토스트로_표시된다', async () => {
    // given — 사용 중 라벨 삭제 시 409 CONFLICT + 서버 userMessage.
    mock.onDelete('/manage/labels/1').reply(409, {
      success: false,
      data: null,
      message: '사용 중인 라벨은 삭제할 수 없습니다',
      errorCode: 'LABEL_IN_USE',
    });
    const user = userEvent.setup();
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // when — 삭제 클릭 → 확인 모달 승인
    await user.click(screen.getByRole('button', { name: '사람 삭제' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '삭제' }));

    // then — 고정 문구가 아니라 BE 서버 메시지가 에러 토스트로 노출.
    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(
        toasts.some(
          (t) => t.variant === 'error' && t.message === '사용 중인 라벨은 삭제할 수 없습니다',
        ),
      ).toBe(true);
    });
  });

  it('WORKER_역할은_manage_labels_라우트에서_차단된다', async () => {
    // given — WORKER 로 인증된 상태 (RoleGuard 는 claims + isHydrated 만 참조)
    useAuthStore.setState({
      isHydrated: true,
      claims: { sub: 'w', role: Role.WORKER, channel: 'INTERNAL', exp: 9999999999 },
    });

    // when — REVIEWER 전용 가드 하위 페이지 진입
    renderWithProviders(
      <RoleGuard allow={[Role.REVIEWER]}>
        <div>라벨 관리 콘텐츠</div>
      </RoleGuard>,
      {
        initialEntries: ['/manage/labels'],
        routes: [{ path: '/forbidden', element: <div>접근 거부됨</div> }],
      },
    );

    // then — /forbidden 으로 리다이렉트, 페이지 콘텐츠는 노출되지 않는다.
    expect(await screen.findByText('접근 거부됨')).toBeInTheDocument();
    expect(screen.queryByText('라벨 관리 콘텐츠')).not.toBeInTheDocument();

    // cleanup
    useAuthStore.getState().clear();
    useAuthStore.setState({ isHydrated: false });
  });
});

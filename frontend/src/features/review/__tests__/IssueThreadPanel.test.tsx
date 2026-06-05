// Phase 2 — IssueThreadPanel (검수자↔작업자 이슈 스레드) 테스트.
//
// 보안: XSS — HTML 입력이 텍스트 노드로만 렌더 (dangerouslySetInnerHTML 미사용).

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { IssueThreadPanel } from '../components/IssueThreadPanel';
import type { IssueThread } from '../types';

function apiOk<T>(data: T) {
  return { success: true, data, message: null, errorCode: null };
}

const rejectionThread: IssueThread = {
  issueSn: 1,
  issueTypeCd: 'REJECTION',
  issueSttsCd: 'OPEN',
  srcSn: null,
  reason: '바운딩 박스 누락',
  reportedUserNo: 'rev01',
  regDt: '2026-05-07T10:00:00Z',
  comments: [
    {
      commentSn: 11,
      authorNo: 'rev01',
      authorRoleCd: 'REVIEWER',
      content: '다시 확인 바랍니다',
      regDt: '2026-05-07T10:01:00Z',
    },
  ],
};

const inquiryOpen: IssueThread = {
  issueSn: 2,
  issueTypeCd: 'INQUIRY',
  issueSttsCd: 'OPEN',
  srcSn: 50,
  reason: '이 객체 클래스가 맞나요?',
  reportedUserNo: 'wkr01',
  regDt: '2026-05-07T11:00:00Z',
  comments: [],
};

const inquiryResolved: IssueThread = {
  issueSn: 3,
  issueTypeCd: 'INQUIRY',
  issueSttsCd: 'RESOLVED',
  srcSn: 50,
  reason: '해소된 문의',
  reportedUserNo: 'wkr01',
  regDt: '2026-05-07T09:00:00Z',
  comments: [],
};

describe('IssueThreadPanel', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('작업자_문의_등록_제출시_POST_호출되고_스레드에_표시', async () => {
    let threads: IssueThread[] = [];
    mock.onGet('/videos/100/issues').reply(() => [200, apiOk(threads)]);

    let posted: { content: string; srcSn?: number } | null = null;
    mock.onPost('/videos/100/issues').reply((config) => {
      posted = JSON.parse(config.data ?? '{}');
      const created: IssueThread = {
        issueSn: 9,
        issueTypeCd: 'INQUIRY',
        issueSttsCd: 'OPEN',
        srcSn: posted?.srcSn ?? null,
        reason: posted?.content ?? '',
        reportedUserNo: 'wkr01',
        regDt: '2026-05-07T12:00:00Z',
        comments: [],
      };
      threads = [...threads, created];
      return [201, apiOk(created)];
    });

    const user = userEvent.setup();
    renderWithProviders(<IssueThreadPanel rawSn={100} mode="worker" />);

    const input = await screen.findByTestId('inquiry-input');
    await user.type(input, '이 라벨 맞나요?');
    await user.click(screen.getByTestId('inquiry-submit'));

    await waitFor(() => {
      expect(posted).not.toBeNull();
    });
    expect(posted).toMatchObject({ content: '이 라벨 맞나요?' });

    await waitFor(() => {
      expect(screen.getByText('이 라벨 맞나요?')).toBeInTheDocument();
    });
  });

  it('검수자_댓글_입력시_addComment_호출_후_쿼리_무효화', async () => {
    let threads: IssueThread[] = [{ ...inquiryOpen }];
    mock.onGet('/videos/100/issues').reply(() => [200, apiOk(threads)]);

    let commentPosted = false;
    mock.onPost('/issues/2/comments').reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      commentPosted = true;
      // 서버가 상태 ANSWERED 로 전이 (FE 는 재조회로 신뢰).
      threads = threads.map((t) =>
        t.issueSn === 2
          ? {
              ...t,
              issueSttsCd: 'ANSWERED',
              comments: [
                ...t.comments,
                {
                  commentSn: 21,
                  authorNo: 'rev01',
                  authorRoleCd: 'REVIEWER',
                  content: body.content,
                  regDt: '2026-05-07T13:00:00Z',
                },
              ],
            }
          : t,
      );
      return [201, apiOk({
        commentSn: 21,
        authorNo: 'rev01',
        authorRoleCd: 'REVIEWER',
        content: body.content,
        regDt: '2026-05-07T13:00:00Z',
      })];
    });

    const user = userEvent.setup();
    renderWithProviders(<IssueThreadPanel rawSn={100} mode="reviewer" />);

    const input = await screen.findByTestId('comment-input-2');
    await user.type(input, '확인했습니다');
    await user.click(screen.getByTestId('comment-submit-2'));

    await waitFor(() => {
      expect(commentPosted).toBe(true);
    });
    // 재조회로 ANSWERED 댓글 반영.
    await waitFor(() => {
      expect(screen.getByText('확인했습니다')).toBeInTheDocument();
    });
  });

  it('검수자에게만_해소_버튼이_보임', async () => {
    mock.onGet('/videos/100/issues').reply(200, apiOk([inquiryOpen]));

    const { unmount } = renderWithProviders(
      <IssueThreadPanel rawSn={100} mode="reviewer" />,
    );
    await waitFor(() => {
      expect(screen.getByTestId('resolve-button-2')).toBeInTheDocument();
    });
    unmount();

    renderWithProviders(<IssueThreadPanel rawSn={100} mode="worker" />);
    await waitFor(() => {
      expect(screen.getByText('이 객체 클래스가 맞나요?')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('resolve-button-2')).not.toBeInTheDocument();
  });

  it('RESOLVED_문의_스레드는_입력창_비활성', async () => {
    mock.onGet('/videos/100/issues').reply(200, apiOk([inquiryResolved]));

    renderWithProviders(<IssueThreadPanel rawSn={100} mode="reviewer" />);

    await waitFor(() => {
      expect(screen.getByText('해소된 문의')).toBeInTheDocument();
    });
    expect(screen.getByTestId('comment-input-3')).toBeDisabled();
    expect(screen.getByText('해소됨')).toBeInTheDocument();
  });

  it('반려_스레드는_RESOLVED여도_댓글_입력_가능', async () => {
    const resolvedRejection: IssueThread = {
      ...rejectionThread,
      issueSttsCd: 'RESOLVED',
    };
    mock.onGet('/videos/100/issues').reply(200, apiOk([resolvedRejection]));

    renderWithProviders(<IssueThreadPanel rawSn={100} mode="reviewer" />);

    await waitFor(() => {
      expect(screen.getByText('바운딩 박스 누락')).toBeInTheDocument();
    });
    // 반려는 RESOLVED 여도 댓글 입력 가능 (BE 정책).
    expect(screen.getByTestId('comment-input-1')).not.toBeDisabled();
  });

  it('미해소_문의_카운트가_탭_배지에_표시', async () => {
    // INQUIRY OPEN 1건 + INQUIRY RESOLVED 1건 + REJECTION 1건 → 미해소 문의 = 1.
    mock
      .onGet('/videos/100/issues')
      .reply(200, apiOk([inquiryOpen, inquiryResolved, rejectionThread]));

    renderWithProviders(
      <IssueThreadPanel rawSn={100} mode="worker" />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('unresolved-inquiry-count')).toHaveTextContent('1');
    });
  });

  it('1000자_초과_입력시_제출_차단_zod', async () => {
    let threads: IssueThread[] = [];
    let postCalled = false;
    mock.onGet('/videos/100/issues').reply(() => [200, apiOk(threads)]);
    mock.onPost('/videos/100/issues').reply(() => {
      postCalled = true;
      return [201, apiOk(inquiryOpen)];
    });

    const user = userEvent.setup();
    renderWithProviders(<IssueThreadPanel rawSn={100} mode="worker" />);

    const input = (await screen.findByTestId('inquiry-input')) as HTMLTextAreaElement;
    // maxLength 로 브라우저 차단 + zod 보강 — 1001자 직접 set 후 submit.
    await user.click(input);
    // fireEvent 대신 직접 값 주입 후 제출 시도.
    const longText = 'a'.repeat(1001);
    await user.clear(input);
    // type 은 maxLength 로 잘리므로 paste 로 우회 후 zod 검증 확인.
    input.value = longText;
    input.dispatchEvent(new Event('input', { bubbles: true }));

    const submit = screen.getByTestId('inquiry-submit');
    await user.click(submit);

    // zod 검증 실패 → POST 미발사.
    await waitFor(() => {
      expect(screen.getByTestId('inquiry-error')).toBeInTheDocument();
    });
    expect(postCalled).toBe(false);
  });

  it('409_응답시_충돌_안내_후_스레드_재조회', async () => {
    let getCount = 0;
    mock.onGet('/videos/100/issues').reply(() => {
      getCount += 1;
      return [200, apiOk([inquiryOpen])];
    });
    mock.onPost('/issues/2/resolve').reply(409, {
      success: false,
      data: null,
      message: '다른 사용자가 먼저 처리했습니다',
      errorCode: 'CONFLICT',
    });

    const user = userEvent.setup();
    renderWithProviders(<IssueThreadPanel rawSn={100} mode="reviewer" />);

    const resolveBtn = await screen.findByTestId('resolve-button-2');
    const beforeCount = getCount;
    await user.click(resolveBtn);

    // 충돌 안내 노출.
    await waitFor(() => {
      expect(screen.getByText(/다른 사용자가 먼저 처리/)).toBeInTheDocument();
    });
    // 재조회 발생.
    await waitFor(() => {
      expect(getCount).toBeGreaterThan(beforeCount);
    });
  });

  it('본문에_HTML_포함시_텍스트로만_렌더', async () => {
    const xssThread: IssueThread = {
      ...inquiryOpen,
      issueSn: 7,
      reason: '<script>alert(1)</script>',
      comments: [
        {
          commentSn: 71,
          authorNo: 'wkr01',
          authorRoleCd: 'WORKER',
          content: '<img src=x onerror=alert(2)>',
          regDt: '2026-05-07T14:00:00Z',
        },
      ],
    };
    mock.onGet('/videos/100/issues').reply(200, apiOk([xssThread]));

    const { container } = renderWithProviders(
      <IssueThreadPanel rawSn={100} mode="reviewer" />,
    );

    // 텍스트 노드로 렌더 — <script>/<img> 태그가 DOM 요소로 생성되지 않아야 함.
    await waitFor(() => {
      expect(screen.getByText('<script>alert(1)</script>')).toBeInTheDocument();
    });
    expect(screen.getByText('<img src=x onerror=alert(2)>')).toBeInTheDocument();
    expect(container.querySelector('script')).toBeNull();
    // 본문 영역에 주입된 img 태그가 없어야 함 (텍스트로만 존재).
    const injectedImg = within(container).queryByRole('img', { hidden: true });
    expect(injectedImg).toBeNull();
  });
});

// 화면ID: KLID-AT-SC-031 — 게시판(공지) 상세
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Eye, EyeOff, Pencil, Trash2 } from 'lucide-react';

import { AttachmentList } from '@/components/common/AttachmentList';
import { Badge } from '@/components/common/Badge';
import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/common/Skeleton';
import { downloadAttachment } from '@/features/notice/api';
import { useNoticeActions } from '@/features/notice/hooks/useNoticeActions';
import { useNotice } from '@/features/notice/hooks/useNotices';
import { NoticePubStatus, type NoticeAttach } from '@/features/notice/types';
import { cn } from '@/lib/cn';
import { resolveDisplayName } from '@/lib/displayName';
import { formatFileSize } from '@/lib/formatFileSize';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCREEN-031 고충실 디자인의 카드 셸(design.css `.card`).
 * 이 화면은 액션 바 / 본문 / 관리 액션 / 첨부를 **각각 독립 카드**로 쌓는다 — 시각적으로도
 * 서로 다른 액션 그룹임을 드러내기 위한 것이라 하나로 합치지 말 것(아래 관리 액션 주석 참조).
 */
const CARD = 'rounded-lg border border-gray-200 bg-white shadow-sm';

function formatDateTime(iso: string | null): string {
  if (!iso) return '-';
  return iso.replace('T', ' ').slice(0, 16);
}

/**
 * 게시 메타 한 쌍(`작성자: 홍길동`).
 *
 * ⚠ **라벨과 값을 자식 요소로 쪼개지 않는다** — 한 요소의 직접 텍스트로 이어 둔다.
 *   ① 라벨을 별도 행/요소로 떼면 "작성자"와 이름이 서로 다른 문맥으로 읽힌다.
 *   ② 회귀 가드가 `작성자: 홍길동` 한 덩어리로 이 메타를 찾는다(getByText 는 자식 요소의
 *      텍스트를 합치지 않고 **직접 텍스트 노드만** 본다 — 쪼개면 조회가 통째로 실패한다).
 *   그래서 dt/dd 2열 그리드가 아니라 인라인 한 쌍으로 두고, 시각 위계는 pair 사이 간격으로만 준다.
 */
function MetaPair({ label, value }: { label: string; value: string }) {
  return <span className="text-body-sm text-gray-600">{`${label}: ${value}`}</span>;
}

export function NoticeDetailPage() {
  const { id: idParam } = useParams<{ id: string }>();
  const id = Number(idParam);
  const navigate = useNavigate();
  const role = useAuthStore((s) => s.claims?.role) ?? Role.WORKER;
  const isReviewer = role === Role.REVIEWER;
  const pushToast = useUiStore((s) => s.pushToast);

  const { data: notice, isLoading, error } = useNotice(
    Number.isFinite(id) && id > 0 ? id : undefined,
  );
  // 수정·첨부 관리는 전용 수정 화면(/notice/:id/edit)이 담당한다 — 여기서는 발행·삭제만 다룬다.
  const { remove, publish, unpublish } = useNoticeActions();

  const [deleteOpen, setDeleteOpen] = useState(false);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);

  const handleDownload = (attach: NoticeAttach) => {
    setDownloadingId(attach.attachSn);
    downloadAttachment(id, attach.attachSn, attach.fileName)
      .catch(() =>
        pushToast({ variant: 'error', message: '첨부파일 다운로드에 실패했습니다.' }),
      )
      .finally(() => setDownloadingId(null));
  };

  const handleDelete = () => {
    remove.mutate(id, {
      onSuccess: () => {
        setDeleteOpen(false);
        navigate('/notice');
      },
    });
  };

  /** 목록으로 — 정상·로딩·오류 어느 상태에서도 같은 자리에 있어야 하는 이탈 경로다. */
  const backButton = (
    <Button variant="ghost" leftIcon={ArrowLeft} onClick={() => navigate('/notice')}>
      목록으로
    </Button>
  );

  if (isLoading) {
    // 본문 카드 영역 전체를 스켈레톤으로 대체한다(제목 1줄 + 본문 3줄) — 카드 셸을 유지해
    // 로딩→완료 전환에서 레이아웃이 튀지 않게 한다.
    return (
      <div className="flex flex-col gap-6">
        <div className={cn(CARD, 'flex items-center gap-4 px-6 py-4')}>{backButton}</div>
        <div className={cn(CARD, 'px-6 py-6')}>
          <div>
            <Skeleton height={22} width="55%" />
          </div>
          <div className="mt-4 space-y-2">
            <div>
              <Skeleton height={14} width="100%" />
            </div>
            <div>
              <Skeleton height={14} width="92%" />
            </div>
            <div>
              <Skeleton height={14} width="60%" />
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (error || !notice) {
    return (
      <div className="flex flex-col gap-6">
        <div className={cn(CARD, 'flex items-center gap-4 px-6 py-4')}>{backButton}</div>
        <div className={cn(CARD, 'px-6 py-6')}>
          <ErrorState title="공지를 불러올 수 없습니다" />
        </div>
      </div>
    );
  }

  const isPublished = notice.pubStatus === NoticePubStatus.PUBLISHED;
  // 작성자 — 표시명 우선, 없으면 원값(regId) 폴백. 둘 다 없으면 미표시.
  const writerLabel = resolveDisplayName(notice.writerName, notice.regId);

  return (
    <div className="flex flex-col gap-6">
      {/* 상단 액션 바 — 목록으로(좌) + 발행 제어(우, REVIEWER 전용).
          ★수정·삭제는 여기 두지 않는다. 사양 SCREEN-031 이 "하나의 액션 바가 아니다" 라고
          명시했다 — 발행 상태 제어(가역·게시 노출 축)와 콘텐츠 관리(수정·비가역 삭제 축)는
          성격이 달라, 한 줄에 모아 두면 '발행취소' 옆에서 '삭제' 를 잘못 누르기 쉽다.
          고충실 디자인이 이 둘을 **서로 다른 카드**로 떼어 둔 이유도 같다. */}
      <div className={cn(CARD, 'flex items-center justify-between gap-4 px-6 py-4')}>
        <div className="flex items-center gap-2">{backButton}</div>
        {isReviewer && (
          <div data-testid="notice-publish-actions" className="flex items-center gap-2">
            {/* 현재 상태에 해당하는 하나만 노출한다(동시 노출 안 함). */}
            {isPublished ? (
              <Button
                variant="outline"
                leftIcon={EyeOff}
                loading={unpublish.isPending}
                onClick={() => unpublish.mutate(id)}
              >
                발행취소
              </Button>
            ) : (
              <Button
                variant="primary"
                leftIcon={Eye}
                loading={publish.isPending}
                onClick={() => publish.mutate(id)}
              >
                발행
              </Button>
            )}
          </div>
        )}
      </div>

      {/* 게시글 본문 — 배지 → 제목 → 메타 → 구분선 → 본문 순의 시각 흐름 */}
      <article className={cn(CARD, 'px-6 pb-10 pt-6')} aria-labelledby="notice-title">
        {(notice.pinned || isReviewer) && (
          <div className="mb-4 flex flex-wrap items-center gap-2">
            {/* 고정·발행상태 모두 색상 단독 구분을 피해 텍스트 라벨을 항상 병기한다(UI-111). */}
            {notice.pinned && <Badge variant="pinned" label="고정" />}
            {isReviewer && (
              <Badge
                variant={isPublished ? 'success' : 'neutral'}
                label={isPublished ? '발행' : '작성중'}
              />
            )}
          </div>
        )}

        <h1
          id="notice-title"
          className="mb-4 break-words text-title-lg font-bold text-gray-950"
        >
          {notice.title}
        </h1>

        <div className="flex flex-wrap items-baseline gap-x-6 gap-y-1">
          {/* ★게시 메타는 작성자·등록·수정 3종만이다 — 발행일시(pubDt)는 응답에 있어도
              화면에 렌더하지 않는다(사양 SCREEN-031 '게시 메타' note).
              그 값은 "최근 발행 시점"이라 발행을 취소하면 비워지고 다시 발행하면 새 값으로
              덮인다 — 최초 발행 이력이 아니다. 여기 '발행: …' 으로 놓이면 게시 이력처럼
              읽히지만 실제로는 토글할 때마다 사라졌다 바뀌는 값이라 사실을 오도한다.
              발행 여부는 위 발행 상태 배지와 상단 발행/발행취소 버튼이 말한다. */}
          {writerLabel && <MetaPair label="작성자" value={writerLabel} />}
          <MetaPair label="등록" value={formatDateTime(notice.regDt)} />
          {notice.mdfcnDt && <MetaPair label="수정" value={formatDateTime(notice.mdfcnDt)} />}
        </div>

        <hr className="my-6 border-0 border-t border-gray-100" />

        <p className="whitespace-pre-wrap break-words text-body-md text-gray-900">
          {notice.content}
        </p>
      </article>

      {/* 하단 관리 액션 — 본문(article) 아래 별도 카드(사양 SCREEN-031).
          상단 발행 제어와 레이아웃이 분리되어 있다. */}
      {isReviewer && (
        <div
          data-testid="notice-manage-actions"
          className={cn(CARD, 'flex items-center justify-end gap-2 px-6 py-4')}
        >
          {/* 수정은 이 화면 안의 모달이 아니라 전용 수정 화면으로 이동한다(사양 SCREEN-031).
              모달이면 수정 화면에 직접 진입할 URL 이 없고, 첨부 관리까지 모달 안에 갇힌다. */}
          <Button
            variant="secondary"
            leftIcon={Pencil}
            onClick={() => navigate(`/notice/${id}/edit`)}
          >
            수정
          </Button>
          <Button variant="danger" leftIcon={Trash2} onClick={() => setDeleteOpen(true)}>
            삭제
          </Button>
        </div>
      )}

      {/* 첨부파일 — 첨부가 1건 이상일 때만 노출되는 별도 카드 */}
      {notice.attachments.length > 0 && (
        <section className={cn(CARD, 'px-6 py-6')} aria-label="첨부파일 목록">
          {/* 제목 옆 장식 아이콘은 두지 않는다 — 클립 아이콘은 목록 각 행이 파일 식별 표식으로
              이미 달고 있어(UI-112), 제목에 같은 글리프를 한 번 더 두면 중복이다. */}
          <h2 className="mb-4 text-title-sm font-semibold text-gray-900">
            첨부파일 ({notice.attachments.length})
          </h2>
          <AttachmentList
            action="download"
            items={notice.attachments.map((a) => ({
              id: a.attachSn,
              name: a.fileName,
              // 괄호 표기를 문자열에 포함해 넘긴다 — 편집 화면(SCREEN-030)과 같은 목록
              // 컴포넌트를 공유하므로, 한쪽만 괄호를 떼면 같은 첨부의 크기가 화면마다
              // 다르게 보인다. 괄호는 리스타일 이전 상세 화면의 표기이기도 하다.
              size: `(${formatFileSize(a.fileSize)})`,
              status: downloadingId === a.attachSn ? ('downloading' as const) : ('idle' as const),
            }))}
            // 진행 중 항목은 UI-112 가 aria-busy + disabled 로 재클릭을 막는다.
            onAction={(_item, index) => handleDownload(notice.attachments[index])}
          />
        </section>
      )}

      {/* Delete Confirm */}
      {/* 삭제 확인 — 제목 '공지 삭제', 본문에 **대상 게시글 제목**을 넣는다(사양 SCREEN-031).
          고정 문구는 어느 공지를 지우는지 말해 주지 않아, 목록·상세를 오가다 다른 글에서
          삭제를 누른 경우를 확인 단계가 걸러 내지 못한다(비가역 작업). */}
      <ConfirmDialog
        open={deleteOpen}
        title="공지 삭제"
        description={`${notice.title} 공지를 삭제합니다. 이 작업은 되돌릴 수 없습니다.`}
        confirmLabel="삭제"
        variant="danger"
        loading={remove.isPending}
        onConfirm={handleDelete}
        onCancel={() => setDeleteOpen(false)}
      />
    </div>
  );
}

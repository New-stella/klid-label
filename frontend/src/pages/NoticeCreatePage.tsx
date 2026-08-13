// 공지 작성 화면 (route /notice/new) — REVIEWER 전용.
//
// 사양이 작성 동선을 모달이 아니라 전용 화면으로 규정한다. 모달이면 이 화면에 직접 진입할 URL 이
// 없어 북마크·공유·뒤로가기가 성립하지 않는다.
// 첨부파일 관리 영역은 여기 두지 않는다 — 게시글 id 가 발급되기 전이라 업로드 대상이 없고,
// 첨부는 저장 후 수정 화면에서 추가한다.
import { ArrowLeft } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { Breadcrumb } from '@/components/common/Breadcrumb';
import { Button } from '@/components/common/Button';
import { Card, CardContent } from '@/components/common/Card';
import { NoticeCreateForm } from '@/features/notice/components/NoticeCreateForm';
import { useNoticeActions } from '@/features/notice/hooks/useNoticeActions';
import type { NoticeForm } from '@/features/notice/types';

export function NoticeCreatePage() {
  const navigate = useNavigate();
  const { create } = useNoticeActions();

  const handleSubmit = (form: NoticeForm) => {
    create.mutate(form, {
      onSuccess: (created) => {
        // 저장 성공 시 새로 생성된 게시글의 상세로 이동한다.
        // replace 인 이유 — 히스토리에 작성 폼을 남기면 상세에서 뒤로가기 했을 때 이미 저장된
        // 내용을 다시 입력하는 빈 폼으로 돌아가 중복 작성을 유도한다.
        navigate(`/notice/${created.id}`, { replace: true });
      },
    });
  };

  return (
    // 헤더와 폼 카드 사이는 40px — 절차 단위를 갈라 보이게 하는 여백이다(SCREEN-036 디자인).
    <div className="flex flex-col gap-10">
      {/* 페이지 헤더 — 현재 위치 + 뒤로 가기(이전 화면) + 제목·설명 */}
      <header className="flex flex-col gap-2">
        <Breadcrumb
          items={[{ label: '공지사항', href: '/notice' }, { label: '새 공지 작성' }]}
        />
        <div className="flex items-start gap-4">
          <Button
            variant="outline"
            size="icon"
            aria-label="뒤로 가기"
            onClick={() => navigate(-1)}
            className="mt-0.5 shrink-0"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          </Button>
          <div className="flex min-w-0 flex-col gap-2">
            <h1 className="text-title-lg text-gray-900">새 공지 작성</h1>
            {/* 설명 회색은 gray-600 이 하한이다 — 이 페이지 배경이 gray-50 이라 gray-500 은
                그 위에서 AA(4.5:1) 미달이다. */}
            <p className="max-w-[720px] text-body-sm text-gray-600">
              공지·가이드라인 게시글을 새로 작성합니다.{' '}
              <strong className="font-semibold text-gray-700">REVIEWER 전용</strong> 화면이며,
              저장 후 수정 화면에서 첨부파일을 추가할 수 있습니다.
            </p>
          </div>
        </div>
      </header>

      {/* 폼 카드 — 헤더 없이 본문만 두는 조합. 상하 여백도 24px 로 맞춘다. */}
      <Card className="py-6">
        <CardContent>
          <h2 className="sr-only">공지 작성 폼</h2>
          <NoticeCreateForm
            submitting={create.isPending}
            onSubmit={handleSubmit}
            // 취소는 목록 화면으로 이동한다(사양 SCREEN-036 '취소' note).
            onCancel={() => navigate('/notice')}
          />
        </CardContent>
      </Card>
    </div>
  );
}

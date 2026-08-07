// 공지 작성 화면 (route /notice/new) — REVIEWER 전용.
//
// 사양이 작성 동선을 모달이 아니라 전용 화면으로 규정한다. 모달이면 이 화면에 직접 진입할 URL 이
// 없어 북마크·공유·뒤로가기가 성립하지 않는다.
// 첨부파일 관리 영역은 여기 두지 않는다 — 게시글 id 가 발급되기 전이라 업로드 대상이 없고,
// 첨부는 저장 후 수정 화면에서 추가한다.
import { ArrowLeft } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { NoticeFormSection } from '@/features/notice/components/NoticeFormSection';
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
    <div className="space-y-4">
      {/* 페이지 헤더 — 뒤로 가기(이전 화면) + 제목 */}
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          leftIcon={ArrowLeft}
          onClick={() => navigate(-1)}
        >
          뒤로 가기
        </Button>
        <h1 className="text-title-lg font-bold text-gray-900">새 공지 작성</h1>
      </div>

      <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <NoticeFormSection
          submitLabel="작성"
          submitting={create.isPending}
          onSubmit={handleSubmit}
          // 취소는 목록 화면으로 이동한다(사양 SCREEN-036 '취소' note).
          onCancel={() => navigate('/notice')}
        />
      </div>
    </div>
  );
}

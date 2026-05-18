import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

import { ErrorState } from '@/components/common/ErrorState';
import { HistoryPanel } from '@/features/version/components/HistoryPanel';

/**
 * SCR-HIST-001 — 버전 목록·diff 라우트 페이지.
 *
 * 본문 UI 는 {@link HistoryPanel} 로 추출되어 라벨링 화면 우측 인라인 패널과 공유한다.
 * 본 페이지는 외부 링크/북마크 호환을 위해 라우트(/history/:videoId)는 그대로 유지.
 *
 * 권한:
 * - 조회(WORKER+REVIEWER): 버전 목록 + diff 가능
 * - 롤백(REVIEWER+WORKER): HistoryPanel 내부에서 role 검증. WORKER는 본인 배정만 (BE IDOR 검증)
 *
 * 보안: 사용자 입력 commit hash 는 BE 에서 SHA hex 검증. FE 는 단순 전달.
 */
export function HistoryPage() {
  const { videoId } = useParams<{ videoId: string }>();
  const navigate = useNavigate();
  const numericId = videoId ? Number(videoId) : NaN;

  if (Number.isNaN(numericId)) {
    return <ErrorState title="잘못된 영상 ID" />;
  }

  return (
    <div className="space-y-5 p-6" data-testid="history-page">
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate(-1)}
          className="rounded-lg p-2 text-gray-500 transition-colors hover:bg-gray-100"
          aria-label="뒤로가기"
        >
          <ArrowLeft size={18} />
        </button>
        <div>
          <h1 className="text-xl font-bold text-gray-900">버전 관리</h1>
          <p className="mt-0.5 text-sm text-gray-500">{videoId}</p>
        </div>
      </div>

      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <HistoryPanel srcSn={numericId} />
      </div>
    </div>
  );
}

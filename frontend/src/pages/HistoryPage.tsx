import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

import { ErrorState } from '@/components/common/ErrorState';
import { Spinner } from '@/components/common/Spinner';
import { HistoryPanel } from '@/features/version/components/HistoryPanel';
import { useReviewFrames } from '@/features/review/hooks/useReviewFrames';

/**
 * SCR-HIST-001 — 버전 목록·diff 라우트 페이지.
 *
 * 라우트 파라미터는 영상(LS_DATA_RAW.RAW_SN = videoId)이다. 버전은 프레임(srcSn) 단위 개념이므로,
 * videoId 로 프레임 목록을 조회해 프레임 선택 UI 를 제공하고 선택된 프레임의 srcSn 을 {@link HistoryPanel}
 * 에 전달한다. (이전 구현은 videoId 를 srcSn 으로 직접 전달해 항상 빈 화면 — R6-C.)
 *
 * 권한:
 * - 조회(REVIEWER): 버전 목록 + diff 가능. 프레임 목록 조회 API(/reviews/{videoId}/frames)는 REVIEWER.
 * - 롤백(REVIEWER+WORKER): HistoryPanel 내부에서 role 검증.
 *
 * 보안: videoId/srcSn 은 axios 가 URL 인코딩. commit hash 는 BE 에서 SHA hex 검증.
 */
export function HistoryPage() {
  const { videoId } = useParams<{ videoId: string }>();
  const navigate = useNavigate();
  const numericVideoId = videoId ? Number(videoId) : NaN;

  const isValidId = !Number.isNaN(numericVideoId);
  const { data: frameList, isLoading, error } = useReviewFrames(
    isValidId ? numericVideoId : undefined,
  );

  const frames = useMemo(
    () => (Array.isArray(frameList?.frames) ? frameList!.frames : []),
    [frameList],
  );

  // 선택된 프레임(srcSn) — 첫 프레임 자동 선택.
  const [selectedSrcSn, setSelectedSrcSn] = useState<number | null>(null);
  useEffect(() => {
    if (frames.length > 0) {
      // 현재 선택이 목록에 없으면 첫 프레임으로 복귀 (영상 전환/초기 진입)
      setSelectedSrcSn((prev) =>
        prev !== null && frames.some((f) => f.srcSn === prev) ? prev : frames[0].srcSn,
      );
    } else {
      setSelectedSrcSn(null);
    }
  }, [frames]);

  if (!isValidId) {
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
          <p className="mt-0.5 text-sm text-gray-500">영상 #{videoId}</p>
        </div>
      </div>

      {/* 프레임 선택 — 버전은 프레임(srcSn) 단위이므로 영상의 프레임 중 하나를 선택한다. */}
      {frames.length > 0 && selectedSrcSn !== null && (
        <div className="flex items-center gap-2">
          <label htmlFor="history-frame-select" className="text-sm font-medium text-gray-700">
            프레임
          </label>
          <select
            id="history-frame-select"
            data-testid="history-frame-select"
            value={selectedSrcSn}
            onChange={(e) => setSelectedSrcSn(Number(e.target.value))}
            className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm focus:border-primary-500 focus:outline-none"
          >
            {frames.map((f) => (
              <option key={f.srcSn} value={f.srcSn}>
                프레임 #{f.frameNo} (srcSn {f.srcSn})
              </option>
            ))}
          </select>
        </div>
      )}

      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        {isLoading ? (
          <div className="flex items-center justify-center p-10">
            <Spinner label="프레임 로딩" />
          </div>
        ) : error ? (
          <ErrorState title="프레임 조회 실패" message={error.message} />
        ) : selectedSrcSn !== null ? (
          // 버전 브라우징 전용 페이지 — 커밋(버전) 탭을 기본 활성화.
          <HistoryPanel srcSn={selectedSrcSn} defaultTab="versions" />
        ) : (
          <div className="p-10 text-center text-sm text-gray-500">
            이 영상에는 추출된 프레임이 없습니다.
          </div>
        )}
      </div>
    </div>
  );
}

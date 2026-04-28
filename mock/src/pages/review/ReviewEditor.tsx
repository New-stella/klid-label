import { useEffect, useRef, useCallback, useState } from 'react';
import { useParams, useSearchParams, useNavigate } from 'react-router-dom';
import { X } from 'lucide-react';
import { useFetch } from '../../api/queries';
import { api } from '../../api/client';
import { useLabelStore } from '../../store/labelStore';
import { useToast } from '../../components/common/Toast';
import { LabelCanvas } from '../../components/label/canvas/LabelCanvas';
import { ObjectTree } from '../../components/label/ObjectTree';
import { AttributePanel } from '../../components/label/AttributePanel';
import { FrameStrip } from '../../components/label/FrameStrip';
import { FrameSlider } from '../../components/label/FrameSlider';
import { ReviewNotePanel } from '../../components/review/ReviewNotePanel';
import { RejectModal } from '../../components/review/RejectModal';
import { ApproveConfirm } from '../../components/review/ApproveConfirm';
import { IssueCommentModal } from '../../components/review/IssueCommentModal';
import type { ReviewDto, VideoDto, FrameLabels } from '../../api/types';
import type { ReviewIssue } from '../../types/review';

function generateIssueId(): string {
  return `issue-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
}

export function ReviewEditor() {
  const { id: reviewId = '' } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { showToast } = useToast();

  const isReadOnly = searchParams.get('readonly') === 'true';

  // Label store
  const setVideo = useLabelStore((s) => s.setVideo);
  const setFrame = useLabelStore((s) => s.setFrame);
  const setFrameLabels = useLabelStore((s) => s.setFrameLabels);
  const setReadOnly = useLabelStore((s) => s.setReadOnly);
  const currentFrame = useLabelStore((s) => s.currentFrame);
  const frames = useLabelStore((s) => s.frames);

  const loadedFramesRef = useRef<Set<number>>(new Set());

  // Fetch review
  const { data: review, isLoading: reviewLoading, error: reviewError } = useFetch<ReviewDto>(`/reviews/${reviewId}`);

  // Fetch video info (from review.videoId)
  const videoId = review?.videoId ?? '';
  const { data: video } = useFetch<VideoDto>(`/videos/${videoId}`, undefined);

  // Init label store when video loads
  useEffect(() => {
    if (!video) return;
    loadedFramesRef.current = new Set();
    setVideo(video.id, 60);
    setFrame(0);
    setReadOnly(true); // Always read-only in review context
  }, [video, setVideo, setFrame, setReadOnly]);

  // Cleanup: restore readOnly=false on unmount
  useEffect(() => {
    return () => { setReadOnly(false); };
  }, [setReadOnly]);

  // Load frame labels
  useEffect(() => {
    if (!videoId) return;
    if (loadedFramesRef.current.has(currentFrame)) return;
    if (frames[currentFrame] !== undefined) {
      loadedFramesRef.current.add(currentFrame);
      return;
    }
    loadedFramesRef.current.add(currentFrame);
    api
      .get<FrameLabels>(`/videos/${videoId}/frames/${currentFrame}/labels`)
      .then((data) => setFrameLabels(currentFrame, data.objects))
      .catch(() => setFrameLabels(currentFrame, []));
  }, [videoId, currentFrame, frames, setFrameLabels]);

  // Issue state — initialize from review.issues if REJECTED
  const [issues, setIssues] = useState<ReviewIssue[]>([]);
  useEffect(() => {
    if (review?.issues && review.issues.length > 0) {
      setIssues(
        review.issues.map((iss) => ({
          ...iss,
          id: generateIssueId(),
          createdAt: new Date().toISOString(),
        })),
      );
    }
  }, [review]);

  // Issue mode & comment modal (frame-level only — no coordinate)
  const [isIssueMode, setIsIssueMode] = useState(false);
  const [commentModalOpen, setCommentModalOpen] = useState(false);
  const [overallComment, setOverallComment] = useState('');

  const openIssueComposer = useCallback(() => {
    if (isReadOnly) return;
    setCommentModalOpen(true);
  }, [isReadOnly]);

  const handleAddIssue = (comment: string) => {
    setIssues((prev) => [
      ...prev,
      {
        id: generateIssueId(),
        frameNo: currentFrame,
        comment,
        createdAt: new Date().toISOString(),
      },
    ]);
    setCommentModalOpen(false);
    setIsIssueMode(false);
    showToast('이슈가 추가되었습니다', 'success');
  };

  const handleRemoveIssue = (idx: number) => {
    setIssues((prev) => prev.filter((_, i) => i !== idx));
  };

  const handleEditIssue = (idx: number, comment: string) => {
    setIssues((prev) => prev.map((iss, i) => (i === idx ? { ...iss, comment } : iss)));
  };

  // Approve / Reject modals
  const [showApprove, setShowApprove] = useState(false);
  const [showReject, setShowReject] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleApprove = async () => {
    setIsSubmitting(true);
    try {
      await api.post(`/reviews/${reviewId}/approve`);
      showToast('승인 완료', 'success');
      navigate('/review/pending');
    } catch {
      showToast('승인 실패', 'error');
    } finally {
      setIsSubmitting(false);
      setShowApprove(false);
    }
  };

  const handleReject = async (reason: string, issueList: ReviewIssue[]) => {
    setIsSubmitting(true);
    try {
      await api.post(`/reviews/${reviewId}/reject`, {
        reason,
        issues: issueList.map(({ frameNo, comment }) => ({ frameNo, comment })),
      });
      showToast('반려 완료', 'success');
      navigate('/review/pending');
    } catch {
      showToast('반려 실패', 'error');
    } finally {
      setIsSubmitting(false);
      setShowReject(false);
    }
  };

  // ── Render guards ──
  if (reviewLoading) {
    return (
      <div className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-gray-300">검수 정보 로드 중...</p>
        </div>
      </div>
    );
  }

  if (reviewError || !review) {
    return (
      <div className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white">
        <div className="text-center">
          <p className="text-lg font-semibold mb-2">검수 항목을 찾을 수 없습니다</p>
          <button
            onClick={() => navigate('/review/pending')}
            className="px-4 py-2 bg-blue-600 rounded-lg text-sm hover:bg-blue-700 transition-colors"
          >
            목록으로
          </button>
        </div>
      </div>
    );
  }

  const frameIssueNos = new Set(issues.map((iss) => iss.frameNo));

  return (
    <div className="fixed inset-0 bg-gray-900 flex flex-col overflow-hidden" style={{ zIndex: 50 }}>
      {/* ── Top bar ── */}
      <header className="flex items-center gap-3 px-4 bg-gray-800 border-b border-gray-700 shrink-0" style={{ height: 56 }}>
        <button
          onClick={() => navigate('/review/pending')}
          className="p-1.5 rounded text-gray-400 hover:text-white hover:bg-gray-700 transition-colors"
          aria-label="나가기"
        >
          <X size={18} />
        </button>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-semibold text-white truncate">{review.videoName}</p>
          <p className="text-xs text-gray-400">
            작업자: {review.workerName} · 제출: {new Date(review.submittedAt).toLocaleDateString('ko-KR')}
          </p>
        </div>
        {/* Status */}
        <div className="text-center hidden sm:block">
          <p className="text-xs text-gray-400">Frame {currentFrame + 1}</p>
          <p className="text-xs text-gray-500">이슈 {issues.length}개</p>
        </div>
        {/* Read-only banner */}
        {isReadOnly && (
          <span className="px-2 py-1 text-xs rounded bg-yellow-600/80 text-yellow-100 font-medium shrink-0">
            읽기 전용
          </span>
        )}
      </header>

      {/* ── Main content ── */}
      <div className="flex flex-1 overflow-hidden">
        {/* Canvas area */}
        <div className="flex-1 relative overflow-hidden">
          {videoId && (
            <LabelCanvas
              videoId={videoId}
              frameNo={currentFrame}
              readOnly
            />
          )}
        </div>

        {/* Right panel (object tree + review note) */}
        <div className="w-72 flex flex-col bg-gray-800 border-l border-gray-700 overflow-hidden">
          {/* Object Tree (top ~40%) */}
          <div className="flex flex-col overflow-hidden border-b border-gray-700" style={{ flex: '0 0 40%' }}>
            <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide border-b border-gray-700 shrink-0">
              객체 목록
            </div>
            <ObjectTree />
          </div>

          {/* Attribute Panel (middle ~25%) */}
          <div className="flex flex-col overflow-hidden border-b border-gray-700" style={{ flex: '0 0 25%' }}>
            <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide border-b border-gray-700 shrink-0">
              속성
            </div>
            <AttributePanel readOnly />
          </div>

          {/* Review Note Panel (remaining) */}
          <div className="flex-1 overflow-hidden">
            <ReviewNotePanel
              issues={issues}
              frameNo={currentFrame}
              isIssueMode={isIssueMode}
              onToggleIssueMode={() => {
                setIsIssueMode((v) => !v);
                if (!isIssueMode) openIssueComposer();
              }}
              onRemoveIssue={handleRemoveIssue}
              onEditIssue={handleEditIssue}
              onGoToFrame={(n) => setFrame(n)}
              overallComment={overallComment}
              onOverallCommentChange={setOverallComment}
              readOnly={isReadOnly}
            />
          </div>
        </div>
      </div>

      {/* ── Bottom bar ── */}
      <div className="shrink-0 border-t border-gray-700">
        {/* FrameStrip with issue highlights */}
        <div style={{ height: 60 }}>
          <FrameStripWithIssues videoId={videoId} issueFrameNos={frameIssueNos} />
        </div>
        {/* FrameSlider */}
        <div style={{ height: 40 }}>
          <FrameSlider />
        </div>
        {/* Action buttons */}
        {!isReadOnly && (
          <div className="flex items-center justify-end gap-3 px-4 py-3 bg-gray-800 border-t border-gray-700">
            <button
              onClick={() => setShowReject(true)}
              className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-red-600 text-white hover:bg-red-700 transition-colors"
            >
              ❌ 반려
            </button>
            <button
              onClick={() => setShowApprove(true)}
              className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-green-600 text-white hover:bg-green-700 transition-colors"
            >
              ✅ 승인
            </button>
          </div>
        )}
      </div>

      {/* Modals */}
      <ApproveConfirm
        open={showApprove}
        onClose={() => setShowApprove(false)}
        onConfirm={handleApprove}
        isLoading={isSubmitting}
      />

      <RejectModal
        open={showReject}
        onClose={() => setShowReject(false)}
        onConfirm={handleReject}
        issues={issues}
        workerName={review.workerName}
        isLoading={isSubmitting}
      />

      <IssueCommentModal
        open={commentModalOpen}
        frameNo={currentFrame}
        onClose={() => { setCommentModalOpen(false); setIsIssueMode(false); }}
        onConfirm={handleAddIssue}
      />
    </div>
  );
}

// ── Helper: FrameStrip with external issue frame highlights ──
function FrameStripWithIssues({
  videoId,
  issueFrameNos,
}: {
  videoId: string;
  issueFrameNos: Set<number>;
}) {
  if (!videoId) return null;
  return (
    <div className="relative h-full">
      <FrameStrip videoId={videoId} issueFrameNos={issueFrameNos} />
    </div>
  );
}

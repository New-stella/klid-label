import { useState, useEffect } from 'react';
import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';
import { Badge } from '../ui/Badge';
import { useFetch } from '../../api/queries';
import { api } from '../../api/client';
import { useToast } from '../common/Toast';
import { useSessionStore } from '../../store/sessionStore';
import type {
  TaskDto,
  UserDto,
  Page,
  BulkAssignRequest,
  BulkAssignResponse,
} from '../../api/types';
import { formatDate } from '../../utils/format';

interface AssignModalProps {
  open: boolean;
  onClose: () => void;
  /** single 모드일 때 사용. bulk 모드에서는 무시된다. */
  task: TaskDto | null;
  mode: 'assign' | 'reassign' | 'bulk';
  /** single 모드 — 단일 갱신 콜백 */
  onSuccess?: (updated: TaskDto) => void;
  /** bulk 모드 — 일괄 갱신 콜백 */
  onBulkSuccess?: (result: BulkAssignResponse) => void;
  /** bulk 모드일 때 대상 영상 ID 목록 */
  videoIds?: string[];
  /** bulk 모드 요약 표시용 영상명 매핑 */
  videoNameById?: Record<string, string>;
}

export function AssignModal({
  open,
  onClose,
  task,
  mode,
  onSuccess,
  onBulkSuccess,
  videoIds = [],
  videoNameById = {},
}: AssignModalProps) {
  const { showToast } = useToast();
  const { currentRole, currentUser } = useSessionStore();

  // Workers & Reviewers
  const { data: workersData } = useFetch<Page<UserDto>>('/users', { role: 'WORKER', size: 50 });
  const { data: reviewersData } = useFetch<Page<UserDto>>('/users', { role: 'REVIEWER', size: 50 });

  const workers = workersData?.content ?? [];
  const reviewers = reviewersData?.content ?? [];

  // Form state
  const [assigneeId, setAssigneeId] = useState('');
  const [reviewerId, setReviewerId] = useState('');
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const isBulk = mode === 'bulk';

  // Initialise form when task changes / modal opens
  useEffect(() => {
    if (!open) return;
    if (isBulk) {
      // bulk: 현재 사용자를 기본 작업자로 미리 선택 (사용자가 변경 가능)
      setAssigneeId(currentUser.id);
      setReviewerId(currentRole === 'REVIEWER' ? currentUser.id : '');
      setErrors({});
      return;
    }
    if (task) {
      // single: 기존 배정자 유지, 비어있으면 현재 사용자로 기본 채움
      setAssigneeId(task.assigneeId ?? currentUser.id);
      // REVIEWER can only assign to themselves as reviewer
      if (currentRole === 'REVIEWER') {
        setReviewerId(currentUser.id);
      } else {
        setReviewerId(task.reviewerId ?? '');
      }
      setErrors({});
    }
  }, [open, task, currentRole, currentUser.id, isBulk]);

  function validate(): boolean {
    const errs: Record<string, string> = {};
    if (!assigneeId) errs['assigneeId'] = '작업자를 선택해주세요.';
    setErrors(errs);
    return Object.keys(errs).length === 0;
  }

  async function handleSave() {
    if (!validate()) return;
    if (saving) return; // 이중 클릭 방어
    setSaving(true);
    const assigneeName = workers.find((w) => w.id === assigneeId)?.name;
    try {
      if (isBulk) {
        if (videoIds.length === 0) {
          showToast('선택된 영상이 없습니다.', 'error');
          setSaving(false);
          return;
        }
        const payload: BulkAssignRequest = {
          videoIds,
          assigneeId,
          assigneeName,
          reviewerId: reviewerId || undefined,
        };
        const result = await api.post<BulkAssignResponse>('/tasks/bulk-assign', payload);
        // 토스트는 호출 측(onBulkSuccess)에서 처리한다. 여기서는 생략.
        onBulkSuccess?.(result);
        onClose();
      } else {
        if (!task) return;
        const updated = await api.post<TaskDto>(`/tasks/${task.id}/assign`, {
          assigneeId,
          assigneeName,
          reviewerId: reviewerId || undefined,
        });
        showToast('배정 완료', 'success');
        onSuccess?.(updated);
        onClose();
      }
    } catch {
      showToast('배정 중 오류가 발생했습니다.', 'error');
    } finally {
      setSaving(false);
    }
  }

  if (!isBulk && !task) return null;
  if (isBulk && videoIds.length === 0) return null;

  const canChangeReviewer = currentRole === 'REVIEWER';
  const isReassign = mode === 'reassign';

  const title = isBulk
    ? `${videoIds.length}개 영상 일괄 배정`
    : isReassign
      ? '작업 재배정'
      : '작업 배정';

  // bulk 요약 — 처음 3개 + 외 N건
  const previewIds = videoIds.slice(0, 3);
  const remaining = Math.max(0, videoIds.length - previewIds.length);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      size="lg"
      footer={
        <>
          <Button variant="secondary" size="sm" onClick={onClose} disabled={saving}>
            취소
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={handleSave}
            loading={saving}
            disabled={!assigneeId || saving}
          >
            {isBulk ? `${videoIds.length}건 일괄 배정` : '저장'}
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        {/* 1. 영상 정보 (읽기전용) */}
        {isBulk ? (
          <div className="bg-gray-50 rounded-lg px-4 py-3 space-y-2">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              대상 영상 ({videoIds.length}건)
            </p>
            <div className="flex flex-wrap gap-1.5">
              {previewIds.map((vid) => (
                <Badge key={vid} tone="info" size="sm">
                  {videoNameById[vid] ?? vid}
                </Badge>
              ))}
              {remaining > 0 && (
                <Badge tone="neutral" size="sm">
                  외 {remaining}건
                </Badge>
              )}
            </div>
            <p className="text-xs text-gray-500">
              선택된 모든 영상에 동일한 작업자/검수자가 배정됩니다. 이미 동일 배정인 영상은 변경되지 않습니다.
            </p>
          </div>
        ) : task ? (
          <div className="bg-gray-50 rounded-lg px-4 py-3 space-y-1.5">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">영상 정보</p>
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-medium text-gray-800 text-sm">{task.videoName}</span>
              <Badge tone="info" size="sm">{task.videoId}</Badge>
            </div>
            <div className="flex items-center gap-3 text-xs text-gray-500">
              <span>생성: {formatDate(task.createdAt, 'YYYY-MM-DD')}</span>
              <span>레이블: {task.labelCount.toLocaleString('ko-KR')}개</span>
              <span>진행률: {task.progress}%</span>
            </div>
          </div>
        ) : null}

        {/* 2. 작업자 선택 */}
        <div className="space-y-1">
          <label htmlFor="assign-worker" className="text-sm font-medium text-gray-700">
            작업자 <span className="text-red-500">*</span>
          </label>
          <select
            id="assign-worker"
            value={assigneeId}
            onChange={(e) => {
              setAssigneeId(e.target.value);
              setErrors((prev) => ({ ...prev, assigneeId: '' }));
            }}
            disabled={saving}
            className="w-full py-2 px-3 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:bg-gray-50 disabled:text-gray-400"
          >
            <option value="">작업자 선택</option>
            {workers.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name} {w.status === 'INACTIVE' ? '(비활성)' : ''}
              </option>
            ))}
          </select>
          {errors['assigneeId'] && (
            <p className="text-xs text-red-500">{errors['assigneeId']}</p>
          )}
        </div>

        {/* 3. 검수자 선택 */}
        <div className="space-y-1">
          <label htmlFor="assign-reviewer" className="text-sm font-medium text-gray-700">
            검수자
          </label>
          {canChangeReviewer ? (
            <select
              id="assign-reviewer"
              value={reviewerId}
              onChange={(e) => setReviewerId(e.target.value)}
              disabled={saving}
              className="w-full py-2 px-3 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:bg-gray-50 disabled:text-gray-400"
            >
              <option value="">검수자 선택 (선택)</option>
              {reviewers.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          ) : (
            <input
              id="assign-reviewer"
              type="text"
              readOnly
              value={reviewers.find((r) => r.id === currentUser.id)?.name ?? currentUser.name}
              className="w-full py-2 px-3 text-sm border border-gray-200 rounded-md bg-gray-50 text-gray-500"
            />
          )}
        </div>
      </div>
    </Modal>
  );
}

export default AssignModal;

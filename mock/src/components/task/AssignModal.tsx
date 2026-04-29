import { useState, useEffect } from 'react';
import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';
import { Badge } from '../ui/Badge';
import { useFetch } from '../../api/queries';
import { api } from '../../api/client';
import { useToast } from '../common/Toast';
import { useSessionStore } from '../../store/sessionStore';
import type { TaskDto, UserDto, Page } from '../../api/types';
import { formatDate } from '../../utils/format';

interface AssignModalProps {
  open: boolean;
  onClose: () => void;
  task: TaskDto | null;
  mode: 'assign' | 'reassign';
  onSuccess: (updated: TaskDto) => void;
}

export function AssignModal({ open, onClose, task, mode, onSuccess }: AssignModalProps) {
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

  // Initialise form when task changes / modal opens
  useEffect(() => {
    if (open && task) {
      setAssigneeId(task.assigneeId ?? '');
      // REVIEWER can only assign to themselves as reviewer
      if (currentRole === 'REVIEWER') {
        setReviewerId(currentUser.id);
      } else {
        setReviewerId(task.reviewerId ?? '');
      }
      setErrors({});
    }
  }, [open, task, currentRole, currentUser.id]);

  function validate(): boolean {
    const errs: Record<string, string> = {};
    if (!assigneeId) errs['assigneeId'] = '작업자를 선택해주세요.';
    setErrors(errs);
    return Object.keys(errs).length === 0;
  }

  async function handleSave() {
    if (!task) return;
    if (!validate()) return;
    setSaving(true);
    try {
      const assigneeName = workers.find((w) => w.id === assigneeId)?.name;
      const updated = await api.post<TaskDto>(`/tasks/${task.id}/assign`, {
        assigneeId,
        assigneeName,
        reviewerId: reviewerId || undefined,
      });
      showToast('배정 완료', 'success');
      onSuccess(updated);
      onClose();
    } catch {
      showToast('배정 중 오류가 발생했습니다.', 'error');
    } finally {
      setSaving(false);
    }
  }

  if (!task) return null;

  const canChangeReviewer = currentRole === 'REVIEWER';
  const isReassign = mode === 'reassign';

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isReassign ? '작업 재배정' : '작업 배정'}
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
            disabled={!assigneeId}
          >
            저장
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        {/* 1. 영상 정보 (읽기전용) */}
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
            className="w-full py-2 px-3 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
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
              className="w-full py-2 px-3 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
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

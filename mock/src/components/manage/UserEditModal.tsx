import { useState } from 'react';
import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';
import type { UserDto } from '../../api/types';
import { ROLES, ROLE_LABEL } from '../../types/role';
import { avatarColorFromId } from '../../utils/format';

interface UserEditModalProps {
  user: UserDto | null;
  open: boolean;
  onClose: () => void;
  onSave: (id: string, patch: Partial<Pick<UserDto, 'role' | 'status'>>) => Promise<void>;
}

export function UserEditModal({ user, open, onClose, onSave }: UserEditModalProps) {
  const [role, setRole] = useState<UserDto['role']>(user?.role ?? 'WORKER');
  const [status, setStatus] = useState<UserDto['status']>(user?.status ?? 'ACTIVE');
  const [saving, setSaving] = useState(false);

  // Sync form when user changes
  const handleOpen = () => {
    if (user) {
      setRole(user.role);
      setStatus(user.status);
    }
  };

  if (!user) return null;

  const handleSave = async () => {
    setSaving(true);
    try {
      await onSave(user.id, { role, status });
      onClose();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="사용자 정보 수정"
      size="md"
      footer={
        <>
          <Button variant="secondary" size="md" onClick={onClose} disabled={saving}>
            취소
          </Button>
          <Button
            variant="primary"
            size="md"
            loading={saving}
            onClick={() => { void handleSave(); }}
          >
            저장
          </Button>
        </>
      }
    >
      <div className="space-y-5" onFocus={handleOpen}>
        {/* User info */}
        <div className="flex items-center gap-3 p-3 bg-gray-50 rounded-lg">
          <div
            className={[
              'w-10 h-10 rounded-full flex items-center justify-center text-white font-bold text-sm shrink-0',
              avatarColorFromId(user.id),
            ].join(' ')}
          >
            {user.name[0]}
          </div>
          <div className="min-w-0">
            <p className="font-semibold text-gray-900 text-sm">{user.name}</p>
            <p className="text-xs text-gray-400 truncate">{user.email}</p>
          </div>
        </div>

        {/* Role */}
        <div className="space-y-2">
          <label className="block text-sm font-medium text-gray-700" htmlFor="user-role">
            역할
          </label>
          <select
            id="user-role"
            value={role}
            onChange={(e) => setRole(e.target.value as UserDto['role'])}
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            {ROLES.map((r) => (
              <option key={r} value={r}>{ROLE_LABEL[r]}</option>
            ))}
          </select>
        </div>

        {/* Status */}
        <div className="space-y-2">
          <p className="text-sm font-medium text-gray-700">상태</p>
          <div className="flex items-center gap-6">
            {(['ACTIVE', 'INACTIVE'] as UserDto['status'][]).map((s) => (
              <label key={s} className="flex items-center gap-2 text-sm cursor-pointer">
                <input
                  type="radio"
                  name="user-status"
                  value={s}
                  checked={status === s}
                  onChange={() => setStatus(s)}
                  className="w-4 h-4 text-primary-600 border-gray-300 focus:ring-primary-500"
                />
                <span className={s === 'ACTIVE' ? 'text-green-700' : 'text-gray-500'}>
                  {s === 'ACTIVE' ? '활성' : '비활성'}
                </span>
              </label>
            ))}
          </div>
        </div>

        {/* Last login */}
        <div className="text-xs text-gray-400">
          최근 로그인: {new Date(user.lastLoginAt).toLocaleString('ko-KR')}
        </div>
      </div>
    </Modal>
  );
}

export default UserEditModal;

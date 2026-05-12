import { Lock } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

const ROLE_LABEL: Record<string, string> = {
  REVIEWER: '검수자',
  WORKER: '작업자',
  PORTAL_USER: '포털',
};

const ROLE_COLOR: Record<string, string> = {
  REVIEWER: 'bg-cyan-100 text-cyan-700',
  WORKER: 'bg-blue-100 text-blue-700',
  PORTAL_USER: 'bg-emerald-100 text-emerald-700',
};

export function ForbiddenPage() {
  const navigate = useNavigate();
  const role = useAuthStore((s) => s.claims?.role) ?? Role.WORKER;

  return (
    <main
      role="alert"
      className="flex min-h-full flex-col items-center justify-center py-20 px-6"
    >
      <div className="flex items-center justify-center w-20 h-20 rounded-full bg-red-50 mb-6">
        <Lock size={36} className="text-red-400" />
      </div>
      <h1 className="text-xl font-bold text-gray-800 mb-2">
        이 화면에 접근할 수 없습니다
      </h1>
      <p className="text-sm text-gray-500 mb-5 text-center max-w-xs">
        현재 역할로는 이 페이지에 접근 권한이 없습니다.
      </p>
      <div className="flex items-center gap-2 mb-8">
        <span className="text-xs text-gray-400">현재 역할:</span>
        <span
          className={[
            'text-xs font-semibold px-2.5 py-1 rounded-full',
            ROLE_COLOR[role] ?? 'bg-gray-100 text-gray-600',
          ].join(' ')}
        >
          {ROLE_LABEL[role] ?? role}
        </span>
      </div>
      <Button variant="primary" onClick={() => navigate('/')}>
        대시보드로
      </Button>
    </main>
  );
}

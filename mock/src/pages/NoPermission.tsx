import { Lock } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useSessionStore } from '../store/sessionStore';
import { ROLE_LABEL, ROLE_COLOR } from '../types/role';
import { Button } from '../components/ui/Button';

export function NoPermission() {
  const navigate = useNavigate();
  const currentRole = useSessionStore((s) => s.currentRole);

  return (
    <div className="flex flex-col items-center justify-center min-h-full py-20 px-6">
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
            ROLE_COLOR[currentRole],
          ].join(' ')}
        >
          {ROLE_LABEL[currentRole]}
        </span>
      </div>
      <Button onClick={() => navigate('/')}>대시보드로</Button>
    </div>
  );
}

export default NoPermission;

import { useLocation } from 'react-router-dom';
import { useSessionStore } from '../store/sessionStore';
import { ROLE_LABEL, ROLE_COLOR } from '../types/role';
import type { Role } from '../types/role';
import { useFetch } from '../api/queries';
import type { Page, VideoDto } from '../api/types';

interface PlaceholderProps {
  label: string;
  allowedRoles?: Role[];
}

export function Placeholder({ label, allowedRoles }: PlaceholderProps) {
  const location = useLocation();
  const currentRole = useSessionStore((s) => s.currentRole);
  const isDashboard = label === '대시보드';

  return (
    <div className="flex items-center justify-center min-h-full py-16 px-6">
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-10 max-w-lg w-full text-center">
        <div className="text-5xl mb-4">🚧</div>
        <h2 className="text-xl font-bold text-gray-800 mb-2">{label}</h2>
        <p className="text-sm text-gray-400 mb-6">Phase N에서 구현 예정</p>
        <div className="bg-gray-50 rounded-lg px-4 py-2 mb-6 inline-block">
          <code className="text-xs text-gray-500 font-mono">{location.pathname}</code>
        </div>
        {allowedRoles && allowedRoles.length > 0 && (
          <div className="flex flex-wrap items-center justify-center gap-2 mb-6">
            <span className="text-xs text-gray-400">허용 역할:</span>
            {allowedRoles.map((role) => (
              <span
                key={role}
                className={[
                  'text-xs font-semibold px-2.5 py-0.5 rounded-full',
                  ROLE_COLOR[role],
                  currentRole === role ? 'ring-2 ring-offset-1 ring-current' : '',
                ].join(' ')}
              >
                {ROLE_LABEL[role]}
              </span>
            ))}
          </div>
        )}
        {isDashboard && <ApiDemo />}
      </div>
    </div>
  );
}

function ApiDemo() {
  const { data, isLoading, error } = useFetch<Page<VideoDto>>('/videos', { size: 5 });

  return (
    <div className="mt-2 border-t border-gray-100 pt-4 text-left">
      <p className="text-xs font-semibold text-gray-500 mb-2">MSW API 샘플 응답</p>
      {isLoading && (
        <p className="text-xs text-gray-400 animate-pulse">로딩 중...</p>
      )}
      {error && (
        <p className="text-xs text-red-500">오류: {error.message}</p>
      )}
      {data && (
        <div className="bg-blue-50 rounded-lg p-3 text-xs text-blue-800 space-y-1">
          <p>
            <span className="font-semibold">API 샘플 응답:</span>{' '}
            {data.totalElements}건 / 첫 영상: {data.content[0]?.cctvName ?? '-'}
          </p>
          <p>
            <span className="font-semibold">처리 상태:</span> {data.content[0]?.batchStatus ?? '-'}
          </p>
          <p className="text-blue-500 text-[10px]">/api/v1/videos → ApiResponse 래퍼 수신 완료</p>
        </div>
      )}
    </div>
  );
}

export default Placeholder;

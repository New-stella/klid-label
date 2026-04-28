import { useNavigate } from 'react-router-dom';
import { Button } from '../components/ui/Button';

export function NotFound() {
  const navigate = useNavigate();

  return (
    <div className="flex flex-col items-center justify-center min-h-full py-20 px-6">
      <div className="text-6xl font-extrabold text-gray-200 mb-4 select-none">404</div>
      <h1 className="text-xl font-bold text-gray-800 mb-2">페이지를 찾을 수 없습니다</h1>
      <p className="text-sm text-gray-500 mb-8 text-center max-w-xs">
        요청한 페이지가 존재하지 않거나 이동되었습니다.
      </p>
      <Button onClick={() => navigate('/')}>홈으로</Button>
    </div>
  );
}

export default NotFound;

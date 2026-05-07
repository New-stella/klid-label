import { Link } from 'react-router-dom';

interface AppErrorPageProps {
  status?: 403 | 404 | 500;
  message?: string;
}

const TITLES: Record<403 | 404 | 500, string> = {
  403: '접근 권한이 없습니다',
  404: '페이지를 찾을 수 없습니다',
  500: '서버 오류가 발생했습니다',
};

export function AppErrorPage({ status = 404, message }: AppErrorPageProps) {
  const title = TITLES[status];
  return (
    <main role="alert" className="flex min-h-[60vh] flex-col items-center justify-center p-8">
      <h1 className="text-page-title text-primary">{status}</h1>
      <p className="mt-4 text-section-title text-primary">{title}</p>
      {message && <p className="mt-2 text-body text-neutral">{message}</p>}
      <Link to="/" className="mt-6 text-secondary underline">
        메인으로 이동
      </Link>
    </main>
  );
}

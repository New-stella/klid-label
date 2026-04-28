import { Navigate } from 'react-router-dom';
import { useSessionStore } from '../store/sessionStore';

export function HomeRedirect() {
  const currentRole = useSessionStore((s) => s.currentRole);

  if (currentRole === 'PORTAL_USER') {
    return <Navigate to="/portal" replace />;
  }

  return <Navigate to="/dashboard" replace />;
}

export default HomeRedirect;

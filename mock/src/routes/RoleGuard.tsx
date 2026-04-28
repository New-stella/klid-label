import type { Role } from '../types/role';
import { useSessionStore } from '../store/sessionStore';
import { NoPermission } from '../pages/NoPermission';

interface RoleGuardProps {
  roles: Role[];
  children: React.ReactNode;
}

export function RoleGuard({ roles, children }: RoleGuardProps) {
  const currentRole = useSessionStore((s) => s.currentRole);

  // Empty roles array means all authenticated roles are allowed
  if (roles.length > 0 && !roles.includes(currentRole)) {
    return <NoPermission />;
  }

  return <>{children}</>;
}

export default RoleGuard;

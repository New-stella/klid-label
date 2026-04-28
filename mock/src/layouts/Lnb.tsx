import { NavLink } from 'react-router-dom';
import { useSessionStore } from '../store/sessionStore';
import { APP_ROUTES } from '../routes/routes';

export function Lnb() {
  const currentRole = useSessionStore((s) => s.currentRole);

  // Filter routes visible in menu for current role
  const menuRoutes = APP_ROUTES.filter(
    (r) =>
      !r.hideInMenu &&
      !r.portal &&
      r.roles.includes(currentRole)
  );

  // Group by menuGroup
  const groupMap = new Map<string, typeof menuRoutes>();
  for (const route of menuRoutes) {
    const group = route.menuGroup ?? '기타';
    if (!groupMap.has(group)) groupMap.set(group, []);
    groupMap.get(group)!.push(route);
  }

  return (
    <nav className="fixed top-14 left-0 bottom-0 w-60 bg-white border-r border-gray-200 overflow-y-auto z-30">
      <div className="py-3">
        {Array.from(groupMap.entries()).map(([group, routes]) => (
          <div key={group} className="mb-1">
            <div className="px-4 py-1.5">
              <span className="text-[10px] font-semibold text-gray-400 uppercase tracking-widest">
                {group}
              </span>
            </div>
            {routes.map((route) => (
              <NavLink
                key={route.path}
                to={route.path.replace(/\/:[^/]+/g, '/1')}
                className={({ isActive }) =>
                  [
                    'flex items-center mx-2 px-3 py-2 rounded-md text-sm font-medium transition-colors',
                    isActive
                      ? 'bg-primary-50 text-primary-700 border-l-2 border-primary-500 pl-[10px]'
                      : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900',
                  ].join(' ')
                }
              >
                {route.label}
              </NavLink>
            ))}
          </div>
        ))}
      </div>
    </nav>
  );
}

export default Lnb;

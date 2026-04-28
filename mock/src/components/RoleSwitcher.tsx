import { useEffect, useRef, useState } from 'react';
import { ChevronDown, Check, AlertTriangle } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useSessionStore } from '../store/sessionStore';
import { ROLES, ROLE_LABEL, ROLE_COLOR } from '../types/role';

export function RoleSwitcher() {
  const [open, setOpen] = useState(false);
  const currentRole = useSessionStore((s) => s.currentRole);
  const setRole = useSessionStore((s) => s.setRole);
  const navigate = useNavigate();
  const containerRef = useRef<HTMLDivElement>(null);

  // Close on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  // Close on ESC
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, []);

  const handleSelect = (role: (typeof ROLES)[number]) => {
    setRole(role);
    setOpen(false);
    navigate('/');
  };

  return (
    <div ref={containerRef} className="relative">
      {/* Trigger button */}
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2 px-3 py-1.5 rounded-lg border border-gray-200 bg-white hover:bg-gray-50 transition-colors text-sm font-medium text-gray-700"
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <span
          className={[
            'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold',
            ROLE_COLOR[currentRole],
          ].join(' ')}
        >
          {ROLE_LABEL[currentRole]}
        </span>
        <ChevronDown
          size={14}
          className={['text-gray-400 transition-transform', open ? 'rotate-180' : ''].join(' ')}
        />
      </button>

      {/* Dropdown */}
      {open && (
        <div
          role="listbox"
          className="absolute right-0 top-full mt-1.5 w-56 bg-white border border-gray-200 rounded-xl shadow-lg z-50 py-1.5 overflow-hidden"
        >
          {/* Warning header */}
          <div className="px-3 py-2 border-b border-gray-100">
            <div className="flex items-center gap-1.5 text-xs text-gray-400">
              <AlertTriangle size={12} className="text-yellow-500 shrink-0" aria-hidden="true" />
              <span>데모 전용 — 실제 운영에서는 상위 시스템 SSO</span>
            </div>
          </div>

          {/* Role options */}
          {ROLES.map((role) => {
            const isActive = role === currentRole;
            return (
              <button
                key={role}
                role="option"
                aria-selected={isActive}
                onClick={() => handleSelect(role)}
                className={[
                  'w-full flex items-center justify-between px-3 py-2.5 text-sm transition-colors',
                  isActive
                    ? 'bg-primary-50 text-primary-700'
                    : 'text-gray-700 hover:bg-gray-50',
                ].join(' ')}
              >
                <span
                  className={[
                    'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold',
                    ROLE_COLOR[role],
                  ].join(' ')}
                >
                  {ROLE_LABEL[role]}
                </span>
                {isActive && <Check size={14} className="text-primary-600 shrink-0" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default RoleSwitcher;

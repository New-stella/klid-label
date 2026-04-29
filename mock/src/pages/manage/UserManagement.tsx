import { useState } from 'react';
import { Users, Search } from 'lucide-react';
import { useFetch, useMutation } from '../../api/queries';
import { api } from '../../api/client';
import type { Page, UserDto } from '../../api/types';
import { Table } from '../../components/ui/Table';
import type { ColumnDef } from '../../components/ui/Table';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Pagination } from '../../components/ui/Pagination';
import { Skeleton } from '../../components/ui/Skeleton';
import { useToast } from '../../components/common/Toast';
import { UserEditModal } from '../../components/manage/UserEditModal';
import { avatarColorFromId } from '../../utils/format';

const ROLE_LABEL: Record<UserDto['role'], string> = {
  REVIEWER: '검수자',
  WORKER: '작업자',
  PORTAL_USER: '포털',
};

const ROLE_TONE: Record<UserDto['role'], 'warning' | 'info' | 'neutral'> = {
  REVIEWER: 'warning',
  WORKER: 'info',
  PORTAL_USER: 'neutral',
};

const STATUS_TONE: Record<UserDto['status'], 'success' | 'neutral'> = {
  ACTIVE: 'success',
  INACTIVE: 'neutral',
};

const PAGE_SIZE = 10;

export function UserManagement() {
  const { showToast } = useToast();

  const [page, setPage] = useState(0);
  const [roleFilter, setRoleFilter] = useState<UserDto['role'] | ''>('');
  const [statusFilter, setStatusFilter] = useState<UserDto['status'] | ''>('');
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');

  const [editUser, setEditUser] = useState<UserDto | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const queryParams: Record<string, unknown> = {
    page,
    size: PAGE_SIZE,
  };
  if (roleFilter) queryParams['role'] = roleFilter;

  const { data, isLoading, refetch } = useFetch<Page<UserDto>>('/manage/users', queryParams);

  const { mutate: updateUser } = useMutation<
    { id: string; patch: Partial<Pick<UserDto, 'role' | 'status'>> },
    UserDto
  >(({ id, patch }) => api.put<UserDto>(`/manage/users/${id}`, patch));

  // Client-side search + status filter
  const filtered = (data?.content ?? []).filter((u) => {
    const matchSearch =
      !search ||
      u.name.includes(search) ||
      u.email.toLowerCase().includes(search.toLowerCase());
    const matchStatus = !statusFilter || u.status === statusFilter;
    return matchSearch && matchStatus;
  });

  const handleSearch = () => {
    setSearch(searchInput);
    setPage(0);
  };

  const handleEditOpen = (user: UserDto) => {
    setEditUser(user);
    setModalOpen(true);
  };

  const handleSave = async (id: string, patch: Partial<Pick<UserDto, 'role' | 'status'>>) => {
    await updateUser({ id, patch });
    showToast('사용자 정보가 수정되었습니다.', 'success');
    refetch();
  };

  const handleToggleStatus = async (user: UserDto) => {
    const newStatus: UserDto['status'] = user.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    await updateUser({ id: user.id, patch: { status: newStatus } });
    showToast(
      newStatus === 'ACTIVE' ? `${user.name} 계정을 활성화했습니다.` : `${user.name} 계정을 비활성화했습니다.`,
      'info',
    );
    refetch();
  };

  const columns: ColumnDef<UserDto>[] = [
    {
      key: 'name',
      header: '이름',
      render: (u) => (
        <div className="flex items-center gap-2">
          <div
            className={[
              'w-7 h-7 rounded-full flex items-center justify-center text-white text-xs font-bold shrink-0',
              avatarColorFromId(u.id),
            ].join(' ')}
          >
            {u.name[0]}
          </div>
          <span className="font-medium text-gray-800">{u.name}</span>
        </div>
      ),
    },
    {
      key: 'email',
      header: '이메일',
      render: (u) => <span className="text-gray-500 text-xs">{u.email}</span>,
    },
    {
      key: 'role',
      header: '역할',
      render: (u) => (
        <Badge tone={ROLE_TONE[u.role]} size="sm">{ROLE_LABEL[u.role]}</Badge>
      ),
    },
    {
      key: 'status',
      header: '상태',
      render: (u) => (
        <Badge tone={STATUS_TONE[u.status]} size="sm">
          {u.status === 'ACTIVE' ? '활성' : '비활성'}
        </Badge>
      ),
    },
    {
      key: 'lastLoginAt',
      header: '최근 로그인',
      render: (u) => (
        <span className="text-xs text-gray-400">
          {new Date(u.lastLoginAt).toLocaleDateString('ko-KR')}
        </span>
      ),
    },
    {
      key: 'actions',
      header: '관리',
      render: (u) => (
        <div className="flex items-center gap-1">
          <Button
            variant="ghost"
            size="sm"
            onClick={(e) => { e.stopPropagation(); handleEditOpen(u); }}
          >
            수정
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={(e) => {
              e.stopPropagation();
              void handleToggleStatus(u);
            }}
            className={u.status === 'ACTIVE' ? 'text-red-500 hover:text-red-700 hover:bg-red-50' : 'text-green-600 hover:bg-green-50'}
          >
            {u.status === 'ACTIVE' ? '비활성화' : '활성화'}
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-gray-100">
          <Users size={20} className="text-gray-600" />
        </div>
        <div>
          <h1 className="text-xl font-bold text-gray-900">사용자 관리</h1>
          {data && (
            <p className="text-xs text-gray-400 mt-0.5">
              전체 {data.totalElements}명
            </p>
          )}
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-3">
        {/* Search */}
        <div className="flex items-center gap-2 border border-gray-300 rounded-lg px-3 py-2 bg-white focus-within:ring-2 focus-within:ring-primary-500">
          <Search size={14} className="text-gray-400 shrink-0" />
          <input
            type="text"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleSearch(); }}
            placeholder="이름 또는 이메일 검색"
            className="text-sm bg-transparent outline-none w-48"
          />
          <button
            onClick={handleSearch}
            className="text-xs text-primary-600 font-medium hover:underline"
          >
            검색
          </button>
        </div>

        {/* Role filter */}
        <select
          value={roleFilter}
          onChange={(e) => { setRoleFilter(e.target.value as UserDto['role'] | ''); setPage(0); }}
          className="text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
          aria-label="역할 필터"
        >
          <option value="">전체 역할</option>
          <option value="REVIEWER">검수자</option>
          <option value="WORKER">작업자</option>
          <option value="PORTAL_USER">포털</option>
        </select>

        {/* Status filter */}
        <select
          value={statusFilter}
          onChange={(e) => { setStatusFilter(e.target.value as UserDto['status'] | ''); setPage(0); }}
          className="text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
          aria-label="상태 필터"
        >
          <option value="">전체 상태</option>
          <option value="ACTIVE">활성</option>
          <option value="INACTIVE">비활성</option>
        </select>

        {(search || roleFilter || statusFilter) && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setSearch('');
              setSearchInput('');
              setRoleFilter('');
              setStatusFilter('');
              setPage(0);
            }}
          >
            필터 초기화
          </Button>
        )}
      </div>

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        {isLoading ? (
          <div className="p-4 space-y-3">
            {Array.from({ length: 8 }).map((_, i) => <Skeleton key={i} height="2.5rem" />)}
          </div>
        ) : (
          <Table
            columns={columns}
            rows={filtered}
            rowKey={(u) => u.id}
            emptyMessage="사용자가 없습니다."
          />
        )}
      </div>

      {/* Pagination */}
      {data && (
        <Pagination
          page={page}
          totalPages={data.totalPages}
          onChange={setPage}
        />
      )}

      {/* Edit modal */}
      <UserEditModal
        user={editUser}
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onSave={handleSave}
      />
    </div>
  );
}

export default UserManagement;

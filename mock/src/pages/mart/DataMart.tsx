import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { DatabaseZap, Download, Search, RotateCcw, ChevronDown, ChevronUp, Info, Plus, Pencil, Trash2 } from 'lucide-react';
import { useFetch } from '../../api/queries';
import { api } from '../../api/client';
import type { Page, MartDataset } from '../../api/types';
import { Button } from '../../components/ui/Button';
import { Pagination } from '../../components/ui/Pagination';
import { StatCard } from '../../components/ui/StatCard';
import { Skeleton } from '../../components/ui/Skeleton';
import { useToast } from '../../components/common/Toast';
import { useSessionStore } from '../../store/sessionStore';
import { MartCreateModal } from '../../components/mart/MartCreateModal';

const PAGE_SIZE = 15;

const EVENT_TYPES = [
  { value: '', label: '전체' },
  { value: '쓰러짐', label: '쓰러짐' },
  { value: '폭력', label: '폭력' },
  { value: '교통사고', label: '교통사고' },
  { value: '이상행동(유괴)', label: '이상행동(유괴)' },
  { value: '침수', label: '침수' },
  { value: '산불', label: '산불' },
];

const WEATHER_TYPES = [
  { value: '', label: '전체' },
  { value: '맑음', label: '맑음' },
  { value: '흐림', label: '흐림' },
  { value: '비', label: '비' },
  { value: '눈', label: '눈' },
  { value: '안개', label: '안개' },
];

const SEASONS = [
  { value: '', label: '전체' },
  { value: '봄', label: '봄' },
  { value: '여름', label: '여름' },
  { value: '가을', label: '가을' },
  { value: '겨울', label: '겨울' },
];

function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) {
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(0)} MB`;
}

function formatGB(bytes: number): string {
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

// Simple label distribution bar
function LabelDistribution() {
  const bars = [
    { label: 'person', pct: 42, color: 'bg-blue-500' },
    { label: 'vehicle', pct: 31, color: 'bg-green-500' },
    { label: 'animal', pct: 14, color: 'bg-yellow-400' },
    { label: 'object', pct: 13, color: 'bg-purple-500' },
  ];

  return (
    <div className="space-y-2">
      {bars.map((b) => (
        <div key={b.label} className="flex items-center gap-2 text-xs">
          <span className="w-14 text-gray-500 text-right shrink-0">{b.label}</span>
          <div className="flex-1 bg-gray-100 rounded-full h-2">
            <div
              className={['h-2 rounded-full', b.color].join(' ')}
              style={{ width: `${b.pct}%` }}
            />
          </div>
          <span className="w-8 text-gray-600 font-medium tabular-nums">{b.pct}%</span>
        </div>
      ))}
    </div>
  );
}

interface ExpandedDetailProps {
  dataset: MartDataset;
}

function ExpandedDetail({ dataset }: ExpandedDetailProps) {
  const linkedModels = dataset.linkedModels ?? [];
  return (
    <tr>
      <td colSpan={11} className="bg-gray-50 px-6 py-4 border-b border-gray-200">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div className="space-y-3">
            <h4 className="text-xs font-semibold text-gray-600 uppercase tracking-wide">라벨 분포</h4>
            <LabelDistribution />
          </div>
          <div className="space-y-2 text-sm">
            <h4 className="text-xs font-semibold text-gray-600 uppercase tracking-wide">상세 정보</h4>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-sm">
              <dt className="text-gray-500">포맷</dt>
              <dd className="font-medium text-gray-800">COCO</dd>
              <dt className="text-gray-500">버전</dt>
              <dd className="font-medium text-gray-800">{dataset.version}</dd>
              <dt className="text-gray-500">크기</dt>
              <dd className="font-medium text-gray-800">{formatBytes(dataset.sizeBytes)}</dd>
              <dt className="text-gray-500">건수</dt>
              <dd className="font-medium text-gray-800">{dataset.count.toLocaleString()}</dd>
              <dt className="text-gray-500">계절</dt>
              <dd className="font-medium text-gray-800">{dataset.season}</dd>
              <dt className="text-gray-500">활용 AI 모델</dt>
              <dd className="font-medium text-gray-800">
                {linkedModels.length > 0 ? (
                  <div className="flex flex-wrap gap-1">
                    {linkedModels.map((m) => (
                      <span
                        key={m}
                        className="inline-flex items-center text-xs bg-indigo-50 text-indigo-700 px-2 py-0.5 rounded-full"
                      >
                        {m}
                      </span>
                    ))}
                  </div>
                ) : (
                  <span className="text-gray-400">— 연동 없음</span>
                )}
              </dd>
            </dl>
            <p className="text-xs text-gray-400 mt-2">
              이 데이터셋은 COCO 포맷으로 구성됩니다.
            </p>
          </div>
        </div>
      </td>
    </tr>
  );
}

export function DataMart() {
  const { showToast } = useToast();
  const { currentRole } = useSessionStore();
  const [searchParams, setSearchParams] = useSearchParams();

  // Filters from URL
  const [q, setQ] = useState(searchParams.get('q') ?? '');
  const [qInput, setQInput] = useState(searchParams.get('q') ?? '');
  const [eventType, setEventType] = useState(searchParams.get('eventType') ?? '');
  const [weather, setWeather] = useState(searchParams.get('weather') ?? '');
  const [season, setSeason] = useState(searchParams.get('season') ?? '');
  const [page, setPage] = useState(0);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [downloadingIds, setDownloadingIds] = useState<Set<string>>(new Set());
  const [createOpen, setCreateOpen] = useState(false);

  const canManage = currentRole !== 'PORTAL_USER';

  const params: Record<string, unknown> = { page, size: PAGE_SIZE };
  if (q) params['q'] = q;
  if (eventType) params['eventType'] = eventType;
  if (weather) params['weather'] = weather;
  if (season) params['season'] = season;

  const { data, isLoading, refetch } = useFetch<Page<MartDataset>>('/mart', params);

  // All datasets for KPI stats
  const { data: allData, refetch: refetchAll } = useFetch<Page<MartDataset>>('/mart', { page: 0, size: 9999 });
  const allItems = allData?.content ?? [];
  const totalCount = allData?.totalElements ?? 0;
  const totalItemCount = allItems.reduce((s, d) => s + d.count, 0);
  const totalBytes = allItems.reduce((s, d) => s + d.sizeBytes, 0);

  const items = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  const syncSearchParams = (updates: Record<string, string>) => {
    const next = new URLSearchParams(searchParams);
    Object.entries(updates).forEach(([k, v]) => {
      if (v) {
        next.set(k, v);
      } else {
        next.delete(k);
      }
    });
    setSearchParams(next, { replace: true });
  };

  const handleSearch = () => {
    setQ(qInput);
    setPage(0);
    syncSearchParams({
      q: qInput,
      eventType,
      weather,
      season,
    });
  };

  const handleReset = () => {
    setQ('');
    setQInput('');
    setEventType('');
    setWeather('');
    setSeason('');
    setPage(0);
    setSearchParams({}, { replace: true });
  };

  const handleDownload = async (id: string) => {
    setDownloadingIds((prev) => new Set(prev).add(id));
    try {
      await api.get(`/mart/${id}/download`);
      showToast('다운로드 링크 생성됨 (데모)', 'success');
    } catch {
      showToast('다운로드 요청 실패', 'error');
    } finally {
      setDownloadingIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  };

  const toggleExpand = (id: string) => {
    setExpandedId((prev) => (prev === id ? null : id));
  };

  const handleCreated = () => {
    setPage(0);
    refetch();
    refetchAll();
  };

  const handleEdit = (name: string) => {
    showToast(`수정 기능 준비 중입니다 (${name})`, 'info');
  };

  const handleDelete = (name: string) => {
    if (window.confirm(`"${name}" 데이터셋을 삭제하시겠습니까?`)) {
      showToast('삭제 기능 준비 중입니다', 'info');
    }
  };

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-indigo-50">
          <DatabaseZap size={20} className="text-indigo-600" />
        </div>
        <h1 className="text-xl font-bold text-gray-900">데이터마트</h1>
        {canManage && (
          <div className="ml-auto">
            <Button
              variant="primary"
              size="sm"
              leftIcon={Plus}
              onClick={() => setCreateOpen(true)}
            >
              데이터셋 생성
            </Button>
          </div>
        )}
      </div>

      {/* PORTAL_USER banner */}
      {currentRole === 'PORTAL_USER' && (
        <div className="flex items-center gap-3 bg-amber-50 border border-amber-200 rounded-lg px-4 py-3">
          <Info size={16} className="text-amber-600 shrink-0" />
          <p className="text-sm text-amber-700 font-medium">
            본인 업로드 데이터만 다운로드 가능합니다.
          </p>
        </div>
      )}

      {/* KPI */}
      <div className="grid grid-cols-3 gap-4">
        <StatCard
          label="총 데이터셋"
          value={isLoading ? '—' : totalCount.toLocaleString()}
          icon={DatabaseZap}
          tone="primary"
        />
        <StatCard
          label="총 건수"
          value={isLoading ? '—' : totalItemCount.toLocaleString()}
          icon={Search}
          tone="success"
        />
        <StatCard
          label="총 용량"
          value={isLoading ? '—' : formatGB(totalBytes)}
          icon={Download}
          tone="warning"
        />
      </div>

      {/* Filter bar */}
      <div className="bg-white border border-gray-200 rounded-lg p-4 flex flex-wrap items-end gap-3">
        <div className="flex-1 min-w-44">
          <label className="block text-xs font-medium text-gray-600 mb-1">데이터셋명</label>
          <input
            type="text"
            value={qInput}
            onChange={(e) => setQInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleSearch();
            }}
            placeholder="데이터셋명 검색"
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>

        <div className="min-w-32">
          <label className="block text-xs font-medium text-gray-600 mb-1">이벤트 유형</label>
          <select
            value={eventType}
            onChange={(e) => {
              setEventType(e.target.value);
              setPage(0);
            }}
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            {EVENT_TYPES.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        <div className="min-w-28">
          <label className="block text-xs font-medium text-gray-600 mb-1">날씨</label>
          <select
            value={weather}
            onChange={(e) => {
              setWeather(e.target.value);
              setPage(0);
            }}
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            {WEATHER_TYPES.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        <div className="min-w-28">
          <label className="block text-xs font-medium text-gray-600 mb-1">계절</label>
          <select
            value={season}
            onChange={(e) => {
              setSeason(e.target.value);
              setPage(0);
            }}
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            {SEASONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        <div className="flex gap-2">
          <Button variant="primary" size="sm" leftIcon={Search} onClick={handleSearch}>
            조회
          </Button>
          <Button variant="secondary" size="sm" leftIcon={RotateCcw} onClick={handleReset}>
            초기화
          </Button>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-200">
              <th className="text-left px-4 py-3 font-medium text-gray-600 w-8" />
              <th className="text-left px-4 py-3 font-medium text-gray-600">데이터셋명</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">이벤트</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">날씨</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">계절</th>
              <th className="text-right px-4 py-3 font-medium text-gray-600">건수</th>
              <th className="text-right px-4 py-3 font-medium text-gray-600">크기</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">버전</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">활용 모델</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">생성일</th>
              <th className="text-center px-4 py-3 font-medium text-gray-600">다운로드</th>
            </tr>
          </thead>
          <tbody>
            {isLoading
              ? Array.from({ length: 8 }, (_, i) => (
                  <tr key={i} className="border-b border-gray-100">
                    {Array.from({ length: 10 }, (__, j) => (
                      <td key={j} className="px-4 py-3">
                        <Skeleton height="1rem" width={j === 1 ? '80%' : '60%'} />
                      </td>
                    ))}
                  </tr>
                ))
              : items.flatMap((item) => {
                  const isExpanded = expandedId === item.id;
                  const rows = [
                    <tr
                      key={item.id}
                      className="border-b border-gray-100 hover:bg-gray-50 cursor-pointer transition-colors"
                      onClick={() => toggleExpand(item.id)}
                    >
                      <td className="px-4 py-3 text-gray-400">
                        {isExpanded ? (
                          <ChevronUp size={14} />
                        ) : (
                          <ChevronDown size={14} />
                        )}
                      </td>
                      <td className="px-4 py-3 font-medium text-gray-800 max-w-48 truncate">
                        {item.name}
                      </td>
                      <td className="px-4 py-3 text-gray-600">{item.eventType}</td>
                      <td className="px-4 py-3 text-gray-600">{item.weather}</td>
                      <td className="px-4 py-3 text-gray-600">{item.season}</td>
                      <td className="px-4 py-3 text-right tabular-nums text-gray-700">
                        {item.count.toLocaleString()}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-gray-600">
                        {formatBytes(item.sizeBytes)}
                      </td>
                      <td className="px-4 py-3 text-gray-500 font-mono text-xs">
                        {item.version}
                      </td>
                      <td className="px-4 py-3 text-xs">
                        {(() => {
                          const models = item.linkedModels ?? [];
                          if (models.length === 0) {
                            return <span className="text-gray-400">—</span>;
                          }
                          const visible = models.slice(0, 2);
                          const overflow = models.length - visible.length;
                          return (
                            <div className="flex flex-wrap gap-1">
                              {visible.map((m) => (
                                <span
                                  key={m}
                                  className="inline-flex items-center bg-indigo-50 text-indigo-700 px-1.5 py-0.5 rounded"
                                >
                                  {m}
                                </span>
                              ))}
                              {overflow > 0 && (
                                <span className="inline-flex items-center bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded">
                                  +{overflow}
                                </span>
                              )}
                            </div>
                          );
                        })()}
                      </td>
                      <td className="px-4 py-3 text-gray-500 text-xs">
                        {new Date(item.createdAt).toLocaleDateString('ko-KR')}
                      </td>
                      <td className="px-4 py-3 text-center" onClick={(e) => e.stopPropagation()}>
                        <div className="inline-flex items-center gap-1">
                          <button
                            type="button"
                            onClick={() => { void handleDownload(item.id); }}
                            disabled={downloadingIds.has(item.id)}
                            className="inline-flex items-center justify-center w-8 h-8 rounded-lg text-gray-500 hover:bg-blue-50 hover:text-blue-600 transition-colors disabled:opacity-40"
                            aria-label={`${item.name} 다운로드`}
                            title="다운로드"
                          >
                            <Download size={15} />
                          </button>
                          {canManage && (
                            <>
                              <button
                                type="button"
                                onClick={() => handleEdit(item.name)}
                                className="inline-flex items-center justify-center w-8 h-8 rounded-lg text-gray-500 hover:bg-amber-50 hover:text-amber-600 transition-colors"
                                aria-label={`${item.name} 수정`}
                                title="수정"
                              >
                                <Pencil size={14} />
                              </button>
                              <button
                                type="button"
                                onClick={() => handleDelete(item.name)}
                                className="inline-flex items-center justify-center w-8 h-8 rounded-lg text-gray-500 hover:bg-red-50 hover:text-red-600 transition-colors"
                                aria-label={`${item.name} 삭제`}
                                title="삭제"
                              >
                                <Trash2 size={14} />
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>,
                  ];

                  if (isExpanded) {
                    rows.push(<ExpandedDetail key={`${item.id}-detail`} dataset={item} />);
                  }

                  return rows;
                })}

            {!isLoading && items.length === 0 && (
              <tr>
                <td colSpan={11} className="text-center py-16 text-gray-400 text-sm">
                  <div className="flex flex-col items-center gap-3">
                    <span>조건에 맞는 데이터셋이 없습니다.</span>
                    {canManage && (
                      <Button
                        variant="primary"
                        size="sm"
                        leftIcon={Plus}
                        onClick={() => setCreateOpen(true)}
                      >
                        지금 데이터셋 생성
                      </Button>
                    )}
                  </div>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <Pagination page={page} totalPages={totalPages} onChange={setPage} />
      )}

      <MartCreateModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onSuccess={handleCreated}
      />
    </div>
  );
}

export default DataMart;

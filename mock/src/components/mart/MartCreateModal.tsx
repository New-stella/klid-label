import { useEffect, useRef, useState } from 'react';
import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';
import { useFetch } from '../../api/queries';
import { api } from '../../api/client';
import { useToast } from '../common/Toast';
import type { Page, VideoDto, MartDataset } from '../../api/types';

type Format = 'COCO' | 'YOLO' | 'PASCAL_VOC';

interface MartCreateModalProps {
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

const FORMATS: { value: Format; label: string }[] = [
  { value: 'COCO', label: 'COCO' },
  { value: 'YOLO', label: 'YOLO' },
  { value: 'PASCAL_VOC', label: 'Pascal VOC' },
];

const EVENT_TYPES = ['쓰러짐', '폭력', '교통사고', '이상행동(유괴)', '침수', '산불'];
const WEATHERS = ['맑음', '흐림', '비', '눈', '안개', '강풍'];
const SEASONS = ['봄', '여름', '가을', '겨울'];
// SFR-13 — 연동 가능 자체개발 AI 모델
const AI_MODELS = ['침수탐지', '이상상황탐지', '쓰러짐탐지', '폭력감지', '교통사고감지', '산불감지'];

export function MartCreateModal({ open, onClose, onSuccess }: MartCreateModalProps) {
  const { showToast } = useToast();
  const nameRef = useRef<HTMLInputElement>(null);

  const [name, setName] = useState('');
  const [version, setVersion] = useState('1.0.0');
  const [description, setDescription] = useState('');
  const [format, setFormat] = useState<Format>('COCO');
  const [eventType, setEventType] = useState(EVENT_TYPES[0] ?? '');
  const [weather, setWeather] = useState(WEATHERS[0] ?? '');
  const [season, setSeason] = useState('');
  const [videoIds, setVideoIds] = useState<string[]>([]);
  const [videoSearch, setVideoSearch] = useState('');
  const [linkedModels, setLinkedModels] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const { data: videosPage } = useFetch<Page<VideoDto>>('/videos', { page: 0, size: 10 });
  const allVideos = videosPage?.content ?? [];
  const filteredVideos = videoSearch
    ? allVideos.filter((v) => {
        const q = videoSearch.toLowerCase();
        return (
          v.cctvName.toLowerCase().includes(q) ||
          v.eventType.toLowerCase().includes(q)
        );
      })
    : allVideos;

  // Reset form whenever modal opens
  useEffect(() => {
    if (open) {
      setName('');
      setVersion('1.0.0');
      setDescription('');
      setFormat('COCO');
      setEventType(EVENT_TYPES[0] ?? '');
      setWeather(WEATHERS[0] ?? '');
      setSeason('');
      setVideoIds([]);
      setVideoSearch('');
      setLinkedModels([]);
      setSubmitting(false);
    }
  }, [open]);

  const toggleLinkedModel = (model: string) => {
    setLinkedModels((prev) =>
      prev.includes(model) ? prev.filter((x) => x !== model) : [...prev, model],
    );
  };

  const toggleVideo = (id: string) => {
    setVideoIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  };

  const handleSubmit = async () => {
    const trimmedName = name.trim();
    if (!trimmedName) {
      showToast('데이터셋 이름을 입력하세요', 'error');
      nameRef.current?.focus();
      return;
    }
    if (!season) {
      showToast('계절을 선택하세요', 'error');
      return;
    }
    if (videoIds.length === 0) {
      showToast('영상을 1건 이상 선택하세요', 'error');
      return;
    }

    setSubmitting(true);
    try {
      await api.post<MartDataset>('/mart', {
        name: trimmedName,
        version: version.trim() || '1.0.0',
        description: description.trim() || undefined,
        format,
        eventType,
        weather,
        season,
        videoIds,
        linkedModels,
      });
      showToast('데이터셋이 생성되었습니다', 'success');
      onSuccess();
      onClose();
    } catch {
      showToast('데이터셋 생성에 실패했습니다', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={submitting ? () => undefined : onClose}
      title="데이터셋 생성"
      size="xl"
      footer={
        <>
          <Button variant="secondary" size="sm" onClick={onClose} disabled={submitting}>
            취소
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={() => { void handleSubmit(); }}
            loading={submitting}
          >
            생성
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        {/* 이름 */}
        <div className="space-y-1">
          <label htmlFor="mart-name" className="text-sm font-medium text-gray-700">
            데이터셋 이름 <span className="text-red-500">*</span>
          </label>
          <input
            id="mart-name"
            ref={nameRef}
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="예: 침수_v1"
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>

        {/* 버전 */}
        <div className="space-y-1">
          <label htmlFor="mart-version" className="text-sm font-medium text-gray-700">
            버전 <span className="text-red-500">*</span>
          </label>
          <input
            id="mart-version"
            type="text"
            value={version}
            onChange={(e) => setVersion(e.target.value)}
            placeholder="1.0.0"
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>

        {/* 설명 */}
        <div className="space-y-1">
          <label htmlFor="mart-description" className="text-sm font-medium text-gray-700">
            설명
          </label>
          <textarea
            id="mart-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            placeholder="데이터셋 설명을 입력하세요 (선택)"
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500 resize-none"
          />
        </div>

        {/* 포맷 */}
        <div className="space-y-1.5">
          <p className="text-sm font-medium text-gray-700">포맷</p>
          <div className="flex gap-4 flex-wrap">
            {FORMATS.map((f) => (
              <label key={f.value} className="flex items-center gap-1.5 cursor-pointer">
                <input
                  type="radio"
                  name="mart-format"
                  value={f.value}
                  checked={format === f.value}
                  onChange={() => setFormat(f.value)}
                  className="accent-primary-600"
                />
                <span className="text-sm text-gray-700">{f.label}</span>
              </label>
            ))}
          </div>
        </div>

        {/* 메타데이터 그리드 */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div className="space-y-1">
            <label htmlFor="mart-event" className="text-sm font-medium text-gray-700">
              이벤트 <span className="text-red-500">*</span>
            </label>
            <select
              id="mart-event"
              value={eventType}
              onChange={(e) => setEventType(e.target.value)}
              className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              {EVENT_TYPES.map((ev) => (
                <option key={ev} value={ev}>
                  {ev}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-1">
            <label htmlFor="mart-weather" className="text-sm font-medium text-gray-700">
              날씨 <span className="text-red-500">*</span>
            </label>
            <select
              id="mart-weather"
              value={weather}
              onChange={(e) => setWeather(e.target.value)}
              className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              {WEATHERS.map((w) => (
                <option key={w} value={w}>
                  {w}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-1">
            <label htmlFor="mart-season" className="text-sm font-medium text-gray-700">
              계절 <span className="text-red-500">*</span>
            </label>
            <select
              id="mart-season"
              value={season}
              onChange={(e) => setSeason(e.target.value)}
              className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              <option value="">선택</option>
              {SEASONS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* 활용 AI 모델 (SFR-13) */}
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <p className="text-sm font-medium text-gray-700">활용 AI 모델</p>
            <span className="text-xs text-gray-500">선택: {linkedModels.length}개 (선택 사항)</span>
          </div>
          <p className="text-xs text-gray-500">
            데이터마트와 연동될 자체개발 AI 모델을 선택하세요. (선택 사항 — 연동 없는 데이터셋도 가능)
          </p>
          <div className="flex flex-wrap gap-2">
            {AI_MODELS.map((m) => {
              const checked = linkedModels.includes(m);
              return (
                <label
                  key={m}
                  className={[
                    'flex items-center gap-1.5 px-3 py-1.5 rounded-lg border cursor-pointer text-sm transition-colors',
                    checked
                      ? 'bg-indigo-50 border-indigo-400 text-indigo-700'
                      : 'bg-white border-gray-300 text-gray-700 hover:bg-gray-50',
                  ].join(' ')}
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => toggleLinkedModel(m)}
                    className="accent-indigo-600"
                  />
                  <span>{m}</span>
                </label>
              );
            })}
          </div>
        </div>

        {/* 포함 영상 */}
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <p className="text-sm font-medium text-gray-700">
              포함 영상 <span className="text-red-500">*</span>
            </p>
            <span className="text-xs text-gray-500">선택: {videoIds.length}건</span>
          </div>
          <input
            type="text"
            value={videoSearch}
            onChange={(e) => setVideoSearch(e.target.value)}
            placeholder="CCTV명/이벤트명 검색"
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
          <div className="border border-gray-200 rounded-lg max-h-56 overflow-y-auto divide-y divide-gray-100">
            {filteredVideos.length === 0 ? (
              <div className="text-center text-xs text-gray-400 py-8">
                조건에 맞는 영상이 없습니다.
              </div>
            ) : (
              filteredVideos.map((v) => {
                const checked = videoIds.includes(v.id);
                return (
                  <label
                    key={v.id}
                    className={[
                      'flex items-center gap-3 px-3 py-2.5 cursor-pointer hover:bg-gray-50 transition-colors',
                      checked ? 'bg-primary-50/40' : '',
                    ].join(' ')}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleVideo(v.id)}
                      className="accent-primary-600"
                    />
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-gray-800 truncate">
                        {v.cctvName}
                      </div>
                      <div className="text-xs text-gray-500">{v.eventType}</div>
                    </div>
                  </label>
                );
              })
            )}
          </div>
        </div>
      </div>
    </Modal>
  );
}

export default MartCreateModal;

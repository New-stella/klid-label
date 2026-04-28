import { useToast } from '../common/Toast';
import { useLabelStore } from '../../store/labelStore';
import { LABEL_DEFS } from '../../utils/labelColors';

interface AttributePanelProps {
  readOnly?: boolean;
}

export function AttributePanel({ readOnly = false }: AttributePanelProps) {
  const frames = useLabelStore((s) => s.frames);
  const currentFrame = useLabelStore((s) => s.currentFrame);
  const selectedId = useLabelStore((s) => s.selectedId);
  const updateObject = useLabelStore((s) => s.updateObject);
  const { showToast } = useToast();

  const objects = frames[currentFrame] ?? [];
  const obj = selectedId ? objects.find((o) => o.id === selectedId) : null;

  if (!obj) {
    return (
      <div className="p-4 flex flex-col items-center justify-center h-full text-center text-gray-400">
        <p className="text-sm">객체를 선택하세요</p>
        <p className="text-xs mt-1 text-gray-500">캔버스 또는 목록에서 객체를 클릭</p>
      </div>
    );
  }

  const handleLabelChange = (code: string) => {
    updateObject(obj.id, { labelCode: code });
  };

  const handleConfidenceChange = (val: number) => {
    updateObject(obj.id, { confidence: val / 100 });
  };

  const handleBboxChange = (field: 'x' | 'y' | 'w' | 'h', val: number) => {
    if (!obj.bbox) return;
    updateObject(obj.id, { bbox: { ...obj.bbox, [field]: val } });
  };

  const handleTrackStart = () => {
    showToast('트랙 기능은 곧 지원 예정입니다', 'info');
  };

  const handleAttrChange = (key: string, val: string) => {
    updateObject(obj.id, {
      attributes: { ...(obj.attributes ?? {}), [key]: val },
    });
  };

  const confidencePercent = Math.round(obj.confidence * 100);

  const roInputCls = 'w-full bg-gray-700/50 border border-gray-600/50 rounded px-2 py-1.5 text-sm text-gray-300 cursor-not-allowed opacity-70';

  return (
    <div className="overflow-y-auto flex-1 p-3 space-y-3 text-sm text-gray-200">
      {readOnly && (
        <div className="text-xs text-yellow-400 bg-yellow-900/30 rounded px-2 py-1 border border-yellow-700/50">
          읽기 전용 — 편집 불가
        </div>
      )}
      {/* Label */}
      <div>
        <label className="block text-xs text-gray-400 mb-1">라벨</label>
        <select
          value={obj.labelCode}
          onChange={(e) => handleLabelChange(e.target.value)}
          disabled={readOnly}
          className={readOnly ? roInputCls : 'w-full bg-gray-700 border border-gray-600 rounded px-2 py-1.5 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500'}
        >
          {Object.entries(LABEL_DEFS).map(([code, def]) => (
            <option key={code} value={code}>
              {def.name}
            </option>
          ))}
        </select>
      </div>

      {/* Confidence */}
      <div>
        <div className="flex items-center justify-between mb-1">
          <label className="text-xs text-gray-400">신뢰도</label>
          <input
            type="number"
            min={0}
            max={100}
            value={confidencePercent}
            onChange={(e) => handleConfidenceChange(Number(e.target.value))}
            disabled={readOnly}
            className={readOnly ? 'w-16 bg-gray-700/50 border border-gray-600/50 rounded px-2 py-0.5 text-xs text-gray-300 text-right cursor-not-allowed opacity-70' : 'w-16 bg-gray-700 border border-gray-600 rounded px-2 py-0.5 text-xs text-white text-right focus:outline-none focus:ring-1 focus:ring-blue-500'}
          />
        </div>
        <input
          type="range"
          min={0}
          max={100}
          value={confidencePercent}
          onChange={(e) => handleConfidenceChange(Number(e.target.value))}
          disabled={readOnly}
          className="w-full accent-blue-500 h-1.5 disabled:opacity-50"
        />
      </div>

      {/* Created by badge */}
      <div className="flex items-center gap-2">
        <span className="text-xs text-gray-400">생성방식</span>
        <span
          className={[
            'text-xs px-2 py-0.5 rounded-full',
            obj.createdBy === 'auto'
              ? 'bg-orange-900/60 text-orange-300'
              : 'bg-blue-900/60 text-blue-300',
          ].join(' ')}
        >
          {obj.createdBy === 'auto' ? '🤖 자동' : '✏️ 수동'}
        </span>
      </div>

      {/* BBox coordinates */}
      {obj.type === 'bbox' && obj.bbox && (
        <div>
          <label className="block text-xs text-gray-400 mb-1">좌표 (이미지 기준 640×360)</label>
          <div className="grid grid-cols-2 gap-1.5">
            {(['x', 'y', 'w', 'h'] as const).map((field) => (
              <div key={field}>
                <label className="text-xs text-gray-500 mb-0.5 block">{field.toUpperCase()}</label>
                <input
                  type="number"
                  value={obj.bbox![field]}
                  onChange={(e) => handleBboxChange(field, Number(e.target.value))}
                  disabled={readOnly}
                  className={readOnly ? 'w-full bg-gray-700/50 border border-gray-600/50 rounded px-2 py-1 text-xs text-gray-300 cursor-not-allowed opacity-70' : 'w-full bg-gray-700 border border-gray-600 rounded px-2 py-1 text-xs text-white focus:outline-none focus:ring-1 focus:ring-blue-500'}
                />
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Polygon info */}
      {obj.type === 'polygon' && obj.points && (
        <div>
          <label className="block text-xs text-gray-400 mb-1">폴리곤 좌표</label>
          <p className="text-xs text-gray-500">포인트 수: {obj.points.length / 2}개</p>
        </div>
      )}

      {/* Track ID */}
      {obj.trackId && (
        <div>
          <label className="block text-xs text-gray-400 mb-1">트랙 ID</label>
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-300 bg-gray-700 rounded px-2 py-1 flex-1">{obj.trackId}</span>
            <button
              onClick={handleTrackStart}
              className="text-xs bg-gray-600 hover:bg-gray-500 text-gray-300 rounded px-2 py-1 transition-colors"
            >
              트랙 시작
            </button>
          </div>
        </div>
      )}

      {/* Attributes */}
      {obj.attributes && Object.keys(obj.attributes).length > 0 && (
        <div>
          <label className="block text-xs text-gray-400 mb-1">속성</label>
          <div className="space-y-1">
            {Object.entries(obj.attributes).map(([key, val]) => (
              <div key={key} className="flex items-center gap-2">
                <span className="text-xs text-gray-400 w-16 shrink-0">{key}</span>
                <select
                  value={String(val)}
                  onChange={(e) => handleAttrChange(key, e.target.value)}
                  disabled={readOnly}
                  className={readOnly ? 'flex-1 bg-gray-700/50 border border-gray-600/50 rounded px-2 py-1 text-xs text-gray-300 cursor-not-allowed opacity-70' : 'flex-1 bg-gray-700 border border-gray-600 rounded px-2 py-1 text-xs text-white focus:outline-none focus:ring-1 focus:ring-blue-500'}
                >
                  {key === 'occluded' || key === 'truncated' ? (
                    <>
                      <option value="0">아니오</option>
                      <option value="1">예</option>
                    </>
                  ) : (
                    <option value={String(val)}>{String(val)}</option>
                  )}
                </select>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

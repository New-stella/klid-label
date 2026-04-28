import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';
import type { PresetDto } from '../../api/types';

interface LabelEntry {
  code: string;
  name: string;
}

interface PresetFormData {
  name: string;
  description: string;
  labelCodes: string[];
}

interface PresetFormModalProps {
  preset: PresetDto | null;
  open: boolean;
  onClose: () => void;
  onSave: (data: PresetFormData) => Promise<void>;
}

const PRESET_LABEL_SUGGESTIONS: LabelEntry[] = [
  { code: 'PERSON', name: '사람' },
  { code: 'VEHICLE', name: '차량' },
  { code: 'BICYCLE', name: '자전거' },
  { code: 'MOTORCYCLE', name: '오토바이' },
  { code: 'TRUCK', name: '트럭' },
  { code: 'BUS', name: '버스' },
  { code: 'FIRE', name: '화재' },
  { code: 'SMOKE', name: '연기' },
  { code: 'WATER', name: '침수물' },
  { code: 'FALLEN', name: '쓰러진 사람' },
];

export function PresetFormModal({ preset, open, onClose, onSave }: PresetFormModalProps) {
  const isEdit = !!preset;

  const [name, setName] = useState(preset?.name ?? '');
  const [description, setDescription] = useState(preset?.description ?? '');
  const [labelCodes, setLabelCodes] = useState<string[]>(preset?.labelCodes ?? []);
  const [newCode, setNewCode] = useState('');
  const [saving, setSaving] = useState(false);
  const [nameError, setNameError] = useState('');

  // Reset when modal opens
  const handleFocus = () => {
    if (!name && preset) {
      setName(preset.name);
      setDescription(preset.description);
      setLabelCodes(preset.labelCodes);
    }
  };

  const addLabel = (code: string) => {
    const upper = code.trim().toUpperCase();
    if (!upper || labelCodes.includes(upper)) return;
    setLabelCodes((prev) => [...prev, upper]);
    setNewCode('');
  };

  const removeLabel = (code: string) => {
    setLabelCodes((prev) => prev.filter((c) => c !== code));
  };

  const handleSave = async () => {
    if (!name.trim()) {
      setNameError('프리셋 이름을 입력하세요.');
      return;
    }
    if (labelCodes.length === 0) {
      setNameError('라벨 항목을 하나 이상 추가하세요.');
      return;
    }
    setNameError('');
    setSaving(true);
    try {
      await onSave({ name: name.trim(), description: description.trim(), labelCodes });
      onClose();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isEdit ? '프리셋 수정' : '새 프리셋 만들기'}
      size="lg"
      footer={
        <>
          <Button variant="secondary" size="md" onClick={onClose} disabled={saving}>
            취소
          </Button>
          <Button
            variant="primary"
            size="md"
            loading={saving}
            onClick={() => { void handleSave(); }}
          >
            {isEdit ? '저장' : '만들기'}
          </Button>
        </>
      }
    >
      <div className="space-y-5" onFocus={handleFocus}>
        {/* Name */}
        <div className="space-y-1.5">
          <label className="block text-sm font-medium text-gray-700" htmlFor="preset-name">
            프리셋 이름 <span className="text-red-500">*</span>
          </label>
          <input
            id="preset-name"
            type="text"
            value={name}
            onChange={(e) => { setName(e.target.value); setNameError(''); }}
            placeholder="예: 교통사고 표준 프리셋"
            className={[
              'w-full text-sm border rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500',
              nameError ? 'border-red-400' : 'border-gray-300',
            ].join(' ')}
          />
          {nameError && <p className="text-xs text-red-500">{nameError}</p>}
        </div>

        {/* Description */}
        <div className="space-y-1.5">
          <label className="block text-sm font-medium text-gray-700" htmlFor="preset-desc">
            설명
          </label>
          <textarea
            id="preset-desc"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="프리셋에 대한 설명을 입력하세요."
            rows={2}
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 resize-none focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>

        {/* Label codes */}
        <div className="space-y-3">
          <label className="block text-sm font-medium text-gray-700">
            라벨 항목 <span className="text-red-500">*</span>
            <span className="text-gray-400 font-normal ml-1">({labelCodes.length}개)</span>
          </label>

          {/* Existing labels */}
          {labelCodes.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {labelCodes.map((code) => (
                <span
                  key={code}
                  className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-primary-100 text-primary-700 text-xs font-medium"
                >
                  {code}
                  <button
                    type="button"
                    onClick={() => removeLabel(code)}
                    className="ml-0.5 hover:text-red-500 transition-colors"
                    aria-label={`${code} 삭제`}
                  >
                    <Trash2 size={11} />
                  </button>
                </span>
              ))}
            </div>
          )}

          {/* Add new label */}
          <div className="flex items-center gap-2">
            <input
              type="text"
              value={newCode}
              onChange={(e) => setNewCode(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addLabel(newCode); } }}
              placeholder="라벨 코드 입력 (Enter로 추가)"
              className="flex-1 text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
            />
            <Button
              variant="secondary"
              size="sm"
              leftIcon={Plus}
              onClick={() => addLabel(newCode)}
              disabled={!newCode.trim()}
            >
              추가
            </Button>
          </div>

          {/* Suggestions */}
          <div className="space-y-1">
            <p className="text-xs text-gray-400">빠른 추가:</p>
            <div className="flex flex-wrap gap-1.5">
              {PRESET_LABEL_SUGGESTIONS.filter((s) => !labelCodes.includes(s.code)).map((s) => (
                <button
                  key={s.code}
                  type="button"
                  onClick={() => addLabel(s.code)}
                  className="text-xs px-2 py-0.5 rounded border border-gray-200 bg-gray-50 text-gray-600 hover:border-primary-300 hover:bg-primary-50 hover:text-primary-700 transition-colors"
                >
                  + {s.code} <span className="text-gray-400">({s.name})</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>
    </Modal>
  );
}

export default PresetFormModal;

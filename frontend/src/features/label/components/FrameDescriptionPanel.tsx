// blocker#2 Phase 3 — 프레임 설명(NIA image.description) 입력 패널.
//
// 라벨링 RightPanel 에 삽입되는 접이식 설명 편집 패널.
// - useFrameDescription(srcSn) 로 기존 설명 로드 → textarea 바인딩
// - 수정 후 저장 버튼으로 useUpdateFrameDescription(srcSn) 호출
// - dirty 체크: 원본과 다를 때만 저장 활성
//
// 보안(저장형 XSS 방어): 설명은 textarea value 로만 바인딩 — React 기본 escape.
//   dangerouslySetInnerHTML 미사용. maxLength 1000(BE @Size 정합).
// a11y: <label htmlFor> ↔ textarea id 연결.

import { useEffect, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Textarea } from '@/components/common/Textarea';

import { useFrameDescription, useUpdateFrameDescription } from '../hooks/useFrameDescription';

import { MetaCharCount, MetaSection } from './MetaSection';

export interface FrameDescriptionPanelProps {
  srcSn: number | undefined;
}

const TEXTAREA_ID = 'frame-description-input';
const MAX_LEN = 1000;

export function FrameDescriptionPanel({ srcSn }: FrameDescriptionPanelProps) {
  const { data, isLoading } = useFrameDescription(srcSn);
  const update = useUpdateFrameDescription(srcSn);

  const [text, setText] = useState('');

  // 프레임 전환(data 변경) 시 로컬 입력 상태 동기화.
  useEffect(() => {
    setText(data?.description ?? '');
  }, [data?.description, srcSn]);

  const original = data?.description ?? '';
  const dirty = text !== original;
  const canSave = srcSn !== undefined && dirty && !update.isPending;

  const handleSave = () => {
    if (!canSave) return;
    // 빈 문자열은 설명 삭제(null) 로 전송 — BE 가 blank→null 정규화.
    const payload = text.trim() === '' ? null : text;
    update.mutate(payload);
  };

  return (
    <MetaSection title="프레임 설명">
      <label htmlFor={TEXTAREA_ID} className="sr-only">
        프레임 설명 입력
      </label>
      <Textarea
        id={TEXTAREA_ID}
        value={text}
        onChange={(e) => setText(e.target.value)}
        disabled={srcSn === undefined || isLoading || update.isPending}
        maxLength={MAX_LEN}
        aria-label="프레임 설명 입력"
        placeholder="이 프레임의 상황을 자연어로 설명하세요"
        className="min-h-[127px] resize-y text-body-md"
      />
      <MetaCharCount current={text.length} max={MAX_LEN} />

      {update.isError && (
        <p className="text-caption text-danger" role="alert">
          설명 저장에 실패했습니다. 다시 시도해 주세요.
        </p>
      )}

      <Button
        size="sm"
        fullWidth
        onClick={handleSave}
        disabled={!canSave}
        loading={update.isPending}
      >
        저장
      </Button>
    </MetaSection>
  );
}

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { RadioGroup } from '@/components/common/RadioGroup';
import { FrameGrid12, type FrameGrid12Frame } from '@/features/deident/components/FrameGrid12';
import { useUiStore } from '@/stores/useUiStore';
import type { Video } from '@/features/video/types';

import { useRequestBackgroundGenerate } from '../hooks/useGenerate';
import { BackgroundGenType, type BackgroundGenType as GenType } from '../types';

export interface BackgroundGenerateModalProps {
  open: boolean;
  video: Video | null;
  /** 12 프레임 페어 — 외부에서 fetch 후 주입 (Phase 7 FrameGrid12 재사용) */
  frames: FrameGrid12Frame[];
  onClose(): void;
}

/**
 * SCR-GEN-001 BackgroundGenerateModal — 영상 행 액션 ✨에서 진입.
 *
 * UI/UX §4-13:
 * - 영상 정보 readonly + FrameGrid12 단일 라디오 + 산불/침수 라디오
 * - 둘 다 선택 시 [요청 보내기] 활성
 * - POST 성공 시 navigate `/generate/result/:jobId`
 *
 * 보안:
 * - genType은 RadioGroup으로 WILDFIRE/FLOOD allowlist 강제 (BE에서도 검증).
 * - srcSn/videoId는 number 타입 — IDOR 방어는 BE 책임.
 * - 영상 정보는 readonly로 노출 (사용자 입력 → BE 전달 경로 없음).
 */
export function BackgroundGenerateModal({
  open,
  video,
  frames,
  onClose,
}: BackgroundGenerateModalProps) {
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);
  const [selectedSrcSn, setSelectedSrcSn] = useState<number | null>(null);
  const [genType, setGenType] = useState<GenType | null>(null);

  // 모달 열릴 때마다 선택 상태 초기화
  useEffect(() => {
    if (!open) {
      setSelectedSrcSn(null);
      setGenType(null);
    }
  }, [open]);

  const { mutate, isPending } = useRequestBackgroundGenerate({
    onSuccess: ({ jobId }) => {
      pushToast({ variant: 'success', message: '배경영상 요청 완료' });
      onClose();
      navigate(`/generate/result/${jobId}`);
    },
    onError: () => {
      pushToast({ variant: 'error', message: '배경영상 요청 실패' });
    },
  });

  const canSubmit = useMemo(
    () => selectedSrcSn !== null && genType !== null && !isPending,
    [selectedSrcSn, genType, isPending],
  );

  const handleSubmit = () => {
    if (!video || selectedSrcSn === null || genType === null) return;
    mutate({ videoId: video.id, srcSn: selectedSrcSn, genType });
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="배경영상 요청"
      description="외부 생성형 AI 시스템에 배경영상 생성을 요청합니다."
      size="lg"
    >
      {video && (
        <div className="flex flex-col gap-4">
          <div
            data-testid="bg-modal-video-info"
            className="grid grid-cols-2 gap-3 rounded border border-border bg-bgLight p-3 sm:grid-cols-4"
          >
            <Field label="CCTV명">{video.cctvName}</Field>
            <Field label="Clip ID">{video.vmsClipId}</Field>
            <Field label="이벤트">{video.eventName}</Field>
            <Field label="지자체">{video.localGov}</Field>
          </div>

          <section aria-label="프레임 단일 선택">
            <h3 className="mb-2 text-section-title text-primary">기준 프레임 선택</h3>
            <FrameGrid12
              frames={frames}
              selectedSrcSn={selectedSrcSn}
              onSelect={setSelectedSrcSn}
              pairLabel={{ top: '원본', bottom: '비식별' }}
            />
          </section>

          <section aria-label="배경영상 유형 선택">
            <h3 className="mb-2 text-section-title text-primary">배경영상 유형</h3>
            <RadioGroup
              name="bg-gen-type"
              label="유형"
              value={genType ?? undefined}
              onChange={(v) => setGenType(v as GenType)}
              options={[
                { value: BackgroundGenType.WILDFIRE, label: '🔥 산불' },
                { value: BackgroundGenType.FLOOD, label: '🌊 침수' },
              ]}
            />
          </section>

          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={onClose} disabled={isPending}>
              취소
            </Button>
            <Button
              data-testid="bg-modal-submit"
              variant="primary"
              onClick={handleSubmit}
              disabled={!canSubmit}
              loading={isPending}
            >
              요청 보내기
            </Button>
          </div>
        </div>
      )}
    </Modal>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-sub text-neutral">{label}</dt>
      <dd className="text-body text-primary">{children}</dd>
    </div>
  );
}

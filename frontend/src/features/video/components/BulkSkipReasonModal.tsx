// 시계열 묶음 **일괄** 건너뛰기 사유 입력 모달. [@design API-212] [@design SCREEN-008]
//
// ★ 사유는 요청당 하나다 — 대상 전건에 같은 값으로 기록된다. 영상마다 다른 사유를 받게 만들면
//   일괄로 처리할 이유가 사라진다(단건 축은 `BatchStageSkipModal` 이 계속 담당한다).
//
// ★ 빈 값·공백만은 **보내기 전에** 막는다. 서버는 그런 요청을 일부만 실패시키는 것이 아니라
//   요청 전체를 400 으로 거부하므로, 사용자가 대상 목록을 다 고르고 사유를 다 쓴 뒤에야
//   전부 헛일이 되는 동선이 생긴다.
//   ⚠ 판정은 `trim().length > 0` 까지만 한다 — 보이지 않는 문자 전수 판정을 화면이 흉내내면
//   서버(단일 진실원)와 두 판정이 갈려, 서버는 받는 값을 화면이 막거나 그 반대가 된다.

import { useEffect, useState } from 'react';
import { SkipForward } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { Field, FieldDescription, FieldLabel } from '@/components/common/Field';
import { Modal } from '@/components/common/Modal';
import { Textarea } from '@/components/common/Textarea';

import { SKIP_REASON_MAX } from '../types';

/** 대상 미리보기 칩 개수 — 그 뒤는 "외 N건"으로 접는다(AssignModal 일괄 배정과 같은 관례). */
const PREVIEW_COUNT = 3;

export interface BulkSkipReasonModalProps {
  open: boolean;
  /** 대상 영상 식별자 목록(선택 순서 그대로). */
  rawSns: number[];
  /** 목록 행에서 만든 rawSn → CCTV명. 없는 영상은 식별자로 대신 표기한다. */
  videoNameById: Record<number, string>;
  loading?: boolean;
  onClose(): void;
  onConfirm(reason: string): void;
}

export function BulkSkipReasonModal({
  open,
  rawSns,
  videoNameById,
  loading,
  onClose,
  onConfirm,
}: BulkSkipReasonModalProps) {
  const [reason, setReason] = useState('');

  // 닫혀도 마운트된 채 남으므로 다시 열릴 때 입력을 비운다(앞 요청의 사유가 남지 않게).
  useEffect(() => {
    if (open) setReason('');
  }, [open]);

  const count = rawSns.length;
  const preview = rawSns.slice(0, PREVIEW_COUNT);
  const remaining = Math.max(0, count - PREVIEW_COUNT);
  const canSubmit = reason.trim().length > 0 && !loading;

  const handleClose = () => {
    if (loading) return;
    onClose();
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title={`선택한 ${count}건의 시계열을 건너뛸까요?`}
      size="md"
      footer={
        <>
          <Button variant="secondary" size="sm" onClick={handleClose} disabled={loading}>
            취소
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={() => onConfirm(reason.trim())}
            disabled={!canSubmit}
            loading={loading}
          >
            <SkipForward size={14} aria-hidden />
            {count}건 건너뛰기
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4" data-testid="bulk-skip-reason-modal">
        {/* 대상 영상 — 무엇에 적용되는지 먼저 보인다(일괄 배정 모달과 같은 표현). */}
        <div className="rounded-lg bg-gray-50 px-4 py-3 space-y-2">
          <p className="text-label font-semibold text-gray-600 uppercase tracking-wide">
            대상 영상 {count}건
          </p>
          <div className="flex flex-wrap gap-1.5">
            {preview.map((rawSn) => (
              <span
                key={rawSn}
                className="inline-flex items-center rounded-full bg-info/10 px-2 py-0.5 text-label font-medium text-info-700"
              >
                {videoNameById[rawSn] ?? `영상 ${rawSn}`}
              </span>
            ))}
            {remaining > 0 && (
              <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-600">
                외 {remaining}건
              </span>
            )}
          </div>
          <p className="text-caption text-gray-600">
            건너뛴 영상의 서술은 사람이 직접 쓴 전문으로 대신합니다. 나중에 해제할 수 있습니다.
          </p>
        </div>

        <Field>
          {/*
            글자 수는 라벨 **밖**의 형제로 둔다 — `<label>` 안에 넣으면 필드의 접근성 이름이
            "건너뛰는 사유 (필수) 31 / 500" 이 되어 타이핑할 때마다 이름이 바뀐다.
          */}
          <div className="flex items-baseline justify-between gap-2">
            <FieldLabel required>건너뛰는 사유</FieldLabel>
            <span className="text-caption tabular-nums text-gray-500" data-testid="bulk-skip-reason-count">
              {reason.length} / {SKIP_REASON_MAX}
            </span>
          </div>
          <Textarea
            className="min-h-[120px]"
            aria-required="true"
            maxLength={SKIP_REASON_MAX}
            placeholder="예) 외부 시계열 분석 벤더 연동 전이라 시계열 없이 진행"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            disabled={loading}
          />
          {/* Field 컨텍스트가 이 문단을 textarea 의 aria-describedby 로 이어 준다. */}
          <FieldDescription>
            사유는 한 번만 입력해 선택한 전건에 같은 값으로 남습니다. 비우거나 공백만 넣으면 일부만
            실패하는 것이 아니라 요청 전체가 거부됩니다.
          </FieldDescription>
        </Field>
      </div>
    </Modal>
  );
}

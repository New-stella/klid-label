import { Check, X } from 'lucide-react';

import { cn } from '@/lib/cn';

import type { VlmVerification } from '../types';

export interface VlmVerificationCardProps {
  verifications: VlmVerification[];
  className?: string;
}

/**
 * SCR-AUTO-002 VLM 객체 검증 영역 (저작도구 책임).
 * - YOLO/SAM2 객체별 VLM 재검증 결과 (agree/disagree + reason)
 * - V1.7: 본 영역은 VLM 객체 검증 결과만 표시. 시계열 메타는 별도 EnvMeta/EventMeta 폼.
 */
export function VlmVerificationCard({ verifications, className }: VlmVerificationCardProps) {
  return (
    <section
      data-testid="vlm-verification-card"
      aria-label="VLM 객체 검증 결과"
      className={cn('rounded border border-border bg-white p-4', className)}
    >
      <h3 className="mb-1 text-section-title text-primary">VLM 객체 검증 결과</h3>
      <p className="mb-3 text-sub text-neutral">
        YOLO/SAM2가 감지한 객체에 대한 VLM 재검증 결과입니다. (저작도구 영역)
      </p>
      {verifications.length === 0 ? (
        <p className="py-3 text-sub text-neutral">검증된 객체가 없습니다.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {verifications.map((v) => (
            <li
              key={v.objectId}
              data-testid={`vlm-row-${v.objectId}`}
              className="flex items-start gap-3 rounded border border-border p-2"
            >
              <span
                className={cn(
                  'mt-1 flex h-5 w-5 items-center justify-center rounded-full',
                  v.vlmAgree ? 'bg-emerald-100 text-emerald-700' : 'bg-rose-100 text-rose-700',
                )}
                aria-label={v.vlmAgree ? '동의' : '거부'}
              >
                {v.vlmAgree ? (
                  <Check className="h-3 w-3" aria-hidden />
                ) : (
                  <X className="h-3 w-3" aria-hidden />
                )}
              </span>
              <div className="flex-1">
                <p className="text-body text-primary">
                  {v.className}{' '}
                  <span className="text-sub text-neutral">
                    (YOLO {(v.yoloConfidence * 100).toFixed(0)}%)
                  </span>
                </p>
                {v.vlmReason && (
                  <p className="mt-1 text-sub text-neutral">{v.vlmReason}</p>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

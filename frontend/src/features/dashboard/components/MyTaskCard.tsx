import type { MyTask } from '../types';

export interface MyTaskCardProps {
  task: MyTask;
}

/** WORKER 전용 — 내 작업 현황 카드 */
export function MyTaskCard({ task }: MyTaskCardProps) {
  const items: { label: string; value: number }[] = [
    { label: '배정 완료', value: task.pendingCount },
    { label: '작업중', value: task.inProgressCount },
    { label: '검수요청', value: task.reviewPendingCount },
    { label: '반려', value: task.rejectedCount },
  ];
  return (
    <section
      aria-label="내 작업 현황"
      data-testid="my-task-card"
      className="rounded border border-border bg-white p-4"
    >
      <h2 className="mb-3 text-section-title text-primary">내 작업 현황</h2>
      <dl className="grid grid-cols-4 gap-3">
        {items.map((it) => (
          <div key={it.label} className="flex flex-col gap-1">
            <dt className="text-sub text-neutral">{it.label}</dt>
            <dd className="text-page-title text-primary">
              {it.value.toLocaleString('ko-KR')}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

// SCR-PORTAL-001 — 포털 메인 페이지 (V1.5).
// - 2단계 카드 (업로드 / 라벨링)
// - KPI 2개 (업로드 수 · 라벨링 완료 수)
// - 내 업로드 목록 (다운로드 UI 미제공 — V1.5 포털 자체 책임)

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { KpiCard } from '@/components/common/KpiCard';
import { PageHeader } from '@/components/common/PageHeader';
import { Spinner } from '@/components/common/Spinner';

import { listMyUploads } from '../../features/portal/api';
import { MyUploadList } from '../../features/portal/components/MyUploadList';
import { UploadDropzone } from '../../features/portal/components/UploadDropzone';
import { UploadProgressItem } from '../../features/portal/components/UploadProgressList';
import type { UploadValidationError } from '../../features/portal/types';
import { useTusUpload } from '../../features/portal/upload/hooks/useTusUpload';

const REJECTION_LABEL: Record<UploadValidationError, string> = {
  EXTENSION_NOT_ALLOWED: '허용되지 않은 확장자입니다 (mp4/mov/avi).',
  FILE_TOO_LARGE: '파일 크기가 5GB를 초과합니다.',
  MIME_NOT_ALLOWED: '영상 파일이 아닙니다.',
  EMPTY_FILE: '빈 파일입니다.',
};

export function PortalHomePage() {
  const { data: uploads = [], isLoading } = useQuery({
    queryKey: ['portal', 'uploads'],
    queryFn: listMyUploads,
  });
  const tus = useTusUpload();
  const [rejectionMsg, setRejectionMsg] = useState<string | null>(null);
  const [activeFile, setActiveFile] = useState<File | null>(null);

  const totalUploads = uploads.length;
  const labeledCount = uploads.filter((u) => u.status === 'AUTOLABEL_DONE').length;

  function handleAccept(file: File) {
    setRejectionMsg(null);
    setActiveFile(file);
    tus.start(file);
  }

  function handleReject(reason: UploadValidationError) {
    setRejectionMsg(REJECTION_LABEL[reason]);
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="포털"
        description="영상을 업로드하고 간편 라벨링을 체험해보세요."
      />

      <section
        aria-label="요약"
        className="grid grid-cols-1 gap-3 md:grid-cols-2"
      >
        <KpiCard label="업로드 수" value={totalUploads} unit="건" />
        <KpiCard label="라벨링 완료" value={labeledCount} unit="건" />
      </section>

      <section
        aria-label="단계 카드"
        className="grid grid-cols-1 gap-3 md:grid-cols-2"
      >
        <article className="flex flex-col gap-3 rounded border border-border bg-white p-4">
          <h2 className="text-section-title">1. 업로드</h2>
          <UploadDropzone onAccept={handleAccept} onReject={handleReject} />
          {rejectionMsg && (
            <p role="alert" className="text-sub text-danger">
              {rejectionMsg}
            </p>
          )}
          {activeFile && tus.status !== 'IDLE' && (
            <UploadProgressItem
              filename={activeFile.name}
              status={tus.status}
              progress={tus.progress}
              onPause={tus.pause}
              onResume={tus.resume}
              onCancel={tus.cancel}
            />
          )}
        </article>
        <article className="flex flex-col gap-3 rounded border border-border bg-white p-4">
          <h2 className="text-section-title">2. 라벨링</h2>
          <p className="text-body text-neutral">
            업로드된 영상을 선택하여 간편 라벨링을 진행할 수 있습니다.
          </p>
        </article>
      </section>

      <section aria-label="내 업로드 목록" className="flex flex-col gap-2">
        <h2 className="text-section-title">내 업로드</h2>
        {isLoading ? <Spinner label="목록 로딩" /> : <MyUploadList uploads={uploads} />}
      </section>

      <p className="text-sub text-neutral">
        ※ 업로드한 영상은 본인만 조회/라벨링할 수 있으며, 결과 파일 제공은 포털 시스템에서 별도로 안내됩니다.
      </p>
    </div>
  );
}

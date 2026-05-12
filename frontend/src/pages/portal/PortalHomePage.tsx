// SCR-PORTAL-001 — 포털 메인 페이지 (V1.x mock 시각 정합).
// - hero 섹션 (orange gradient)
// - KPI 2개 (업로드 수 · 라벨링 완료 수)
// - 2단계 카드 (업로드 / 라벨링)
// - 내 업로드 목록 (다운로드 UI 미제공 — V1.5 포털 자체 책임)

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Upload as UploadIcon, Tag, Play } from 'lucide-react';

import { KpiCard } from '@/components/common/KpiCard';
import { Spinner } from '@/components/common/Spinner';

import { listMyUploads } from '../../features/portal/api';
import { MyUploadList } from '../../features/portal/components/MyUploadList';
import { UploadDropzone } from '../../features/portal/components/UploadDropzone';
import { UploadProgressItem } from '../../features/portal/components/UploadProgressList';
import type { UploadValidationError } from '../../features/portal/types';
import { useTusUpload } from '../../features/portal/upload/hooks/useTusUpload';
import { useAuthStore } from '@/stores/useAuthStore';

const REJECTION_LABEL: Record<UploadValidationError, string> = {
  EXTENSION_NOT_ALLOWED: '허용되지 않은 확장자입니다 (mp4/mov/avi).',
  FILE_TOO_LARGE: '파일 크기가 5GB를 초과합니다.',
  MIME_NOT_ALLOWED: '영상 파일이 아닙니다.',
  EMPTY_FILE: '빈 파일입니다.',
};

export function PortalHomePage() {
  const navigate = useNavigate();
  // 첫 마운트 시 토큰 ingress race 방어 — 토큰이 적재된 후에만 호출
  // (token 없는 상태에서 호출 → 401 → 인터셉터 redirectToUpstream → 18081 connection refused 폭탄 방지)
  const token = useAuthStore((s) => s.token);
  const { data: uploads = [], isLoading } = useQuery({
    queryKey: ['portal', 'uploads'],
    queryFn: listMyUploads,
    enabled: !!token,
  });
  const tus = useTusUpload();
  const [rejectionMsg, setRejectionMsg] = useState<string | null>(null);
  const [activeFile, setActiveFile] = useState<File | null>(null);

  const totalUploads = uploads.length;
  const labeledCount = uploads.filter((u) => u.status === 'AUTOLABEL_DONE').length;
  const pendingUploads = uploads.filter(
    (u) => u.status === 'COMPLETED' || u.status === 'AUTOLABEL_PENDING',
  );
  const pendingCount = pendingUploads.length;
  const firstPendingSrcSn = pendingUploads[0]?.srcSn ?? null;

  function handleAccept(file: File) {
    setRejectionMsg(null);
    setActiveFile(file);
    tus.start(file);
  }

  function handleReject(reason: UploadValidationError) {
    setRejectionMsg(REJECTION_LABEL[reason]);
  }

  return (
    <div className="flex flex-col">
      {/* Hero 섹션 — orange gradient */}
      <section className="bg-gradient-to-br from-orange-500 to-orange-600 px-6 py-10 text-white">
        <div className="mx-auto max-w-4xl">
          <h1 className="mb-2 text-page-title">AI 학습데이터 작성 포털</h1>
          <p className="text-body text-orange-50">
            영상/이미지 업로드, 간편 라벨링, 오토라벨링 체험
          </p>
        </div>
      </section>

      <div className="mx-auto flex w-full max-w-4xl flex-col gap-6 px-4 py-6">
        {/* KPI 2개 */}
        <section aria-label="요약" className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <KpiCard label="업로드 수" value={totalUploads} unit="건" />
          <KpiCard label="라벨링 완료" value={labeledCount} unit="건" />
        </section>

        {/* 2단계 카드 */}
        <section aria-label="이용 방법" className="flex flex-col gap-3">
          <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-500">
            이용 방법
          </h2>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            {/* Step 1: 업로드 */}
            <article className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
              <div className="flex items-center gap-2">
                <span className="flex h-7 w-7 items-center justify-center rounded-full bg-orange-100 text-sub font-bold text-orange-600">
                  1
                </span>
                <UploadIcon className="h-4 w-4 text-orange-500" aria-hidden />
                <h2 className="text-section-title text-gray-800">1. 업로드</h2>
              </div>
              <p className="text-sub text-gray-500">영상 또는 이미지를 업로드하세요</p>
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

            {/* Step 2: 라벨링 */}
            <article className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
              <div className="flex items-center gap-2">
                <span className="flex h-7 w-7 items-center justify-center rounded-full bg-orange-100 text-sub font-bold text-orange-600">
                  2
                </span>
                <Tag className="h-4 w-4 text-orange-500" aria-hidden />
                <h2 className="text-section-title text-gray-800">2. 라벨링</h2>
              </div>
              <p className="text-sub text-gray-500">
                업로드한 데이터에 라벨을 추가하세요
              </p>
              <p className="text-body text-gray-600">
                라벨링 필요{' '}
                <span className="font-semibold text-orange-600">{pendingCount}건</span>
              </p>
              <button
                onClick={() => {
                  if (firstPendingSrcSn !== null) {
                    navigate(`/portal/label/${firstPendingSrcSn}`);
                  }
                }}
                disabled={firstPendingSrcSn === null}
                className="mt-auto flex w-full items-center justify-center gap-1.5 rounded-lg bg-orange-500 px-3 py-2 text-sub font-medium text-white transition-colors hover:bg-orange-600 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <Play className="h-3.5 w-3.5" aria-hidden />
                시작하기 ▶
              </button>
            </article>
          </div>
        </section>

        {/* 내 업로드 목록 */}
        <section aria-label="내 업로드 목록" className="flex flex-col gap-2">
          <h2 className="text-section-title text-gray-900">내 업로드</h2>
          {isLoading ? <Spinner label="목록 로딩" /> : <MyUploadList uploads={uploads} />}
        </section>

        <p className="text-sub text-gray-500">
          ※ 업로드한 영상은 본인만 조회/라벨링할 수 있으며, 결과 파일 제공은 포털 시스템에서
          별도로 안내됩니다.
        </p>
      </div>
    </div>
  );
}

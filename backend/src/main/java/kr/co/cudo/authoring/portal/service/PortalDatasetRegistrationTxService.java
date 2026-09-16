package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.dataset.export.ExportFileNaming;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetFrame;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetShape;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetVideo;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 포털 데이터셋 영상 <b>1건 = 1 트랜잭션</b> 적재 — 영상 · 프레임 · 원본 라벨 · 영상 메타(ADR-068).
 *
 * <h3>왜 한 트랜잭션인가</h3>
 * <p>나누면 「영상만 있고 프레임이 없는」 상태가 남는다. {@code LS_DATA_LBL} 에는 프레임 외래키가 없어
 * 롤백이 연쇄로 정리해 주지 않으므로, 라벨도 <b>같은 트랜잭션</b>에서 함께 사라져야 한다.
 *
 * <h3>★ 파일은 트랜잭션 밖에서 먼저 복사돼 있다 — 여기서는 이름만 바꾼다</h3>
 * <p>프레임 이미지를 비식별 프레임 영역으로 옮기는 무거운 복사는 호출자가 <b>트랜잭션 밖</b>에서 작업 중
 * 자리에 끝내 두었다(커넥션을 쥔 채 저장소 입출력을 하지 않는다). 영상 식별자는 적재 순간에야 생기므로,
 * 여기서는 그 자리를 {@code frames/deid/{영상 식별자}/portal-dataset-{난수}} 로 <b>한 번의 원자적 이름
 * 바꾸기</b>만 한다. 이름
 * 바꾸기는 모든 행을 넣은 <b>뒤</b>에 한다 — 앞에서 실패하면 옮긴 것이 없어 되돌릴 파일도 없다.
 * 옮긴 자리는 {@code movedTo} 로 호출자에게 알린다 — 커밋이 뒤에서 실패하면 호출자가 그 자리를 지운다.
 *
 * <h3>이 경로가 <b>하지 않는</b> 것</h3>
 * <ul>
 *   <li><b>검수 워크플로 상태 행을 만들지 않는다</b> — 만들면 관제 조회 뷰·통지·산출물 연동이 반응한다.
 *       포털 작업 허용 근거는 승인이 아니라 출처다({@link PortalWorkableVideoPolicy}).</li>
 *   <li><b>적재 이벤트를 발행하지 않는다</b> — 발행하면 비식별 선두 단계가 이 영상을 집어 간다.</li>
 *   <li><b>원본 경로를 채우지 않는다</b> — 원본 이미지를 복사하지 않았다.</li>
 *   <li><b>라벨 이름으로 마스터를 역매핑하지 않는다</b> — 이름에 유일성 제약이 없어 다른 분류로 저장된다.
 *       실물 배포본은 분류 식별자 없이 <b>이름 문자열만</b> 싣는다 — 그 경우 이름만 옮기고 마스터에 잇지
 *       않는다(ADR-068).</li>
 * </ul>
 *
 * @design ADR-068
 * @design AC-1118
 */
@Service
@RequiredArgsConstructor
public class PortalDatasetRegistrationTxService {

    /** 활성 라벨 마스터 표식. */
    private static final String USE_YN_ACTIVE = "Y";

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository labelRepository;
    private final LsDataMetaRepository metaRepository;
    private final LsLabelRepository labelMasterRepository;

    /**
     * 적재 대상 데이터셋 — 번호와 <b>배포 코드·버전</b>.
     *
     * <p>세 값을 한 묶음으로 넘긴다 — 인자로 늘어놓으면 인접한 두 문자열(코드·버전)이 뒤바뀌어도
     * 컴파일되고 그 어긋남이 조용히 저장된다. 여기서 조립하면 이름이 값에 붙는다.
     *
     * @param datasetId 포털 데이터셋 번호 — 목록 창구의 대상 판정 키
     * @param code      배포 코드. <b>비어 있을 수 있다</b>(옛 데이터 · 요약을 읽지 못했을 때)
     * @param version   배포 버전. <b>비어 있을 수 있다</b>
     */
    public record DatasetRef(long datasetId, String code, String version) {

        /** 영상 키로 쓸 수 있는 글자 — 그 밖은 밑줄로 바꾼다(경로 구분자·제어 문자·공백이 여기 걸린다). */
        private static final java.util.regex.Pattern KEY_UNSAFE =
                java.util.regex.Pattern.compile("[^A-Za-z0-9._-]");

        /**
         * 문서에 영상 파일명이 <b>없을 때</b> 쓸 영상 키 — 배포 코드와 버전으로 만든다(ADR-068).
         *
         * <p>둘 다 비어 있으면(옛 데이터 · 요약을 읽지 못했을 때) 데이터셋 번호를 쓴다. 키가 아예 없으면
         * 그 배포본을 하나도 등록하지 못하므로 <b>비우지 않는다</b>.
         *
         * <p>★ 이 값은 그대로 {@link LsDataRaw#portalDatasetClipId} 에 들어가므로 <b>그 규칙을 여기서
         * 통과시킨다</b> — 허용 글자 밖은 밑줄로 바꾸고, 클립 식별자 폭에 맞춰 자른다. 자르는 것이 안전한
         * 이유는 이 키가 <b>데이터셋 하나에 하나</b>뿐이고 클립 식별자에 데이터셋 번호가 이미 들어 있어
         * 서로 다른 영상이 같은 식별자로 접힐 수 없기 때문이다(영상 파일명 쪽은 반대라 자르지 않고 거부한다).
         */
        public String fallbackVideoKey() {
            String joined = join(sanitize(code), sanitize(version));
            if (joined == null) {
                return String.valueOf(datasetId);
            }
            int room = LsDataRaw.VMS_CLIP_ID_MAX
                    - (LsDataRaw.PORTAL_DATASET_CLIP_ID_PREFIX.length() + String.valueOf(datasetId).length() + 1);
            return joined.length() <= room ? joined : joined.substring(0, room);
        }

        private static String join(String a, String b) {
            if (a == null) {
                return b;
            }
            return b == null ? a : a + "_" + b;
        }

        /** 키로 쓸 수 있게 다듬는다 — 비었거나 자리 참조(.·..)뿐이면 {@code null}. */
        private static String sanitize(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String safe = KEY_UNSAFE.matcher(value.trim()).replaceAll("_");
            return (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) ? null : safe;
        }
    }

    /** 적재 결과. */
    public record Persisted(long rawSn, int frameCount, int labelCount) {
    }

    /**
     * 영상 한 건을 적재한다.
     *
     * @param dataset       포털 데이터셋 — 번호와 배포 코드·버전(코드·버전은 비어 있을 수 있다)
     * @param video         해제본에서 읽은 영상
     * @param vmsClipId     멱등 키 — {@link LsDataRaw#portalDatasetClipId} 로 조립한 값
     * @param rawFilePathNm 해제본 안 그 영상의 프레임들이 공유하는 폴더 위치
     * @param stagingDir    이미지를 미리 복사해 둔 작업 중 자리(비식별 프레임 영역 안)
     * @param deidBase      비식별 저장소 base — 서빙 판정기가 쓰는 것과 <b>같은 표기</b>(절대·정규화)
     * @param movedTo       이름 바꾸기에 성공한 최종 자리를 담는다
     * @throws org.springframework.dao.DataIntegrityViolationException 같은 영상이 동시에 등록돼
     *         클립 식별자 유일 제약을 어겼을 때 — 호출자가 「이미 등록됨」으로 마감한다
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Persisted persist(DatasetRef dataset, DatasetVideo video, String vmsClipId, String rawFilePathNm,
                             Path stagingDir, Path deidBase, AtomicReference<Path> movedTo) {
        // ★ 유일 제약 위반을 <여기서> 드러나게 한다 — 프레임·라벨을 다 넣은 뒤 커밋에서 깨지면 비용만 크다.
        LsDataRaw raw = videoRepository.saveAndFlush(LsDataRaw.createPortalDataset(vmsClipId, rawFilePathNm));
        long rawSn = raw.getRawSn();

        // 최종 자리 = frames/deid/{영상 식별자}/portal-dataset-{작업 중 자리의 난수}. 영상 식별자 폴더 바로
        // 아래가 아니라 <한 겹 더> 둔다 — 그 폴더가 이미 있으면(저장소와 원장이 어긋난 흔적) 덮어쓰거나 지우지
        // 않고 옆에 앉기 위해서다. 서빙 판정은 frames/deid/** 하위면 통과하므로 규약은 그대로다.
        Path finalDir = deidBase.resolve(StorageSubtreePolicy.deidFramesDir(rawSn))
                .resolve(PortalDatasetRegistrationService.finalBatchName(stagingDir));

        List<LsDataSrc> frames = new ArrayList<>(video.frames().size());
        for (DatasetFrame f : video.frames()) {
            String deidPath = finalDir.resolve(ExportFileNaming.imageFileName(f.frameNo())).toString();
            LsDataSrc src = LsDataSrc.createFromImport(rawSn, f.frameNo(), f.videoFrameNo(),
                    null, deidPath, null, f.anonymity(), f.pseudonymity(), f.privacyIncluded());
            if (f.description() != null) {
                src.updateDescription(f.description());
            }
            frames.add(src);
        }
        List<LsDataSrc> savedFrames = srcRepository.saveAll(frames);

        Set<Long> activeLabelIds = activeLabelIds(video);
        List<LsDataLbl> labels = new ArrayList<>();
        for (int i = 0; i < savedFrames.size(); i++) {
            Long srcSn = savedFrames.get(i).getSrcSn();
            for (DatasetShape shape : video.frames().get(i).shapes()) {
                Long labelId = (shape.labelId() != null && activeLabelIds.contains(shape.labelId()))
                        ? shape.labelId() : null;
                labels.add(LsDataLbl.createRestored(srcSn, shape.lblTypeCd(), labelId, shape.labelName(),
                        shape.pointsJson(), null, null, shape.trackId(), null));
            }
        }
        if (!labels.isEmpty()) {
            labelRepository.saveAll(labels);
        }

        List<LsDataMeta> metas = new ArrayList<>();
        metas.add(LsDataMeta.create(rawSn, PortalDatasetLedger.KEY_DATASET_ID,
                Long.toString(dataset.datasetId())));
        // ★ 정리 삭제 트리거는 <코드와 버전>으로 온다 — 번호만 적어 두면 그 신호가 가리키는 행을 찾지
        //   못한다. 값은 소재 조달 응답이 이미 준 것(해제본 옆 요약)이라 포털에 더 요구하지 않는다.
        //   옛 데이터라 비어 오면 그 키를 쓰지 않는다 — 지어내지 않는다.
        addIfFits(metas, rawSn, PortalDatasetLedger.KEY_DATASET_CODE, dataset.code());
        addIfFits(metas, rawSn, PortalDatasetLedger.KEY_DATASET_VERSION, dataset.version());
        addIfFits(metas, rawSn, PortalDatasetLedger.KEY_DATASET_VIDEO_KEY, video.videoKey());
        addIfFits(metas, rawSn, PortalDatasetLedger.KEY_ORIGINAL_FILENAME, video.meta().originalFilename());
        if (PortalDatasetLedger.parseDecimal(video.meta().fps()) != null) {
            addIfFits(metas, rawSn, PortalDatasetLedger.KEY_FPS, video.meta().fps().trim());
        }
        if (video.meta().width() != null && video.meta().width() > 0) {
            metas.add(LsDataMeta.create(rawSn, PortalDatasetLedger.KEY_WIDTH, video.meta().width().toString()));
        }
        if (video.meta().height() != null && video.meta().height() > 0) {
            metas.add(LsDataMeta.create(rawSn, PortalDatasetLedger.KEY_HEIGHT, video.meta().height().toString()));
        }
        // 문서의 영상 길이·검증 이벤트 유형 — 내려받기 문서 조립이 되읽는다(없으면 그 칸을 비운다).
        if (video.meta().lengthSec() != null && video.meta().lengthSec() > 0) {
            metas.add(LsDataMeta.create(rawSn, PortalDatasetLedger.KEY_LENGTH_SEC,
                    video.meta().lengthSec().toString()));
        }
        addIfFits(metas, rawSn, PortalDatasetLedger.KEY_EVENT_TYPE_CD, video.meta().eventTypeCd());
        metaRepository.saveAll(metas);
        // 이름 바꾸기 전에 모든 문장을 DB 에 보낸다 — 제약 위반을 파일을 옮기기 전에 드러낸다.
        metaRepository.flush();

        // ★ 마지막 — 작업 중 자리를 최종 자리로 한 번에 옮긴다(같은 파일 시스템 · 원자적).
        try {
            Files.createDirectories(finalDir.getParent());
            Files.move(stagingDir, finalDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("프레임 이미지 자리를 확정하지 못했습니다.", e);
        }
        movedTo.set(finalDir);
        return new Persisted(rawSn, savedFrames.size(), labels.size());
    }

    /** 문서가 가리킨 분류 식별자 중 <b>활성 마스터에 실재하는 것</b>만. 한 영상당 조회 1회. */
    private Set<Long> activeLabelIds(DatasetVideo video) {
        Set<Long> requested = new LinkedHashSet<>();
        for (DatasetFrame f : video.frames()) {
            for (DatasetShape s : f.shapes()) {
                if (s.labelId() != null) {
                    requested.add(s.labelId());
                }
            }
        }
        if (requested.isEmpty()) {
            return Set.of();
        }
        Set<Long> active = new HashSet<>();
        for (LsLabel label : labelMasterRepository.findByLabelIdInAndUseYn(requested, USE_YN_ACTIVE)) {
            active.add(label.getLabelId());
        }
        return active;
    }

    /** 값이 있고 메타 칸 폭 안일 때만 적는다 — 넘치면 자르지 않고 비운다(지어내지도 자르지도 않는다). */
    private static void addIfFits(List<LsDataMeta> metas, long rawSn, String key, String value) {
        if (value == null || value.isBlank() || value.length() > PortalUploadLedger.META_VALUE_MAX) {
            return;
        }
        metas.add(LsDataMeta.create(rawSn, key, value));
    }
}

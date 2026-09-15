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
 *   <li><b>라벨 이름으로 마스터를 역매핑하지 않는다</b> — 이름에 유일성 제약이 없어 다른 분류로 저장된다.</li>
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

    /** 적재 결과. */
    public record Persisted(long rawSn, int frameCount, int labelCount) {
    }

    /**
     * 영상 한 건을 적재한다.
     *
     * @param datasetId     포털 데이터셋 번호
     * @param video         해제본에서 읽은 영상
     * @param vmsClipId     멱등 키 — {@link LsDataRaw#portalDatasetClipId} 로 조립한 값
     * @param rawFilePathNm 해제본 안 그 영상 폴더 위치
     * @param stagingDir    이미지를 미리 복사해 둔 작업 중 자리(비식별 프레임 영역 안)
     * @param deidBase      비식별 저장소 base — 서빙 판정기가 쓰는 것과 <b>같은 표기</b>(절대·정규화)
     * @param movedTo       이름 바꾸기에 성공한 최종 자리를 담는다
     * @throws org.springframework.dao.DataIntegrityViolationException 같은 영상이 동시에 등록돼
     *         클립 식별자 유일 제약을 어겼을 때 — 호출자가 「이미 등록됨」으로 마감한다
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Persisted persist(long datasetId, DatasetVideo video, String vmsClipId, String rawFilePathNm,
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
        metas.add(LsDataMeta.create(rawSn, PortalDatasetLedger.KEY_DATASET_ID, Long.toString(datasetId)));
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

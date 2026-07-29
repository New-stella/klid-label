package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 프레임 원본 이미지를 base64 로 인코딩하는 공용 컴포넌트.
 *
 * <p>Sam2TrackService / YoloTrackService 등 ai-server 로 프레임 이미지를 송신하는 경로에서
 * 중복되던 경로 가드 + 인코딩 로직을 단일화한다.
 *
 * <p>보안:
 * <ul>
 *   <li>Path Traversal (CWE-22): storage.raw-path 기준 디렉토리 범위 밖 접근 차단
 *       ({@code resolve().normalize()} + {@code startsWith}).</li>
 *   <li>Info Leak (CWE-209): 클라이언트로 나가는 예외 메시지에 내부 파일 경로·원문을 노출하지 않는다.</li>
 *   <li><b>PII 유출 (CWE-359)</b>: 비식별 누락 신고 구간의 프레임 픽셀이 외부 프로세스(ai-server)로
 *       나가지 못하도록 {@link #resolveFrameImageForInference} 에서 {@link DeidentReportGate} 로 차단한다
 *       (아래 "외부 전송 단일 진입점" 참조).</li>
 * </ul>
 *
 * <h3>★ 사용자 트리거 추론(ai-server) 전송의 단일 진입점 — 그리고 그 <b>경계</b></h3>
 * <b>사용자 요청으로 실행되는</b> 프레임 전송 경로는 예외 없이 {@link #resolveFrameImageForInference}
 * / {@link #encodeFrame} / {@link #encodeDeidentifiedFrameForInference} 를 거친다 — 내부 채널의
 * SAM2 분할·SAM2 추적·YOLO 추적·온라인 오토라벨, 외부 채널의 포털 SAM2 분할/추적이 전부 해당한다.
 * 신고 구간 차단을 각 서비스에 개별 배선하면 반드시 새는 경로가 생기므로(실제로 SAM2 분할/추적 2경로에
 * 이어 포털 SAM2 2경로가 연달아 누락됐다), 판정을 <b>이 한 지점</b>에 두고 <b>인코딩·전송 이전</b>에
 * fail-closed 로 끝낸다.
 *
 * <p><b>경계(이 컴포넌트를 지나지 않는 전송이 존재한다)</b>: 배치 파이프라인의
 * {@code YoloAutolabelStep} · {@code Sam2SegmentStep} 은 자체적으로 원본 프레임을 읽어 base64 로
 * ai-server 에 보낸다. 이는 설계상 정당하다 — 배치의 입력은 <b>모든 영상에 대해 항상 원본(비식별 전)</b>
 * 이며(CLAUDE.md "오토라벨링: YOLO/SAM2 는 원본 이미지에만 실행"), 신고 여부와 무관하게 같은 픽셀이
 * 나간다. 즉 신고 게이트를 붙여도 새로 보호되는 픽셀이 없다. 배치를 게이팅해야 한다면 그것은
 * "원본을 ai-server 에 보내도 되는가"라는 별개의 신뢰경계 결정이며, 그때는 파이프라인 진입부
 * ({@code BatchOrchestrator.process})에 두어야 한다. <b>"모든 전송이 이 클래스를 통과한다"고 쓰지 말 것</b>
 * — 사실이 아니고, 다음 사람이 그 문장을 믿고 검사를 생략한다.
 *
 * <p>{@link #resolveFrameImageWithoutGate} 는 <b>로컬 판독 전용</b>(치수 측정 등 앱 내부 소비)이며
 * 패키지 밖으로 열려 있지 않다.
 */
@Slf4j
@Component
public class FrameImageEncoder {

    private final Path baseDir;
    private final Path deidBaseDir;
    /**
     * 신고 구간 판정 단일 원천 — <b>이 프레임이 속한 영상 행</b>을 본다(파생영상은 원본 신고와 무관하다는
     * 확정 정책 — {@link DeidentReportGate} javadoc). 여기서 {@code "F"} 비교를 재구현하지 않는다.
     */
    private final DeidentReportGate deidentReportGate;

    public FrameImageEncoder(@Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
                             @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String storageDeidPath,
                             DeidentReportGate deidentReportGate) {
        this.baseDir = Path.of(storageRawPath).toAbsolutePath().normalize();
        this.deidBaseDir = Path.of(storageDeidPath).toAbsolutePath().normalize();
        this.deidentReportGate = deidentReportGate;
    }

    /**
     * M-6 — 프레임 엔티티에서 <b>비식별 우선 폴백</b>으로 이미지 경로를 해석한다.
     *
     * <p>해상도 파생 프레임은 원본 픽셀이 실재하지 않아 {@code SRC_FILE_PATH_NM} 이 null 이다(정책 A).
     * 원본 컬럼만 보던 구 구현은 파생 프레임에서 AI 탐지·분할·추적이 전부
     * {@code "이미지 경로가 비어있습니다"}(400)로 실패했다. 비식별 경로를 먼저 쓰되, 검증 base 를
     * <b>출처 컬럼에 맞춰</b> 고른다(비식별=deid base + 비식별 서브트리, 원본=raw base).
     *
     * <p><b>★ 게이트 없음 · 로컬 판독 전용</b> — 해석된 이미지를 앱 내부에서만 소비하는 호출부
     * (치수 측정 등)용이다. 이름에 {@code WithoutGate} 를 박고 <b>패키지 전용</b>으로 좁힌 이유는,
     * 이 메서드가 public 으로 열려 있는 한 "단일 진입점"이 명목에 그치기 때문이다(실제로 게이트 없는
     * 쌍둥이 메서드를 호출한 포털 SAM2 경로가 원본 픽셀을 외부로 내보냈다).
     *
     * <p>현재 유일한 패키지 외부 소비자는 {@code FrameBoundsResolver} — 라벨 좌표 정규화를 위해
     * 이미지 <b>치수만</b> 읽고 픽셀을 밖으로 내보내지 않으므로 게이트 대상이 아니다. 새 호출부를 추가할
     * 때는 "이 픽셀/파생물이 앱 밖(외부 프로세스·응답 본문·파일 산출)으로 나가는가"를 먼저 답하고,
     * 나간다면 {@link #resolveFrameImageForInference} 계열을 쓴다.
     *
     * @return 존재하는 이미지 절대경로
     * @throws CustomException 두 컬럼 모두 결측/해석 불가(NOT_FOUND·INVALID_INPUT)
     */
    Path resolveFrameImageWithoutGate(LsDataSrc frame) {
        if (frame == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다.");
        }
        String deid = frame.getDeidFilePath();
        if (deid != null && !deid.isBlank()) {
            StorageSubtreePolicy.Verification v =
                    StorageSubtreePolicy.verifyDeidentifiedFile(deidBaseDir, deid);
            if (v.ok()) {
                return v.path();
            }
        }
        String original = frame.getSrcFilePathNm();
        if (original == null || original.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "프레임 이미지를 찾을 수 없습니다.");
        }
        Path resolved = resolveSafe(original);
        if (!Files.exists(resolved)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일을 찾을 수 없습니다.");
        }
        return resolved;
    }

    /**
     * <b>외부 추론(ai-server) 전송 전 단일 진입점</b> — 신고 구간이면 <b>파일을 읽기도 전에</b> 차단한다.
     *
     * <p>차단 대상은 "이 프레임이 속한 영상이 비식별 누락 신고 구간({@code DE_IDNTF_YN='F'})"인 경우다.
     * 판정은 {@link DeidentReportGate} 단일 원천이며 <b>그 영상 행만</b> 본다 — 파생영상(증강·해상도)은
     * 원본의 신고에 영향받지 않는다(2026-07-29 확정 정책, 게이트 javadoc 참조).
     *
     * <p>응답 규약은 라벨 조회 게이트({@code LabelAccessGuard.requireNotUnderDeidentReport})·증강 결과
     * 조회·export 와 동일한 {@link ErrorCode#PRECONDITION_FAILED}(412)다. AI 추론은 라벨 생성 보조
     * 기능이므로 라벨 계열 관례를 따른다(영상 스트리밍만 오라클 회피 목적의 404를 쓴다). 이 경로들은
     * 모두 상위에서 IDOR 인가({@code LabelAccessGuard.verifyAccess})를 먼저 통과하므로 412가 미인가자에게
     * 영상 상태를 알려주지 않는다.
     *
     * @return 존재하는 이미지 절대경로
     * @throws CustomException PRECONDITION_FAILED(신고 구간) / NOT_FOUND·INVALID_INPUT(경로 해석 실패)
     */
    public Path resolveFrameImageForInference(LsDataSrc frame) {
        requireNotUnderDeidentReport(frame);
        return resolveFrameImageWithoutGate(frame);
    }

    /**
     * <b>비식별본 전용</b> 외부 추론 전송 진입점 — 신고 게이트 + <b>원본 폴백 금지</b>.
     *
     * <p>포털(외부 채널)용이다. 포털은 데이터마트 비식별본만 다루므로 프레임 서빙
     * ({@code PortalLabelService.serveFrameImage})이 비식별 경로만 내보내고 원본으로 폴백하지 않는다.
     * 추론 전송도 같은 규약을 따라야 한다 — 구 구현은 게이트 없는 {@code encodeToBase64(원본경로)} 를
     * 호출해 <b>원본(비식별 전) 픽셀</b>을 ai-server 로 보냈다(HIGH, CWE-359).
     *
     * @throws CustomException PRECONDITION_FAILED(신고 구간) / NOT_FOUND(비식별 프레임 부재·파일 없음)
     */
    public String encodeDeidentifiedFrameForInference(LsDataSrc frame) {
        requireNotUnderDeidentReport(frame);
        Path imagePath = resolveDeidentifiedFrame(frame);
        return encode(imagePath);
    }

    /** 비식별 프레임 경로 해석 — 원본 폴백 없음(부재/범위 밖이면 NOT_FOUND). */
    private Path resolveDeidentifiedFrame(LsDataSrc frame) {
        if (frame == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다.");
        }
        String deid = frame.getDeidFilePath();
        if (deid == null || deid.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 프레임이 존재하지 않습니다.");
        }
        StorageSubtreePolicy.Verification v =
                StorageSubtreePolicy.verifyDeidentifiedFile(deidBaseDir, deid);
        if (!v.ok()) {
            // CWE-209 — 내부 경로·사유 원문 비노출.
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 프레임이 존재하지 않습니다.");
        }
        return v.path();
    }

    /**
     * 프레임 엔티티 기준(비식별 우선) 이미지를 base64 로 인코딩한다.
     *
     * <p>본 메서드의 결과는 전량 ai-server 요청 본문으로 나가므로 {@link #resolveFrameImageForInference}
     * 를 경유해 <b>신고 구간이면 인코딩 자체를 수행하지 않는다</b>(전송 후 폐기가 아니라 전송 전 차단).
     */
    public String encodeFrame(LsDataSrc frame) {
        return encode(resolveFrameImageForInference(frame));
    }

    /**
     * 해석이 끝난 이미지 파일을 base64 로 읽는다.
     *
     * <p><b>게이트 없는 경로 문자열 오버로드는 두지 않는다</b>: 구 {@code encodeToBase64(String)} 는
     * 게이트가 걸린 {@code encodeFrame} 과 같은 클래스에 나란히 존재하는 "쌍둥이"였고, 포털 SAM2 가
     * 그쪽을 골라 신고 구간의 <b>원본</b> 픽셀을 ai-server 로 내보냈다. 진입점은 반드시
     * 프레임 엔티티({@link LsDataSrc})를 받아 게이트를 통과한 뒤 이 private 헬퍼로 수렴한다.
     */
    private String encode(Path imagePath) {
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기에 실패했습니다.");
        }
    }

    /**
     * 신고 구간 차단 판정(CWE-359) — 판정은 {@link DeidentReportGate} 단일 원천에 위임한다.
     *
     * <p>{@code frame} 이 null 이면 여기서 끝내지 않는다 — 후속 {@link #resolveFrameImageWithoutGate} 가 기존 규약대로
     * NOT_FOUND 로 마감한다(판정 대상이 없으므로 통과시켜도 픽셀이 나가지 않는다). {@code rawSn} 이 null 인
     * 경우도 게이트가 통과로 판정하며, 이 경우 역시 프레임 경로 해석이 상위 가드를 이미 통과한 상태다.
     */
    private void requireNotUnderDeidentReport(LsDataSrc frame) {
        if (frame == null) {
            return;
        }
        Long rawSn = frame.getRawSn();
        if (deidentReportGate.isUnderDeidentReport(rawSn)) {
            log.warn("[FrameImage] inference transfer blocked — deident report open on this video rawSn={} srcSn={}",
                    rawSn, frame.getSrcSn());
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다.");
        }
    }

    /** Path Traversal (CWE-22) 방어 — 기준 디렉토리 외부 접근 차단. */
    private Path resolveSafe(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 경로가 비어있습니다.");
        }
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않은 경로입니다.");
        }
        return resolved;
    }
}

package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.transfer.parser.ExternalNameSanitizer;
import kr.co.cudo.authoring.video.entity.LsDataRaw;

/**
 * 이관 영상의 <b>식별자와 저장 위치</b>를 정하는 단일 지점.
 *
 * <h3>식별자 — 중복 반입 거부의 실제 근거</h3>
 * <p>{@code IMPORT} 접두에 폴더 식별자와 산출물이 선언한 데이터셋 식별자를 이어 붙인다. 이 값이 들어가는
 * {@code LS_DATA_RAW.VMS_CLIP_ID} 에는 유일 제약이 걸려 있고, <b>같은 산출물을 두 번 가져오는 것을
 * 실제로 막는 것은 그 제약</b>이다. 이관 이력 표는 사람이 경위를 되짚는 기록이며, 두 노드가 같은 순간에
 * 들어오는 것까지 막지는 못한다(조회 후 판정은 check-then-act 라 창이 열린다).
 *
 * <h3>저장 위치 — 비울 수 없는 값이다</h3>
 * <p>{@code RAW_FILE_PATH_NM} 은 저장소 원본 기준경로 아래 {@code imports}, 그 아래 그 영상의 식별자,
 * 그 아래 <b>산출물이 준 원본 파일명</b>이다. 이 값을 비우면 안 되는 이유가 둘이다.
 * <ul>
 *   <li>비식별 산출물과 학습데이터 산출물의 저장 위치가 이 값의 <b>디렉터리 부분에서 파생</b>된다.
 *       비어 있으면 그 도출이 입력 오류로 끝나고, 학습데이터 산출물 생성이 <b>예외 없이 조용히
 *       실패로만 마감</b>되어 원인을 밖에서 알 수 없다.</li>
 *   <li>학습데이터 산출물의 영상 파일명이 이 경로의 <b>마지막 이름</b>에서 나온다. 자리표시자를 쓰면
 *       산출물에 인공 파일명이 실린다.</li>
 * </ul>
 *
 * <h3>외부 문자열은 조립 전에 정제한다</h3>
 * <p>폴더명과 데이터셋 식별자는 키가 되므로 허용 문자만 남기고, 파일명은 원문을 최대한 보존하되
 * 마지막 요소만 취한다(CWE-22). 길이 상한을 넘으면 <b>자르지 않고 거부</b>한다 — 잘린 경로는 존재하지
 * 않는 자리를 가리키므로, 그 상태로 적재하면 나중에 파일을 찾지 못하는 영상이 남는다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 */
public final class ImportPathPolicy {

    /** 이관 영상 식별자 접두. */
    public static final String CLIP_ID_PREFIX = "IMPORT";

    /** 저장소 원본 기준경로 아래 이관 전용 디렉터리 이름. */
    public static final String SEG_IMPORTS = "imports";

    /** {@code LS_DATA_RAW.RAW_FILE_PATH_NM} 컬럼 폭. */
    public static final int RAW_FILE_PATH_MAX = 500;

    /** 식별자 조각 하나의 상한 — 둘을 합쳐도 식별자 컬럼 폭 안에 들어오게 잡는다. */
    private static final int IDENTIFIER_PART_MAX = 50;

    private ImportPathPolicy() {
    }

    /**
     * 기준경로를 <b>절대경로</b>로 편다 — 저장하는 값이 어디서 읽어도 같은 자리를 가리키게 한다.
     *
     * <p>설정값은 상대경로일 수 있는데, 그대로 이어 붙여 저장하면 그 값을 읽는 쪽이 기준경로 아래에서
     * 다시 한 번 해석해 <b>존재하지 않는 자리</b>를 가리킨다(경로가 두 번 겹친다). 저작도구가 이미
     * 프레임을 쓰는 다른 경로들도 같은 이유로 기준경로를 먼저 절대경로로 편다.
     */
    private static String absoluteRoot(String base) {
        return java.nio.file.Paths.get(base).toAbsolutePath().normalize().toString();
    }

    /**
     * 이관 영상 식별자를 만든다.
     *
     * @param folderName        산출물 폴더 이름
     * @param datasetIdentifier 산출물 문서가 선언한 데이터셋 식별자
     * @throws IllegalArgumentException 두 값이 모두 비어 식별자를 만들 수 없을 때
     */
    public static String vmsClipId(String folderName, String datasetIdentifier) {
        String folder = ExternalNameSanitizer.identifier(folderName, IDENTIFIER_PART_MAX);
        String dataset = ExternalNameSanitizer.identifier(datasetIdentifier, IDENTIFIER_PART_MAX);
        if (folder == null && dataset == null) {
            // 둘 다 없으면 같은 산출물인지 가릴 축이 없다 — 임의 값을 만들면 중복 반입이 통과한다.
            throw new IllegalArgumentException("이관 식별자를 만들 수 없습니다. 폴더 이름과 데이터셋 식별자가 모두 비어 있습니다.");
        }
        String clipId = CLIP_ID_PREFIX + "-" + (folder == null ? "" : folder)
                + "-" + (dataset == null ? "" : dataset);
        if (clipId.length() > LsDataRaw.VMS_CLIP_ID_MAX) {
            throw new IllegalArgumentException("이관 식별자가 허용 길이를 넘습니다.");
        }
        return clipId;
    }

    /**
     * 이관 영상 파일의 저장 위치를 만든다.
     *
     * @param storageRawPath 저장소 원본 기준경로(설정값 — 외부 입력이 아니다)
     * @param vmsClipId      {@link #vmsClipId} 가 만든 이관 영상 식별자
     * @param videoFileName  산출물이 준 영상 파일명 <b>원문</b>
     * @throws IllegalArgumentException 파일명을 쓸 수 없거나 조립 결과가 컬럼 폭을 넘을 때
     */
    public static String rawFilePath(String storageRawPath, String vmsClipId, String videoFileName) {
        if (storageRawPath == null || storageRawPath.isBlank()) {
            throw new IllegalArgumentException("저장소 원본 기준경로가 설정되지 않았습니다.");
        }
        if (vmsClipId == null || vmsClipId.isBlank()) {
            throw new IllegalArgumentException("이관 영상 식별자가 비어 있습니다.");
        }
        String fileName = ExternalNameSanitizer.fileName(videoFileName, IDENTIFIER_PART_MAX * 2);
        if (fileName == null) {
            // 학습데이터 산출물의 영상 파일명이 여기서 나오므로 자리표시자를 만들지 않는다.
            throw new IllegalArgumentException("산출물이 준 영상 파일명을 쓸 수 없습니다.");
        }
        String path = absoluteRoot(storageRawPath) + "/" + SEG_IMPORTS + "/" + vmsClipId + "/" + fileName;
        if (path.length() > RAW_FILE_PATH_MAX) {
            // 자르면 존재하지 않는 자리를 가리킨다 — 자르지 않고 거부한다.
            throw new IllegalArgumentException("이관 영상 저장 경로가 허용 길이를 넘습니다.");
        }
        return path;
    }

    /**
     * 이관 <b>프레임 이미지</b>의 저장 위치를 만든다 —
     * {@code {base}/frames/deid/{식별자}/{파일명}} 또는 {@code {base}/frames/raw/{식별자}/{파일명}}.
     *
     * <h3>접두가 곧 원본·비식별 축의 판별자다</h3>
     * <p>저장소 원본 기준경로와 비식별 기준경로는 <b>같은 디렉터리일 수 있다</b>(운영 형상). 그래서 어느
     * 벌인지를 가리는 것은 기준경로가 아니라 <b>그 아래의 접두</b>이며, 그 규약은
     * {@link StorageSubtreePolicy} 한 곳이 정한다. 이관 전용 접두를 쓰면 그 판정기가 어느 축인지 말할 수
     * 없어 <b>거부</b>하고, 그러면 이 경로로 들어온 영상은 프레임 이미지가 열리지 않고 학습데이터 산출도
     * 한 장도 나오지 않는다. 판정기를 넓히는 대신 <b>경로를 규약에 맞춘다</b> — 넓히면 두 벌을 가르는
     * 유일한 수단이 무너진다.
     *
     * <h3>왜 {@code rawSn} 이 아니라 이관 식별자로 잎을 잡는가</h3>
     * <p>이 경로는 <b>영속 이전</b>에 정해져야 한다. 파일을 먼저 옮기고 한 트랜잭션에서 영상·프레임·
     * 라벨을 함께 넣어야 "영상만 있고 프레임이 없는" 부분 실패가 남지 않는데, 그러려면 아직 발급되지
     * 않은 {@code rawSn} 을 쓸 수 없다. 규약이 요구하는 것은 <b>앞 두 세그먼트</b>뿐이고 그 아래 잎은
     * 자유이므로 이관 식별자를 잎으로 써도 규약을 만족한다.
     *
     * @param base          저장소 기준경로(설정값 — 원본이면 원본 기준경로, 비식별 완료본이면 비식별 기준경로)
     * @param deidentified  가져올 때 사람이 지정한 값 — 참이면 비식별 벌, 거짓이면 원본 벌
     * @param vmsClipId     {@link #vmsClipId} 가 만든 이관 영상 식별자
     * @param imageFileName 산출물이 준 이미지 파일명 <b>원문</b>
     * @throws IllegalArgumentException 파일명을 쓸 수 없거나 조립 결과가 컬럼 폭을 넘을 때
     */
    public static String frameFilePath(String base, boolean deidentified,
                                       String vmsClipId, String imageFileName) {
        String fileName = ExternalNameSanitizer.fileName(imageFileName, IDENTIFIER_PART_MAX * 2);
        if (fileName == null) {
            throw new IllegalArgumentException("산출물이 준 프레임 파일명을 쓸 수 없습니다.");
        }
        String path = frameDirectory(base, deidentified, vmsClipId) + "/" + fileName;
        if (path.length() > RAW_FILE_PATH_MAX) {
            throw new IllegalArgumentException("이관 프레임 저장 경로가 허용 길이를 넘습니다.");
        }
        return path;
    }

    /**
     * 이관 프레임이 놓이는 디렉터리 — {@code {base}/frames/{deid|raw}/{식별자}}.
     *
     * <p>세그먼트 이름은 {@link StorageSubtreePolicy} 의 상수를 그대로 쓴다. 문자열을 여기서 다시 적으면
     * 그쪽 규약이 바뀔 때 이 경로만 조용히 뒤처져 판정에서 거부된다.
     */
    public static String frameDirectory(String base, boolean deidentified, String vmsClipId) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("저장소 기준경로가 설정되지 않았습니다.");
        }
        if (vmsClipId == null || vmsClipId.isBlank()) {
            throw new IllegalArgumentException("이관 영상 식별자가 비어 있습니다.");
        }
        return absoluteRoot(base) + "/" + StorageSubtreePolicy.SEG_FRAMES + "/"
                + (deidentified ? StorageSubtreePolicy.SEG_DEID : StorageSubtreePolicy.SEG_RAW)
                + "/" + vmsClipId;
    }
}

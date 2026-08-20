package kr.co.cudo.authoring.transfer.parser;

import kr.co.cudo.authoring.common.util.Point;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 1차 어노테이션 산출물 폴더 하나를 읽어 낸 <b>결과 스냅샷</b>(불변).
 *
 * <h3>이 모델이 하지 않는 일</h3>
 * <p>여기에는 저작도구 엔티티가 없다. 적재 규칙(어느 컬럼에 무엇을 넣는가)은 이관 서비스의 몫이고,
 * 이 모델은 <b>산출물이 실제로 무엇을 담고 있었는가</b>만 담는다. 두 관심사를 섞으면 산출물 형식이
 * 하나 늘 때마다 적재 규칙까지 갈라진다.
 *
 * <h3>확인되지 않은 것은 해석하지 않는다</h3>
 * <p>확인한 산출물에 값이 없던 항목({@code bbox}·{@code keypoints}, 파일 크기 단위)은
 * <b>원문 그대로</b> 담고 경고를 남긴다. 형식을 짐작해 변환하면 그 변환이 곧 사실이 되어 나중에
 * 되돌릴 수 없다.
 *
 * @param folderName 폴더 이름(정제됨) — 이관 식별자 조립의 한 축
 * @param folderPath 읽어 들인 폴더의 실제 경로
 * @param info       산출물 문서의 {@code dataset} 블록
 * @param video      산출물 문서의 {@code video} 블록(폴더 안에서 하나로 본다)
 * @param frames     프레임 목록 — <b>실제 파일 기준</b>이며 문서 선언 건수가 아니다(AC-047)
 * @param warnings   적재를 막지 않는 경고 — 호출부가 사람에게 보여 준다(AC-047)
 * @design DOMAIN-017
 * @design ERD-031
 * @design AC-047
 */
public record ImportedDataset(
        String folderName,
        Path folderPath,
        DatasetInfo info,
        VideoBlock video,
        List<Frame> frames,
        List<Warning> warnings) {

    /** 문서 짝이 있는(=라벨을 가진) 프레임 수. */
    public long documentedFrameCount() {
        return frames.stream().filter(Frame::hasDocument).count();
    }

    /** 실제로 읽어 낸 도형 라벨 총 개수. */
    public long shapeCount() {
        return frames.stream().mapToLong(f -> f.shapes().size()).sum();
    }

    /**
     * 산출물 문서의 {@code dataset} 블록.
     *
     * <p>⚠ {@code totalCount}/{@code originCount} 는 <b>영상의 총 프레임 수가 아니다</b> —
     * 일정 간격으로 뽑은 <b>라벨링 대상 프레임 수</b>다. 총 프레임으로 쓰면 학습데이터 산출물의
     * 프레임 수가 실제와 크게 어긋난다.
     */
    public record DatasetInfo(String identifier, String name, String srcPath, String labelPath,
                              Integer totalCount, Integer originCount, Integer augmentationCount) {
    }

    /**
     * 산출물 문서의 {@code video} 블록 — 영상 1건의 메타.
     *
     * <p>저작도구에 대응 컬럼이 없는 항목(좌표·위치·카메라 높이/방위/관리번호·데이터 출처·이벤트 로그)도
     * 버리지 않고 담는다. 버리면 되돌릴 수 없다(ERD-031 메타 절).
     *
     * @param externalVideoId 산출물의 영상 식별자 원문
     * @param fileName        영상 파일명(정제됨) — {@code RAW_FILE_PATH_NM} 의 마지막 이름이 된다
     * @param fileSizeRaw     파일 크기 <b>원문</b>. 단위가 확인되지 않아 숫자로 해석하지 않는다
     * @param lengthMillis    재생 길이(밀리초). 원문의 {@code ms} 접미를 떼어 읽은 값
     * @param labeledFrames   {@code video.frames} — 라벨링 대상 프레임 수(총 프레임 아님)
     * @param weather         저작도구 허용값으로 정규화된 날씨. 허용값 밖이면 {@code null}
     * @param timeOfDay       {@code DAY}/{@code NGT} 로 정규화. 알 수 없으면 {@code null}
     * @param season          {@code SPRING}/{@code SUMMER}/{@code FALL}/{@code WINTER}. 알 수 없으면 {@code null}
     * @param stdgCd          법정동코드 — 저작도구 {@code LCLGV_CD} 로 들어간다
     */
    public record VideoBlock(
            String externalVideoId,
            String fileName,
            LocalDateTime dateCreated,
            String type,
            String format,
            String fileSizeRaw,
            String location,
            Long lengthMillis,
            Double fps,
            Integer labeledFrames,
            String aspectRatio,
            Integer width,
            Integer height,
            String resolution,
            Long bitRate,
            String pixel,
            String weather,
            String coordinates,
            String stdgCd,
            String dataSource,
            String cctvName,
            String cctvHeight,
            String cctvAzimuth,
            String cctvMngNo,
            String anonymity,
            String pseudonymity,
            String privacyIncluded,
            String aiGenerated,
            String eventName,
            String eventLevel1Name,
            String eventLevel2Name,
            String eventLevel3Name,
            String timeOfDay,
            String season,
            String eventLog) {

        /**
         * 재생 길이(초) — {@code LS_DATA_RAW.VDO_LEN_SEC} 용. 값이 없으면 {@code null}
         * (0 으로 채우지 않는다 — "모른다"와 "0초"는 다른 사실이다).
         */
        public Integer durationSec() {
            if (lengthMillis == null) {
                return null;
            }
            long sec = lengthMillis / 1000L;
            return sec > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sec;
        }
    }

    /**
     * 프레임 1건 — 이미지 파일과 그 이미지를 설명하는 문서의 짝.
     *
     * <p>둘 중 하나만 있어도 프레임이다(AC-047): 문서 없는 이미지는 <b>라벨이 없는 프레임</b>으로,
     * 이미지 없는 문서는 이미지 경로가 빈 프레임으로 담긴다. 어느 쪽이든 경고가 함께 남는다.
     *
     * @param videoFrameNo 문서의 {@code image.frame_num} — <b>영상 내 실제 위치</b>다.
     *                     저작도구의 추출 순번({@code FRM_NO})과 의미가 다르며, 둘을 바꾸면 프레임과
     *                     라벨이 서로 다른 장면을 가리킨다
     * @param imagePath    실제 이미지 파일 경로. 파일이 없으면 {@code null}
     */
    public record Frame(
            String imageFileName,
            Path imagePath,
            Path documentPath,
            String imageId,
            Integer width,
            Integer height,
            Long videoFrameNo,
            LocalDateTime dateCaptured,
            String description,
            String anonymity,
            String pseudonymity,
            String privacyIncluded,
            Texts texts,
            List<Shape> shapes) {

        /** 짝 문서가 있었는가 — 없으면 라벨이 없는 프레임이다. */
        public boolean hasDocument() {
            return documentPath != null;
        }

        /** 이미지 파일이 실제로 있었는가. */
        public boolean hasImage() {
            return imagePath != null;
        }
    }

    /**
     * 도형 라벨 1건.
     *
     * @param categoryId    외부 분류 식별 문자열 <b>원문</b> — 분류 대응의 열쇠
     * @param categoryName  외부 분류 표시 이름(문서의 {@code categories} 에서 조달)
     * @param shapeType     문서가 선언한 도형 종류 원문(예: {@code POLYGON})
     * @param polygonRings  다각형 좌표 — 저작도구 정규 형식({@code [[x,y],...]})으로 변환된 링 목록.
     *                      산출물은 링마다 좌표를 평면으로 나열하므로 그대로 쓸 수 없다
     * @param bbox          경계상자 <b>원문 숫자열</b>. 확인된 산출물에 값이 없어 해석 규칙이
     *                      확정되지 않았으므로 변환하지 않는다
     * @param keypointsRaw  키포인트 <b>원문 JSON</b>. 같은 이유로 변환하지 않는다
     */
    public record Shape(
            String annotationId,
            String imageId,
            String categoryId,
            String categoryName,
            String shapeType,
            String trackId,
            List<List<Point>> polygonRings,
            List<Double> bbox,
            String keypointsRaw) {
    }

    /**
     * 도형이 아닌 <b>텍스트 항목</b> — 산출물은 이것을 도형 라벨과 같은 배열에 섞어 보낸다.
     * 섞인 채로 라벨로 적재하면 좌표 없는 라벨이 생기므로 여기서 갈라 담는다.
     *
     * @param others 위 세 가지 밖의 텍스트 항목(분류 식별 문자열 → 값). 버리지 않는다
     */
    public record Texts(String imageDescription, String privacyIncluded, String deIdentification,
                        Map<String, String> others) {

        /** 텍스트 항목이 하나라도 있었는가. */
        public boolean isEmpty() {
            return imageDescription == null && privacyIncluded == null && deIdentification == null
                    && others.isEmpty();
        }
    }

    /**
     * 적재를 막지 않는 경고 — 산출물이 온전하지 않아도 <b>실제 파일을 기준으로</b> 받아들이되
     * 그 사실을 사람에게 알린다(AC-047).
     *
     * @param code    기계가 분기할 수 있는 사유 코드({@link ImportWarningCode})
     * @param message 사람이 읽는 설명 — 외부 문자열은 정제해 담는다(CWE-117)
     */
    public record Warning(String code, String message) {
    }
}

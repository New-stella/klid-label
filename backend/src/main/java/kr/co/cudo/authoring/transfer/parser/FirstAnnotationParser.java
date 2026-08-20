package kr.co.cudo.authoring.transfer.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset.DatasetInfo;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset.Frame;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset.Shape;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset.Texts;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset.VideoBlock;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset.Warning;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 1차 어노테이션 산출물 폴더를 읽어 {@link ImportedDataset} 으로 옮긴다.
 *
 * <h3>산출물의 생김새 (실물 기준)</h3>
 * <p>폴더 하나에 <b>프레임 이미지와 그 이미지를 설명하는 문서가 짝</b>으로 들어 있고, 문서마다
 * 영상 단위 정보({@code video})·프레임 단위 정보({@code image})·도형 라벨·텍스트 항목이 함께 담긴다.
 * 즉 영상 정보는 문서마다 <b>반복</b>되며 폴더 전체에서 하나로 본다.
 *
 * <h3>우리 산출물과 필드 이름이 다르다 — 역방향 재사용 불가</h3>
 * <p>같은 뜻을 다른 이름으로 적는다({@code file_name}과 {@code filename}, {@code bit_rate}와
 * {@code bit}, {@code stdg_cd}와 {@code og_cd}). 1차에만 있는 것({@code event_level1~3_name},
 * {@code ai_generated})과 우리에만 있는 것({@code event_id}, {@code vd_description})도 있다.
 * 우리 산출물 작성기를 거꾸로 돌려 쓸 수 없으므로 읽기 전용 파서를 따로 둔다.
 *
 * <h3>온전하지 않아도 받아들인다 (AC-047)</h3>
 * <p>짝 문서가 없는 이미지, 문서가 선언한 건수와 다른 실제 파일 수, 읽히지 않는 문서 하나 —
 * 어느 것도 폴더 전체를 거부할 이유가 되지 않는다. <b>실제 파일을 기준으로</b> 담고 그 사실을
 * 경고로 남긴다. 정상 파일 하나 때문에 수백 프레임이 통째로 막히면 안 되고, 선언값을 믿으면
 * 실제로 없는 프레임을 있다고 적재하게 된다.
 *
 * <h3>확인되지 않은 것은 해석하지 않는다</h3>
 * <p>{@code bbox}와 {@code keypoints} 는 확인한 산출물에 값이 없어 해석 규칙이 확정되지 않았다.
 * 원문 그대로 담고 경고만 남긴다 — 형식을 짐작해 좌표로 옮기면 그 짐작이 곧 학습데이터가 된다.
 *
 * <h3>외부 문자열은 믿지 않는다</h3>
 * <p>폴더명, 파일명, 분류명은 전부 {@link ExternalNameSanitizer} 를 거친다 — 경로 이탈(CWE-22)과
 * 로그 위조(CWE-117), 컬럼 폭 초과를 입구에서 없앤다. 이미지 경로는 <b>주어진 폴더의 바로 아래</b>
 * 여야만 인정한다.
 *
 * <h3>폴더 안의 바로가기(링크)도 믿지 않는다 (AC-048)</h3>
 * <p>폴더 위치가 읽어도 되는 자리인지는 호출부가 <b>한 겹</b>만 판정한다. 그 판정을 통과한 폴더 안에
 * 밖을 가리키는 바로가기를 두면 허용 범위 밖 파일이 읽힌다. 그래서 폴더를 훑을 때도 문서를 열 때도
 * 링크를 따라가지 않는다(CWE-22/59/367). 건너뛴 항목은 경고로 알린다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 * @design AC-047
 * @design AC-048
 * @design UC-035
 */
@Component
@RequiredArgsConstructor
public class FirstAnnotationParser {

    /** 문서가 선언하는 텍스트 항목의 종류 표기 — 이 값이면 도형이 아니라 텍스트다. */
    static final String CATEGORY_TYPE_TEXT = "TEXT";

    /** 텍스트 항목 — 이미지 설명. */
    static final String TEXT_IMAGE_DESCRIPTION = "image_description";

    /** 텍스트 항목 — 개인정보 포함여부. */
    static final String TEXT_PRIVACY_INCLUDED = "privacy_included";

    /** 텍스트 항목 — 비식별 여부. */
    static final String TEXT_DE_IDENTIFICATION = "de-identification";

    /** 이미지로 인정하는 확장자(allowlist). 그 밖의 파일은 프레임 이미지로 보지 않는다. */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private static final String DOCUMENT_EXTENSION = "json";

    /** 단위가 붙은 재생 길이(밀리초 표기). 단위 없는 숫자도 밀리초로 읽는다. */
    private static final Pattern LENGTH_MILLIS = Pattern.compile("^\\s*(\\d+)\\s*(ms)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 경고 메시지에 담는 외부 문자열 길이 상한 — 경고가 응답을 뒤덮지 않게 한다. */
    private static final int MESSAGE_VALUE_MAX = 120;

    /** 사람이 읽는 텍스트 컬럼 상한 — 프레임 설명 등 1000자 컬럼에 맞춘다. */
    private static final int LONG_TEXT_MAX = 1000;

    /** 짧은 표시 문자열 상한 — 이름과 코드 계열. */
    private static final int SHORT_TEXT_MAX = 200;

    private final ObjectMapper objectMapper;

    /**
     * 산출물 폴더 하나를 읽는다.
     *
     * <p>폴더 <b>바로 아래</b>만 본다(하위 폴더로 내려가지 않는다) — 어디까지가 한 산출물인지는
     * 사람이 고른 폴더가 정하고, 파서가 트리를 훑으며 넓히지 않는다.
     *
     * @throws IllegalArgumentException 폴더가 아니거나 읽을 수 없을 때
     */
    public ImportedDataset parseFolder(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            throw new IllegalArgumentException("산출물 폴더를 찾을 수 없습니다.");
        }
        Path normalized = folder.toAbsolutePath().normalize();

        List<Warning> warnings = new ArrayList<>();
        List<Path> documents = new ArrayList<>();
        List<Path> images = new ArrayList<>();
        collectEntries(normalized, documents, images, warnings);

        DatasetInfo info = null;
        VideoBlock video = null;
        String firstVideoKey = null;
        boolean conflictReported = false;

        List<Frame> frames = new ArrayList<>();
        Set<String> pairedImages = new LinkedHashSet<>();

        for (Path document : documents) {
            JsonNode root;
            try (InputStream in = Files.newInputStream(document, LinkOption.NOFOLLOW_LINKS)) {
                root = objectMapper.readTree(in);
            } catch (IOException | RuntimeException e) {
                // 문서 하나가 깨졌다고 폴더 전체를 버리지 않는다(AC-047). 그 문서만 건너뛴다.
                warnings.add(warn(ImportWarningCode.UNREADABLE_DOCUMENT,
                        "문서를 읽을 수 없어 건너뛴다: " + display(document.getFileName().toString())));
                continue;
            }
            if (info == null) {
                info = readDatasetInfo(root.path("dataset"));
            }
            boolean firstVideo = video == null;
            VideoBlock parsedVideo = readVideo(root.path("video"), warnings, firstVideo);
            String parsedKey = videoKey(parsedVideo);
            if (firstVideo) {
                video = parsedVideo;
                firstVideoKey = parsedKey;
            } else if (!conflictReported && firstVideoKey != null && !firstVideoKey.equals(parsedKey)) {
                // 첫 문서의 영상 정보를 쓴다 — 어느 것이 맞는지 파서가 고를 근거가 없다.
                warnings.add(warn(ImportWarningCode.VIDEO_META_CONFLICT,
                        "폴더 안의 문서들이 서로 다른 영상을 가리킨다. 첫 문서의 영상 정보를 쓴다."));
                conflictReported = true;
            }

            Frame frame = readFrame(root, document, images, warnings);
            frames.add(frame);
            if (frame.hasImage()) {
                pairedImages.add(frame.imagePath().getFileName().toString());
            }
        }

        // 짝 문서가 없는 이미지도 라벨이 없는 프레임으로 담는다 (AC-047).
        for (Path image : images) {
            String name = image.getFileName().toString();
            if (pairedImages.contains(name)) {
                continue;
            }
            warnings.add(warn(ImportWarningCode.UNPAIRED_IMAGE,
                    "짝 문서가 없는 이미지를 라벨 없는 프레임으로 담는다: " + display(name)));
            frames.add(unpairedFrame(image));
        }

        Integer declared = info == null ? null : info.totalCount();
        if (declared != null && declared != frames.size()) {
            warnings.add(warn(ImportWarningCode.DECLARED_COUNT_MISMATCH,
                    "문서가 선언한 건수(" + declared + ")와 실제 파일 수(" + frames.size()
                            + ")가 다르다. 실제 파일을 기준으로 적재한다."));
        }

        String folderName = ExternalNameSanitizer.fileName(
                normalized.getFileName().toString(), SHORT_TEXT_MAX);
        return new ImportedDataset(folderName, normalized, info, video,
                List.copyOf(frames), List.copyOf(warnings));
    }

    // ------------------------------------------------------------------ 폴더 훑기

    /**
     * 폴더 바로 아래에서 읽을 항목을 고른다.
     *
     * <h3>바로가기(심링크)는 따라가지 않는다 (CWE-22/59/367)</h3>
     * <p>폴더 위치는 허용 범위 안인지 <b>한 겹</b>만 판정한다. 그 안에 밖을 가리키는 바로가기를 두고
     * 산출물 문서처럼 이름 붙이면, 판정을 통과한 폴더를 통해 <b>허용 범위 밖 파일의 내용</b>이 읽혀
     * 검사 응답으로 나간다(읽히지 않더라도 "읽을 수 없다"는 경고 자체가 그 파일의 존재를 알려 준다).
     * 그래서 링크는 대상이 안에 있든 밖에 있든 가리지 않고 <b>모두</b> 건너뛴다 — 대상이 어디인지
     * 확인한 뒤 여는 방식은 확인과 열기 사이에 링크를 바꿔치기할 창이 남는다.
     *
     * <h3>건너뛴 것은 반드시 알린다</h3>
     * <p>조용히 빼면 산출물에 있던 항목이 이유 없이 사라진 것이 되어, 사람이 "덜 들어온 것"을
     * "원래 그만큼인 것"으로 읽는다. 그래서 산출물 항목으로 보이는 이름의 링크만 골라 경고를 남긴다
     * (관계없는 이름의 링크까지 알리면 경고가 잡음이 된다).
     */
    private void collectEntries(Path folder, List<Path> documents, List<Path> images,
                                List<Warning> warnings) {
        try (Stream<Path> entries = Files.list(folder)) {
            entries
                    // 이름 순 — 같은 폴더를 두 번 읽어도 프레임 순서가 흔들리지 않게 한다.
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String ext = extension(name);
                        boolean document = DOCUMENT_EXTENSION.equals(ext);
                        boolean image = IMAGE_EXTENSIONS.contains(ext);
                        if (!document && !image) {
                            return;
                        }
                        if (Files.isSymbolicLink(p)) {
                            warnings.add(warn(ImportWarningCode.SYMBOLIC_LINK_SKIPPED,
                                    "바로가기(링크) 항목은 따라가지 않고 건너뛴다: " + display(name)));
                            return;
                        }
                        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                            return;
                        }
                        if (document) {
                            documents.add(p);
                        } else {
                            images.add(p);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ dataset / video

    private DatasetInfo readDatasetInfo(JsonNode dataset) {
        if (!isPresent(dataset)) {
            return null;
        }
        return new DatasetInfo(
                text(dataset, "identifier", SHORT_TEXT_MAX),
                text(dataset, "name", SHORT_TEXT_MAX),
                text(dataset, "src_path", SHORT_TEXT_MAX),
                text(dataset, "label_path", SHORT_TEXT_MAX),
                integer(dataset, "total_count"),
                integer(dataset, "origin_count"),
                integer(dataset, "augmentation_count"));
    }

    /**
     * @param reportEnvironment 영상 단위 경고는 <b>첫 문서에서만</b> 남긴다 — 영상 정보가 문서마다
     *                          반복되므로 그러지 않으면 같은 경고가 프레임 수만큼 쌓인다
     */
    private VideoBlock readVideo(JsonNode video, List<Warning> warnings, boolean reportEnvironment) {
        if (!isPresent(video)) {
            return null;
        }
        String rawFileName = text(video, "file_name", SHORT_TEXT_MAX);
        String fileName = ExternalNameSanitizer.fileName(rawFileName, SHORT_TEXT_MAX);
        if (reportEnvironment && fileName == null) {
            // 이 값이 없으면 저장 위치의 마지막 이름을 정할 수 없다 — 호출부가 fail-closed 로 막는다.
            warnings.add(warn(ImportWarningCode.UNUSABLE_VIDEO_FILE_NAME, "영상 파일명을 쓸 수 없다."));
        }

        String rawWeather = text(video, "weather", SHORT_TEXT_MAX);
        String weather = ImportShootingEnvironment.weather(rawWeather);
        if (reportEnvironment && !ImportShootingEnvironment.isAbsent(rawWeather) && weather == null) {
            warnings.add(warn(ImportWarningCode.UNKNOWN_WEATHER,
                    "날씨 표기를 허용값으로 옮길 수 없어 비운다: " + display(rawWeather)));
        }

        String rawTimeOfDay = text(video, "time_of_day", SHORT_TEXT_MAX);
        String timeOfDay = ImportShootingEnvironment.timeOfDay(rawTimeOfDay);
        if (reportEnvironment && !ImportShootingEnvironment.isAbsent(rawTimeOfDay) && timeOfDay == null) {
            warnings.add(warn(ImportWarningCode.UNKNOWN_TIME_OF_DAY,
                    "시간대 표기를 알 수 없어 비운다: " + display(rawTimeOfDay)));
        }

        String rawSeason = text(video, "season", SHORT_TEXT_MAX);
        String season = ImportShootingEnvironment.season(rawSeason);
        if (reportEnvironment && !ImportShootingEnvironment.isAbsent(rawSeason) && season == null) {
            warnings.add(warn(ImportWarningCode.UNKNOWN_SEASON,
                    "계절 표기를 알 수 없어 비운다: " + display(rawSeason)));
        }

        return new VideoBlock(
                text(video, "id", SHORT_TEXT_MAX),
                fileName,
                dateTime(video, "date_created", warnings, reportEnvironment),
                text(video, "type", SHORT_TEXT_MAX),
                text(video, "format", SHORT_TEXT_MAX),
                text(video, "filesize", SHORT_TEXT_MAX),
                text(video, "location", SHORT_TEXT_MAX),
                lengthMillis(text(video, "length", SHORT_TEXT_MAX)),
                decimal(video, "fps"),
                integer(video, "frames"),
                text(video, "aspect_ratio", SHORT_TEXT_MAX),
                integer(video, "width"),
                integer(video, "height"),
                text(video, "resolution", SHORT_TEXT_MAX),
                number(video, "bit_rate"),
                text(video, "pixel", SHORT_TEXT_MAX),
                weather,
                text(video, "coordinates", SHORT_TEXT_MAX),
                text(video, "stdg_cd", SHORT_TEXT_MAX),
                text(video, "data_source", SHORT_TEXT_MAX),
                text(video, "cctv_name", SHORT_TEXT_MAX),
                text(video, "cctv_height", SHORT_TEXT_MAX),
                text(video, "cctv_azimuth", SHORT_TEXT_MAX),
                text(video, "cctv_mng_no", SHORT_TEXT_MAX),
                text(video, "anonymity", SHORT_TEXT_MAX),
                text(video, "pseudonymity", SHORT_TEXT_MAX),
                text(video, "privacy_included", SHORT_TEXT_MAX),
                text(video, "ai_generated", SHORT_TEXT_MAX),
                text(video, "event_name", SHORT_TEXT_MAX),
                text(video, "event_level1_name", SHORT_TEXT_MAX),
                text(video, "event_level2_name", SHORT_TEXT_MAX),
                text(video, "event_level3_name", SHORT_TEXT_MAX),
                timeOfDay,
                season,
                ExternalNameSanitizer.multilineText(rawText(video, "event_log"), LONG_TEXT_MAX));
    }

    private static String videoKey(VideoBlock video) {
        if (video == null) {
            return null;
        }
        return video.externalVideoId() + " " + video.fileName();
    }

    // ------------------------------------------------------------------ frame

    private Frame readFrame(JsonNode root, Path document, List<Path> images, List<Warning> warnings) {
        JsonNode image = root.path("image");
        String documentName = display(document.getFileName().toString());

        String imageFileName = ExternalNameSanitizer.fileName(
                text(image, "file_name", SHORT_TEXT_MAX), SHORT_TEXT_MAX);
        Path imagePath = resolveImage(images, imageFileName);
        if (imagePath == null) {
            warnings.add(warn(ImportWarningCode.MISSING_IMAGE_FILE,
                    "문서가 가리키는 이미지 파일이 없다: " + documentName));
        }

        Map<String, CategoryDef> categories = readCategories(root.path("categories"));

        List<Shape> shapes = new ArrayList<>();
        Map<String, String> otherTexts = new LinkedHashMap<>();
        String imageDescription = null;
        String privacyIncluded = null;
        String deIdentification = null;

        for (JsonNode annotation : arrayOf(root.path("annotations"))) {
            String categoryId = text(annotation, "category_id", SHORT_TEXT_MAX);
            CategoryDef category = categoryId == null ? null : categories.get(categoryId);
            if (categoryId != null && category == null) {
                warnings.add(warn(ImportWarningCode.UNDECLARED_CATEGORY,
                        "문서가 선언하지 않은 분류가 쓰였다: " + display(categoryId)
                                + " (" + documentName + ")"));
            }
            if (isTextAnnotation(annotation, category)) {
                String value = ExternalNameSanitizer.multilineText(
                        rawText(annotation, "text"), LONG_TEXT_MAX);
                switch (categoryId == null ? "" : categoryId) {
                    case TEXT_IMAGE_DESCRIPTION -> imageDescription = value;
                    case TEXT_PRIVACY_INCLUDED -> privacyIncluded = value;
                    case TEXT_DE_IDENTIFICATION -> deIdentification = value;
                    // 아는 세 가지 밖의 텍스트도 버리지 않는다 — 버리면 되돌릴 수 없다.
                    default -> {
                        if (categoryId != null) {
                            otherTexts.put(categoryId, value);
                        }
                    }
                }
                continue;
            }
            Shape shape = readShape(annotation, categoryId, category, documentName, warnings);
            if (shape != null) {
                shapes.add(shape);
            }
        }

        return new Frame(
                imageFileName,
                imagePath,
                document,
                text(image, "id", SHORT_TEXT_MAX),
                integer(image, "width"),
                integer(image, "height"),
                number(image, "frame_num"),
                dateTime(image, "date_captured", warnings, true),
                ExternalNameSanitizer.multilineText(rawText(image, "description"), LONG_TEXT_MAX),
                text(image, "anonymity", SHORT_TEXT_MAX),
                text(image, "pseudonymity", SHORT_TEXT_MAX),
                text(image, "privacy_included", SHORT_TEXT_MAX),
                new Texts(imageDescription, privacyIncluded, deIdentification, Map.copyOf(otherTexts)),
                List.copyOf(shapes));
    }

    /** 짝 문서가 없는 이미지 — 라벨도 텍스트도 없는 프레임이다(AC-047). */
    private static Frame unpairedFrame(Path image) {
        String name = image.getFileName().toString();
        return new Frame(name, image, null, null, null, null, null, null, null, null, null, null,
                new Texts(null, null, null, Map.of()), List.of());
    }

    /**
     * 문서가 가리키는 이미지를 <b>미리 훑어 둔 목록에서만</b> 찾는다. 이름을 경로로 조립하지 않으므로
     * 폴더 밖을 가리키는 이름으로 파일을 열 수 없다(CWE-22).
     */
    private static Path resolveImage(List<Path> images, String imageFileName) {
        if (imageFileName == null) {
            return null;
        }
        for (Path image : images) {
            if (image.getFileName().toString().equals(imageFileName)) {
                return image;
            }
        }
        // 대소문자만 다른 경우까지는 같은 파일로 본다(윈도우에서 만든 산출물을 리눅스에서 읽는 경우).
        for (Path image : images) {
            if (image.getFileName().toString().equalsIgnoreCase(imageFileName)) {
                return image;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ annotations

    private record CategoryDef(String name, String type) {
    }

    private Map<String, CategoryDef> readCategories(JsonNode categories) {
        Map<String, CategoryDef> map = new LinkedHashMap<>();
        for (JsonNode category : arrayOf(categories)) {
            String id = text(category, "id", SHORT_TEXT_MAX);
            if (id == null) {
                continue;
            }
            map.put(id, new CategoryDef(text(category, "name", SHORT_TEXT_MAX),
                    text(category, "type", SHORT_TEXT_MAX)));
        }
        return map;
    }

    /**
     * 텍스트 항목인가 — <b>문서가 스스로 선언한 종류</b>({@code categories} 의 {@code type})가 1차
     * 판정이다. 선언이 없으면 내용으로 가른다(도형 값이 하나도 없고 텍스트만 있으면 텍스트).
     */
    private static boolean isTextAnnotation(JsonNode annotation, CategoryDef category) {
        if (category != null && category.type() != null) {
            return CATEGORY_TYPE_TEXT.equalsIgnoreCase(category.type());
        }
        boolean hasShape = isPresent(annotation.path("polygon"))
                || isPresent(annotation.path("bbox"))
                || isPresent(annotation.path("keypoints"));
        return !hasShape && isPresent(annotation.path("text"));
    }

    private Shape readShape(JsonNode annotation, String categoryId, CategoryDef category,
                            String documentName, List<Warning> warnings) {
        List<List<Point>> rings = new ArrayList<>();
        JsonNode polygon = annotation.path("polygon");
        if (isPresent(polygon) && polygon.isArray()) {
            for (JsonNode ring : polygon) {
                if (!ring.isArray()) {
                    continue;
                }
                List<Double> flat = new ArrayList<>(ring.size());
                boolean numeric = true;
                for (JsonNode value : ring) {
                    if (!value.isNumber()) {
                        numeric = false;
                        break;
                    }
                    flat.add(value.asDouble());
                }
                if (!numeric) {
                    warnings.add(warn(ImportWarningCode.INVALID_POLYGON,
                            "좌표에 숫자가 아닌 값이 있어 도형 하나를 버린다: " + documentName));
                    return null;
                }
                try {
                    // 산출물은 링마다 좌표를 평면으로 나열한다. 저작도구 정규 형식으로 옮기는 변환은
                    // 이미 있는 단일 지점을 재사용한다(홀수 길이 등 비정상 입력을 거기서 거부).
                    rings.add(List.copyOf(LabelPointSerializer.flatToPoints(flat)));
                } catch (IllegalArgumentException e) {
                    warnings.add(warn(ImportWarningCode.INVALID_POLYGON,
                            "좌표가 짝을 이루지 않아 도형 하나를 버린다: " + documentName));
                    return null;
                }
            }
            if (rings.size() > 1) {
                warnings.add(warn(ImportWarningCode.MULTI_RING_POLYGON,
                        "다각형이 링을 둘 이상 가진다: " + documentName));
            }
        }

        List<Double> bbox = null;
        JsonNode bboxNode = annotation.path("bbox");
        if (isPresent(bboxNode) && bboxNode.isArray()) {
            List<Double> values = new ArrayList<>(bboxNode.size());
            for (JsonNode value : bboxNode) {
                if (value.isNumber()) {
                    values.add(value.asDouble());
                }
            }
            bbox = List.copyOf(values);
            warnings.add(warn(ImportWarningCode.UNRESOLVED_BBOX,
                    "경계상자 해석 규칙이 확정되지 않아 원문 그대로 담는다: " + documentName));
        }

        String keypointsRaw = null;
        JsonNode keypoints = annotation.path("keypoints");
        if (isPresent(keypoints)) {
            keypointsRaw = keypoints.toString();
            warnings.add(warn(ImportWarningCode.UNRESOLVED_KEYPOINTS,
                    "키포인트 해석 규칙이 확정되지 않아 원문 그대로 담는다: " + documentName));
        }

        return new Shape(
                text(annotation, "id", SHORT_TEXT_MAX),
                text(annotation, "image_id", SHORT_TEXT_MAX),
                categoryId,
                category == null ? null : category.name(),
                category == null ? null : category.type(),
                text(annotation, "track_id", SHORT_TEXT_MAX),
                List.copyOf(rings),
                bbox,
                keypointsRaw);
    }

    // ------------------------------------------------------------------ 값 읽기

    private static Iterable<JsonNode> arrayOf(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    /** 값이 실제로 있는가 — 없음과 null 을 한 판정으로 묶는다. */
    private static boolean isPresent(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull();
    }

    /** 정제 전 원문(제어문자 포함 가능) — 여러 줄 텍스트 전용. */
    private static String rawText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return isPresent(value) ? value.asText() : null;
    }

    private static String text(JsonNode node, String field, int maxLength) {
        return ExternalNameSanitizer.text(rawText(node, field), maxLength);
    }

    private static Integer integer(JsonNode node, String field) {
        Long value = number(node, field);
        if (value == null) {
            return null;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }

    /** 숫자 또는 <b>숫자 문자열</b>. 산출물은 같은 뜻의 값을 문자열로도 숫자로도 적는다. */
    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!isPresent(value)) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        String raw = value.asText().trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!isPresent(value)) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        String raw = value.asText().trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 밀리초 단위 표기를 숫자로 읽는다. 단위가 다르거나 읽을 수 없으면 null. */
    private static Long lengthMillis(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = LENGTH_MILLIS.matcher(raw);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime dateTime(JsonNode node, String field, List<Warning> warnings,
                                          boolean report) {
        String raw = text(node, field, SHORT_TEXT_MAX);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, DATE_TIME);
        } catch (DateTimeParseException e) {
            if (report) {
                warnings.add(warn(ImportWarningCode.UNPARSABLE_DATE,
                        "날짜 표기를 읽을 수 없어 비운다: " + display(raw)));
            }
            return null;
        }
    }

    private static Warning warn(String code, String message) {
        return new Warning(code, message);
    }

    /** 경고 메시지에 외부 문자열을 담을 때의 정제 — 로그 위조와 과도한 길이를 함께 막는다(CWE-117). */
    private static String display(String raw) {
        String cleaned = ExternalNameSanitizer.text(raw, MESSAGE_VALUE_MAX);
        return cleaned == null ? "(값 없음)" : cleaned;
    }
}

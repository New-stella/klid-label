package kr.co.cudo.authoring.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CVAT 비디오 manifest.jsonl 포맷 작성기 (Java 포팅).
 * <p>
 * 출처: CVAT (https://github.com/cvat-ai/cvat) utils/dataset_manifest
 *   Copyright (C) 2021-2022 Intel Corporation / CVAT.ai Corporation
 *   SPDX-License-Identifier: MIT
 * <p>
 * 포맷 (docs/analysis/portable-modules/04-manifest-jsonl.md 참조):
 * <pre>
 * {"version":"1.1"}
 * {"type":"video"}
 * {"properties":{"name":"video.mp4","resolution":[1920,1080],"length":300,"chapters":[]}}
 * {"number":0,"pts":0,"checksum":"e0b3..."}
 * ...
 * </pre>
 * <p>
 * 헤더 라인을 먼저 호출(begin)하고, 키프레임을 1건씩 write 한 뒤 close.
 * BufferedWriter 기반 스트리밍 — 메모리 부담 없이 큰 영상도 처리.
 */
public class ManifestJsonlWriter implements Closeable {

    public static final String VERSION = "1.1";
    public static final String TYPE_VIDEO = "video";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Writer writer;
    private boolean headerWritten = false;
    private boolean closed = false;

    public ManifestJsonlWriter(Path manifestFile) throws IOException {
        if (manifestFile == null) {
            throw new IllegalArgumentException("manifestFile must not be null");
        }
        Path parent = manifestFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(manifestFile), StandardCharsets.UTF_8));
    }

    /** 테스트/임의 OutputStream 대상 작성 시 사용. close 시 stream 도 함께 닫힘. */
    public ManifestJsonlWriter(OutputStream out) {
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    /**
     * 비디오 manifest 헤더 3줄 작성.
     *  - {"version":"1.1"}
     *  - {"type":"video"}
     *  - {"properties":{"name":..., "resolution":[w,h], "length":N, "chapters":[]}}
     */
    public void writeVideoHeader(String name, int width, int height, long lengthFrames) throws IOException {
        ensureNotClosed();
        if (headerWritten) {
            throw new IllegalStateException("header already written");
        }
        writeJsonLine(Map.of("version", VERSION));
        writeJsonLine(Map.of("type", TYPE_VIDEO));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", name);
        props.put("resolution", List.of(width, height));
        props.put("length", lengthFrames);
        props.put("chapters", List.of());

        writeJsonLine(Map.of("properties", props));
        headerWritten = true;
    }

    /**
     * 키프레임 1건 작성.
     * - number: 프레임 인덱스 (0-base)
     * - pts: PTS (libavcodec)
     * - checksum: MD5 헥사 (대소문자 무관, 그대로 기록)
     */
    public void writeKeyFrame(long number, long pts, String checksum) throws IOException {
        ensureNotClosed();
        if (!headerWritten) {
            throw new IllegalStateException("header must be written before key frames");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("number", number);
        entry.put("pts", pts);
        entry.put("checksum", checksum);
        writeJsonLine(entry);
    }

    private void writeJsonLine(Object obj) throws IOException {
        try {
            writer.write(MAPPER.writeValueAsString(obj));
        } catch (JsonProcessingException e) {
            throw new IOException("failed to serialize manifest line", e);
        }
        writer.write('\n');
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("writer is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        writer.flush();
        writer.close();
    }
}

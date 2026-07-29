package com.multimodalAgent.agent.service.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文本切块器。
 *
 * <p>优先在换行、句号和英文标点附近切分，减少单个片段语义被截断。</p>
 */
public class KnowledgeChunker {

    public List<String> chunk(String content, int chunkSize, int overlap) {
        String text = content.replace("\r\n", "\n").trim();
        if (text.isBlank()) {
            return List.of();
        }
        int safeSize = Math.max(120, chunkSize);
        int safeOverlap = Math.max(0, Math.min(overlap, safeSize / 2));
        List<Section> sections = sections(text);
        List<String> chunks = new ArrayList<>();
        for (Section section : sections) {
            appendSection(chunks, section, safeSize, safeOverlap);
        }
        return chunks.stream().filter(value -> !value.isBlank()).toList();
    }

    private List<Section> sections(String text) {
        List<Section> sections = new ArrayList<>();
        String heading = "";
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.stripTrailing();
            if (line.stripLeading().startsWith("#")) {
                flushBlock(blocks, block);
                flushSection(sections, heading, blocks);
                heading = line.trim();
                blocks = new ArrayList<>();
            } else if (line.isBlank()) {
                flushBlock(blocks, block);
            } else {
                if (!block.isEmpty()) {
                    block.append('\n');
                }
                block.append(line);
            }
        }
        flushBlock(blocks, block);
        flushSection(sections, heading, blocks);
        if (sections.isEmpty()) {
            sections.add(new Section("", List.of(text)));
        }
        return sections;
    }

    private void appendSection(List<String> chunks, Section section, int maxSize, int overlap) {
        String prefix = section.heading().isBlank() ? "" : section.heading() + "\n\n";
        StringBuilder current = new StringBuilder(prefix);
        for (String block : section.blocks()) {
            if (prefix.length() + block.length() > maxSize) {
                if (current.length() > prefix.length()) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder(prefix);
                }
                List<String> pieces = splitLongBlock(block, Math.max(120, maxSize - prefix.length()), overlap);
                for (int index = 0; index < pieces.size(); index++) {
                    String piece = pieces.get(index);
                    if (index < pieces.size() - 1) {
                        chunks.add((prefix + piece).trim());
                    } else {
                        current.append(piece);
                    }
                }
                continue;
            }
            int separatorLength = current.length() > prefix.length() ? 2 : 0;
            if (current.length() + separatorLength + block.length() > maxSize
                    && current.length() > prefix.length()) {
                chunks.add(current.toString().trim());
                String carry = semanticSuffix(current.substring(prefix.length()), overlap);
                current = new StringBuilder(prefix);
                if (!carry.isBlank()) {
                    current.append(carry).append("\n\n");
                }
            }
            if (current.length() > prefix.length() && !current.toString().endsWith("\n\n")) {
                current.append("\n\n");
            }
            current.append(block);
        }
        if (current.length() > prefix.length()) {
            chunks.add(current.toString().trim());
        }
    }

    private List<String> splitLongBlock(String block, int maxSize, int overlap) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < block.length()) {
            int end = Math.min(block.length(), start + maxSize);
            if (end < block.length()) {
                int boundary = bestBoundary(block, start, end);
                if (boundary > start + maxSize / 2) {
                    end = boundary;
                }
            }
            pieces.add(block.substring(start, end).trim());
            if (end >= block.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return pieces;
    }

    private int bestBoundary(String text, int start, int end) {
        int boundary = -1;
        for (String marker : List.of("\n", "。", "！", "？", ".", "!", "?")) {
            int candidate = text.lastIndexOf(marker, end - 1);
            if (candidate >= start) {
                boundary = Math.max(boundary, candidate + marker.length());
            }
        }
        return boundary;
    }

    private String semanticSuffix(String text, int overlap) {
        if (overlap <= 0 || text.isBlank()) {
            return "";
        }
        int start = Math.max(0, text.length() - overlap);
        int boundary = Integer.MAX_VALUE;
        for (String marker : List.of("\n", "。", "！", "？", ".", "!", "?")) {
            int candidate = text.indexOf(marker, start);
            if (candidate >= 0) {
                boundary = Math.min(boundary, candidate + marker.length());
            }
        }
        if (boundary != Integer.MAX_VALUE && boundary < text.length()) {
            start = boundary;
        }
        return text.substring(start).trim();
    }

    private void flushBlock(List<String> blocks, StringBuilder block) {
        if (!block.isEmpty()) {
            blocks.add(block.toString().trim());
            block.setLength(0);
        }
    }

    private void flushSection(List<Section> sections, String heading, List<String> blocks) {
        if (!heading.isBlank() || !blocks.isEmpty()) {
            sections.add(new Section(heading, List.copyOf(blocks)));
        }
    }

    private record Section(String heading, List<String> blocks) {
    }
}

package com.crall.kokororeader;

import java.util.ArrayList;

/** Pure text normalization and pagination shared by the UI and unit tests. */
final class TextPaginator {
    private TextPaginator() {
    }

    static String normalizeCopiedText(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replace((char) 160, ' ');
        text = text.replace('\t', ' ');
        text = text.replace('\f', ' ');
        text = text.replace((char) 11, ' ');
        text = text.replaceAll("(?m)-\\n(?=\\p{Ll})", "");
        text = text.trim();
        if (text.isEmpty()) {
            return "";
        }

        String[] paragraphs = text.split("\\n\\s*\\n+");
        StringBuilder out = new StringBuilder();
        for (String paragraph : paragraphs) {
            String normalized = paragraph
                    .replaceAll("\\s*\\n\\s*", " ")
                    .replaceAll(" {2,}", " ")
                    .trim();
            if (!normalized.isEmpty()) {
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                out.append(normalized);
            }
        }
        return out.toString();
    }

    static ArrayList<String> splitIntoPages(String text, int requestedMaxChars) {
        ArrayList<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        int maxChars = Math.max(1, requestedMaxChars);
        String[] paragraphs = text.split("\\n\\n+");
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String normalized = paragraph.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.length() > maxChars) {
                flushPage(result, current);
                result.addAll(splitLongParagraph(normalized, maxChars));
            } else if (current.length() > 0
                    && current.length() + normalized.length() + 2 > maxChars) {
                flushPage(result, current);
                current.append(normalized);
            } else {
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(normalized);
            }
        }
        flushPage(result, current);
        return result;
    }

    private static ArrayList<String> splitLongParagraph(String paragraph, int maxChars) {
        ArrayList<String> result = new ArrayList<>();
        String[] sentences = paragraph.split("(?<=[.!?])\\s+");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String normalized = sentence.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.length() > maxChars) {
                flushPage(result, current);
                hardSplit(result, normalized, maxChars);
            } else if (current.length() > 0
                    && current.length() + normalized.length() + 1 > maxChars) {
                flushPage(result, current);
                current.append(normalized);
            } else {
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(normalized);
            }
        }
        flushPage(result, current);
        return result;
    }

    private static void hardSplit(ArrayList<String> result, String text, int maxChars) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int space = text.lastIndexOf(' ', end);
                if (space > start + maxChars / 2) {
                    end = space;
                }
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                result.add(piece);
            }
            start = Math.max(end, start + 1);
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
    }

    private static void flushPage(ArrayList<String> result, StringBuilder current) {
        String page = current.toString().trim();
        if (!page.isEmpty()) {
            result.add(page);
        }
        current.setLength(0);
    }
}

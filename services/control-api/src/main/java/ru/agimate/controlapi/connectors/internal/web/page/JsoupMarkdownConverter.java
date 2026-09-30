package ru.agimate.controlapi.connectors.internal.web.page;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Markdown for a model to read, not for a renderer to round-trip: structure (headings, lists,
 * quotes, code, tables) and absolute links survive, pictures leave only their {@code alt}, and
 * nothing is escaped — an asterisk in the text is just an asterisk.
 */
@Component
public class JsoupMarkdownConverter implements MarkdownConverter {

    private static final int MAX_URL_LENGTH = 300;

    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "section", "article", "main", "header", "footer", "aside", "nav",
            "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "pre", "table",
            "hr", "figure", "figcaption", "dl", "dt", "dd", "address", "details", "summary",
            "center", "fieldset", "form", "body");

    @Override
    public String convert(Element root) {
        return normalize(String.join("\n\n", blocks(root)));
    }

    /** A container's children: runs of inline content become paragraphs, block children speak for themselves. */
    private List<String> blocks(Element container) {
        List<String> blocks = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (Node child : container.childNodes()) {
            if (child instanceof Element element && isBlock(element)) {
                flush(run, blocks);
                blocks.addAll(block(element));
            } else {
                inline(child, run);
            }
        }
        flush(run, blocks);
        return blocks;
    }

    private List<String> block(Element element) {
        String tag = element.normalName();
        return switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                String text = inlineText(element);
                yield text.isEmpty() ? List.of() : List.of("#".repeat(tag.charAt(1) - '0') + " " + text);
            }
            case "p", "dt", "dd", "figcaption", "summary" -> {
                String text = inlineText(element);
                yield text.isEmpty() ? List.of() : List.of(text);
            }
            case "ul", "ol" -> {
                String list = list(element, "");
                yield list.isEmpty() ? List.of() : List.of(list);
            }
            case "blockquote" -> {
                String inner = String.join("\n\n", blocks(element));
                yield inner.isBlank() ? List.of() : List.of(prefixLines(inner, "> "));
            }
            case "pre" -> List.of(codeBlock(element));
            case "table" -> table(element);
            case "hr" -> List.of("---");
            default -> blocks(element);
        };
    }

    private String list(Element list, String indent) {
        boolean ordered = list.normalName().equals("ol");
        int number = ordered ? parseStart(list.attr("start")) : 0;
        List<String> lines = new ArrayList<>();
        for (Element item : list.children()) {
            if (!item.normalName().equals("li")) {
                continue;
            }
            String marker = ordered ? (number++) + ". " : "- ";
            StringBuilder text = new StringBuilder();
            List<Element> nested = new ArrayList<>();
            for (Node child : item.childNodes()) {
                if (child instanceof Element element && (element.normalName().equals("ul") || element.normalName().equals("ol"))) {
                    nested.add(element);
                } else {
                    inline(child, text);
                    if (child instanceof Element element && isBlock(element)) {
                        text.append(' ');
                    }
                }
            }
            String line = clean(text.toString()).replace("\n", " ");
            if (!line.isEmpty()) {
                lines.add(indent + marker + line);
            }
            for (Element sub : nested) {
                String subList = list(sub, indent + " ".repeat(marker.length()));
                if (!subList.isEmpty()) {
                    lines.add(subList);
                }
            }
        }
        return String.join("\n", lines);
    }

    private static int parseStart(String start) {
        try {
            return start.isBlank() ? 1 : Integer.parseInt(start.strip());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String codeBlock(Element pre) {
        Element code = pre.selectFirst("code");
        String language = "";
        if (code != null) {
            for (String name : code.classNames()) {
                if (name.startsWith("language-")) {
                    language = name.substring("language-".length());
                }
            }
        }
        String text = pre.wholeText().replaceAll("^\\n+|\\s+$", "");
        return "```" + language + "\n" + text + "\n```";
    }

    /**
     * GFM when the grid is plain; merged cells or blocks inside cells do not fit a pipe table, so
     * such a table goes out row by row. A one-column table is layout, not data.
     */
    private List<String> table(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element row : table.select("tr")) {
            if (row.closest("table") == table) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return blocks(table);
        }
        List<List<Element>> grid = new ArrayList<>();
        int columns = 0;
        boolean plain = true;
        for (Element row : rows) {
            List<Element> cells = new ArrayList<>();
            for (Element cell : row.children()) {
                if (cell.normalName().equals("td") || cell.normalName().equals("th")) {
                    cells.add(cell);
                    plain &= span(cell, "colspan") == 1 && span(cell, "rowspan") == 1
                            && cell.selectFirst("table, ul, ol, pre, blockquote") == null;
                }
            }
            columns = Math.max(columns, cells.size());
            grid.add(cells);
        }
        if (columns <= 1) {
            List<String> blocks = new ArrayList<>();
            for (List<Element> cells : grid) {
                for (Element cell : cells) {
                    blocks.addAll(blocks(cell));
                }
            }
            return blocks;
        }
        List<String> lines = new ArrayList<>();
        for (int r = 0; r < grid.size(); r++) {
            List<String> texts = new ArrayList<>();
            for (Element cell : grid.get(r)) {
                texts.add(inlineText(cell).replace("\n", " ").replace("|", "\\|"));
            }
            if (texts.stream().allMatch(String::isEmpty)) {
                continue;
            }
            if (!plain) {
                lines.add(String.join(" | ", texts));
                continue;
            }
            while (texts.size() < columns) {
                texts.add("");
            }
            lines.add("| " + String.join(" | ", texts) + " |");
            if (lines.size() == 1) {
                lines.add("|" + " --- |".repeat(columns));
            }
        }
        return lines.isEmpty() ? List.of() : List.of(String.join("\n", lines));
    }

    private static int span(Element cell, String attribute) {
        try {
            return cell.hasAttr(attribute) ? Integer.parseInt(cell.attr(attribute).strip()) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void inline(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            out.append(text.getWholeText().replaceAll("\\s+", " "));
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        switch (element.normalName()) {
            case "br" -> out.append('\n');
            case "a" -> link(element, out);
            case "strong", "b" -> wrap(element, "**", out);
            case "em", "i" -> wrap(element, "*", out);
            case "code", "kbd", "samp" -> {
                String code = element.text().strip();
                if (!code.isEmpty()) {
                    out.append('`').append(code).append('`');
                }
            }
            case "img" -> {
                String alt = element.attr("alt").strip();
                if (!alt.isEmpty()) {
                    out.append(alt);
                }
            }
            default -> {
                if (isBlock(element)) {
                    out.append(' ');
                }
                for (Node child : element.childNodes()) {
                    inline(child, out);
                }
                if (isBlock(element)) {
                    out.append(' ');
                }
            }
        }
    }

    /**
     * In-page anchors and script links carry nothing a reader can follow — only their text stays. So
     * do very long URLs: those are tracking redirects, and one costs as many tokens as a paragraph.
     */
    private void link(Element anchor, StringBuilder out) {
        String text = inlineText(anchor).replace("\n", " ");
        if (text.isEmpty()) {
            return;
        }
        String href = anchor.attr("href").strip();
        String url = anchor.absUrl("href");
        if (href.startsWith("#") || url.isEmpty() || url.length() > MAX_URL_LENGTH
                || url.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
            out.append(text);
        } else if (text.equals(url)) {
            out.append(url);
        } else {
            out.append('[').append(text).append("](").append(url).append(')');
        }
    }

    private void wrap(Element element, String mark, StringBuilder out) {
        String inner = inlineText(element);
        if (!inner.isEmpty()) {
            out.append(mark).append(inner).append(mark);
        }
    }

    private String inlineText(Element element) {
        StringBuilder out = new StringBuilder();
        for (Node child : element.childNodes()) {
            inline(child, out);
        }
        return clean(out.toString());
    }

    private static void flush(StringBuilder run, List<String> blocks) {
        String text = clean(run.toString());
        if (!text.isEmpty()) {
            blocks.add(text);
        }
        run.setLength(0);
    }

    private static boolean isBlock(Element element) {
        return BLOCK_TAGS.contains(element.normalName());
    }

    /** Spaces collapsed and trimmed per line — {@code <br>} is the only line break inline text keeps. */
    private static String clean(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.replaceAll("[ \\t\\u00a0]+", " ").strip();
            if (!trimmed.isEmpty()) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(trimmed);
            }
        }
        return out.toString();
    }

    private static String prefixLines(String text, String prefix) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line.isEmpty() ? prefix.strip() : prefix + line);
        }
        return out.toString();
    }

    private static String normalize(String markdown) {
        return markdown.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }
}

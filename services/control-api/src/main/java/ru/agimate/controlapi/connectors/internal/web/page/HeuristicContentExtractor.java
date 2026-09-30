package ru.agimate.controlapi.connectors.internal.web.page;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Main text by structure first ({@code <article>}, {@code <main>}), by text density otherwise — the
 * readability idea cut down to what Russian news, blogs and shop pages need. Replaceable as a whole
 * (Readability4J works on the same jsoup types), see docs/decisions/web-search.md.
 */
@Component
public class HeuristicContentExtractor implements ContentExtractor {

    /** Less than this is a stub, a cookie banner or a JavaScript shell — not a text worth reading. */
    static final int MIN_TEXT = 200;
    private static final int MAX_JSON_LD = 3000;

    private static final String NOISE_TAGS = "script, style, noscript, template, svg, canvas, iframe, "
            + "nav, aside, form, button, select, input, dialog, [hidden], [aria-hidden=true], "
            + "[role=navigation], [role=banner], [role=contentinfo], [role=complementary], [role=dialog]";

    /** Class and id words of page chrome; matched as whole words, so {@code header-ad} hits and {@code heading} does not. */
    private static final Set<String> NOISE_WORDS = Set.of(
            "comment", "comments", "share", "sharing", "social", "related", "recommended", "advert",
            "advertisement", "ads", "banner", "cookie", "cookies", "popup", "modal", "subscribe",
            "subscription", "breadcrumb", "breadcrumbs", "sidebar", "navbar", "menu", "promo");

    private static final String CONTENT_ROOTS = "article, main, [role=main], [itemprop=articleBody]";

    /** Their JSON-LD repeats the text; only the date and the author are worth lifting out of it. */
    private static final Set<String> ARTICLE_TYPES = Set.of(
            "Article", "NewsArticle", "BlogPosting", "Report", "ScholarlyArticle", "TechArticle");
    /** Describe the site, not the page. */
    private static final Set<String> CHROME_TYPES = Set.of(
            "WebSite", "WebPage", "Organization", "NewsMediaOrganization", "BreadcrumbList",
            "SiteNavigationElement", "ImageObject", "VideoObject", "SearchAction", "Person");
    /** Pictures, links and text copies: long and useless to a reader of the data. */
    private static final Set<String> JSON_LD_NOISE_FIELDS = Set.of(
            "@context", "@id", "image", "logo", "thumbnailUrl", "associatedMedia", "sameAs", "url",
            "mainEntityOfPage", "potentialAction", "publisher", "copyrightHolder", "articleBody", "text",
            "review", "reviewBody");

    @Override
    public ExtractedContent extract(Document document) {
        Document page = document.clone();
        String title = title(page);
        Map<String, String> metadata = metadata(page);

        Element body = page.body();
        stripNoise(body);
        Element main = mainElement(body);
        if (main.text().length() < MIN_TEXT) {
            main = body.text().length() < MIN_TEXT ? null : body;
        }
        return new ExtractedContent(title, main, metadata);
    }

    private static String title(Document page) {
        String title = page.title().strip();
        if (title.isEmpty()) {
            title = page.select("meta[property=og:title]").attr("content").strip();
        }
        if (title.isEmpty()) {
            Element h1 = page.selectFirst("h1");
            title = h1 == null ? "" : h1.text().strip();
        }
        return title.isEmpty() ? null : title;
    }

    private static Map<String, String> metadata(Document page) {
        Map<String, String> metadata = new LinkedHashMap<>();
        put(metadata, "description", page.select("meta[name=description]").attr("content"));
        put(metadata, "author", page.select("meta[name=author]").attr("content"));
        for (Element meta : page.select("meta[property~=^(og|article|product):]")) {
            String property = meta.attr("property");
            if (property.startsWith("og:image") || property.startsWith("og:video")
                    || property.equals("og:title") || property.equals("og:url") || property.equals("og:locale")) {
                continue;
            }
            put(metadata, property, meta.attr("content"));
        }
        if (metadata.get("og:description") != null
                && metadata.get("og:description").equals(metadata.get("description"))) {
            metadata.remove("og:description");
        }
        List<JsonNode> objects = new ArrayList<>();
        for (Element script : page.select("script[type=application/ld+json]")) {
            JsonNode node = JsonUtils.toJsonNodeOrNull(script.data().strip());
            if (node != null) {
                collectObjects(node, objects);
            }
        }
        int n = 0;
        for (JsonNode object : objects) {
            JsonNode typeNode = object.path("@type");
            String type = (typeNode.isArray() ? typeNode.path(0) : typeNode).asText("");
            if (ARTICLE_TYPES.contains(type)) {
                put(metadata, "published", object.path("datePublished").asText(""));
                put(metadata, "modified", object.path("dateModified").asText(""));
                put(metadata, "author", authorName(object.path("author")));
            } else if (!CHROME_TYPES.contains(type)) {
                String json = compact((ObjectNode) object);
                if (json != null) {
                    metadata.put(++n == 1 ? "json-ld" : "json-ld " + n, json);
                }
            }
        }
        return metadata;
    }

    private static void put(Map<String, String> metadata, String key, String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        if (!clean.isEmpty()) {
            metadata.putIfAbsent(key, clean);
        }
    }

    /** A block is an object, an array of them, or a {@code @graph}; a broken block is the page's bug and is skipped. */
    private static void collectObjects(JsonNode node, List<JsonNode> out) {
        if (node.isArray()) {
            node.forEach(item -> collectObjects(item, out));
        } else if (node.isObject()) {
            if (node.has("@graph")) {
                collectObjects(node.get("@graph"), out);
            } else {
                out.add(node);
            }
        }
    }

    private static String authorName(JsonNode author) {
        JsonNode first = author.isArray() ? author.path(0) : author;
        return first.isTextual() ? first.asText() : first.path("name").asText("");
    }

    /** Without pictures, links and the fields that repeat the page's text — what is left is the data. */
    private static String compact(ObjectNode object) {
        ObjectNode copy = object.deepCopy();
        dropNoise(copy);
        if (copy.size() <= 1) {
            return null;
        }
        String json = copy.toString();
        return json.length() > MAX_JSON_LD ? json.substring(0, MAX_JSON_LD) + "…" : json;
    }

    private static void dropNoise(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(JSON_LD_NOISE_FIELDS);
            object.forEach(HeuristicContentExtractor::dropNoise);
        } else if (node instanceof ArrayNode array) {
            array.forEach(HeuristicContentExtractor::dropNoise);
        }
    }

    private static void stripNoise(Element body) {
        body.select(NOISE_TAGS).remove();
        // A header or footer inside the article carries its title and date; only the page's own go.
        for (Element element : body.select("header, footer")) {
            if (element.closest("article") == null) {
                element.remove();
            }
        }
        for (Element element : body.select("[class], [id]")) {
            if (element.parent() != null && isNoise(element) && element.selectFirst(CONTENT_ROOTS) == null
                    && !element.is(CONTENT_ROOTS)) {
                element.remove();
            }
        }
        body.select("[style~=(?i)display:\\s*none]").remove();
    }

    private static boolean isNoise(Element element) {
        String names = (element.className() + " " + element.id()).toLowerCase(Locale.ROOT);
        for (String word : names.split("[^a-z]+")) {
            if (NOISE_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static Element mainElement(Element body) {
        var articles = body.select("article");
        if (articles.size() == 1 && articles.first().text().length() >= MIN_TEXT) {
            return articles.first();
        }
        Element marked = body.selectFirst("[itemprop=articleBody], main, [role=main]");
        if (marked != null && marked.text().length() >= MIN_TEXT) {
            return marked;
        }
        return densest(body);
    }

    /**
     * Readability's scoring: each paragraph votes for its parent and, at half weight, its
     * grandparent; the winner is discounted by how much of its text is links.
     */
    private static Element densest(Element body) {
        Map<Element, Double> scores = new IdentityHashMap<>();
        for (Element paragraph : body.select("p, pre, td")) {
            String text = paragraph.text();
            if (text.length() < 25) {
                continue;
            }
            double score = 1 + text.chars().filter(c -> c == ',').count() + Math.min(text.length() / 100.0, 3);
            Element parent = paragraph.parent();
            if (parent == null) {
                continue;
            }
            scores.merge(parent, score, Double::sum);
            if (parent.parent() != null) {
                scores.merge(parent.parent(), score / 2, Double::sum);
            }
        }
        Element best = body;
        double bestScore = 0;
        for (Map.Entry<Element, Double> entry : scores.entrySet()) {
            double score = entry.getValue() * (1 - linkDensity(entry.getKey()));
            if (score > bestScore) {
                best = entry.getKey();
                bestScore = score;
            }
        }
        return best;
    }

    private static double linkDensity(Element element) {
        int total = element.text().length();
        if (total == 0) {
            return 1;
        }
        int links = 0;
        for (Element link : element.select("a")) {
            links += link.text().length();
        }
        return Math.min(1, (double) links / total);
    }
}

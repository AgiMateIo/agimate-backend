package ru.agimate.controlapi.connectors.internal.web.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.web.WebProperties;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Yandex Search API v2, the synchronous call. The answer is the classic Yandex XML, base64-encoded
 * inside a JSON envelope.
 */
@Slf4j
@Component
public class YandexSearchBackend implements WebSearchBackend {

    private static final String ENDPOINT = "https://searchapi.api.cloud.yandex.net/v2/web/search";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** «Nothing found» is reported as an error of the response, not as an empty result list. */
    private static final String NOTHING_FOUND = "15";

    private final WebProperties properties;
    private final RestClient client;

    public YandexSearchBackend(WebProperties properties, PublicOnlyHttp http) {
        this.properties = properties;
        this.client = http.restClient(TIMEOUT).build();
    }

    @Override
    public List<WebSearchHit> search(WebSearchQuery query) {
        WebProperties.Yandex yandex = properties.getYandex();
        if (!yandex.configured()) {
            throw new ConnectorException("Web search is not configured by the operator of this installation. "
                    + "Do not retry it; web_fetch still works for a known URL.");
        }
        // int64 fields travel as strings: that is how the API's proto-JSON spells them.
        Map<String, Object> body = Map.of(
                "query", Map.of(
                        "searchType", "SEARCH_TYPE_RU",
                        "queryText", query.text(),
                        "familyMode", "FAMILY_MODE_MODERATE",
                        "page", "0"),
                "groupSpec", Map.of(
                        "groupMode", "GROUP_MODE_DEEP",
                        "groupsOnPage", String.valueOf(query.limit()),
                        "docsInGroup", "1"),
                "maxPassages", "2",
                "region", String.valueOf(query.region()),
                "l10n", "LOCALIZATION_RU",
                "folderId", yandex.getFolderId(),
                "responseFormat", "FORMAT_XML");
        Map<?, ?> response;
        try {
            response = client.post()
                    .uri(ENDPOINT)
                    .header("Authorization", "Api-Key " + yandex.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            log.warn("Yandex Search API answered {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ConnectorException("Search provider failed with HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            log.warn("Yandex Search API unreachable: {}", e.getMessage());
            throw new ConnectorException("Search provider is unreachable, try again later");
        }
        Object rawData = response == null ? null : response.get("rawData");
        if (!(rawData instanceof String encoded)) {
            throw new ConnectorException("Search provider returned an empty answer");
        }
        return parse(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8), query.limit());
    }

    static List<WebSearchHit> parse(String xml, int limit) {
        Document document = parseXml(xml);
        NodeList errors = document.getElementsByTagName("error");
        if (errors.getLength() > 0) {
            Element error = (Element) errors.item(0);
            if (NOTHING_FOUND.equals(error.getAttribute("code"))) {
                return List.of();
            }
            throw new ConnectorException("Search provider error " + error.getAttribute("code")
                    + ": " + text(error));
        }
        List<WebSearchHit> hits = new ArrayList<>();
        NodeList docs = document.getElementsByTagName("doc");
        for (int i = 0; i < docs.getLength() && hits.size() < limit; i++) {
            Element doc = (Element) docs.item(i);
            String url = childText(doc, "url");
            if (url == null) {
                continue;
            }
            hits.add(new WebSearchHit(childText(doc, "title"), url, snippet(doc), date(childText(doc, "modtime"))));
        }
        return hits;
    }

    /** Passages are the query-dependent excerpts; the headline is the page's own description, a fallback. */
    private static String snippet(Element doc) {
        List<String> passages = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("passage");
        for (int i = 0; i < nodes.getLength(); i++) {
            String passage = text((Element) nodes.item(i));
            if (!passage.isEmpty()) {
                passages.add(passage);
            }
        }
        return passages.isEmpty() ? childText(doc, "headline") : String.join(" … ", passages);
    }

    /** {@code 20240115T123456} → {@code 2024-01-15}. */
    private static String date(String modtime) {
        if (modtime == null || modtime.length() < 8) {
            return null;
        }
        return modtime.substring(0, 4) + "-" + modtime.substring(4, 6) + "-" + modtime.substring(6, 8);
    }

    private static String childText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = text((Element) nodes.item(0));
        return text.isEmpty() ? null : text;
    }

    /** Text content flattens {@code <hlword>} highlighting away. */
    private static String text(Element element) {
        return element.getTextContent().replaceAll("\\s+", " ").strip();
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new ConnectorException("Search provider returned malformed XML");
        }
    }
}

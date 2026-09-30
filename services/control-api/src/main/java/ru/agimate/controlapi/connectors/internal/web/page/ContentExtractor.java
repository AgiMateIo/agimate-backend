package ru.agimate.controlapi.connectors.internal.web.page;

import org.jsoup.nodes.Document;

/**
 * Separates a page's main text from its chrome. A pure function: no I/O, and the document passed in
 * is left as it was.
 */
public interface ContentExtractor {

    ExtractedContent extract(Document document);
}

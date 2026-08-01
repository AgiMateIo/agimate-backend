package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed {@code WWW-Authenticate} challenge (RFC 9110 §11.6.1). The header is structural, not a
 * comma-separated list: parameter values are quoted, quoted values legitimately contain commas, and
 * one header may carry several challenges — so {@code split(",")} produces garbage on exactly the
 * inputs that matter.
 *
 * <p>It arrives both on 401 (authorisation needed) and on 403 with {@code error="insufficient_scope"},
 * and those are different branches.
 *
 * @param scheme     the auth scheme, lowercased ({@code bearer})
 * @param parameters auth-params, keys lowercased; values already unquoted
 */
public record WwwAuthenticate(String scheme, Map<String, String> parameters) {

    public static final String BEARER = "bearer";

    public Optional<String> parameter(String name) {
        String value = parameters.get(name.toLowerCase(Locale.ROOT));
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /** The first {@code Bearer} challenge across all header values, if any. */
    public static Optional<WwwAuthenticate> bearer(List<String> headerValues) {
        if (headerValues == null) {
            return Optional.empty();
        }
        return headerValues.stream()
                .flatMap(value -> parse(value).stream())
                .filter(challenge -> BEARER.equals(challenge.scheme()))
                .findFirst();
    }

    /**
     * Splits one header value into challenges. A token followed by another token (rather than by
     * {@code name=value}) starts a new challenge — that is the only signal the grammar gives.
     */
    public static List<WwwAuthenticate> parse(String headerValue) {
        List<WwwAuthenticate> challenges = new ArrayList<>();
        if (headerValue == null || headerValue.isBlank()) {
            return challenges;
        }

        String scheme = null;
        Map<String, String> parameters = new LinkedHashMap<>();
        int index = 0;
        int length = headerValue.length();

        while (index < length) {
            while (index < length && (headerValue.charAt(index) == ' ' || headerValue.charAt(index) == ',')) {
                index++;
            }
            if (index >= length) {
                break;
            }

            int tokenStart = index;
            while (index < length && !isDelimiter(headerValue.charAt(index))) {
                index++;
            }
            String token = headerValue.substring(tokenStart, index);
            if (token.isEmpty()) {
                index++;
                continue;
            }

            int afterToken = index;
            while (afterToken < length && headerValue.charAt(afterToken) == ' ') {
                afterToken++;
            }

            if (afterToken < length && headerValue.charAt(afterToken) == '=') {
                index = afterToken + 1;
                Value value = readValue(headerValue, index);
                index = value.next();
                if (scheme != null) {
                    parameters.put(token.toLowerCase(Locale.ROOT), value.text());
                }
            } else {
                if (scheme != null) {
                    challenges.add(new WwwAuthenticate(scheme, Map.copyOf(parameters)));
                    parameters = new LinkedHashMap<>();
                }
                scheme = token.toLowerCase(Locale.ROOT);
            }
        }

        if (scheme != null) {
            challenges.add(new WwwAuthenticate(scheme, Map.copyOf(parameters)));
        }
        return challenges;
    }

    private record Value(String text, int next) {}

    private static Value readValue(String source, int from) {
        int index = from;
        while (index < source.length() && source.charAt(index) == ' ') {
            index++;
        }
        if (index < source.length() && source.charAt(index) == '"') {
            StringBuilder text = new StringBuilder();
            index++;
            while (index < source.length()) {
                char symbol = source.charAt(index);
                if (symbol == '\\' && index + 1 < source.length()) {
                    text.append(source.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (symbol == '"') {
                    index++;
                    break;
                }
                text.append(symbol);
                index++;
            }
            return new Value(text.toString(), index);
        }
        int start = index;
        while (index < source.length() && source.charAt(index) != ',' && source.charAt(index) != ' ') {
            index++;
        }
        return new Value(source.substring(start, index), index);
    }

    private static boolean isDelimiter(char symbol) {
        return symbol == ' ' || symbol == ',' || symbol == '=';
    }
}

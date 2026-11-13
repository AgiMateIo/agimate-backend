package ru.agimate.controlapi.service.seed;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.model.PresetFieldOverride;
import ru.agimate.controlapi.database.model.PresetHeaderField;
import ru.agimate.controlapi.database.model.PresetHeaderGroup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads {@code seed/connectors/<connectorCode>/*.json} — the copied server files — and reduces each
 * to the columns the catalogue stores. The probe inputs that only compute availability stay here:
 * they are read once and not persisted.
 *
 * <p>The reader is deliberately tolerant of unknown JSON fields: the source repository carries more
 * than the catalogue uses, and ignoring it is cheaper than a schema negotiation. Malformed input,
 * duplicate identity and an unknown category are errors: the files ship inside the jar, so a bad edit
 * is ours and must fail the build or the startup loudly.
 */
@Slf4j
@Component
public class ConnectorPresetSeedReader {

    static final String SEED_ROOT = "seed/connectors";
    private static final String DEFAULT_NAME = "Bearer token";

    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

    /** The eight dataset categories, mapped to the existing taxonomy. A seed override may change one. */
    private static final Map<String, ContentCategory> DEFAULT_CATEGORIES = Map.of(
            "dev-tools", ContentCategory.DEVELOPMENT,
            "productivity", ContentCategory.WORK,
            "data", ContentCategory.WORK,
            "payments", ContentCategory.FINANCE,
            "search", ContentCategory.WORK,
            "design", ContentCategory.CONTENT,
            "crm", ContentCategory.WORK,
            "knowledge", ContentCategory.LEARNING);

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /** All seed files, sorted by code; empty when the directory has not been populated yet. */
    public List<ConnectorPresetSeed> readAll() {
        PresetOverrides overrides = readOverrides();
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath*:" + SEED_ROOT + "/*/*.json");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list " + SEED_ROOT, e);
        }
        if (resources.length == 0) {
            log.warn("No connector preset seed files under {}; the catalogue stays empty", SEED_ROOT);
            return List.of();
        }

        List<ConnectorPresetSeed> seeds = new ArrayList<>(resources.length);
        Set<String> codes = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (Resource resource : resources) {
            String[] location = location(resource);
            ConnectorPresetSeed seed = read(resource, location[0], location[1], overrides);
            if (!codes.add(seed.code())) {
                throw new IllegalStateException("Duplicate preset code '" + seed.code() + "' in " + SEED_ROOT);
            }
            if (!identities.add(seed.connectorCode() + "\u0000" + seed.identity())) {
                throw new IllegalStateException("Duplicate identity '" + seed.identity()
                        + "' for connector '" + seed.connectorCode() + "'");
            }
            seeds.add(seed);
        }
        seeds.sort(Comparator.comparing(ConnectorPresetSeed::code));
        validateOverrideCodes(codes, overrides);
        return seeds;
    }

    /** Package-private seam: tests pass a {@link Resource} and the location explicitly. */
    ConnectorPresetSeed read(Resource resource, String connectorCode, String code, PresetOverrides overrides) {
        String where = SEED_ROOT + "/" + connectorCode + "/" + code + ".json";
        if (!CODE.matcher(code).matches()) {
            throw new IllegalStateException(where + ": code must match " + CODE.pattern());
        }

        JsonNode root = readTree(resource, where);
        String name = requiredText(root, "name", where);
        String url = requiredText(root, "url", where);
        JsonNode declared = root.path("declared");
        JsonNode probe = root.path("probe");

        String description = text(root, "description");
        String nameRu = text(root, "nameRu");
        String descriptionRu = text(root, "descriptionRu");
        String iconUrl = text(root, "iconUrl");
        String docsUrl = text(root, "docsUrl");

        ContentCategory category = category(requiredText(root, "category", where), overrides, where);
        String jurisdiction = text(root.path("jurisdiction"), "country");
        if (jurisdiction != null && !COUNTRY.matcher(jurisdiction).matches()) {
            throw new IllegalStateException(where + ": jurisdiction must be an ISO 3166-1 alpha-2 code");
        }

        List<RawHeader> headers = parseHeaders(declared.path("headers"), where);
        List<RawGroup> groups = parseGroups(declared.path("headerGroups"), where);
        PresetOverride override = overrides.preset(code);

        List<ConnectorPresetAuth> authMethods = authMethods(declared, probe, headers, override, where);
        Form form = form(headers, groups, authMethods, override, where);
        validateForm(authMethods, form, headers, where);

        String endpoint = endpoint(url, probe, where);
        String status = requiredText(probe, "status", where);
        String probeTransport = text(probe, "transport");
        String declaredTransport = text(declared, "transport");
        String transport = probeTransport != null ? probeTransport : declaredTransport;
        String transportSource = transportSource(probe, probeTransport, declaredTransport);
        boolean legacySse = legacySse(url);
        Boolean handshake = handshake(probe);

        return new ConnectorPresetSeed(
                code,
                connectorCode,
                name,
                description,
                nameRu,
                descriptionRu,
                iconUrl,
                category,
                jurisdiction,
                docsUrl,
                authMethods,
                PresetAvailabilityPolicy.evaluate(status, transport, transportSource, legacySse,
                        handshake, !authMethods.isEmpty()),
                Map.of("url", endpoint),
                endpoint.trim(),
                form.fields(),
                form.headerFields(),
                form.headerGroups());
    }

    private static JsonNode readTree(Resource resource, String where) {
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = JsonUtils.MAPPER.readTree(in);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException(where + ": expected a JSON object");
            }
            return root;
        } catch (IOException e) {
            throw new IllegalStateException(where + ": cannot read JSON: " + e.getMessage(), e);
        }
    }

    private static List<ConnectorPresetAuth> authMethods(JsonNode declared, JsonNode probe,
                                                        List<RawHeader> headers, PresetOverride override,
                                                        String where) {
        List<ConnectorPresetAuth> methods = override != null && override.authMethods() != null
                ? override.authMethods()
                : parseAuthMethods(declared.path("authMethods"), where);
        if (methods.isEmpty()) {
            ConnectorPresetAuth primary = primaryAuth(probe, headers);
            methods = primary == null ? List.of() : List.of(primary);
        }
        boolean cimd = bool(probe.path("authDetails"), "clientIdMetadataDocumentSupported", false);
        return methods.stream()
                .filter(method -> method != ConnectorPresetAuth.OAUTH || cimd)
                .distinct()
                .toList();
    }

    private static ConnectorPresetAuth primaryAuth(JsonNode probe, List<RawHeader> headers) {
        String authType = text(probe, "authType");
        if (authType != null && authType.startsWith("oauth2.1")) {
            return ConnectorPresetAuth.OAUTH;
        }
        boolean secretHeader = headers.stream().anyMatch(header ->
                header.name().equalsIgnoreCase("Authorization") || (header.required() && header.secret()));
        if (secretHeader) {
            return ConnectorPresetAuth.TOKEN;
        }
        if ("api_key_or_bearer".equalsIgnoreCase(authType)) {
            return ConnectorPresetAuth.TOKEN;
        }
        if ("none".equalsIgnoreCase(authType)) {
            return ConnectorPresetAuth.NONE;
        }
        return null;
    }

    private static List<ConnectorPresetAuth> parseAuthMethods(JsonNode array, String where) {
        if (array == null || array.isMissingNode() || array.isNull()) {
            return List.of();
        }
        if (!array.isArray()) {
            throw new IllegalStateException(where + ": declared.authMethods must be an array");
        }
        List<ConnectorPresetAuth> methods = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            methods.add(parseAuthMethod(item.asText(), where));
        }
        return methods;
    }

    private static ConnectorPresetAuth parseAuthMethod(String value, String where) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "oauth", "oauth2.1" -> ConnectorPresetAuth.OAUTH;
            case "token", "api_key", "api-key" -> ConnectorPresetAuth.TOKEN;
            case "none" -> ConnectorPresetAuth.NONE;
            default -> throw new IllegalStateException(where + ": unknown auth method '" + value + "'");
        };
    }

    private static Form form(List<RawHeader> headers, List<RawGroup> groups,
                             List<ConnectorPresetAuth> authMethods, PresetOverride override, String where) {
        boolean token = authMethods.contains(ConnectorPresetAuth.TOKEN);
        boolean oauthOnly = authMethods.size() == 1 && authMethods.contains(ConnectorPresetAuth.OAUTH);

        RawHeader bearer = null;
        List<PresetHeaderField> headerFields = new ArrayList<>();
        if (override != null && override.headerFields() != null) {
            headerFields.addAll(override.headerFields());
        } else {
            for (RawHeader header : headers) {
                boolean authorization = header.name().equalsIgnoreCase("Authorization");
                if (authorization && token && isBearer(header.valuePrefix()) && !hasText(header.keyPrefix())) {
                    if (bearer == null) {
                        bearer = header;
                    }
                    continue;
                }
                if (authorization && oauthOnly) {
                    continue;
                }
                headerFields.add(toField(header));
            }
        }

        Map<String, PresetFieldOverride> fields = new LinkedHashMap<>();
        if (override != null && override.fields() != null) {
            fields.putAll(override.fields());
        }
        if (!token) {
            fields.remove("auth_token");
        } else if (!fields.containsKey("auth_token") && !hasCustomSecretHeader(headerFields)) {
            if (bearer != null) {
                fields.put("auth_token", new PresetFieldOverride(
                        bearer.label() != null ? bearer.label() : DEFAULT_NAME,
                        bearer.labelRu(),
                        authMethods.size() == 1));
            } else {
                fields.put("auth_token", new PresetFieldOverride(DEFAULT_NAME, null, authMethods.size() == 1));
            }
        }

        List<PresetHeaderGroup> headerGroups = toGroups(groups, headerFields, where);
        return new Form(
                fields.isEmpty() ? null : Map.copyOf(fields),
                headerFields.isEmpty() ? null : List.copyOf(headerFields),
                headerGroups.isEmpty() ? null : List.copyOf(headerGroups));
    }

    private static void validateForm(List<ConnectorPresetAuth> authMethods, Form form,
                                     List<RawHeader> headers, String where) {
        if (authMethods.contains(ConnectorPresetAuth.TOKEN)) {
            boolean tokenField = form.fields() != null && form.fields().containsKey("auth_token");
            boolean secretHeader = form.headerFields() != null && form.headerFields().stream()
                    .anyMatch(field -> field.secret() && field.required());
            if (!tokenField && !secretHeader) {
                throw new IllegalStateException(where
                        + ": TOKEN needs an auth_token field or a required secret header");
            }
        }
        if (authMethods.size() == 1 && authMethods.contains(ConnectorPresetAuth.NONE)
                && headers.stream().anyMatch(header -> header.required() && header.secret())) {
            throw new IllegalStateException(where
                    + ": NONE cannot have a required secret header");
        }
        if (form.headerFields() != null) {
            Set<String> names = new HashSet<>();
            for (PresetHeaderField field : form.headerFields()) {
                if (!names.add(field.name())) {
                    throw new IllegalStateException(where + ": duplicate header field '" + field.name() + "'");
                }
            }
        }
    }

    private static boolean hasCustomSecretHeader(List<PresetHeaderField> headerFields) {
        return headerFields.stream().anyMatch(field -> field.secret() && field.required());
    }

    private static boolean isBearer(String valuePrefix) {
        // Only an explicit Bearer prefix is the connector's auth_token. An empty prefix means the
        // user enters the whole header value (Krasoft's krsp_ key, Yandex's "OAuth …" token).
        return valuePrefix != null
                && (valuePrefix.equalsIgnoreCase("Bearer ") || valuePrefix.equalsIgnoreCase("Bearer"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static PresetHeaderField toField(RawHeader header) {
        return new PresetHeaderField(
                header.name(),
                header.label() != null ? header.label() : header.name(),
                header.labelRu(),
                header.secret(),
                header.required(),
                header.valuePrefix() != null ? header.valuePrefix() : "",
                header.keyPrefix());
    }

    private static List<PresetHeaderGroup> toGroups(List<RawGroup> groups,
                                                    List<PresetHeaderField> headerFields,
                                                    String where) {
        if (groups.isEmpty()) {
            return List.of();
        }
        Set<String> names = new HashSet<>();
        for (PresetHeaderField field : headerFields) {
            names.add(field.name());
        }
        Set<String> grouped = new HashSet<>();
        Map<String, Boolean> requiredByName = new LinkedHashMap<>();
        for (PresetHeaderField field : headerFields) {
            requiredByName.put(field.name(), field.required());
        }

        List<PresetHeaderGroup> result = new ArrayList<>(groups.size());
        for (RawGroup group : groups) {
            if (group.names() == null || group.names().size() < 2) {
                throw new IllegalStateException(where + ": header group '" + group.id()
                        + "' must list at least two headers");
            }
            for (String name : group.names()) {
                if (!names.contains(name)) {
                    throw new IllegalStateException(where + ": header group '" + group.id()
                            + "' references missing header '" + name + "'");
                }
                if (!grouped.add(name)) {
                    throw new IllegalStateException(where + ": header '" + name
                            + "' belongs to more than one group");
                }
                if (Boolean.TRUE.equals(requiredByName.get(name))) {
                    throw new IllegalStateException(where + ": header '" + name
                            + "' is individually required but belongs to a oneOf group");
                }
            }
            result.add(new PresetHeaderGroup(group.id(), List.copyOf(group.names()), group.required()));
        }
        return result;
    }

    private static List<RawHeader> parseHeaders(JsonNode array, String where) {
        if (array == null || array.isMissingNode() || array.isNull()) {
            return List.of();
        }
        if (!array.isArray()) {
            throw new IllegalStateException(where + ": declared.headers must be an array");
        }
        List<RawHeader> headers = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            String name = text(item, "name");
            if (name == null || !HEADER_NAME.matcher(name).matches()) {
                throw new IllegalStateException(where + ": declared.headers[].name is not a valid header name");
            }
            headers.add(new RawHeader(
                    name,
                    bool(item, "isRequired", bool(item, "required", false)),
                    bool(item, "isSecret", bool(item, "secret", false)),
                    text(item, "valuePrefix"),
                    text(item, "keyPrefix"),
                    text(item, "label"),
                    text(item, "labelRu")));
        }
        return headers;
    }

    private static List<RawGroup> parseGroups(JsonNode array, String where) {
        if (array == null || array.isMissingNode() || array.isNull()) {
            return List.of();
        }
        if (!array.isArray()) {
            throw new IllegalStateException(where + ": declared.headerGroups must be an array");
        }
        List<RawGroup> groups = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            String id = text(item, "id");
            if (id == null || id.isBlank()) {
                throw new IllegalStateException(where + ": headerGroups[].id is required");
            }
            List<String> names = strings(item.get("oneOf"));
            if (names == null) {
                names = strings(item.get("anyOf"));
            }
            groups.add(new RawGroup(id, names, bool(item, "required", false)));
        }
        return groups;
    }

    private static String endpoint(String url, JsonNode probe, String where) {
        String endpoint = text(probe, "endpointUrl");
        String chosen = endpoint != null && isHttps(endpoint) ? endpoint : url;
        if (!isHttps(chosen)) {
            throw new IllegalStateException(where + ": endpoint must be https");
        }
        return chosen;
    }

    private static boolean isHttps(String value) {
        try {
            return "https".equalsIgnoreCase(URI.create(value).getScheme());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String transportSource(JsonNode probe, String probeTransport, String declaredTransport) {
        if (bool(probe, "transportConfirmed", false)) {
            return "MEASURED";
        }
        if ("streamable-http".equalsIgnoreCase(declaredTransport)) {
            return "DECLARED";
        }
        return probeTransport != null ? "INFERRED" : "UNKNOWN";
    }

    private static boolean legacySse(String url) {
        return url.trim().replaceAll("/+$", "").endsWith("/sse");
    }

    private static Boolean handshake(JsonNode probe) {
        JsonNode handshake = probe.path("handshake");
        if (!handshake.isObject() || !handshake.has("supported") || handshake.get("supported").isNull()) {
            return null;
        }
        return handshake.get("supported").asBoolean();
    }

    private static ContentCategory category(String key, PresetOverrides overrides, String where) {
        ContentCategory category = overrides.categories().get(key);
        if (category == null) {
            category = DEFAULT_CATEGORIES.get(key);
        }
        if (category == null) {
            throw new IllegalStateException(where + ": unknown dataset category '" + key + "'");
        }
        return category;
    }

    private static String requiredText(JsonNode node, String field, String where) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalStateException(where + ": " + field + " is required");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asBoolean(fallback);
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            values.add(item.asText());
        }
        return values;
    }

    private static String[] location(Resource resource) {
        try {
            String url = resource.getURL().toString();
            int root = url.lastIndexOf(SEED_ROOT + "/");
            if (root < 0) {
                throw new IllegalStateException("Seed resource is outside " + SEED_ROOT + ": " + url);
            }
            String tail = url.substring(root + SEED_ROOT.length() + 1);
            int slash = tail.indexOf('/');
            if (slash < 0) {
                throw new IllegalStateException("Seed resource has no connector directory: " + url);
            }
            String file = tail.substring(slash + 1);
            if (!file.endsWith(".json")) {
                throw new IllegalStateException("Seed resource is not a JSON file: " + url);
            }
            return new String[]{tail.substring(0, slash), file.substring(0, file.length() - ".json".length())};
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve seed resource: " + resource, e);
        }
    }

    static PresetOverrides readOverrides() {
        Resource resource = new ClassPathResource(SEED_ROOT + "/overrides.yaml");
        if (!resource.exists()) {
            return PresetOverrides.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: expected a mapping");
            }
            return parseOverrides(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SEED_ROOT + "/overrides.yaml", e);
        }
    }

    /** Package-private seam for tests: the strict key set is the contract, a typo must not be silent. */
    static PresetOverrides parseOverrides(Map<?, ?> root) {
        rejectUnknown(root, Set.of("categories", "presets"), SEED_ROOT + "/overrides.yaml");
        return new PresetOverrides(parseCategories(root.get("categories")), parsePresets(root.get("presets")));
    }

    static void validateOverrideCodes(Collection<String> seedCodes, PresetOverrides overrides) {
        List<String> unknown = overrides.presets().keySet().stream()
                .filter(code -> !seedCodes.contains(code))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: override for unknown preset(s) "
                    + String.join(", ", unknown));
        }
    }

    private static void rejectUnknown(Map<?, ?> map, Set<String> allowed, String where) {
        for (Object key : map.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                throw new IllegalStateException(where + ": unknown key '" + key + "' (allowed: "
                        + String.join(", ", allowed) + ")");
            }
        }
    }

    private static Map<String, ContentCategory> parseCategories(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: categories must be a mapping");
        }
        Map<String, ContentCategory> categories = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            try {
                categories.put(String.valueOf(key),
                        ContentCategory.valueOf(String.valueOf(value).strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: unknown category '" + value + "'", e);
            }
        });
        return Map.copyOf(categories);
    }

    static Map<String, PresetOverride> parsePresets(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: presets must be a mapping");
        }
        Map<String, PresetOverride> presets = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            String code = String.valueOf(key);
            if (!(value instanceof Map<?, ?> preset)) {
                throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: preset '" + code + "' must be a mapping");
            }
            rejectUnknown(preset, Set.of("authMethods", "fields", "headerFields"),
                    SEED_ROOT + "/overrides.yaml: preset '" + code + "'");
            presets.put(code, new PresetOverride(
                    parseOverrideAuthMethods(preset.get("authMethods"), code),
                    parseOverrideFields(preset.get("fields"), code),
                    parseOverrideHeaders(preset.get("headerFields"), code)));
        });
        return Map.copyOf(presets);
    }

    private static List<ConnectorPresetAuth> parseOverrideAuthMethods(Object raw, String code) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: preset '" + code
                    + "'.authMethods must be a list");
        }
        List<ConnectorPresetAuth> methods = new ArrayList<>(list.size());
        for (Object item : list) {
            methods.add(parseAuthMethod(String.valueOf(item), SEED_ROOT + "/overrides.yaml"));
        }
        return List.copyOf(methods);
    }

    static Map<String, PresetFieldOverride> parseOverrideFields(Object raw, String code) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: preset '" + code
                    + "'.fields must be a mapping");
        }
        Map<String, PresetFieldOverride> fields = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(value instanceof Map<?, ?> field)) {
                throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: field '" + key
                        + "' of preset '" + code + "' must be a mapping");
            }
            String where = SEED_ROOT + "/overrides.yaml: preset '" + code + "'.fields['" + key + "']";
            rejectUnknown(field, Set.of("label", "labelRu", "required"), where);
            Boolean required = booleanOrNull(field.get("required"), where + ".required");
            fields.put(String.valueOf(key), new PresetFieldOverride(
                    text(field, "label"),
                    text(field, "labelRu"),
                    required));
        });
        return Map.copyOf(fields);
    }

    static List<PresetHeaderField> parseOverrideHeaders(Object raw, String code) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: preset '" + code
                    + "'.headerFields must be a list");
        }
        List<PresetHeaderField> fields = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> header)) {
                throw new IllegalStateException(SEED_ROOT + "/overrides.yaml: preset '" + code
                        + "'.headerFields[] must be a mapping");
            }
            String where = SEED_ROOT + "/overrides.yaml: preset '" + code + "'.headerFields[]";
            rejectUnknown(header, Set.of("name", "label", "labelRu", "secret", "required",
                    "valuePrefix", "keyPrefix", "isSecret", "isRequired"), where);
            String name = text(header, "name");
            if (name == null) {
                throw new IllegalStateException(where + ".name is required");
            }
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalStateException(where + ".name '" + name + "' is not a valid header name");
            }
            Boolean secret = booleanOrNull(header.get("secret"), where + ".secret");
            if (secret == null) {
                secret = booleanOrNull(header.get("isSecret"), where + ".isSecret");
            }
            Boolean required = booleanOrNull(header.get("required"), where + ".required");
            if (required == null) {
                required = booleanOrNull(header.get("isRequired"), where + ".isRequired");
            }
            String label = text(header, "label");
            String valuePrefix = text(header, "valuePrefix");
            fields.add(new PresetHeaderField(
                    name,
                    label != null ? label : name,
                    text(header, "labelRu"),
                    Boolean.TRUE.equals(secret),
                    Boolean.TRUE.equals(required),
                    valuePrefix != null ? valuePrefix : "",
                    text(header, "keyPrefix")));
        }
        return List.copyOf(fields);
    }

    private static Boolean booleanOrNull(Object value, String where) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean bool)) {
            throw new IllegalStateException(where + " must be a boolean");
        }
        return bool;
    }

    private record RawHeader(String name, boolean required, boolean secret, String valuePrefix,
                             String keyPrefix, String label, String labelRu) {
    }

    private record RawGroup(String id, List<String> names, boolean required) {
    }

    private record Form(Map<String, PresetFieldOverride> fields,
                        List<PresetHeaderField> headerFields,
                        List<PresetHeaderGroup> headerGroups) {
    }
}

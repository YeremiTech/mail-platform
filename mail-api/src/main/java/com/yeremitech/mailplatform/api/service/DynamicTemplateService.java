package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.application.port.TemplateRendererPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped, immutable revisions. {{variable}} interpolation deliberately never executes template code. */
@Service
public class DynamicTemplateService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{0,79}");
    private static final Pattern VAR = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]{0,63})\\}\\}");
    private static final Pattern PINNED = Pattern.compile("custom/([a-z0-9][a-z0-9_-]{0,79})/v([1-9][0-9]{0,8})");
    private final NamedParameterJdbcTemplate jdbc;

    public DynamicTemplateService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Version create(String clientId, String slug, Body body) {
        checkSlug(slug);
        validate(body);
        jdbc.update("insert into mail_template(client_id, slug, last_version) values (:client,:slug,1)",
                Map.of("client", clientId, "slug", slug));
        insertVersion(clientId, slug, 1, body);
        return new Version(slug, 1, false);
    }

    @Transactional
    public Version revise(String clientId, String slug, Body body) {
        checkSlug(slug);
        validate(body);
        List<Integer> next = jdbc.query("""
                update mail_template set last_version=last_version+1, updated_at=current_timestamp
                where client_id=:client and slug=:slug returning last_version
                """, Map.of("client", clientId, "slug", slug), (rs, row) -> rs.getInt(1));
        if (next.isEmpty()) throw new NoSuchElementException("template not found");
        insertVersion(clientId, slug, next.getFirst(), body);
        return new Version(slug, next.getFirst(), false);
    }

    @Transactional
    public Version publish(String clientId, String slug, int version) {
        checkSlug(slug);
        int changed = jdbc.update("""
                update mail_template set published_version=:version, updated_at=current_timestamp
                where client_id=:client and slug=:slug and exists (
                  select 1 from mail_template_version v where v.client_id=:client and v.slug=:slug and v.version=:version)
                """, Map.of("client", clientId, "slug", slug, "version", version));
        if (changed != 1) throw new NoSuchElementException("template version not found");
        return new Version(slug, version, true);
    }

    @Transactional
    public void unpublish(String clientId, String slug) {
        if (jdbc.update("""
                update mail_template set published_version=null, updated_at=current_timestamp
                where client_id=:client and slug=:slug
                """, Map.of("client", clientId, "slug", slug)) != 1) {
            throw new NoSuchElementException("template not found");
        }
    }

    public List<Summary> list(String clientId) {
        return jdbc.query("""
                select slug, last_version, published_version, created_at, updated_at
                from mail_template where client_id=:client order by slug
                """, Map.of("client", clientId), (rs, row) -> new Summary(rs.getString(1), rs.getInt(2),
                (Integer) rs.getObject(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()));
    }

    public List<Version> versions(String clientId, String slug) {
        checkSlug(slug);
        return jdbc.query("""
                select v.version, t.published_version from mail_template_version v
                join mail_template t on t.client_id=v.client_id and t.slug=v.slug
                where v.client_id=:client and v.slug=:slug order by v.version desc
                """, Map.of("client", clientId, "slug", slug), (rs, row) -> new Version(
                slug, rs.getInt(1), Integer.valueOf(rs.getInt(1)).equals(rs.getObject(2))));
    }

    /** Resolve at submission time: queued messages never silently switch to a new published revision. */
    public String pinPublished(String clientId, String slug) {
        checkSlug(slug);
        List<Integer> versions = jdbc.query("""
                select published_version from mail_template
                where client_id=:client and slug=:slug and published_version is not null
                """, Map.of("client", clientId, "slug", slug), (rs, row) -> rs.getInt(1));
        if (versions.isEmpty()) throw new org.springframework.security.access.AccessDeniedException(
                "template is not published for authenticated client");
        return "custom/" + slug + "/v" + versions.getFirst();
    }

    public TemplateRendererPort.RenderedTemplate preview(String clientId, String slug, int version, Map<String,Object> vars) {
        checkSlug(slug);
        return render(clientId, "custom/" + slug + "/v" + version, vars);
    }

    public TemplateRendererPort.RenderedTemplate render(String clientId, String key, Map<String,Object> vars) {
        Matcher pinned = PINNED.matcher(key);
        if (!pinned.matches()) throw new IllegalArgumentException("invalid pinned template key");
        String slug = pinned.group(1);
        int version = Integer.parseInt(pinned.group(2));
        List<Body> versions = jdbc.query("""
                select html_body, text_body, required_variables from mail_template_version
                where client_id=:client and slug=:slug and version=:version
                """, Map.of("client", clientId, "slug", slug, "version", version),
                (rs, row) -> new Body(rs.getString(1), rs.getString(2), splitRequired(rs.getString(3))));
        if (versions.isEmpty()) throw new NoSuchElementException("template version not found");
        Body body = versions.getFirst();
        Map<String,Object> values = vars == null ? Map.of() : vars;
        for (String required : body.requiredVariables()) {
            Object val = values.get(required);
            if (val == null || val.toString().isBlank()) throw new IllegalArgumentException("missing template variable: " + required);
        }
        return new TemplateRendererPort.RenderedTemplate(
                interpolate(body.html(), values, true), interpolate(body.text(), values, false));
    }

    private void insertVersion(String clientId, String slug, int version, Body body) {
        jdbc.update("""
                insert into mail_template_version(client_id, slug, version, html_body, text_body, required_variables)
                values (:client,:slug,:version,:html,:text,:required)
                """, Map.of("client", clientId, "slug", slug, "version", version,
                "html", body.html(), "text", body.text(), "required", String.join(",", body.requiredVariables())));
    }

    private static void checkSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) throw new IllegalArgumentException("invalid template slug");
    }

    private static void validate(Body body) {
        if (body == null || body.html() == null || body.text() == null || body.html().isBlank() || body.text().isBlank()
                || body.html().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072
                || body.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("HTML and text bodies are required and size limited");
        }
        if (body.requiredVariables() == null || body.requiredVariables().size() > 50) {
            throw new IllegalArgumentException("requiredVariables must contain no more than 50 names");
        }
        Set<String> unique = new HashSet<>();
        for (String var : body.requiredVariables()) {
            if (var == null || !VAR.matcher(var).matches() || !unique.add(var)) {
                throw new IllegalArgumentException("invalid or duplicate variable name");
            }
        }
        // No expression-language evaluation: only literal placeholders are accepted.
        if (body.html().contains("${") || body.html().contains("#{") || body.html().contains("*{")
                || body.html().contains("@{") || body.html().contains("~{")) {
            throw new IllegalArgumentException("template expression languages are not permitted");
        }
    }

    private static List<String> splitRequired(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.asList(csv.split(","));
    }

    private static String interpolate(String template, Map<String,Object> vars, boolean escapeHtml) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder result = new StringBuilder();
        int previous = 0;
        while (matcher.find()) {
            result.append(template, previous, matcher.start());
            Object raw = vars.get(matcher.group(1));
            if (raw == null) throw new IllegalArgumentException("missing template variable: " + matcher.group(1));
            if (!(raw instanceof String || raw instanceof Number || raw instanceof Boolean)) {
                throw new IllegalArgumentException("template variables must be scalar values");
            }
            String value = String.valueOf(raw);
            if (value.length() > 10000) throw new IllegalArgumentException("template variable value exceeds 10000 characters");
            result.append(escapeHtml ? escape(value) : value);
            previous = matcher.end();
        }
        return result.append(template, previous, template.length()).toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public record Body(String html, String text, List<String> requiredVariables) {}
    public record Version(String slug, int version, boolean published) {}
    public record Summary(String slug, int lastVersion, Integer publishedVersion, Instant createdAt, Instant updatedAt) {}
}

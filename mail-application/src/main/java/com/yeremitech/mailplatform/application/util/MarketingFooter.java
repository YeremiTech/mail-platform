package com.yeremitech.mailplatform.application.util;

/** Guarantees the opt-out link appears in both HTML and plaintext marketing bodies. */
public final class MarketingFooter {
    private MarketingFooter() {}
    public record Body(String html, String text) {}

    public static Body apply(String html, String text, String unsubscribeUrl) {
        if (unsubscribeUrl == null || !unsubscribeUrl.matches(
                "https?://[^\\s<>\\\"']+/api/v1/public/unsubscribe\\?token=[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("marketing email is missing a valid unsubscribe URL");
        }
        String safe = unsubscribeUrl.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
        String footer = "<hr><p>Para dejar de recibir correos comerciales: <a href=\"" + safe
                + "\">Cancelar suscripción</a></p>";
        String resultHtml = html;
        if (!html.contains(unsubscribeUrl)) {
            // Keep a complete HTML document well-formed instead of appending after </html>.
            String lower = html.toLowerCase(java.util.Locale.ROOT);
            int insertion = lower.lastIndexOf("</body>");
            if (insertion < 0) insertion = lower.lastIndexOf("</html>");
            resultHtml = insertion < 0 ? html + footer
                    : html.substring(0, insertion) + footer + html.substring(insertion);
        }
        String resultText = text.contains(unsubscribeUrl) ? text
                : text + "\n\nPara dejar de recibir correos comerciales: " + unsubscribeUrl;
        return new Body(resultHtml, resultText);
    }
}

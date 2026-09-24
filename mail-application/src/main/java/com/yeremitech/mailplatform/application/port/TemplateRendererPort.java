package com.yeremitech.mailplatform.application.port;

import java.util.Map;

public interface TemplateRendererPort { RenderedTemplate render(String templateKey, Map<String, Object> variables);
    record RenderedTemplate(String html, String text) {}
}

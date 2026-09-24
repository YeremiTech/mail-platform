package com.yeremitech.mailplatform.template;

import com.yeremitech.mailplatform.application.port.TemplateRendererPort;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import java.util.Map;

public final class ThymeleafTemplateRenderer implements TemplateRendererPort {
    private final TemplateEngine engine;
    public ThymeleafTemplateRenderer(TemplateEngine engine){this.engine=engine;}
    public RenderedTemplate render(String key, Map<String,Object> variables){ Context ctx=new Context(); ctx.setVariables(variables); String html=engine.process("mail/"+key,ctx); String text=html.replaceAll("<[^>]+>"," ").replaceAll("\\s+"," ").trim(); return new RenderedTemplate(html,text); }
}

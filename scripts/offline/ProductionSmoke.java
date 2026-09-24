import com.yeremitech.mailplatform.api.config.ProductionConfigurationValidator;
import java.util.Base64;
import java.util.HashMap;

public class ProductionSmoke {
    public static void main(String[] args) {
        var v = new HashMap<String,String>();
        v.put("app.security.allow-legacy-keys", "false");
        v.put("app.docs.public", "false");
        v.put("app.attachments.antivirus.enabled", "true");
        v.put("app.attachments.antivirus.host", "clamav.internal");
        for (var k : new String[]{"spring.mail.properties.mail.smtp.starttls.enable",
                "spring.mail.properties.mail.smtp.starttls.required",
                "spring.mail.properties.mail.smtp.ssl.checkserveridentity"}) v.put(k,"true");
        for (var k : new String[]{"app.security.api-key-pepper", "app.security.otp-hmac-secret",
                "app.security.admin-api-key"}) v.put(k, "independent-32-byte-or-longer-secret-" + k);
        byte[] payload = new byte[32], webhook = new byte[32];
        for (int i = 0; i < 32; i++) { payload[i] = (byte) (i + 1); webhook[i] = (byte) (i + 51); }
        v.put("app.security.sensitive-payload-key",Base64.getEncoder().encodeToString(payload));
        v.put("app.security.webhook-encryption-key",Base64.getEncoder().encodeToString(webhook));
        v.put("spring.datasource.password", "a-non-default-password");
        v.put("spring.rabbitmq.username", "mail-service");
        v.put("spring.rabbitmq.password", "secure-example-password");
        v.put("app.marketing.public-base-url", "https://mail.example.test");
        ProductionConfigurationValidator.validate(v);
        v.put("app.security.allow-legacy-keys", "true");
        try { ProductionConfigurationValidator.validate(v); throw new AssertionError("legacy keys permitted"); }
        catch (IllegalStateException expected) { /* expected */ }
        v.put("app.security.allow-legacy-keys", "false");
        v.put("app.marketing.public-base-url", "http://localhost:8080");
        try { ProductionConfigurationValidator.validate(v); throw new AssertionError("insecure unsubscribe origin"); }
        catch (IllegalStateException expected) { /* expected */ }
        v.put("app.marketing.public-base-url", "https://mail.example.test");
        v.put("app.security.webhook-encryption-key", v.get("app.security.sensitive-payload-key"));
        try { ProductionConfigurationValidator.validate(v); throw new AssertionError("reused encryption secret"); }
        catch (IllegalStateException expected) { /* expected */ }
        System.out.println("PASS: production security guard accepts secure configuration and rejects unsafe defaults");
    }
}

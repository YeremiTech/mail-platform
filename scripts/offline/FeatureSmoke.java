import com.yeremitech.mailplatform.api.service.CampaignCsvParser;
import com.yeremitech.mailplatform.api.service.ClamAvInstream;
import com.yeremitech.mailplatform.api.service.AttachmentSignatureVerifier;
import com.yeremitech.mailplatform.api.security.ClientPermissions;
import com.yeremitech.mailplatform.api.service.WebhookAddressPolicy;
import com.yeremitech.mailplatform.domain.EmailAddress;
import com.yeremitech.mailplatform.domain.UnsubscribeToken;
import com.yeremitech.mailplatform.application.util.MarketingFooter;
import java.net.InetAddress;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class FeatureSmoke {
    static ByteArrayInputStream input(String s) {return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));}
    public static void main(String[] args) throws Exception {
        var parsed = CampaignCsvParser.parse(input("\ufeffemail,display_name,consent,var_name\r\n"
                + "ana@example.test,\"Ana, Pérez\",true,Ana\r\n"), true);
        if (parsed.size()!=1 || !"Ana, Pérez".equals(parsed.getFirst().displayName())) throw new AssertionError("CSV parsing");
        boolean duplicate=false;
        try {CampaignCsvParser.parse(input("email,display_name\nabc@example.test,A\nABC@example.test,B"),false);}
        catch (IllegalArgumentException e) { duplicate=true; }
        if (!duplicate) throw new AssertionError("duplicate acceptance");
        boolean consent=false;
        try {CampaignCsvParser.parse(input("email,display_name,consent\na@example.test,A,false"),true);}
        catch (IllegalArgumentException e) { consent=true; }
        if (!consent) throw new AssertionError("marketing without consent");
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var f = executor.submit(() -> {
                try (var conn=server.accept()) {
                    var in = new java.io.DataInputStream(conn.getInputStream());
                    if (!"zINSTREAM\0".equals(new String(in.readNBytes(10), StandardCharsets.US_ASCII))) throw new AssertionError("command");
                    if (in.readInt()!=3 || in.readNBytes(3).length!=3 || in.readInt()!=0) throw new AssertionError("chunks");
                    conn.getOutputStream().write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
                    conn.getOutputStream().flush();
                } catch(Exception e) {throw new RuntimeException(e);}
            });
            ClamAvInstream.scan("127.0.0.1",server.getLocalPort(),new byte[]{1,2,3},3000);
            f.get();
        }
        if (!new EmailAddress(" Mixed@EXAMPLE.TEST ").value().equals("mixed@example.test"))
            throw new AssertionError("email normalization");
        for (String blocked : new String[]{"169.254.169.254","100.64.1.1","192.0.2.1",
                "198.18.0.1","198.51.100.1","203.0.113.1","2001:db8::1","2002:7f00:1::1"}) {
            if (WebhookAddressPolicy.isPublic(InetAddress.getByName(blocked)))
                throw new AssertionError("public IP guard accepted " + blocked);
        }
        if (!WebhookAddressPolicy.isPublic(InetAddress.getByName("8.8.8.8"))
                || !WebhookAddressPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")))
            throw new AssertionError("public IP guard blocked public IP");
        byte[] streamedPdf="%PDF-1.7\nstream".getBytes(StandardCharsets.US_ASCII);
        AttachmentSignatureVerifier.verify("application/pdf", new ByteArrayInputStream(streamedPdf), streamedPdf.length);
        boolean spoofed=false;
        try { AttachmentSignatureVerifier.verify("image/png", new ByteArrayInputStream(streamedPdf), streamedPdf.length); }
        catch (IllegalArgumentException expected) { spoofed=true; }
        if (!spoofed) throw new AssertionError("streamed content mismatch not rejected");
        if (!ClientPermissions.normalize(java.util.Set.of("metrics_read"),false).contains("METRICS_READ"))
            throw new AssertionError("monitoring permission missing");
        if (!ClientPermissions.normalize(java.util.Set.of("email_send"),false).contains("EMAIL_SEND"))
            throw new AssertionError("permission normalization");
        boolean wildcard=false;
        try { ClientPermissions.normalize(java.util.Set.of("*"),false); }
        catch (IllegalArgumentException expected) { wildcard=true; }
        if (!wildcard) throw new AssertionError("new clients must not inherit wildcard");
        String token=UnsubscribeToken.issue();
        if (!UnsubscribeToken.wellFormed(token) || !UnsubscribeToken.digest(token).matches("[0-9a-f]{64}"))
            throw new AssertionError("invalid opaque unsubscribe token");
        if (UnsubscribeToken.wellFormed(token.substring(0,42)+"!")) throw new AssertionError("invalid token accepted");
        if (!UnsubscribeToken.validatePublicBaseUrl("http://127.0.0.1:8080/").equals("http://127.0.0.1:8080"))
            throw new AssertionError("loopback normalization");
        for (String blockedOrigin : new String[]{"http://example.org","https://example.org/redirect",
                "https://user:pass@example.org","https://example.org?q=1", "file:///etc/passwd"}) {
            try { UnsubscribeToken.validatePublicBaseUrl(blockedOrigin); throw new AssertionError("accepted " + blockedOrigin); }
            catch (IllegalArgumentException expected) { /* blocked */ }
        }
        String url="https://mail.example.org/api/v1/public/unsubscribe?token="+token;
        var footer=MarketingFooter.apply("<p>Hello</p>","Hello",url);
        if (!footer.html().contains("Cancelar suscripción") || !footer.text().contains(url))
            throw new AssertionError("marketing footer missing URL");
        if (!MarketingFooter.apply(footer.html(),footer.text(),url).equals(footer))
            throw new AssertionError("marketing footer duplicated");
        var full=MarketingFooter.apply("<!doctype html><html><body><p>Offer</p></body></html>","Offer",url);
        if (!full.html().contains(url) || full.html().indexOf("Cancelar suscripción") > full.html().indexOf("</body>"))
            throw new AssertionError("HTML unsubscribe link appended outside body");
        boolean rejected=false;
        try {MarketingFooter.apply("<p>x</p>","x","http://invalid/optout");}
        catch (IllegalArgumentException expected) {rejected=true;}
        if(!rejected) throw new AssertionError("invalid marketing link accepted");
        System.out.println("PASS: CSV, clamd, streamed attachment validation, least-privilege permissions, IP policy, opt-out and marketing footer");
    }
}

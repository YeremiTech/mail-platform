import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Fast syntax-only parse of every source/test; never substitutes for a Maven build. */
public final class ParseAllJava {
    public static void main(String[] args) throws IOException {
        var compiler=ToolProvider.getSystemJavaCompiler();
        if (compiler==null) throw new IllegalStateException("JDK javac is required");
        List<Path> sources;
        try (Stream<Path> found=Files.walk(Path.of("."))) {
            sources=found.filter(p->p.toString().endsWith(".java")
                    && !p.toString().contains("/target/")).sorted().toList();
        }
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            var units=manager.getJavaFileObjectsFromPaths(sources);
            var task=(JavacTask)compiler.getTask(null,manager,diagnostics,
                    List.of("-proc:none"),null,units);
            for (var ignored:task.parse()) { /* Parse only: no dependency resolution. */ }
        }
        var errors=diagnostics.getDiagnostics().stream()
                .filter(d->d.getKind()==Diagnostic.Kind.ERROR).toList();
        if (!errors.isEmpty()) {
            errors.stream().limit(15).forEach(d->System.err.printf("%s:%d: %s%n",
                    d.getSource()==null?"unknown":d.getSource().getName(),
                    d.getLineNumber(),d.getMessage(null)));
            throw new IllegalStateException(errors.size()+" syntax errors");
        }
        System.out.println("PASS: syntax parsed "+sources.size()+" Java source/test files");
    }
}

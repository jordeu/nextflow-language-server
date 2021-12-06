package nextflow.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import nextflow.lsp.compiler.ast.ASTNodeVisitor;
import nextflow.lsp.compiler.control.GroovyLSCompilationUnit;
import nextflow.lsp.config.ICompilationUnitFactory;
import nextflow.lsp.providers.CompletionProvider;
import nextflow.lsp.util.FileContentsTracker;
import nextflow.lsp.util.GroovyLanguageServerUtils;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class NextflowServices implements TextDocumentService, WorkspaceService, LanguageClientAware {

    private LanguageClient languageClient;

    private Path workspaceRoot;
    private ICompilationUnitFactory compilationUnitFactory;
    private GroovyLSCompilationUnit compilationUnit;
    private ASTNodeVisitor astVisitor;
    private Map<URI, List<Diagnostic>> prevDiagnosticsByFile;
    private FileContentsTracker fileContentsTracker = new FileContentsTracker();
    private URI previousContext = null;

    public NextflowServices(ICompilationUnitFactory compilationUnitFactory) {
        this.compilationUnitFactory = compilationUnitFactory;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public void connect(LanguageClient client) {
        this.languageClient = client;
    }

    // -- NOTIFICATIONS

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        fileContentsTracker.didOpen(params);
        URI uri = URI.create(params.getTextDocument().getUri());
        compileAndVisitAST(uri);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        fileContentsTracker.didChange(params);
        URI uri = URI.create(params.getTextDocument().getUri());
        compileAndVisitAST(uri);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        fileContentsTracker.didClose(params);
        URI uri = URI.create(params.getTextDocument().getUri());
        compileAndVisitAST(uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // nothing to handle on save at this time
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        boolean isSameUnit = createOrUpdateCompilationUnit();
        Set<URI> urisWithChanges = params.getChanges().stream().map(fileEvent -> URI.create(fileEvent.getUri()))
                .collect(Collectors.toSet());
        compile();
        if (isSameUnit) {
            visitAST(urisWithChanges);
        } else {
            visitAST();
        }
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        if (!(params.getSettings() instanceof JsonObject)) {
            return;
        }
        JsonObject settings = (JsonObject) params.getSettings();
        this.updateClasspath(settings);
    }

    // --- REQUESTS

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        TextDocumentIdentifier textDocument = params.getTextDocument();
        URI uri = URI.create(textDocument.getUri());
        recompileIfContextChanged(uri);

        CompletionProvider provider = new CompletionProvider(astVisitor, compilationUnit.getClassLoader());
        return provider.provideCompletion(params.getTextDocument(), params.getPosition(), params.getContext());
    }


    // --- INTERNAL

    private void updateClasspath(JsonObject settings) {
        List<String> classpathList = new ArrayList<>();

        if (settings.has("groovy") && settings.get("groovy").isJsonObject()) {
            JsonObject groovy = settings.get("groovy").getAsJsonObject();
            if (groovy.has("classpath") && groovy.get("classpath").isJsonArray()) {
                JsonArray classpath = groovy.get("classpath").getAsJsonArray();
                classpath.forEach(element -> {
                    classpathList.add(element.getAsString());
                });
            }
        }

        if (!classpathList.equals(compilationUnitFactory.getAdditionalClasspathList())) {
            compilationUnitFactory.setAdditionalClasspathList(classpathList);

            createOrUpdateCompilationUnit();
            compile();
            visitAST();
            previousContext = null;
        }
    }

    private void visitAST() {
        if (compilationUnit == null) {
            return;
        }
        astVisitor = new ASTNodeVisitor();
        astVisitor.visitCompilationUnit(compilationUnit);
    }

    private void visitAST(Set<URI> uris) {
        if (astVisitor == null) {
            visitAST();
            return;
        }
        if (compilationUnit == null) {
            return;
        }
        astVisitor.visitCompilationUnit(compilationUnit, uris);
    }

    private boolean createOrUpdateCompilationUnit() {
        if (compilationUnit != null) {
            File targetDirectory = compilationUnit.getConfiguration().getTargetDirectory();
            if (targetDirectory != null && targetDirectory.exists()) {
                try {
                    Files.walk(targetDirectory.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile)
                            .forEach(File::delete);
                } catch (IOException e) {
                    System.err.println("Failed to delete target directory: " + targetDirectory.getAbsolutePath());
                    compilationUnit = null;
                    return false;
                }
            }
        }

        GroovyLSCompilationUnit oldCompilationUnit = compilationUnit;
        compilationUnit = compilationUnitFactory.create(workspaceRoot, fileContentsTracker);
        fileContentsTracker.resetChangedFiles();

        if (compilationUnit != null) {
            File targetDirectory = compilationUnit.getConfiguration().getTargetDirectory();
            if (targetDirectory != null && !targetDirectory.exists() && !targetDirectory.mkdirs()) {
                System.err.println("Failed to create target directory: " + targetDirectory.getAbsolutePath());
            }
        }

        return compilationUnit != null && compilationUnit.equals(oldCompilationUnit);
    }

    protected void recompileIfContextChanged(URI newContext) {
        if (previousContext == null || previousContext.equals(newContext)) {
            return;
        }
        fileContentsTracker.forceChanged(newContext);
        compileAndVisitAST(newContext);
    }

    private void compileAndVisitAST(URI contextURI) {
        Set<URI> uris = Collections.singleton(contextURI);
        boolean isSameUnit = createOrUpdateCompilationUnit();
        compile();
        if (isSameUnit) {
            visitAST(uris);
        } else {
            visitAST();
        }
        previousContext = contextURI;
    }

    private void compile() {
        if (compilationUnit == null) {
            return;
        }
        try {
            //AST is completely built after the canonicalization phase
            //for code intelligence, we shouldn't need to go further
            //http://groovy-lang.org/metaprogramming.html#_compilation_phases_guide
            compilationUnit.compile(Phases.CANONICALIZATION);
        } catch (MultipleCompilationErrorsException e) {
            // ignore
        } catch (GroovyBugError e) {
            System.err.println("Unexpected exception in language server when compiling Groovy.");
            e.printStackTrace(System.err);
        } catch (Exception e) {
            System.err.println("Unexpected exception in language server when compiling Groovy.");
            e.printStackTrace(System.err);
        }
        Set<PublishDiagnosticsParams> diagnostics = handleErrorCollector(compilationUnit.getErrorCollector());
        diagnostics.stream().forEach(languageClient::publishDiagnostics);
    }

    private Set<PublishDiagnosticsParams> handleErrorCollector(ErrorCollector collector) {
        Map<URI, List<Diagnostic>> diagnosticsByFile = new HashMap<>();

        @SuppressWarnings("unchecked")
        List<Message> errors = collector.getErrors();
        if (errors != null) {
            errors.stream().filter((Object message) -> message instanceof SyntaxErrorMessage)
                    .forEach((Object message) -> {
                        SyntaxErrorMessage syntaxErrorMessage = (SyntaxErrorMessage) message;
                        SyntaxException cause = syntaxErrorMessage.getCause();
                        Range range = GroovyLanguageServerUtils.syntaxExceptionToRange(cause);
                        Diagnostic diagnostic = new Diagnostic();
                        diagnostic.setRange(range);
                        diagnostic.setSeverity(cause.isFatal() ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning);
                        diagnostic.setMessage(cause.getMessage());
                        URI uri = Paths.get(cause.getSourceLocator()).toUri();
                        diagnosticsByFile.computeIfAbsent(uri, (key) -> new ArrayList<>()).add(diagnostic);
                    });
        }

        Set<PublishDiagnosticsParams> result = diagnosticsByFile.entrySet().stream()
                .map(entry -> new PublishDiagnosticsParams(entry.getKey().toString(), entry.getValue()))
                .collect(Collectors.toSet());

        if (prevDiagnosticsByFile != null) {
            for (URI key : prevDiagnosticsByFile.keySet()) {
                if (!diagnosticsByFile.containsKey(key)) {
                    // send an empty list of diagnostics for files that had
                    // diagnostics previously or they won't be cleared
                    result.add(new PublishDiagnosticsParams(key.toString(), new ArrayList<>()));
                }
            }
        }
        prevDiagnosticsByFile = diagnosticsByFile;
        return result;
    }
}

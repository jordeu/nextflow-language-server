/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package nextflow.lsp;

import nextflow.lsp.config.CompilationUnitFactory;
import nextflow.lsp.config.ICompilationUnitFactory;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NextflowLanguageServer implements LanguageServer, LanguageClientAware {

    public static void main(String[] args) {
        NextflowLanguageServer server = new NextflowLanguageServer();
        Launcher<LanguageClient> launcher = Launcher.createLauncher(server, LanguageClient.class, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening();
    }

    private NextflowServices nextflowServices;

    public NextflowLanguageServer() {
        this(new CompilationUnitFactory());
    }

    public NextflowLanguageServer(ICompilationUnitFactory compilationUnitFactory) {
        this.nextflowServices = new NextflowServices(compilationUnitFactory);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        String rootUriString = params.getRootUri();
        if (rootUriString != null) {
            URI uri = URI.create(params.getRootUri());
            Path workspaceRoot = Paths.get(uri);
            nextflowServices.setWorkspaceRoot(workspaceRoot);
        }

        CompletionOptions completionOptions = new CompletionOptions(false, List.of("."));
        ServerCapabilities serverCapabilities = new ServerCapabilities();
        serverCapabilities.setCompletionProvider(completionOptions);

        InitializeResult initializeResult = new InitializeResult(serverCapabilities);
        return CompletableFuture.completedFuture(initializeResult);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(new Object());
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return nextflowServices;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return nextflowServices;
    }

    @Override
    public void connect(LanguageClient client) {
        nextflowServices.connect(client);
    }
}

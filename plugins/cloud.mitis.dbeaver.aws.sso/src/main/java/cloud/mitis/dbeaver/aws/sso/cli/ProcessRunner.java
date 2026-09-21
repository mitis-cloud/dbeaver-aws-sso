package cloud.mitis.dbeaver.aws.sso.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ProcessRunner implements CommandRunner {
    private static final int OUTPUT_LIMIT = 262_144;

    @Override
    public Result run(List<String> command, Duration timeout, AuthProgress progress, OutputListener output)
            throws AuthException {
        AuthException.checkCancelled(progress);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("AWS_CLI_AUTO_PROMPT", "off");
        builder.environment().put("AWS_PAGER", "");
        builder.environment().put("PYTHONUNBUFFERED", "1");
        Process process;
        try {
            process = builder.start();
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new AuthException(AuthException.Kind.PROCESS,
                    "Could not start AWS CLI. Install AWS CLI v2 and check the AWS CLI executable setting.");
        }
        Set<ProcessHandle> children = new HashSet<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> stdout = readers.submit(() -> capture(process.getInputStream(), output));
            Future<String> stderr = readers.submit(() -> capture(process.getErrorStream(), line -> { }));
            try {
                while (process.isAlive() || !stdout.isDone() || !stderr.isDone()) {
                    AuthException.checkCancelled(progress);
                    process.descendants().forEach(children::add);
                    if (System.nanoTime() >= deadline) {
                        throw new AuthException(AuthException.Kind.TIMEOUT,
                                "AWS CLI timed out after " + timeout.toSeconds() + " seconds. Reconnect to retry.");
                    }
                    if (stdout.isDone()) {
                        result(stdout);
                    }
                    if (stderr.isDone()) {
                        result(stderr);
                    }
                    TimeUnit.MILLISECONDS.sleep(50);
                }
                AuthException.checkCancelled(progress);
                return new Result(process.exitValue(), result(stdout), result(stderr));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthException(AuthException.Kind.CANCELLED, "AWS authentication cancelled.");
            } finally {
                process.descendants().forEach(children::add);
                children.forEach(child -> {
                    if (child.isAlive()) {
                        child.destroyForcibly();
                    }
                });
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                close(process.getInputStream());
                close(process.getErrorStream());
            }
        }
    }

    private static String capture(InputStream stream, OutputListener output) throws IOException, AuthException {
        var text = new StringBuilder();
        var line = new StringBuilder();
        try (var reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (text.length() + count > OUTPUT_LIMIT) {
                    throw new AuthException(AuthException.Kind.PROCESS, "AWS CLI returned too much output.");
                }
                text.append(buffer, 0, count);
                for (int i = 0; i < count; i++) {
                    if (buffer[i] == '\n') {
                        output.line(line.toString().strip());
                        line.setLength(0);
                    } else {
                        line.append(buffer[i]);
                    }
                }
            }
            if (!line.isEmpty()) {
                output.line(line.toString().strip());
            }
        }
        return text.toString();
    }

    private static String result(Future<String> future) throws AuthException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AuthException auth) {
                throw auth;
            }
            throw new AuthException(AuthException.Kind.PROCESS, "Could not read AWS CLI output.");
        }
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
        }
    }
}

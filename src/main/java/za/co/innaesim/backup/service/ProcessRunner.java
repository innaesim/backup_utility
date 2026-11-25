package za.co.innaesim.backup.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import za.co.innaesim.backup.database.ProcessSpec;

/**
 * Runs a database tool. Each stream gets its own thread, so a tool that fills one pipe while
 * another is unread can't deadlock. If interrupted, the tool is killed.
 */
final class ProcessRunner {
    private static final int STDERR_TAIL_CHARS = 4000;

    /**
     * @param stdoutBytes how many bytes the tool wrote to stdout
     * @param stderrTail  the end of its stderr, for error messages
     */
    record Result(int exitCode, long stdoutBytes, String stderrTail) {}

    private ProcessRunner() {}

    /**
     * @param stdin  fed to the tool; when null its stdin is closed so it can't wait on a prompt
     * @param stdout receives the tool's stdout; when null the output is discarded
     * @throws IOException if {@code stdout} can't be written, such as when the disk is full
     */
    static Result run(ProcessSpec spec, InputStream stdin, OutputStream stdout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(spec.command());
        builder.environment().putAll(spec.environment());
        Process process = builder.start();

        AtomicLong written = new AtomicLong();
        AtomicReference<IOException> writeFailure = new AtomicReference<>();
        StringBuilder stderr = new StringBuilder();

        Thread out = new Thread(() -> copyStdout(process, stdout, written, writeFailure), "tool-stdout");
        Thread err = new Thread(() -> collectStderr(process.getErrorStream(), stderr), "tool-stderr");
        Thread in = new Thread(() -> feedStdin(process, stdin), "tool-stdin");
        out.start();
        err.start();
        in.start();

        try {
            int exit = process.waitFor();
            out.join();
            err.join();
            if (in.isAlive()) in.interrupt();
            if (writeFailure.get() != null) throw writeFailure.get();
            synchronized (stderr) {
                return new Result(exit, written.get(), stderr.toString().strip());
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    private static void copyStdout(Process process, OutputStream target, AtomicLong written, AtomicReference<IOException> failure) {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = process.getInputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (target != null) {
                    try {
                        target.write(buffer, 0, read);
                    } catch (IOException e) {
                        failure.set(e);
                        process.destroyForcibly();
                        return;
                    }
                }
                written.addAndGet(read);
            }
        } catch (IOException e) {
            // The stream closed because the tool died; its exit code reports that.
        }
    }

    private static void collectStderr(InputStream stream, StringBuilder tail) {
        byte[] buffer = new byte[8192];
        try (InputStream in = stream) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                synchronized (tail) {
                    tail.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    if (tail.length() > STDERR_TAIL_CHARS) tail.delete(0, tail.length() - STDERR_TAIL_CHARS);
                }
            }
        } catch (IOException e) {
            // Same as above: the exit code reports it.
        }
    }

    private static void feedStdin(Process process, InputStream stdin) {
        try (OutputStream out = process.getOutputStream()) {
            if (stdin != null) stdin.transferTo(out);
        } catch (IOException e) {
            // A tool that quits early breaks this pipe; its exit code reports why.
        }
    }
}

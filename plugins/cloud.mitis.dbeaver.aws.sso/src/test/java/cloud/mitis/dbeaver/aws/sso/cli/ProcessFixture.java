package cloud.mitis.dbeaver.aws.sso.cli;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "streams" -> {
                System.out.print("o".repeat(131_072));
                System.err.print("e".repeat(131_072));
            }
            case "hang" -> {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                System.out.println("ready");
                System.out.flush();
                Thread.sleep(60_000);
            }
            case "excessive" -> System.out.print("x".repeat(300_000));
            case "environment" -> {
                System.out.println(System.getenv("AWS_CLI_AUTO_PROMPT"));
                System.out.println("pager=" + System.getenv("AWS_PAGER"));
                System.out.println(args[1]);
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}

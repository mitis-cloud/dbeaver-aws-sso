package cloud.mitis.dbeaver.aws.sso;

import org.jkiss.dbeaver.model.impl.auth.AuthModelDatabaseNativeCredentials;

public final class SsoCredentials extends AuthModelDatabaseNativeCredentials {
    public static final String PROFILE = "mitis.aws.profile";
    public static final String REGION = "mitis.aws.region";
    public static final String CLI_PATH = "mitis.aws.cliPath";
    public static final String HOSTNAME = "mitis.aws.hostname";
    public static final String PORT = "mitis.aws.port";

    String profile = "default";
    String region = "";
    String cliPath = "";
    String hostname = "";
    String port = "";

    @Override
    public boolean isComplete() {
        return getUserName() != null && !getUserName().isBlank() && !profile.isBlank() && !region.isBlank();
    }
}

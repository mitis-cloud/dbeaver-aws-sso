package cloud.mitis.dbeaver.aws.sso.cli;

public record RdsConnection(String profile, String region, String hostname, int port, String username) {
    public RdsConnection {
        if (profile == null || profile.isBlank() || region == null || region.isBlank()
                || hostname == null || !hostname.matches("[a-zA-Z0-9.-]+")
                || port < 1 || port > 65535 || username == null || username.isBlank()) {
            throw new IllegalArgumentException("Specify an AWS profile, RDS region, database hostname, port and username.");
        }
    }
}

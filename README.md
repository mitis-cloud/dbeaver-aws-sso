# DBeaver AWS SSO

**Click Connect. Sign in with AWS in your browser. Start querying.**

An Apache-2.0 authentication plugin for DBeaver Community. It uses your installed AWS CLI to authenticate with AWS IAM Identity Center and generate RDS IAM database tokens.

Supports DBeaver's PostgreSQL, MySQL and MariaDB connection providers with their normal JDBC drivers. AWS must support IAM database authentication for the selected RDS/Aurora engine and version.

## Install

Requires **DBeaver Community 26.2.1** and **AWS CLI v2.22.0 or newer**. Use the current AWS CLI v2 release; the minimum enables its default PKCE browser flow.

1. Open **Help → Install New Software → Add**.
2. Enter this update-site URL:

   ```text
   https://mitis-cloud.github.io/dbeaver-aws-sso/
   ```

3. Select **AWS IAM Identity Center for DBeaver**, finish installation, and restart DBeaver.

The update site is published from `main` after Linux, Windows and macOS builds and tests pass. A zipped update site is also available in the [build artifacts](https://github.com/mitis-cloud/dbeaver-aws-sso/actions). Install it using **Add → Archive**.

## Connect

Configure an IAM Identity Center profile once, using your organization's configuration or `aws configure sso`. Interactive login is handled by DBeaver thereafter.

1. Create or edit a PostgreSQL, MySQL or MariaDB connection.
2. Set the host, port, database and database username.
3. Select **Authentication → AWS IAM Identity Center**.
4. Select your **AWS profile** and enter the **RDS region**.
5. Click **Connect** or **Test Connection**.

RDS certificates and verified TLS are configured automatically. Leave the SSL tab at its defaults for a standard RDS/Aurora connection; no certificate files need downloading or selecting.

If AWS can reuse or refresh your session, the connection opens directly. When interactive login is necessary, DBeaver opens your default browser and waits up to five minutes. Complete sign-in and consent; DBeaver resumes the connection automatically.

| Setting | Meaning |
| --- | --- |
| AWS profile | Named profile from the standard AWS configuration files. The dropdown can be refreshed, and a name can also be typed. |
| RDS region | Database region, which can differ from the IAM Identity Center region. |
| AWS CLI executable | Optional full executable path. Useful when a desktop app has a different `PATH`, or AWS CLI v1 is also installed. |
| Signing hostname | Optional real RDS endpoint. Defaults to the original connection hostname before DBeaver rewrites it for a tunnel. |
| Signing port | Optional real RDS port. Defaults to the original connection port. |

AWS CLI discovery honors an explicit executable, then `AWS_CLI_PATH`, standard installation locations, and finally `PATH`. Custom AWS config/credentials locations and proxy settings use the environment inherited by DBeaver. Executable paths and profile names with spaces are supported.

For URL-based connections, or a manually established local tunnel, specify the signing hostname and port explicitly. IAM signing uses the RDS endpoint, not `localhost` or a custom DNS alias. TLS hostname verification through a tunnel still needs the driver's appropriate hostname-preserving configuration.

## AWS prerequisites

- IAM database authentication enabled for the database.
- A database user configured for IAM authentication.
- The selected AWS role permitted to use `rds-db:connect` for that database user and DB resource ID.
- Network access to the database, directly or through your existing tunnel/VPN.

See AWS's [IAM database authentication guide](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.html) for engine-specific setup. Signing a token locally does not prove that the role can connect; the database checks authorization during connection.

## Behavior

- AWS CLI owns AWS profiles, SSO tokens, refresh and account/role credential resolution.
- The plugin runs `aws sso login --no-browser` only for recognized missing/expired SSO-session errors. DBeaver opens the authorization URL using its native desktop browser launcher. Modern `sso-session` profiles use PKCE; legacy SSO profiles can use the CLI's device-code URL with the code prefilled.
- Authentication failures unrelated to an expired session do not start a browser-login loop. Token generation is retried once after sign-in.
- Concurrent connections using the same executable and profile share one login attempt. Cancelling the initiating connection cancels that shared attempt. Cancelling a waiting connection does not cancel another connection's login.
- A fresh IAM token is generated for every new physical JDBC connection. Existing database sessions do not need reconnecting when a token reaches its 15-minute expiry.
- The plugin saves settings, not AWS credentials or generated DB tokens. Command output and authorization URLs are not written to plugin logs or error messages.
- TLS defaults verify the server identity: PostgreSQL/MariaDB `verify-full`, MySQL `VERIFY_IDENTITY`. The plugin supplies AWS's bundled RDS root CAs automatically, selecting the commercial, China or GovCloud bundle from the RDS region. Explicit connection CA files, trust stores and SSL properties take precedence.
- Certificates are packaged with the plugin, so connection setup needs no certificate-download network request. CA updates arrive through plugin updates. The CA snapshot and official sources are documented in [certificates/README.md](plugins/cloud.mitis.dbeaver.aws.sso/certificates/README.md).

The initial integration targets directly configured IAM Identity Center profiles. Profiles that require an interactive `credential_process`, role-chain-specific login handling, and headless DBeaver execution are outside the supported browser workflow.

## Troubleshooting

**AWS CLI v2 is required:** Set the full path in the connection settings, such as `/usr/local/bin/aws`, `/opt/homebrew/bin/aws`, or `C:\Program Files\Amazon\AWSCLIV2\aws.exe`.

**No browser opens:** Ensure the desktop has a working default browser. The plugin opens only recognized AWS authorization URLs emitted by AWS CLI.

**Sign-in succeeds but the database rejects the connection:** Check the database username, IAM database configuration, `rds-db:connect` resource ARN, RDS region, and signing endpoint.

**TLS certificate error:** Update the plugin and check that the connection hostname matches the RDS endpoint. Standard RDS/Aurora certificates are handled automatically. An explicit custom CA/trust store takes precedence; remove stale overrides to use the bundled certificates. Tunnels, custom DNS names and RDS Proxy can require different hostname/trust settings.

**Generic AWS credential error:** Check the profile using AWS CLI separately. The plugin deliberately avoids including raw CLI output in errors because credential providers may put secrets there.

## Development

Use a full **JDK 21** and **Maven 3.9.9+**:

```text
mvn -B -ntp clean verify
```

The build produces `repository/target/repository/` and a zipped p2 update site in `repository/target/`.

The initial build uses Tycho **5.0.4**, JUnit **6.1.3**, Surefire **3.6.0**, and DBeaver **26.2.1** with Eclipse **2026-09**, checked against current stable releases at project creation. Local verification uses Maven **3.9.16**. Java 21 bytecode matches DBeaver's runtime requirement.

`dbeaver.target` pins the DBeaver bundle versions. DBeaver publishes them through a rolling update URL; if upstream removes those artifacts, deliberately update the target and verify compatibility rather than silently selecting new API versions.

The maintained tests exercise expired/missing sessions, warm sessions, bounded retries, concurrent login success/failure, token handling, argument boundaries, process cancellation/timeouts and pipe draining. TLS tests use actual PostgreSQL, MySQL and MariaDB JDBC drivers with a local TLS endpoint to check trusted certificates, untrusted certificates and hostname mismatches. Test-only driver pins (42.7.13, 26.7.0 and 3.5.10 respectively) were checked against current stable releases; drivers are not shipped in the plugin. Tests do not log in to AWS or establish an RDS session.

Full acceptance requires a real DBeaver connection: expire the test SSO session, click Connect, complete browser sign-in, run a query, then open another physical connection after token expiry. Repeat for each engine/platform you intend to support. Automated test success alone does not establish this end-to-end claim.

## License

[Apache License 2.0](LICENSE). Independent community project, not affiliated with DBeaver or AWS.

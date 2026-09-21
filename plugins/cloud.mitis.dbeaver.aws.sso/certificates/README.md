# Amazon RDS root certificates

Snapshot retrieved on 2026-09-21 from AWS's public HTTPS trust stores:

| File | Official source | Root certificates | Packaged SHA-256 |
| --- | --- | --- | --- |
| `commercial.pem` | https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem | 108 | `e5bb2084ccf45087bda1c9bffdea0eb15ee67f0b91646106e466714f9de3c7e3` |
| `china.pem` | https://rds-truststore.s3.cn-north-1.amazonaws.com.cn/global/global-bundle.pem | 6 | `d10db458b3b428b963a764a78694690d3af80616cd047de7ab56db53fb760a4e` |
| `govcloud.pem` | https://truststore.pki.us-gov-west-1.rds.amazonaws.com/global/global-bundle.pem | 6 | `694a8e0f4376f3133dbd76732b7644264c8a8f4c17b66d306cbec18aae58e46a` |

The commercial and China bundles are unchanged. Four intermediate certificates were omitted from the GovCloud bundle, following [AWS's instruction to trust only root CAs](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html). Its original SHA-256 was `bae59f78f2e2ba789e734cdcac78c13a0f0e99aa3f7bd49f1f37477c815b9b33`.

These are public CA certificates, with no private keys. They are scoped to connections using this authentication plugin; the system and JVM trust stores are not changed. PostgreSQL and MariaDB receive the PEM file through their native JDBC properties. MySQL receives a per-connection trust store produced by DBeaver's certificate storage API.

Maintainers: refresh these snapshots from the official URLs when AWS adds or replaces RDS root CAs, retain self-signed CA certificates only, update this record and run `mvn clean verify` before publishing. Existing server certificate rotations under the same root do not require a plugin update.

package cloud.mitis.dbeaver.aws.sso;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.access.DBAAuthModel;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.ui.dialogs.connection.DatabaseNativeAuthModelConfigurator;

import cloud.mitis.dbeaver.aws.sso.cli.AuthException;
import cloud.mitis.dbeaver.aws.sso.cli.AwsCli;
import cloud.mitis.dbeaver.aws.sso.cli.ProcessRunner;

public final class SsoAuthConfigurator extends DatabaseNativeAuthModelConfigurator {
    private Combo profile;
    private Text region;
    private Text cliPath;
    private Text hostname;
    private Text port;
    private Label status;
    private Job profileJob;

    @Override
    public void createControl(Composite parent, DBAAuthModel<?> model, Runnable changed) {
        super.createControl(parent, model, changed);
        label(parent, "AWS profile");
        Composite profileRow = new Composite(parent, SWT.NONE);
        GridLayout rowLayout = new GridLayout(2, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        profileRow.setLayout(rowLayout);
        profileRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        profile = new Combo(profileRow, SWT.DROP_DOWN);
        profile.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        profile.addModifyListener(event -> changed.run());
        Button refresh = new Button(profileRow, SWT.PUSH);
        refresh.setText("Refresh");
        refresh.addListener(SWT.Selection, event -> loadProfiles());
        region = field(parent, "RDS region", "Region containing the database, e.g. eu-west-1. May differ from your SSO region.", changed);
        cliPath = field(parent, "AWS CLI executable", "Optional full path to AWS CLI v2.22+. Leave empty for automatic discovery.", changed);
        hostname = field(parent, "Signing hostname", "Optional real RDS endpoint. Defaults to the connection's original hostname, before DBeaver tunnelling.", changed);
        port = field(parent, "Signing port", "Optional real RDS port. Defaults to the connection's original port.", changed);
        status = new Label(parent, SWT.WRAP);
        GridData statusLayout = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        statusLayout.widthHint = 420;
        status.setLayoutData(statusLayout);
        status.setText("Connect opens your browser when AWS sign-in is needed. RDS certificates and verified TLS are configured automatically.");
        parent.addDisposeListener(event -> {
            if (profileJob != null) {
                profileJob.cancel();
            }
        });
    }

    @Override
    public void loadSettings(DBPDataSourceContainer dataSource) {
        super.loadSettings(dataSource);
        SsoCredentials credentials = new SsoAuthModel().loadCredentials(dataSource, dataSource.getConnectionConfiguration());
        profile.setText(credentials.profile);
        region.setText(credentials.region);
        cliPath.setText(credentials.cliPath);
        hostname.setText(credentials.hostname);
        port.setText(credentials.port);
        loadProfiles();
    }

    @Override
    public void saveSettings(DBPDataSourceContainer dataSource) {
        super.saveSettings(dataSource);
        DBPConnectionConfiguration config = dataSource.getConnectionConfiguration();
        config.setAuthProperty(SsoCredentials.PROFILE, profile.getText().strip());
        config.setAuthProperty(SsoCredentials.REGION, region.getText().strip());
        config.setAuthProperty(SsoCredentials.CLI_PATH, cliPath.getText().strip());
        config.setAuthProperty(SsoCredentials.HOSTNAME, hostname.getText().strip());
        config.setAuthProperty(SsoCredentials.PORT, port.getText().strip());
        config.setUserPassword(null);
        dataSource.setSavePassword(true);
    }

    @Override
    public boolean isComplete() {
        return super.isComplete() && profile != null && !profile.getText().isBlank()
                && region != null && !region.getText().isBlank();
    }

    private void loadProfiles() {
        if (profileJob != null) {
            profileJob.cancel();
        }
        String requestedPath = cliPath.getText();
        Display display = profile.getDisplay();
        status.setText("Loading AWS profiles…");
        profileJob = new Job("Load AWS profiles") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    List<String> profiles = new AwsCli(AwsCli.locate(requestedPath), new ProcessRunner())
                            .profiles(monitor::isCanceled);
                    display.asyncExec(() -> {
                        if (!profile.isDisposed() && !monitor.isCanceled() && cliPath.getText().equals(requestedPath)) {
                            String selected = profile.getText();
                            profile.setItems(profiles.toArray(String[]::new));
                            profile.setText(selected);
                            status.setText("Connect opens your browser when AWS sign-in is needed. RDS certificates and verified TLS are configured automatically.");
                            status.getParent().layout();
                        }
                    });
                } catch (AuthException e) {
                    display.asyncExec(() -> {
                        if (!status.isDisposed() && !monitor.isCanceled()) {
                            status.setText(e.getMessage());
                            status.getParent().layout();
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        profileJob.setSystem(true);
        profileJob.schedule();
    }

    private static void label(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
    }

    private static Text field(Composite parent, String name, String tooltip, Runnable changed) {
        label(parent, name);
        Text field = new Text(parent, SWT.BORDER);
        field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        field.setToolTipText(tooltip);
        field.addModifyListener(event -> changed.run());
        return field;
    }
}

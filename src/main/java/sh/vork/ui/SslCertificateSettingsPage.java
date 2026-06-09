package sh.vork.ui;

import org.springframework.stereotype.Component;

/**
 * Settings page entry for SSL certificate management.
 */
@Component
public class SslCertificateSettingsPage implements SettingsPage {

    @Override
    public String getIcon() { return "fa-lock"; }

    @Override
    public String getName() { return "SSL Certificate"; }

    @Override
    public String getDescription() { return "View, regenerate, or replace the HTTPS certificate and configure Let's Encrypt."; }

    @Override
    public String getPath() { return "ssl-certificate"; }
}

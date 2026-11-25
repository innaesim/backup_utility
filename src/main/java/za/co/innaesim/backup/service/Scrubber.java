package za.co.innaesim.backup.service;

import java.util.ArrayList;
import java.util.List;

import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.model.DestinationConfig;

/** Removes known secrets from text before it reaches logs or Slack. */
final class Scrubber {
    private final List<String> secrets = new ArrayList<>();

    Scrubber(AppConfig db, Iterable<DestinationConfig> destinations) {
        add(db.getPassword());
        for (DestinationConfig d : destinations) {
            add(d.getPassword());
            add(d.getKeyPassphrase());
            add(d.getSecretKey());
            add(d.getConnectionString());
        }
    }

    private void add(String secret) {
        if (secret != null && !secret.isEmpty()) secrets.add(secret);
    }

    String clean(String text) {
        if (text == null) return null;
        String out = text;
        for (String secret : secrets) out = out.replace(secret, "****");
        return out;
    }
}

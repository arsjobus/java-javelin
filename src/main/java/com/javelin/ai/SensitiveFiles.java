package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Credentials must not become AI context by default (see SECURITY.md). Files that are made to hold secrets are refused
 * by name before anything is prepared; text that looks like it contains a secret is flagged in the preview the user
 * approves. Neither check is a guarantee: they catch the common cases, and the preview shows exactly what would be sent.
 */
public final class SensitiveFiles {
    private SensitiveFiles() {}

    private static final Set<String> EXACT = Set.of(".netrc", "_netrc", ".npmrc", ".pypirc", ".htpasswd", ".git-credentials", ".pgpass", ".my.cnf",
        "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "credentials", "credentials.json", "credentials.xml", "secrets.json", "secrets.yml", "secrets.yaml",
        "secret.json", "service-account.json", "terraform.tfstate", "kubeconfig");
    private static final List<String> SUFFIXES = List.of(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx", ".gpg", ".asc", ".secret", ".secrets", ".tfvars");
    private static final Set<String> ENV_TEMPLATES = Set.of(".env.example", ".env.sample", ".env.template", ".env.dist", ".env.defaults");

    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern AWS_KEY = Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b");
    private static final Pattern TOKEN = Pattern.compile("\\b(ghp|gho|ghu|ghs|github_pat|glpat|xox[abpr])[-_][A-Za-z0-9_-]{16,}");
    private static final Pattern ASSIGNED_SECRET = Pattern.compile("(?i)\\b[\\w.-]*(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)[\\w.-]*\\s*[:=]\\s*[\"']?[^\\s\"'<>${}()]{8,}+(?!\\()");

    /** True for a file name that is meant to hold credentials ({@code .env}, private keys, key stores, cloud credential files). */
    public static boolean isSensitive(String fileName) {
        if (fileName == null) return false;
        String name = fileName.toLowerCase(Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (ENV_TEMPLATES.contains(name)) return false;
        if (name.equals(".env") || name.startsWith(".env.")) return true;
        if (EXACT.contains(name)) return true;
        for (String suffix : SUFFIXES) if (name.endsWith(suffix)) return true;
        return false;
    }

    /** What in {@code text} looks like a secret, as short descriptions; empty when nothing does. */
    public static List<String> warnings(String text) {
        List<String> found = new ArrayList<>();
        if (PRIVATE_KEY.matcher(text).find()) found.add("a private key block");
        if (AWS_KEY.matcher(text).find()) found.add("an access key id");
        if (TOKEN.matcher(text).find()) found.add("an access token");
        if (ASSIGNED_SECRET.matcher(text).find()) found.add("a password, key or token assigned to a name");
        return found;
    }
}

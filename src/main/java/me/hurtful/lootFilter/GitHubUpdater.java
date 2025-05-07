package me.hurtful.lootFilter;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Consumer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub-based updater for LootFilter plugin.
 * Checks for updates and can automatically download them.
 */
public class GitHubUpdater {
    private final JavaPlugin plugin;
    private final String owner;
    private final String repository;
    private final String currentVersion;
    private String latestVersion;
    private String downloadUrl;
    private boolean updateAvailable = false;
    private final UpdateCheckResult result = new UpdateCheckResult();

    /**
     * Creates a new GitHubUpdater.
     *
     * @param plugin The plugin instance
     * @param owner GitHub repository owner
     * @param repository GitHub repository name
     */
    public GitHubUpdater(JavaPlugin plugin, String owner, String repository) {
        this.plugin = plugin;
        this.owner = owner;
        this.repository = repository;
        this.currentVersion = plugin.getDescription().getVersion();
    }

    /**
     * Checks for updates asynchronously.
     *
     * @param callback The callback to run after checking
     */
    public void checkForUpdates(Consumer<UpdateCheckResult> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Get the latest release info from GitHub API
                String releaseUrl = String.format("https://api.github.com/repos/%s/%s/releases/latest", owner, repository);
                URL url = new URL(releaseUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                connection.setRequestProperty("User-Agent", "LootFilter-UpdateChecker");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);

                int responseCode = connection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }

                        // Parse version from tag_name
                        Pattern tagPattern = Pattern.compile("\"tag_name\":\\s*\"v?([^\"]+)\"");
                        Matcher tagMatcher = tagPattern.matcher(response.toString());
                        if (tagMatcher.find()) {
                            latestVersion = tagMatcher.group(1);
                        } else {
                            plugin.getLogger().warning("Failed to parse version from GitHub response");
                        }

                        // Parse download URL for jar file
                        Pattern assetPattern = Pattern.compile("\"browser_download_url\":\\s*\"([^\"]+\\.jar)\"");
                        Matcher assetMatcher = assetPattern.matcher(response.toString());
                        if (assetMatcher.find()) {
                            downloadUrl = assetMatcher.group(1);
                        } else {
                            // If no specific jar found, try to use zipball_url as fallback
                            Pattern zipPattern = Pattern.compile("\"zipball_url\":\\s*\"([^\"]+)\"");
                            Matcher zipMatcher = zipPattern.matcher(response.toString());
                            if (zipMatcher.find()) {
                                downloadUrl = zipMatcher.group(1);
                            }
                        }

                        updateAvailable = isNewerVersion(latestVersion, currentVersion);

                        result.setUpdateAvailable(updateAvailable);
                        result.setCurrentVersion(currentVersion);
                        result.setLatestVersion(latestVersion);
                    }
                } else {
                    // Try to read error stream to get more information
                    StringBuilder errorResponse = new StringBuilder();
                    try (BufferedReader errorReader = new BufferedReader(
                            new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            errorResponse.append(line);
                        }
                    } catch (Exception e) {
                        // Ignore error reading error stream
                    }

                    String errorDetails = errorResponse.length() > 0 ?
                            ": " + errorResponse.toString() : "";

                    plugin.getLogger().log(Level.WARNING, "Failed to check for updates: HTTP " + responseCode);
                    result.setErrorMessage("Failed to check for updates: HTTP " + responseCode);
                }

                // Run the callback on the main thread
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error checking for updates", e);
                result.setErrorMessage("Exception: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
            }
        });
    }

    /**
     * Downloads the latest update asynchronously.
     *
     * @param callback The callback to run after downloading
     */
    public void downloadUpdate(Consumer<Boolean> callback) {
        if (!updateAvailable || downloadUrl == null) {
            callback.accept(false);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Create a connection to the download URL
                URL url = new URL(downloadUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                connection.setRequestProperty("User-Agent", "LootFilter-Updater");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);

                // Get the plugin jar file path
                Path pluginPath = plugin.getDataFolder().getParentFile().toPath()
                        .resolve(plugin.getDescription().getName() + ".jar");

                // Create a temporary file
                Path tempFile = Files.createTempFile("LootFilter-update", ".jar");

                // Download the file
                try (InputStream in = connection.getInputStream()) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }

                // Schedule replacing the plugin file on server shutdown
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        Path updateFile = plugin.getDataFolder().getParentFile().toPath()
                                .resolve("update").resolve(plugin.getDescription().getName() + ".jar");

                        // Create update directory if it doesn't exist
                        Files.createDirectories(updateFile.getParent());

                        // Move the temp file to the update directory
                        Files.move(tempFile, updateFile, StandardCopyOption.REPLACE_EXISTING);

                        plugin.getLogger().info("Downloaded new version successfully! Restart the server to apply the update.");
                        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(true));
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to prepare update file", e);
                        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(false));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to download update", e);
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(false));
            }
        });
    }

    /**
     * Compares version strings to determine if newVersion is newer than currentVersion.
     *
     * @param newVersion New version string
     * @param currentVersion Current version string
     * @return true if newVersion is newer
     */
    private boolean isNewerVersion(String newVersion, String currentVersion) {
        if (newVersion == null || currentVersion == null) {
            return false;
        }

        String[] current = currentVersion.split("\\.");
        String[] latest = newVersion.split("\\.");

        // Compare each part of the version
        int length = Math.max(current.length, latest.length);
        for (int i = 0; i < length; i++) {
            int currentPart = i < current.length ? parseInt(current[i]) : 0;
            int latestPart = i < latest.length ? parseInt(latest[i]) : 0;

            if (latestPart > currentPart) {
                return true;
            } else if (latestPart < currentPart) {
                return false;
            }
        }

        // If we get here, versions are identical
        return false;
    }

    /**
     * Safely parses an integer from a string.
     *
     * @param s String to parse
     * @return Parsed integer or 0 if invalid
     */
    private int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Class to hold the result of an update check.
     */
    public static class UpdateCheckResult {
        private boolean updateAvailable;
        private String currentVersion;
        private String latestVersion;
        private String errorMessage;

        public boolean isUpdateAvailable() {
            return updateAvailable;
        }

        public void setUpdateAvailable(boolean updateAvailable) {
            this.updateAvailable = updateAvailable;
        }

        public String getCurrentVersion() {
            return currentVersion;
        }

        public void setCurrentVersion(String currentVersion) {
            this.currentVersion = currentVersion;
        }

        public String getLatestVersion() {
            return latestVersion;
        }

        public void setLatestVersion(String latestVersion) {
            this.latestVersion = latestVersion;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }
}
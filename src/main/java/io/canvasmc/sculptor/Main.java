package io.canvasmc.sculptor;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.jar.JarFile;

public class Main {
    public static void main(String[] args) {
        // build arguments
        LaunchSpecifications specifications = buildSpecificationsFromProperties();
        if (specifications == null) {
            throw new IllegalArgumentException("Couldn't build launch specification arguments");
        }
        System.out.println("Launching Sculptor searching for Minecraft version " + specifications.minecraftVersion());
        ApiClient api = new ApiClient();
        ApiClient.Build build;
        try {
            build = api.getLatestBuildForVersion(
                    specifications.minecraftVersion, specifications.includeExperimental
            );
            if (build == null) {
                throw new FileNotFoundException("Couldn't find build information from Jenkins");
            }
        } catch (Throwable err) {
            System.err.println("Couldn't find Jenkins build from API: " + err.getMessage());
            System.err.println("Defaulting to local server jar if found, will exit if not found");
            err.printStackTrace(System.err);
            build = null;
        }
        Path root = Path.of("");
        File activeBuildFile = getOrCreate(root.toAbsolutePath()).toFile();
        File jarFile = root.toAbsolutePath().resolve(specifications.fileName).toFile();
        if (build != null) {
            // build was found, check for updates
            String commit = build.commits().length == 0 ? "'EMPTY'" : build.commits()[0].hash();
            System.out.println("Located build '" + build.buildNumber() + "', commit " + commit + " from Jenkins API, checking for updates");
            try {
                String hashed = generateKeyBlock(build.buildNumber(), commit, specifications.minecraftVersion);
                String latest = Files.exists(activeBuildFile.toPath())
                        ? Files.readString(activeBuildFile.toPath()).trim()
                        : "";
                if (!hashed.equalsIgnoreCase(latest)) {
                    // needs update
                    System.out.println("Updating Canvas jar...");
                    updateJar(activeBuildFile, hashed, build, root, jarFile);
                    System.out.println("Updated Canvas jar, booting...");
                } else {
                    System.out.println("Canvas jar is up to date! Booting...");
                }
            } catch (Exception err) {
                throw new RuntimeException("Couldn't update Canvas jar", err);
            }
        } else {
            // check if local version exists, if it does, launch, otherwise exit
            System.out.println("Couldn't locate build from Jenkins API, running fallback");
            if (jarFile.exists()) {
                System.out.println("Local jar currently exists, using as fallback");
            }
        }
        if (!jarFile.exists()) {
            System.err.println("Server jar doesn't exist, exiting");
            System.exit(1);
        }
        boot(jarFile, args);
    }

    private static void updateJar(@NonNull File activeBuildFile, String hashed, ApiClient.@NonNull Build build, Path root, File jarFile) throws IOException, InterruptedException {
        Files.writeString(activeBuildFile.toPath(), hashed, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(build.downloadUrl()))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to download file. HTTP " + response.statusCode());
        }

        long length = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1);

        Path tmpJar = root.resolve("server.jar.tmp");

        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(tmpJar, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buf = new byte[8192];
            long total = 0;
            int bytes;

            while ((bytes = in.read(buf)) != -1) {
                out.write(buf, 0, bytes);
                total += bytes;

                if (length > 0) {
                    int progress = (int) ((total * 100) / length);
                    int filled = (int) ((progress / 100.0) * 50);

                    System.out.printf("\rDownloading: [%s] %d%%", "=".repeat(filled) + " ".repeat(50 - filled), progress);
                }
            }
        }

        System.out.println("\nDownload complete.");
        Files.move(tmpJar, jarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(tmpJar);
    }

    @SuppressWarnings("resource")
    private static void boot(File serverJar, String[] args) {
        try {
            JarFile jarFile = new JarFile(serverJar);
            String mainClassName = jarFile.getManifest().getMainAttributes().getValue("Main-Class");
            URLClassLoader classLoader = new URLClassLoader(
                    "sculptor", new URL[]{serverJar.toPath().toUri().toURL()}, Main.class.getClassLoader().getParent()
            );
            final Thread bootThread = new Thread(() -> {
                try {
                    final Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
                    final MethodHandle handle = MethodHandles.lookup()
                            .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
                            .asFixedArity();
                    handle.invoke((Object) args);
                } catch (Throwable err) {
                    throw new RuntimeException("Failed to boot Canvas jar", err);
                }
            }, "PaperclipEntry");
            bootThread.setContextClassLoader(classLoader);
            bootThread.start();
        } catch (Throwable err) {
            throw new RuntimeException("Failed to prepare boot of Canvas jar", err);
        }
    }

    private static Path getOrCreate(Path directory) {
        try {
            if (directory == null) throw new NullPointerException("directory");
            Files.createDirectories(directory);

            Path file = directory.resolve("key.txt");
            try {
                return Files.createFile(file);
            } catch (FileAlreadyExistsException e) {
                if (Files.isDirectory(file)) {
                    throw new IOException("A directory named 'key.txt' exists: " + file);
                }
                return file;
            }
        } catch (Exception err) {
            err.printStackTrace(System.err);
            System.exit(1);
            return null;
        }
    }

    private static @Nullable LaunchSpecifications buildSpecificationsFromProperties() {
        String version = System.getProperty("sculptor.minecraftVersion");
        String experimentalStr = System.getProperty("sculptor.includeExperimental", "false"); // default to stable only
        String fileName = System.getProperty("sculptor.serverFileName", "server.jar");

        if (version == null) {
            System.out.println("Version must be specified");
            return null;
        }

        boolean includeExperimental = Boolean.parseBoolean(experimentalStr);
        return new LaunchSpecifications(version, includeExperimental, fileName);
    }

    private static @NonNull String generateKeyBlock(int buildNumber, String gitCommit, String mcVersion) {
        try {
            String input = buildNumber + "-" + gitCommit + "-" + mcVersion;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
            StringBuilder full = new StringBuilder();
            while (full.length() < 120) {
                int buffer = 0, bitsLeft = 0;
                for (byte b : hash) {
                    buffer = (buffer << 8) | (b & 0xFF);
                    bitsLeft += 8;
                    while (bitsLeft >= 5) {
                        full.append(ALPHABET[(buffer >> (bitsLeft - 5)) & 31]);
                        bitsLeft -= 5;
                    }
                }
                if (bitsLeft > 0) {
                    buffer <<= (5 - bitsLeft);
                    full.append(ALPHABET[buffer & 31]);
                }
                if (full.length() < 120) hash = digest.digest(hash);
            }
            full.setLength(120);
            StringBuilder formatted = new StringBuilder();
            for (int i = 0; i < 120; i += 120) {
                formatted.append(full, i, i + 120).append('\n');
            }
            return formatted.toString().trim();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private record LaunchSpecifications(
            String minecraftVersion,
            boolean includeExperimental,
            String fileName
    ) {
    }
}

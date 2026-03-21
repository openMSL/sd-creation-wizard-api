package eu.gaiax.sdcreationwizard.api;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;


/**
 * Clones or pulls the ontology-management-base (OMB) repository at startup
 * and copies SHACL shape files into the wizard's {@code shapes/} directory
 * so they appear as selectable ecosystems in the frontend.
 *
 * <p>Two ecosystems are populated from OMB:
 * <ul>
 *   <li><b>gx4fm-plc-aad</b> – all OMB SHACL files in a flat "Other" category (legacy behaviour)</li>
 *   <li><b>envited-x</b> – ENVITED-X domain shapes organised into categories
 *       (SimulationData, Georeference, Compliance, …)</li>
 * </ul>
 *
 * <p>The repository URL and the subdirectory that contains the schema folders
 * can be overridden via system properties or environment variables:
 * <ul>
 *   <li>{@code GX4FM_REPO_URL} – Git clone URL (default: ASCS-eV/ontology-management-base)</li>
 *   <li>{@code GX4FM_SHAPES_ROOT} – subdirectory inside the repo (default: {@code artifacts})</li>
 * </ul>
 */
@Service
public class Gx4fmService {

    private static final String DEFAULT_REPO_URL = "https://github.com/ASCS-eV/ontology-management-base.git";
    private static final String DEFAULT_SHAPES_ROOT = "artifacts";
    private static final Logger logger = LoggerFactory.getLogger(Gx4fmService.class);

    private static final String REPO_URL = System.getProperty(
            "GX4FM_REPO_URL",
            System.getenv().getOrDefault("GX4FM_REPO_URL", DEFAULT_REPO_URL));

    private static final String SHAPES_ROOT = System.getProperty(
            "GX4FM_SHAPES_ROOT",
            System.getenv().getOrDefault("GX4FM_SHAPES_ROOT", DEFAULT_SHAPES_ROOT));

    //@Value("${sdcreationwizard.gx4fm.enabled}") //TODO use with a startup runner instead of the hardcoded value
    public static final boolean isGx4FmFrameworkEnabled = true;

    public static final boolean isEnvitedXEnabled = Boolean.parseBoolean(
            System.getProperty("ENVITEDX_ENABLED",
                    System.getenv().getOrDefault("ENVITEDX_ENABLED", "true")));

    /**
     * Maps OMB artifact directory names to ENVITED-X wizard categories.
     * Domains not listed here are skipped for the envited-x ecosystem.
     */
    private static final Map<String, String> ENVITEDX_DOMAIN_TO_CATEGORY;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("hdmap",                "SimulationData");
        m.put("scenario",             "SimulationData");
        m.put("environment-model",    "SimulationData");
        m.put("ositrace",             "SimulationData");
        m.put("openlabel",            "SimulationData");
        m.put("openlabel-v2",         "SimulationData");
        m.put("surface-model",        "SimulationData");
        m.put("simulation-model",     "SimulationData");
        m.put("automotive-simulator", "SimulationData");
        m.put("simulated-sensor",     "SimulationData");
        m.put("georeference",         "Georeference");
        m.put("gx",                   "Compliance");
        m.put("envited-x",            "General");
        m.put("general",              "General");
        m.put("description",          "General");
        m.put("tzip21",               "General");
        m.put("service",              "Service");
        m.put("manifest",             "Other");
        m.put("vv-report",            "Other");
        m.put("leakage-test",         "Other");
        m.put("survey",               "Other");
        ENVITEDX_DOMAIN_TO_CATEGORY = Collections.unmodifiableMap(m);
    }


    /**
     * Optional: when set, skip git clone and read OMB artifacts from this local
     * directory instead.  Typically set via Docker volume mount.
     * Overridable via env {@code GX4FM_LOCAL_PATH}.
     */
    private static final String LOCAL_PATH = System.getProperty(
            "GX4FM_LOCAL_PATH",
            System.getenv().getOrDefault("GX4FM_LOCAL_PATH", ""));


    public static void getRepoAndCopyShaclFiles() {
        File scanRoot;

        if (!LOCAL_PATH.isEmpty()) {
            // Use a locally mounted OMB directory (e.g. Docker volume)
            File localOmb = new File(LOCAL_PATH);
            if (!localOmb.exists() || !localOmb.isDirectory()) {
                logger.error("GX4FM_LOCAL_PATH '{}' does not exist or is not a directory", LOCAL_PATH);
                return;
            }
            scanRoot = SHAPES_ROOT.equals(".")
                    ? localOmb
                    : new File(localOmb, SHAPES_ROOT);
            if (!scanRoot.exists()) {
                logger.warn("Shapes root '{}' not found under local path, falling back to '{}'", SHAPES_ROOT, LOCAL_PATH);
                scanRoot = localOmb;
            }
            logger.info("Using local OMB at {} (shapes root: {})", LOCAL_PATH, scanRoot);
        } else {
            // Clone / pull from remote
            logger.info("Processing ontology-management-base from {} (shapes root: {})...", REPO_URL, SHAPES_ROOT);
            File localPath = new File("./ontology-management-base_temp");

            if (localPath.exists()) {
                try (Git git = Git.open(localPath)) {
                    if (!git.status().call().isClean()) {
                        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();
                    }
                    git.pull().call();
                } catch (GitAPIException | IOException e) {
                    logger.error("Failed to pull repo: {}  ", e.getMessage());
                    return;
                }
            } else {
                try {
                    Git.cloneRepository()
                    .setURI(REPO_URL)
                    .setBranch("main")
                    .setDirectory(localPath)
                    .call();
                } catch (GitAPIException e) {
                    logger.error("Failed to clone repo: {}  ", e.getMessage());
                    return;
                }
            }

            scanRoot = SHAPES_ROOT.equals(".")
                    ? localPath
                    : new File(localPath, SHAPES_ROOT);

            if (!scanRoot.exists()) {
                logger.warn("Shapes root '{}' not found in cloned repo, falling back to repo root", SHAPES_ROOT);
                scanRoot = localPath;
            }
        }

        // --- gx4fm-plc-aad ecosystem (legacy flat copy) ---
        // When envited-x is enabled, exclude domains already mapped there to
        // avoid duplicate shapes across ecosystems.
        Set<String> excludeDomains = isEnvitedXEnabled
                ? ENVITEDX_DOMAIN_TO_CATEGORY.keySet()
                : Collections.emptySet();
        try {
            File gx4fmDest = new File("./shapes/gx4fm-plc-aad/Other");
            prepareDestFolder(gx4fmDest);
            copyShaclFiles(scanRoot, gx4fmDest, excludeDomains);
        } catch (IOException ex) {
            logger.error("Failed to copy gx4fm-plc-aad files: {}  ", ex.getMessage());
        }

        // --- envited-x ecosystem (categorised copy) ---
        if (isEnvitedXEnabled) {
            try {
                copyEnvitedXShapes(scanRoot);
            } catch (IOException ex) {
                logger.error("Failed to copy envited-x files: {}  ", ex.getMessage());
            }
        }
    }

    /**
     * Organise OMB SHACL files into {@code shapes/envited-x/{category}/}
     * using the domain-to-category mapping.
     */
    private static void copyEnvitedXShapes(File scanRoot) throws IOException {
        File[] children = scanRoot.listFiles();
        if (children == null) {
            logger.warn("Cannot list files in {}", scanRoot);
            return;
        }

        for (File domainDir : children) {
            if (!domainDir.isDirectory()) continue;

            String category = ENVITEDX_DOMAIN_TO_CATEGORY.get(domainDir.getName());
            if (category == null) {
                logger.debug("Skipping OMB domain '{}' — not mapped to an ENVITED-X category", domainDir.getName());
                continue;
            }

            File destFolder = new File("./shapes/envited-x/" + category);
            destFolder.mkdirs();

            File[] files = domainDir.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (file.isFile() && isShaclFile(file.getName())) {
                    logger.info("Copy envited-x shacl file {} → {}", file.getName(), category);
                    Path targetPath = destFolder.toPath().resolve(file.getName());
                    Files.copy(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Search for SHACL Turtle files in each subdirectory of {@code scanRoot}
     * and copy them to the destination folder, skipping any directory whose
     * name is in {@code excludeDomains}.
     */
    private static void copyShaclFiles(File scanRoot, File destFolder, Set<String> excludeDomains) throws IOException {
        File[] children = scanRoot.listFiles();
        if (children == null) {
            logger.warn("Cannot list files in {}", scanRoot);
            return;
        }
        for (File folder : children) {
            if (!folder.isDirectory()) continue;
            if (excludeDomains.contains(folder.getName())) {
                logger.debug("Skipping domain '{}' (handled by envited-x ecosystem)", folder.getName());
                continue;
            }
            for (File file : Objects.requireNonNull(folder.listFiles())) {
                if (file.isFile() && isShaclFile(file.getName())) {
                    logger.info("Copy shacl file {}", file.getName());
                    Path targetPath = destFolder.toPath().resolve(file.getName());
                    Files.copy(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Check whether a filename looks like a SHACL Turtle file.
     * Matches both legacy ({@code _shacl.ttl}) and current ({@code .shacl.ttl}) naming.
     */
    private static boolean isShaclFile(String name) {
        return name.endsWith("shacl.ttl");
    }

    private static void prepareDestFolder(File destFolder) {
        if (destFolder.exists()) {
            deleteContent(destFolder);
        } else {
            destFolder.mkdirs();
        }
    }

    /**
     * Delete all files from dir
     */
    private static void deleteContent(File directory){
        Collection<File> shaclFilesInDir = List.of(Objects.requireNonNull(directory.listFiles()));
        logger.info("delete files {}", shaclFilesInDir.size());
        shaclFilesInDir.forEach(FileUtils::deleteQuietly);
    }
}
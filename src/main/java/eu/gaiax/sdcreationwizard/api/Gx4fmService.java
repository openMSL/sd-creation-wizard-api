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
import java.util.Collection;
import java.util.List;
import java.util.Objects;


/**
 * 'getRepoAndCopyShaclFiles' is the primary method called from the controller class.
 * If the Gx4FmFramework is enabled it clones or pulls the ontology-management-base
 * repository and copies the SHACL shape files into the shapes directory.
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


    public static void getRepoAndCopyShaclFiles() {
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
        try {
            File destFolder = new File("./shapes/gx4fm-plc-aad/Other");
            if (destFolder.exists()) {
                deleteContent(destFolder);
            } else {
                destFolder.mkdirs();
            }

            File scanRoot = SHAPES_ROOT.equals(".")
                    ? localPath
                    : new File(localPath, SHAPES_ROOT);

            if (!scanRoot.exists()) {
                logger.warn("Shapes root '{}' not found in cloned repo, falling back to repo root", SHAPES_ROOT);
                scanRoot = localPath;
            }

            copyShaclFiles(scanRoot, destFolder);
        } catch (IOException ex) {
            logger.error("Failed to copy files: {}  ", ex.getMessage());
        }
    }

    /**
     * Search for "_shacl.ttl" files in each subdirectory of {@code scanRoot}
     * and copy them to the shapes folder.
     */
    private static void copyShaclFiles(File scanRoot, File destFolder) throws IOException {
        File[] children = scanRoot.listFiles();
        if (children == null) {
            logger.warn("Cannot list files in {}", scanRoot);
            return;
        }
        for (File folder : children) {
            if (!folder.isDirectory()) continue;
            for (File file : Objects.requireNonNull(folder.listFiles())) {
                if (file.isFile() && file.getName().contains("_shacl.ttl")) {
                    logger.info("Copy shacl file {}", file.getName());
                    Path targetPath = destFolder.toPath().resolve(file.getName());
                    Files.copy(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
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
// Post-generate hook: renames `gitignore` → `.gitignore` in the generated project.
// Workaround for maven-archetype-plugin's default-excludes filtering out files
// matching `**/.gitignore` from the bundled archetype jar — we ship the file under
// a non-dotfile name and rename it back here.

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

def projectDir = Paths.get(request.outputDirectory, request.artifactId)
def src = projectDir.resolve("gitignore")
def dst = projectDir.resolve(".gitignore")

if (Files.exists(src)) {
    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
}

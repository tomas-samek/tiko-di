// Post-generate hook: renames dotfile-named templates back to their dot form in
// the generated project. Workaround for maven-archetype-plugin's default-excludes
// stripping files matching `**/.dotname` from the bundled archetype jar — we ship
// each one under a non-dotfile name and rename it back here.

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

def projectDir = Paths.get(request.outputDirectory, request.artifactId)

def renames = [
    "gitignore": ".gitignore",
    "mcp.json": ".mcp.json",
]

renames.each { src, dst ->
    def srcPath = projectDir.resolve(src)
    def dstPath = projectDir.resolve(dst)
    if (Files.exists(srcPath)) {
        Files.move(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING)
    }
}

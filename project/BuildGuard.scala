import java.io.{File, IOException}
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/**
 * Fail-closed guard for build-policy sources (Phase 2 review F1).
 *
 * sbt loads every `*.sbt` file in every project base directory, and those directories belong to
 * module owners. Only `/build.sbt` and files under `/project/` (owner: build-engineer) may define
 * the build. Any other `*.sbt` file anywhere in the repository fails the build.
 *
 * `enforceAtLoad` is called from a strict top-level `val` in `/build.sbt`. sbt initializes that
 * file while discovering projects, before any subproject `*.sbt` file is evaluated, so a hostile
 * subproject file cannot override this check with settings: the load aborts first. The
 * `verifyBuildSources` task (first step of `sbt check`) repeats the scan against sbt's build base,
 * for files added while an sbt session is already running.
 */
object BuildGuard {
  // Directories that sbt never loads build definitions from; pruned to keep the scan cheap.
  private val prunedNames: Set[String] =
    Set(".git", "target", ".bsp", ".bloop", ".metals", ".idea", ".terraform", "node_modules")
  // Root-relative directories that are owned by build-engineer (allowed) or hold bulk data.
  private val prunedRelative: Set[String] = Set("project", "datasets/downloads")

  /** Directory sbt was launched from; sbt itself uses it as the build root. */
  def launchBase: File = new File(sys.props("user.dir")).getCanonicalFile

  private def isSbtName(name: String): Boolean = name.toLowerCase(Locale.ROOT).endsWith(".sbt")

  /** Root-relative paths of sbt files other than `/build.sbt` and those under `/project/`. */
  def foreignSbtFiles(base: File): Seq[String] = {
    val root = base.getCanonicalFile.toPath
    val found = ListBuffer.empty[String]
    def rel(p: Path): String = root.relativize(p).toString.replace(File.separatorChar, '/')

    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          if (dir == root) FileVisitResult.CONTINUE
          else if (
            prunedNames.contains(dir.getFileName.toString) || prunedRelative.contains(rel(dir))
          ) FileVisitResult.SKIP_SUBTREE
          else FileVisitResult.CONTINUE

        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          val r = rel(file)
          if (isSbtName(file.getFileName.toString) && r != "build.sbt") found += r
          // The walk does not follow symlinks. A symlinked directory could still be a project
          // base, so its direct *.sbt children are reported too.
          if (attrs.isSymbolicLink && Files.isDirectory(file)) {
            val children = Files.list(file)
            try
              children.iterator.asScala
                .filter(c => isSbtName(c.getFileName.toString))
                .foreach(c => found += s"$r/${c.getFileName} (via symlink)")
            finally children.close()
          }
          FileVisitResult.CONTINUE
        }

        override def visitFileFailed(file: Path, exc: IOException): FileVisitResult =
          throw new IOException(s"BuildGuard: cannot inspect ${rel(file)}; refusing to load", exc)
      }
    )
    found.toList.sorted
  }

  private def message(bad: Seq[String]): String =
    s"Build policy violation: *.sbt files outside /build.sbt and /project/ are not allowed " +
      s"(they would let a module owner rewrite build policy). Remove: ${bad.mkString(", ")}. " +
      "Owner of build definitions: build-engineer."

  /** Throws (aborting the build load) when a foreign `*.sbt` file exists. */
  def enforce(base: File): Unit = {
    val bad = foreignSbtFiles(base)
    if (bad.nonEmpty) sys.error(message(bad))
  }

  /** Load-time entry point used by the strict guard `val` in `/build.sbt`. */
  def enforceAtLoad(): Unit = enforce(launchBase)
}

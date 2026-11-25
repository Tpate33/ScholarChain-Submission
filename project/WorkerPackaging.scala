import sbt._
import java.security.MessageDigest
import scala.collection.JavaConverters._
import java.util.concurrent.TimeUnit

/** Explicit worker staging, independent of the normal services package and module graph. */
object WorkerPackaging {

  /**
   * Build-time base-image check (BE-P13-01), a shell-form RUN body for both OCI images: fails the
   * image build unless the base holds a non-empty lib/server/libjvm.so and `java -version` exits 0.
   * Prints the architecture, the library size and the Java version into the build log. No network.
   */
  val BaseJvmProbe: String =
    "set -eu; uname -m; " +
      "jvm=$(find / -xdev -path '*/lib/server/libjvm.so' -type f 2>/dev/null | head -n 1); " +
      "ls -l \"$jvm\"; test -s \"$jvm\"; java -version"

  private val mains = Seq(
    ("production-load", "loader", "scholarchain.services.ProductionLoadMain", "ProductionLoadMain"),
    (
      "s3-materialize",
      "loader",
      "scholarchain.storage.local.S3MaterializeMain",
      "materialize CLI:"
    ),
    (
      "er-decisions",
      "er",
      "scholarchain.entityresolution.review.DecisionRunMain",
      "usage: DecisionRunMain"
    ),
    (
      "er-grouping",
      "er",
      "scholarchain.entityresolution.review.GroupingRunMain",
      "usage: GroupingRunMain"
    )
  )

  private def digest(file: File): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val in = new java.io.FileInputStream(file)
    try {
      val bytes = new Array[Byte](65536)
      var n = in.read(bytes)
      while (n >= 0) {
        if (n > 0) md.update(bytes, 0, n)
        n = in.read(bytes)
      }
    } finally in.close()
    md.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  private def stageClasspath(root: File, name: String, jars: Seq[File]): Unit = {
    val unique = jars.map(_.getCanonicalFile).distinct
    require(
      unique.nonEmpty && unique.forall(f => f.isFile && f.getName.endsWith(".jar")),
      "Worker classpaths must contain packaged jars only"
    )
    require(unique.groupBy(_.getName).forall(_._2.size == 1), "Worker jar filename collision")
    val paths = unique.map { jar =>
      val relative = name + "/lib/" + jar.getName
      IO.copyFile(jar, root / relative)
      relative
    }
    IO.write(root / name / "classpath.txt", paths.mkString(":"))
  }

  def stage(out: File, loader: Seq[File], er: Seq[File], baseImage: String, log: Logger): File = {
    IO.delete(out)
    IO.createDirectory(out)
    stageClasspath(out, "loader", loader)
    stageClasspath(out, "er", er)
    mains.foreach { case (script, cp, main, _) =>
      IO.write(
        out / "bin" / script,
        """#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
exec java -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -cp "$(cat """ +
          cp + "/classpath.txt)\" " + main + " \"$@\"\n"
      )
    }
    IO.write(
      out / "Dockerfile",
      s"""FROM $baseImage
RUN $BaseJvmProbe
WORKDIR /opt/scholarchain-worker
COPY --chown=10001:10001 . .
RUN mkdir -p /work && chown 10001:10001 /work
USER 10001:10001
ENTRYPOINT ["/bin/sh", "/opt/scholarchain-worker/bin/production-load"]
"""
    )
    IO.write(out / ".dockerignore", "Dockerfile\n.dockerignore\n")
    IO.write(
      out / "README.txt",
      """ScholarChain isolated workers (Java 17). No datasets, config or credentials bundled.
Run from any working directory: sh /path/to/bundle/bin/production-load <existing CLI arguments>
S3 materialization: sh /path/to/bundle/bin/s3-materialize --manifest-json <json> --root /work
Add --execute <manifest runId> only under separate authorization; default validates only.
ER: sh /path/to/bundle/bin/er-decisions <existing CLI arguments>
    sh /path/to/bundle/bin/er-grouping <existing CLI arguments>
Configuration follows the existing application configuration contract. Mount inputs read-only
and output roots writable by UID 10001. No automatic download, AWS call or container build
occurs during staging. Launching --execute requires separate run authorization.
Build an OCI image only after separately verifying the ledger base image is cached locally.
Override the image entrypoint for ER with --entrypoint /bin/sh and the desired bin path.
The base is the ledger image reference; the build fails unless its libjvm.so is non-empty
and java -version succeeds. Build for linux/amd64 (the ECS task architecture).
"""
    )
    val files = (out ** "*").get.filter(_.isFile).sortBy(f => IO.relativize(out, f).get)
    IO.write(
      out / "SHA256SUMS",
      files.map(f => digest(f) + "  " + IO.relativize(out, f).get).mkString("", "\n", "\n")
    )
    val archive = out.getParentFile / "scholarchain-workers.zip"
    IO.zip(
      (out ** "*").get
        .filter(_.isFile)
        .sortBy(f => IO.relativize(out, f).get)
        .map(f => f -> IO.relativize(out, f).get),
      archive,
      Some(0L)
    )
    log.info(
      s"Portable worker bundle: $out; archive: $archive; sha256=${digest(archive)}; bytes=${archive.length()}"
    )
    out
  }

  /** Uses a moved, extracted archive: no original target/classes or dependency cache at launch. */
  def verify(staged: File, log: Logger): Unit = {
    val temp = IO.createTemporaryDirectory
    try {
      val relocated = temp / "relocated worker bundle"
      IO.unzip(staged.getParentFile / "scholarchain-workers.zip", relocated)
      IO.readLines(relocated / "SHA256SUMS").foreach { line =>
        val parts = line.split("  ", 2)
        require(
          parts.length == 2 && digest(relocated / parts(1)) == parts(0),
          "Worker checksum mismatch"
        )
      }
      Seq("loader", "er").foreach { cp =>
        val paths = IO.read(relocated / cp / "classpath.txt").split(":").toSeq
        require(
          paths.forall(p => !new File(p).isAbsolute && (relocated / p).isFile),
          "Nonportable classpath"
        )
        require(paths.exists(_.contains("scholarchain-core")), "Missing core artifact")
        if (cp == "loader")
          require(
            paths.exists(_.contains("parquet-hadoop")) && paths.exists(
              _.contains("hadoop-client-api")
            ),
            "Loader readers absent"
          )
        else
          require(
            !paths.exists(p =>
              p.contains("hadoop") || p.contains("parquet") || p.contains("awssdk") || p.contains(
                "scholarchain-services"
              )
            ),
            "ER dependency isolation violated"
          )
      }
      mains.foreach { case (script, _, _, expected) =>
        val output = temp / (script + ".log")
        val pb = new ProcessBuilder(
          "/bin/sh",
          (relocated / "bin" / script).getAbsolutePath,
          "--worker-packaging-invalid-argument"
        )
        pb.directory(temp).redirectErrorStream(true).redirectOutput(output)
        // An isolated invalid-argument probe must not inherit application/cloud configuration.
        pb.environment()
          .keySet()
          .asScala
          .toVector
          .filter(k =>
            k.startsWith("AWS_") || k.startsWith("SCHOLARCHAIN_") ||
              Set("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH").contains(k)
          )
          .foreach(pb.environment().remove)
        val child = pb.start()
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
          child.destroyForcibly()
          sys.error("Worker relocation probe timed out: " + script)
        }
        require(
          child.exitValue() != 0 && IO.read(output).contains(expected),
          "Worker relocation probe failed: " + script + ": " + IO.read(output)
        )
      }
      log.info(
        "Worker archive checksums, relative classpaths, loader/ER isolation and 4 relocated CLI refusal probes PASS (no AWS/model calls)"
      )
    } finally IO.delete(temp)
  }
}

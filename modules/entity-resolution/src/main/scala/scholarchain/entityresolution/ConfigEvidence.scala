package scholarchain.entityresolution

import java.nio.file.Path

import scala.util.Try

import scholarchain.core.Canonical
import scholarchain.core.config.{ConfigError, ConfigKeys, ConfigLoader, ScholarChainConfig}

/**
 * A resolved configuration together with the evidence of where it came from (ER-P5-15).
 *
 * @param result
 *   the core [[ConfigLoader]] result (unchanged: precedence, validation, refusals are core's)
 * @param files
 *   (absolute path, SHA-256 hex) of every configuration file the loader read, in the order of
 *   `SCHOLARCHAIN_CONFIG_FILES`; the hash is of exactly the text the loader parsed (the file's
 *   UTF-8 bytes, so it equals `shasum -a 256 <file>`)
 * @param overrides
 *   names (never values) of the `SCHOLARCHAIN_CFG_*` variables set in the environment; each one
 *   overrides the files for its key (core precedence), and an unknown one is refused by the loader
 * @param injected
 *   true when the configuration was supplied in-process (tests): no file evidence exists
 */
final case class LoadedConfig(
    result: Either[Vector[ConfigError], ScholarChainConfig],
    files: Vector[(String, String)],
    overrides: Vector[String],
    injected: Boolean
)

/**
 * Loads the external configuration through core [[ConfigLoader.load]] and records which files were
 * read and their SHA-256, so every evidence run prints exactly what configured it. One read per
 * file: the hash is computed from the same text the loader parses.
 */
object ConfigEvidence {

  /** Loads from an explicit environment and reader, recording every file read. */
  def load(env: Map[String, String], readFile: String => Either[String, String]): LoadedConfig = {
    val seen = Vector.newBuilder[(String, String)]
    val result = ConfigLoader.load(
      env,
      p => {
        val r = readFile(p)
        r.foreach { text =>
          val abs = Try(Path.of(p).toAbsolutePath.normalize.toString).getOrElse(p)
          seen += (abs -> Canonical.sha256HexUtf8(text))
        }
        r
      }
    )
    LoadedConfig(
      result,
      seen.result(),
      env.keys.filter(_.startsWith(ConfigKeys.EnvPrefix)).toVector.sorted,
      injected = false
    )
  }

  /** Loads from the process environment and the local file system (as `loadFromSystem`). */
  def system(): LoadedConfig = load(sys.env, ConfigLoader.readLocalFile)

  /** An in-process configuration source (tests); it has no file evidence. */
  def injected(loadConfig: () => Either[Vector[ConfigError], ScholarChainConfig]): LoadedConfig =
    LoadedConfig(loadConfig(), Vector.empty, Vector.empty, injected = true)

  /** The evidence lines every configured run prints. */
  def lines(c: LoadedConfig): Vector[String] =
    if (c.injected) Vector("config source=injected (in-process configuration; no file evidence)")
    else
      (if (c.files.isEmpty) Vector(s"config files=none (${ConfigLoader.FilesEnvVar} read no file)")
       else c.files.map { case (p, h) => s"config file=$p sha256=$h" }) :+
        ("config overrides=" +
          (if (c.overrides.isEmpty) "none"
           else
             c.overrides.mkString(",") +
               s" (${ConfigKeys.EnvPrefix}* variables set; they take precedence over the files)"))
}

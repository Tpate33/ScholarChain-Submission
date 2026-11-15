package scholarchain.core.config

import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import com.typesafe.config.{
  Config,
  ConfigException,
  ConfigFactory,
  ConfigIncludeContext,
  ConfigIncluder,
  ConfigIncluderClasspath,
  ConfigIncluderFile,
  ConfigIncluderURL,
  ConfigList,
  ConfigObject,
  ConfigParseOptions,
  ConfigSyntax,
  ConfigUtil,
  ConfigValue,
  ConfigValueType
}

import scholarchain.core.{StableId, TimeWindow}

/** Typed configuration failures. Loading never throws for invalid input. */
enum ConfigError {
  case MissingFileList(envVar: String)
  case FileUnreadable(path: String, detail: String)
  case MalformedFile(path: String, detail: String)
  case IncludeRejected(path: String, target: String)
  case SubstitutionRejected(path: String, key: String)
  case MissingSchemaVersion(path: String)
  case UnsupportedSchemaVersion(path: String, found: String, supported: Vector[String])
  case UnknownKey(source: String, key: String)
  case MissingKey(key: String, envVar: String)
  case InvalidValue(key: String, source: String, reason: String)

  def message: String = this match {
    case MissingFileList(v) => s"$v is not set; at least one config file is required"
    case FileUnreadable(p, d) => s"cannot read config file $p: $d"
    case MalformedFile(p, d) => s"config file $p is not valid HOCON: $d"
    case IncludeRejected(p, t) =>
      s"config file $p uses an include directive ($t); includes are not allowed"
    case SubstitutionRejected(p, k) =>
      s"config file $p uses a substitution at '$k'; $${...} and $${?...} are not allowed"
    case MissingSchemaVersion(p) => s"config file $p has no string configSchemaVersion"
    case UnsupportedSchemaVersion(p, f, s) =>
      s"config file $p has unsupported configSchemaVersion '$f' (supported: ${s.mkString(", ")})"
    case UnknownKey(s, k) => s"unknown configuration key '$k' in $s"
    case MissingKey(k, v) => s"required key '$k' is not set (file key or environment variable $v)"
    case InvalidValue(k, s, r) => s"invalid value for '$k' from $s: $r"
  }
}

/** Value kinds of configuration keys. */
enum ValueKind {
  case Token, Text, OptionalText, Bucket, Region, AccountId, PositiveInt, AnyLong, Probability,
    Window, Windows, IdentityVersion
}

/** One configuration key: dotted path, kind, and its environment variable name. */
final case class ConfigKey(path: String, kind: ValueKind) {
  val envName: String = ConfigKeys.envNameOf(path)
}

/**
 * The frozen key table of configuration schema `scholarchain.config.v2`. History: v2 (Phase 5) adds
 * the required keys `entityResolution.llmBandLower`, `entityResolution.llmBandUpper`, and
 * `entityResolution.promptVersion`; `scholarchain.config.v1` files are rejected (they lack those
 * keys, and no deployed configuration used v1).
 */
object ConfigKeys {
  val EnvPrefix = "SCHOLARCHAIN_CFG_"

  /**
   * Environment variable name of a key: `SCHOLARCHAIN_CFG_` + path segments, each camelCase segment
   * written as UPPER_SNAKE, joined by `__`. Example: `aws.accountId` ->
   * `SCHOLARCHAIN_CFG_AWS__ACCOUNT_ID`.
   */
  def envNameOf(path: String): String =
    EnvPrefix + path
      .split('.')
      .map(_.flatMap(c => if (c.isUpper) "_" + c.toString else c.toUpper.toString))
      .mkString("__")

  import ValueKind.*
  val all: Vector[ConfigKey] = Vector(
    ConfigKey("environment", Token),
    ConfigKey("aws.region", Region),
    ConfigKey("aws.accountId", AccountId),
    ConfigKey("storage.rawBucket", Bucket),
    ConfigKey("storage.curatedBucket", Bucket),
    ConfigKey("storage.contextBucket", Bucket),
    ConfigKey("storage.vectorBucket", Bucket),
    ConfigKey("storage.vectorIndex", Token),
    ConfigKey("storage.dynamoTablePrefix", Token),
    ConfigKey("storage.neptuneEndpoint", Text),
    ConfigKey("storage.openSearchEndpoint", OptionalText),
    ConfigKey("models.llmModelId", Text),
    ConfigKey("models.embeddingModelId", Text),
    ConfigKey("models.embeddingDimension", PositiveInt),
    ConfigKey("graph.windows", Windows),
    ConfigKey("graph.policyVersion", Token),
    ConfigKey("graph.identityVersion", IdentityVersion),
    ConfigKey("bridge.algorithm", Token),
    ConfigKey("bridge.sampleSize", PositiveInt),
    ConfigKey("bridge.randomSeed", AnyLong),
    ConfigKey("bridge.maxIterations", PositiveInt),
    ConfigKey("community.algorithm", Token),
    ConfigKey("community.maxIterations", PositiveInt),
    ConfigKey("entityResolution.policyVersion", Token),
    ConfigKey("entityResolution.acceptThreshold", Probability),
    ConfigKey("entityResolution.rejectThreshold", Probability),
    ConfigKey("entityResolution.llmBandLower", Probability),
    ConfigKey("entityResolution.llmBandUpper", Probability),
    ConfigKey("entityResolution.promptVersion", Token),
    ConfigKey("runtime.maxPathHops", PositiveInt),
    ConfigKey("runtime.retrievalTopK", PositiveInt),
    ConfigKey("runtime.defaultWindow", Window)
  )
  val byPath: Map[String, ConfigKey] = all.map(k => k.path -> k).toMap
  val byEnv: Map[String, ConfigKey] = all.map(k => k.envName -> k).toMap
  private val prefixes: Set[String] =
    all.flatMap(k => k.path.split('.').inits.drop(1).filter(_.nonEmpty).map(_.mkString("."))).toSet
  def isObjectPrefix(path: String): Boolean = prefixes.contains(path)
}

/**
 * Loads [[ScholarChainConfig]].
 *
 * '''File format.''' Each file is a HOCON document (`.conf`) whose root is an object. It is parsed
 * by Typesafe Config with syntax fixed to `CONF` (never inferred from the file name, which the
 * parser does not see), so comments, unquoted keys and values, dotted path keys (`aws.region = x`),
 * `=` or `:` separators, and multi-line objects and lists are accepted. Strict JSON is valid HOCON
 * and loads to the same values. Each file must carry a top-level string `configSchemaVersion` in
 * [[SupportedSchemaVersions]]. Keys follow [[ConfigKeys.all]]; unknown keys (including a quoted key
 * that contains a dot, such as `"aws.region"`, which is one literal segment) are rejected.
 *
 * '''Rejected HOCON features.'''
 *   - `include` directives of every form (`include "x"`, `file(...)`, `url(...)`, `classpath(...)`,
 *     `required(...)`): the parser is given an includer that fails on every include, so no other
 *     file, URL, or class-path resource is ever read ([[ConfigError.IncludeRejected]]).
 *   - substitutions, both required and optional (a dollar sign followed by `{x}` or `{?x}`), and
 *     `+=` (which HOCON expands to a self-substitution): the parsed file is never resolved; an
 *     unresolved file is rejected with the key path of each substitution
 *     ([[ConfigError.SubstitutionRejected]]). This is what keeps environment variables and system
 *     properties out of files.
 *
 * '''Isolation.''' The loader never calls `ConfigFactory.load`, `defaultReference`,
 * `defaultOverrides`, `systemProperties`, `systemEnvironment`, or `resolve`. Typesafe Config's
 * `config.file`, `config.resource`, `config.url`, and `CONFIG_FORCE_*` mechanisms therefore have no
 * effect. Environment values enter only through the `SCHOLARCHAIN_CFG_*` mapping below. Values are
 * read by walking the `ConfigValue` tree; HOCON is never rendered to JSON.
 *
 * '''Duplicates inside one file''' follow HOCON: when a key repeats, a later non-object value
 * replaces the earlier value; two object values merge key by key (later keys win); a later
 * non-object value (including `null`) replaces an earlier object, and a later object replaces an
 * earlier non-object. This merging finishes inside each file before precedence is applied: every
 * file is reduced to its own set of leaf keys, and files are then layered leaf by leaf. A later
 * file can therefore override individual keys but can never delete or replace an earlier file's
 * subtree; assigning a scalar or `null` to an object prefix such as `runtime` is an unknown key.
 * Lists (`graph.windows`) are leaves and are replaced whole, never concatenated.
 *
 * '''Precedence''' (highest first):
 *   1. environment variables `SCHOLARCHAIN_CFG_<KEY>` ([[ConfigKeys.envNameOf]]);
 *   2. config files listed in `SCHOLARCHAIN_CONFIG_FILES` (comma-separated paths), where a later
 *      file overrides an earlier one key by key;
 *   3. nothing else: there are no built-in defaults, and a key set by no source is an error.
 *
 * Environment value syntax: scalars as plain text; a window as `YYYY-YYYY`; a window list as
 * `YYYY-YYYY,YYYY-YYYY`; an empty value unsets an optional key. Unknown `SCHOLARCHAIN_CFG_*`
 * variables are rejected. `configSchemaVersion` cannot be set from the environment.
 *
 * Type rules: account IDs must be quoted strings (an unquoted `000000000000` is a number);
 * `bridge.randomSeed` accepts an integer literal (full 64-bit range) or a string of digits, never a
 * decimal literal such as `7.0`, because HOCON stores decimals as doubles, which can silently round
 * large values.
 */
object ConfigLoader {
  val FilesEnvVar = "SCHOLARCHAIN_CONFIG_FILES"
  val SchemaVersionKey = "configSchemaVersion"
  val SupportedSchemaVersions: Vector[String] = Vector("scholarchain.config.v2")

  /** Superseded config schema versions, rejected like any unsupported version. */
  val RetiredSchemaVersions: Vector[String] = Vector("scholarchain.config.v1")

  /** Loader-internal value tree, built by walking a parsed HOCON `ConfigValue` tree. */
  private enum Raw {
    case Str(s: String)
    case Num(n: BigDecimal, integral: Boolean)
    case NonFinite(text: String)
    case Bool(b: Boolean)
    case NullValue
    case Arr(items: Vector[Raw])
    case Obj(fields: Map[String, Raw])
  }

  /** Thrown by [[RejectingIncluder]] while parsing; caught in [[parseHocon]]. */
  private final class IncludeAttempt(val target: String)
      extends RuntimeException(s"include rejected: $target")

  /**
   * Fails on every include form. It implements the file, URL, and class-path includer interfaces so
   * that Typesafe Config never substitutes its default includer for those forms, and `withFallback`
   * returns itself so that no fallback includer is ever consulted.
   */
  private object RejectingIncluder
      extends ConfigIncluder
      with ConfigIncluderFile
      with ConfigIncluderURL
      with ConfigIncluderClasspath {
    def withFallback(fallback: ConfigIncluder): ConfigIncluder = this
    def include(context: ConfigIncludeContext, what: String): ConfigObject =
      throw IncludeAttempt(s"\"$what\"")
    def includeFile(context: ConfigIncludeContext, what: File): ConfigObject =
      throw IncludeAttempt(s"file(${what.getPath})")
    def includeURL(context: ConfigIncludeContext, what: URL): ConfigObject =
      throw IncludeAttempt(s"url($what)")
    def includeResources(context: ConfigIncludeContext, what: String): ConfigObject =
      throw IncludeAttempt(s"classpath($what)")
  }

  private def parseOptions(path: String): ConfigParseOptions =
    ConfigParseOptions
      .defaults()
      .setSyntax(ConfigSyntax.CONF)
      .setAllowMissing(false)
      .setOriginDescription(path)
      .setIncluder(RejectingIncluder)

  /** Loads from the process environment and the local file system. */
  def loadFromSystem(): Either[Vector[ConfigError], ScholarChainConfig] =
    load(sys.env, readLocalFile)

  def readLocalFile(path: String): Either[String, String] =
    try Right(Files.readString(Path.of(path), StandardCharsets.UTF_8))
    catch { case NonFatal(e) => Left(String.valueOf(e.getMessage)) }

  /** Loads from an explicit environment and file reader (pure apart from `readFile`). */
  def load(
      env: Map[String, String],
      readFile: String => Either[String, String]
  ): Either[Vector[ConfigError], ScholarChainConfig] =
    env.get(FilesEnvVar).filter(_.nonEmpty) match {
      case None => Left(Vector(ConfigError.MissingFileList(FilesEnvVar)))
      case Some(list) =>
        val paths = list.split(",", -1).toVector
        if (paths.exists(_.isEmpty))
          Left(Vector(ConfigError.InvalidValue(FilesEnvVar, "environment", "empty path in list")))
        else {
          val files = paths.map(p => p -> readFile(p))
          val readErrors = files.collect { case (p, Left(d)) => ConfigError.FileUnreadable(p, d) }
          if (readErrors.nonEmpty) Left(readErrors)
          else resolve(files.collect { case (p, Right(c)) => p -> c }, env)
        }
    }

  /** Resolves file contents (in precedence order, lowest first) plus environment overrides. */
  def resolve(
      files: Vector[(String, String)],
      env: Map[String, String]
  ): Either[Vector[ConfigError], ScholarChainConfig] = {
    val errors = ArrayBuffer.empty[ConfigError]
    var versions = Vector.empty[String]
    var values = Map.empty[String, (Raw, String)]

    files.foreach { case (path, content) =>
      parseFile(path, content) match {
        case Left(e) => errors ++= e
        case Right((version, leaves)) =>
          versions :+= version
          values = values ++ leaves.map { case (k, v) => k -> (v, s"file $path") }
      }
    }
    env.toVector.sortBy(_._1).foreach { case (name, raw) =>
      if (name.startsWith(ConfigKeys.EnvPrefix)) {
        ConfigKeys.byEnv.get(name) match {
          case None => errors += ConfigError.UnknownKey("environment", name)
          case Some(key) =>
            envValue(key, raw) match {
              case Left(reason) =>
                errors += ConfigError.InvalidValue(key.path, s"env $name", reason)
              case Right(v) => values = values.updated(key.path, (v, s"env $name"))
            }
        }
      }
    }
    if (errors.nonEmpty) Left(errors.toVector)
    else build(versions.lastOption.getOrElse(""), values)
  }

  @tailrec
  private def includeAttempt(t: Throwable): Option[IncludeAttempt] = t match {
    case null => None
    case i: IncludeAttempt => Some(i)
    case other => if (other.getCause eq other) None else includeAttempt(other.getCause)
  }

  /** Parses one HOCON document without resolving it; rejects includes and substitutions. */
  private def parseHocon(
      path: String,
      content: String
  ): Either[Vector[ConfigError], ConfigObject] = {
    val parsed: Either[ConfigError, Config] =
      try Right(ConfigFactory.parseString(content, parseOptions(path)))
      catch {
        case NonFatal(e) =>
          includeAttempt(e) match {
            case Some(i) => Left(ConfigError.IncludeRejected(path, i.target))
            case None =>
              e match {
                case c: ConfigException =>
                  Left(ConfigError.MalformedFile(path, String.valueOf(c.getMessage)))
                case other =>
                  Left(
                    ConfigError.MalformedFile(
                      path,
                      s"${other.getClass.getName}: ${other.getMessage}"
                    )
                  )
              }
          }
      }
    parsed match {
      case Left(e) => Left(Vector(e))
      case Right(c) if !c.isResolved =>
        val keys = unresolvedPaths(Vector.empty, c.root)
        Left(
          (if (keys.isEmpty) Vector("<unknown>") else keys).map(
            ConfigError.SubstitutionRejected(path, _)
          )
        )
      case Right(c) => Right(c.root)
    }
  }

  private def display(segments: Vector[String]): String =
    if (segments.isEmpty) "<root>" else ConfigUtil.joinPath(segments.asJava)

  /** Key paths of unresolved values (substitutions) in a parsed, unresolved tree. */
  private def unresolvedPaths(segments: Vector[String], v: ConfigValue): Vector[String] =
    try
      v.valueType match {
        case ConfigValueType.OBJECT =>
          val o = v.asInstanceOf[ConfigObject]
          o.keySet.asScala.toVector.sorted.flatMap(k => unresolvedPaths(segments :+ k, o.get(k)))
        case ConfigValueType.LIST =>
          v.asInstanceOf[ConfigList]
            .asScala
            .toVector
            .zipWithIndex
            .flatMap { case (item, i) =>
              unresolvedPaths(segments, item).map(p => if (p == display(segments)) s"$p[$i]" else p)
            }
        case _ => Vector.empty
      }
    catch { case _: ConfigException.NotResolved => Vector(display(segments)) }

  private val IntegerLiteral = "^-?[0-9]+$".r

  private def toRaw(v: ConfigValue): Raw = v.valueType match {
    case ConfigValueType.OBJECT =>
      val o = v.asInstanceOf[ConfigObject]
      Raw.Obj(o.keySet.asScala.toVector.map(k => k -> toRaw(o.get(k))).toMap)
    case ConfigValueType.LIST => Raw.Arr(v.asInstanceOf[ConfigList].asScala.toVector.map(toRaw))
    case ConfigValueType.NUMBER =>
      // Typesafe Config stores decimals as doubles and turns whole-valued doubles into longs
      // (`9007199254740993.0` becomes 9007199254740992), so the number's type and value are not
      // exact. Its original literal text is: the typed `getString` coercion of a number returns it.
      // That text is parsed exactly, and only an integer literal counts as integral.
      val text = v.atKey("n").getString("n")
      try Raw.Num(BigDecimal(text), integral = IntegerLiteral.matches(text))
      catch { case NonFatal(_) => Raw.NonFinite(text) }
    case ConfigValueType.BOOLEAN =>
      Raw.Bool(v.unwrapped.asInstanceOf[java.lang.Boolean].booleanValue)
    case ConfigValueType.NULL => Raw.NullValue
    case ConfigValueType.STRING => Raw.Str(v.unwrapped.asInstanceOf[String])
  }

  private def parseFile(
      path: String,
      content: String
  ): Either[Vector[ConfigError], (String, Vector[(String, Raw)])] =
    parseHocon(path, content).flatMap { root =>
      val version: Either[ConfigError, String] =
        Option(root.get(SchemaVersionKey)).map(toRaw) match {
          case Some(Raw.Str(v)) if SupportedSchemaVersions.contains(v) => Right(v)
          case Some(Raw.Str(v)) =>
            Left(ConfigError.UnsupportedSchemaVersion(path, v, SupportedSchemaVersions))
          case _ => Left(ConfigError.MissingSchemaVersion(path))
        }
      version match {
        case Left(e) => Left(Vector(e))
        case Right(v) =>
          val leaves = ArrayBuffer.empty[(String, Raw)]
          val unknown = ArrayBuffer.empty[ConfigError]
          def walk(segments: Vector[String], o: ConfigObject): Unit =
            o.keySet.asScala.toVector.sorted.foreach { k =>
              val segs = segments :+ k
              val p = display(segs)
              val child = o.get(k)
              if (segments.isEmpty && k == SchemaVersionKey) ()
              else if (ConfigKeys.byPath.contains(p)) leaves += (p -> toRaw(child))
              else
                child match {
                  case c: ConfigObject if ConfigKeys.isObjectPrefix(p) => walk(segs, c)
                  case _ => unknown += ConfigError.UnknownKey(s"file $path", p)
                }
            }
          walk(Vector.empty, root)
          if (unknown.nonEmpty) Left(unknown.toVector) else Right((v, leaves.toVector))
      }
    }

  private val WindowText = "^([0-9]{4})-([0-9]{4})$".r

  private def windowRaw(s: String): Either[String, Raw] = s match {
    case WindowText(a, b) =>
      Right(
        Raw.Obj(
          Map(
            "startYear" -> Raw.Num(BigDecimal(a.toInt), integral = true),
            "endYear" -> Raw.Num(BigDecimal(b.toInt), integral = true)
          )
        )
      )
    case _ => Left(s"'$s' is not a window YYYY-YYYY")
  }

  private def envValue(key: ConfigKey, raw: String): Either[String, Raw] = key.kind match {
    case ValueKind.OptionalText => Right(if (raw.isEmpty) Raw.NullValue else Raw.Str(raw))
    case _ if raw.isEmpty => Left("empty value")
    case ValueKind.PositiveInt =>
      raw.toIntOption
        .map(i => Raw.Num(BigDecimal(i), integral = true))
        .toRight(s"'$raw' is not an integer")
    case ValueKind.Probability =>
      raw.toDoubleOption
        .filter(d => !d.isNaN && !d.isInfinite)
        .map(d => Raw.Num(BigDecimal(d), integral = false))
        .toRight(s"'$raw' is not a number")
    case ValueKind.Window => windowRaw(raw)
    case ValueKind.Windows =>
      val parts = raw.split(",", -1).toVector.map(windowRaw)
      parts.collectFirst { case Left(e) => e } match {
        case Some(e) => Left(e)
        case None => Right(Raw.Arr(parts.collect { case Right(v) => v }))
      }
    case _ => Right(Raw.Str(raw))
  }

  private val BucketPattern = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$".r
  private val RegionPattern = "^[a-z]{2}(-[a-z]+)+-[0-9]+$".r
  private val AccountPattern = "^[0-9]{12}$".r
  private val IdentityVersionPattern = "^v[0-9]+$".r

  private def build(
      version: String,
      values: Map[String, (Raw, String)]
  ): Either[Vector[ConfigError], ScholarChainConfig] = {
    val errors = ArrayBuffer.empty[ConfigError]

    def raw(path: String): Option[(Raw, String)] = {
      val key = ConfigKeys.byPath(path)
      values.get(path) match {
        case None | Some((Raw.NullValue, _)) if key.kind != ValueKind.OptionalText =>
          errors += ConfigError.MissingKey(path, key.envName); None
        case other => other
      }
    }
    def fail(path: String, src: String, reason: String): Unit =
      errors += ConfigError.InvalidValue(path, src, reason)

    def str(path: String, ok: String => Boolean, what: String): String =
      raw(path) match {
        case Some((Raw.Str(s), src)) =>
          if (ok(s)) s else { fail(path, src, s"'$s' is not $what"); "" }
        case Some((_, src)) => fail(path, src, "expected a string"); ""
        case None => ""
      }
    def text(path: String): String =
      str(path, s => s.nonEmpty && s.trim == s && !s.exists(_.isControl), "non-empty trimmed text")
    def token(path: String): String = str(path, StableId.isToken, "a token [A-Za-z0-9._-]+")
    def optText(path: String): Option[String] =
      values.get(path) match {
        case None | Some((Raw.NullValue, _)) => None
        case Some((Raw.Str(s), _)) if s.nonEmpty && s.trim == s => Some(s)
        case Some((_, src)) => fail(path, src, "expected non-empty trimmed text or null"); None
      }
    def positiveInt(path: String): Int =
      raw(path) match {
        case Some((Raw.Num(n, _), src)) =>
          if (n.isWhole && n >= 1 && n <= Int.MaxValue) n.toInt
          else { fail(path, src, s"$n is not a whole number in [1, ${Int.MaxValue}]"); 0 }
        case Some((_, src)) => fail(path, src, "expected a finite number"); 0
        case None => 0
      }
    def anyLong(path: String): Long =
      raw(path) match {
        case Some((Raw.Num(n, integral), src)) =>
          if (integral && n.isValidLong) n.toLong
          else { fail(path, src, s"$n is not a 64-bit integer literal"); 0L }
        case Some((Raw.Str(s), src)) =>
          s.toLongOption.getOrElse { fail(path, src, s"'$s' is not a 64-bit integer"); 0L }
        case Some((_, src)) => fail(path, src, "expected an integer"); 0L
        case None => 0L
      }
    def probability(path: String): Double =
      raw(path) match {
        case Some((Raw.Num(n, _), src)) =>
          if (n >= 0 && n <= 1) n.toDouble else { fail(path, src, s"$n is outside [0,1]"); 0.0 }
        case Some((_, src)) => fail(path, src, "expected a finite number"); 0.0
        case None => 0.0
      }
    def windowOf(path: String, src: String, v: Raw): Option[TimeWindow] = v match {
      case Raw.Obj(fields) if fields.keySet == Set("startYear", "endYear") =>
        (fields("startYear"), fields("endYear")) match {
          case (Raw.Num(a, _), Raw.Num(b, _)) if a.isValidInt && b.isValidInt =>
            TimeWindow.of(a.toInt, b.toInt) match {
              case Right(w) => Some(w)
              case Left(e) => fail(path, src, e.message); None
            }
          case _ => fail(path, src, "startYear and endYear must be integers"); None
        }
      case _ => fail(path, src, "expected { startYear = Y, endYear = Y }"); None
    }
    def window(path: String): TimeWindow =
      raw(path).flatMap { case (v, src) => windowOf(path, src, v) }.getOrElse(TimeWindow(0, 0))
    def windows(path: String): Vector[TimeWindow] =
      raw(path) match {
        case Some((Raw.Arr(items), src)) =>
          if (items.isEmpty) { fail(path, src, "at least one window is required"); Vector.empty }
          else items.flatMap(windowOf(path, src, _))
        case Some((_, src)) => fail(path, src, "expected a list of windows"); Vector.empty
        case None => Vector.empty
      }

    val config = ScholarChainConfig(
      configSchemaVersion = version,
      environment = token("environment"),
      aws = AwsConfig(
        region = str("aws.region", RegionPattern.matches, "an AWS region name"),
        accountId = str("aws.accountId", AccountPattern.matches, "a 12-digit AWS account ID string")
      ),
      storage = StorageConfig(
        rawBucket = str("storage.rawBucket", BucketPattern.matches, "an S3 bucket name"),
        curatedBucket = str("storage.curatedBucket", BucketPattern.matches, "an S3 bucket name"),
        contextBucket = str("storage.contextBucket", BucketPattern.matches, "an S3 bucket name"),
        vectorBucket = str("storage.vectorBucket", BucketPattern.matches, "an S3 bucket name"),
        vectorIndex = token("storage.vectorIndex"),
        dynamoTablePrefix = token("storage.dynamoTablePrefix"),
        neptuneEndpoint = text("storage.neptuneEndpoint"),
        openSearchEndpoint = optText("storage.openSearchEndpoint")
      ),
      models = ModelConfig(
        llmModelId = text("models.llmModelId"),
        embeddingModelId = text("models.embeddingModelId"),
        embeddingDimension = positiveInt("models.embeddingDimension")
      ),
      graph = GraphConfig(
        windows = windows("graph.windows"),
        policyVersion = token("graph.policyVersion"),
        identityVersion = str("graph.identityVersion", IdentityVersionPattern.matches, "v<digits>")
      ),
      bridge = BridgeConfig(
        algorithm = token("bridge.algorithm"),
        sampleSize = positiveInt("bridge.sampleSize"),
        randomSeed = anyLong("bridge.randomSeed"),
        maxIterations = positiveInt("bridge.maxIterations")
      ),
      community = CommunityConfig(
        algorithm = token("community.algorithm"),
        maxIterations = positiveInt("community.maxIterations")
      ),
      entityResolution = EntityResolutionConfig(
        policyVersion = token("entityResolution.policyVersion"),
        acceptThreshold = probability("entityResolution.acceptThreshold"),
        rejectThreshold = probability("entityResolution.rejectThreshold"),
        llmBandLower = probability("entityResolution.llmBandLower"),
        llmBandUpper = probability("entityResolution.llmBandUpper"),
        promptVersion = token("entityResolution.promptVersion")
      ),
      runtime = RuntimeConfig(
        maxPathHops = positiveInt("runtime.maxPathHops"),
        retrievalTopK = positiveInt("runtime.retrievalTopK"),
        defaultWindow = window("runtime.defaultWindow")
      )
    )
    // Cross-key rules (reject < llmBandLower < llmBandUpper < accept) once every key is valid.
    if (errors.isEmpty)
      EntityResolutionConfig.problems(config.entityResolution).foreach { case (key, reason) =>
        errors += ConfigError.InvalidValue(key, "resolved configuration", reason)
      }
    if (errors.nonEmpty) Left(errors.toVector) else Right(config)
  }
}

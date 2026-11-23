package scholarchain.core.config

import upickle.default.ReadWriter

/** Explicit bounds/paths of one operational load; every location is non-secret configuration. */
final case class ProductionLoadConfig(
    analysisConfigFile: Option[String] = None,
    runDirectory: Option[String] = None,
    loadDirectory: Option[String] = None,
    groupingDirectory: Option[String] = None,
    curatedDirectory: Option[String] = None,
    benchmarkDirectory: Option[String] = None,
    benchmarkFile: Option[String] = None,
    graphBucket: Option[String] = None,
    reportsBucket: Option[String] = None,
    logsBucket: Option[String] = None,
    rawPrefix: Option[String] = None,
    curatedPrefix: Option[String] = None,
    graphPrefix: Option[String] = None,
    semanticContextPrefix: Option[String] = None,
    reportsPrefix: Option[String] = None,
    logsPrefix: Option[String] = None,
    vectorDistance: Option[String] = None,
    writerEndpoint: Option[String] = None,
    sourceMaxFiles: Option[Int] = None,
    sourceMaxDepth: Option[Int] = None,
    parquetMaxFooterBytes: Option[Int] = None,
    parquetMaxPageBytes: Option[Int] = None,
    upstreamMaxRows: Option[Int] = None,
    keyValueMaxItemBytes: Option[Int] = None,
    layoutMaxKeyBytes: Option[Int] = None,
    vectorMaxBatchSize: Option[Int] = None,
    vectorMaxValueBytes: Option[Int] = None,
    vectorMaxFilterableBytes: Option[Int] = None,
    vectorMaxTotalBytes: Option[Int] = None,
    vectorMaxKeys: Option[Int] = None,
    vectorMaxNonFilterableKeys: Option[Int] = None,
    vectorMaxListValues: Option[Int] = None,
    vectorMaxTopK: Option[Int] = None,
    vectorBatchSize: Option[Int] = None,
    vectorRetryMaxAttempts: Option[Int] = None,
    maxRejected: Option[Int] = None,
    maxTextBytes: Option[Int] = None,
    maxKeyBytes: Option[Int] = None,
    maxItemsPerCall: Option[Int] = None,
    maxEntityLinks: Option[Int] = None,
    maxMentionIdsPerLink: Option[Int] = None,
    sourceMaxBytes: Option[Long] = None,
    parquetMaxPartBytes: Option[Long] = None,
    parquetMaxRowsPerPart: Option[Long] = None,
    upstreamMaxBytes: Option[Long] = None,
    benchmarkMaxBytes: Option[Long] = None,
    vectorRetryBaseMillis: Option[Long] = None,
    vectorRetryMaxMillis: Option[Long] = None,
    entityLinksExpiresAtEpochSeconds: Option[Long] = None
) derives ReadWriter

/** Validated external inputs; services maps these into existing storage loader contracts. */
final case class ProductionLoadSettings(
    analysisConfigFile: String,
    runDirectory: String,
    loadDirectory: String,
    groupingDirectory: String,
    curatedDirectory: String,
    benchmarkDirectory: String,
    benchmarkFile: String,
    graphBucket: String,
    reportsBucket: String,
    logsBucket: String,
    rawPrefix: String,
    curatedPrefix: String,
    graphPrefix: String,
    semanticContextPrefix: String,
    reportsPrefix: String,
    logsPrefix: String,
    vectorDistance: String,
    writerEndpoint: String,
    sourceMaxFiles: Int,
    sourceMaxDepth: Int,
    parquetMaxFooterBytes: Int,
    parquetMaxPageBytes: Int,
    upstreamMaxRows: Int,
    keyValueMaxItemBytes: Int,
    layoutMaxKeyBytes: Int,
    vectorMaxBatchSize: Int,
    vectorMaxValueBytes: Int,
    vectorMaxFilterableBytes: Int,
    vectorMaxTotalBytes: Int,
    vectorMaxKeys: Int,
    vectorMaxNonFilterableKeys: Int,
    vectorMaxListValues: Int,
    vectorMaxTopK: Int,
    vectorBatchSize: Int,
    vectorRetryMaxAttempts: Int,
    maxRejected: Int,
    maxTextBytes: Int,
    maxKeyBytes: Int,
    maxItemsPerCall: Int,
    maxEntityLinks: Int,
    maxMentionIdsPerLink: Int,
    sourceMaxBytes: Long,
    parquetMaxPartBytes: Long,
    parquetMaxRowsPerPart: Long,
    upstreamMaxBytes: Long,
    benchmarkMaxBytes: Long,
    vectorRetryBaseMillis: Long,
    vectorRetryMaxMillis: Long,
    entityLinksExpiresAtEpochSeconds: Long
)

object ProductionLoadSettings {
  def from(c: ScholarChainConfig): Either[Vector[ConfigError], ProductionLoadSettings] = {
    val m = ProductionConfig.flatten(c)
    val missing = ProductionLoadKeys.all
      .filterNot(k => m.contains(k.path))
      .map(k => ConfigError.MissingKey(k.path, ConfigKeys.envNameOf(k.path)))
    val invalid = ProductionConfig.problems(c).map { case (k, reason) =>
      ConfigError.InvalidValue(k, "resolved configuration", reason)
    }
    val errors = missing ++ invalid
    if (errors.nonEmpty) Left(errors)
    else Right(ProductionLoadKeys.settings(m))
  }
}

object ProductionLoadKeys {
  import ValueKind.*
  val Prefix = "productionLoad."
  val StringFields: Vector[String] = Vector(
    "analysisConfigFile",
    "runDirectory",
    "loadDirectory",
    "groupingDirectory",
    "curatedDirectory",
    "benchmarkDirectory",
    "benchmarkFile",
    "graphBucket",
    "reportsBucket",
    "logsBucket",
    "rawPrefix",
    "curatedPrefix",
    "graphPrefix",
    "semanticContextPrefix",
    "reportsPrefix",
    "logsPrefix",
    "vectorDistance",
    "writerEndpoint"
  )
  val IntFields: Vector[String] = Vector(
    "sourceMaxFiles",
    "sourceMaxDepth",
    "parquetMaxFooterBytes",
    "parquetMaxPageBytes",
    "upstreamMaxRows",
    "keyValueMaxItemBytes",
    "layoutMaxKeyBytes",
    "vectorMaxBatchSize",
    "vectorMaxValueBytes",
    "vectorMaxFilterableBytes",
    "vectorMaxTotalBytes",
    "vectorMaxKeys",
    "vectorMaxNonFilterableKeys",
    "vectorMaxListValues",
    "vectorMaxTopK",
    "vectorBatchSize",
    "vectorRetryMaxAttempts",
    "maxRejected",
    "maxTextBytes",
    "maxKeyBytes",
    "maxItemsPerCall",
    "maxEntityLinks",
    "maxMentionIdsPerLink"
  )
  val LongFields: Vector[String] = Vector(
    "sourceMaxBytes",
    "parquetMaxPartBytes",
    "parquetMaxRowsPerPart",
    "upstreamMaxBytes",
    "benchmarkMaxBytes",
    "vectorRetryBaseMillis",
    "vectorRetryMaxMillis",
    "entityLinksExpiresAtEpochSeconds"
  )
  private val buckets = Set("graphBucket", "reportsBucket", "logsBucket")
  private val prefixes = Set(
    "rawPrefix",
    "curatedPrefix",
    "graphPrefix",
    "semanticContextPrefix",
    "reportsPrefix",
    "logsPrefix"
  )
  val all: Vector[KeySpec] =
    StringFields.map { n =>
      val bound =
        if (buckets(n)) KeyBound.Matches("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", "an S3 bucket name")
        else if (prefixes(n))
          KeyBound.Matches(
            "[A-Za-z0-9_=.-]+(/[A-Za-z0-9_=.-]+)*",
            "a non-empty relative object prefix"
          )
        else if (n == "writerEndpoint") KeyBound.NeptuneEndpoint
        else if (n == "analysisConfigFile") KeyBound.LocalArtifactFile
        else if (n == "vectorDistance")
          KeyBound.Matches("cosine|euclidean", "a supported vector distance")
        else if (n == "benchmarkFile")
          KeyBound.Matches("[A-Za-z0-9_=-][A-Za-z0-9_.=-]*", "a local benchmark filename")
        else KeyBound.LocalDirectory
      KeySpec(Prefix + n, OptionalString, bound)
    } ++
      IntFields.map(n =>
        KeySpec(
          Prefix + n,
          OptionalInt32,
          KeyBound.Range(if (n == "maxRejected") 0 else 1, Int.MaxValue.toLong)
        )
      ) ++
      LongFields.map(n =>
        KeySpec(
          Prefix + n,
          OptionalInt64,
          KeyBound.Range(if (n.startsWith("vectorRetry")) 0 else 1, Long.MaxValue - 1)
        )
      )

  private def num(m: Map[String, Any], n: String): Long = m(Prefix + n) match {
    case i: Int => i.toLong
    case l: Long => l
  }
  private def rule(id: String, names: Vector[String], formula: String)(
      f: Map[String, Any] => Boolean
  ) =
    ConfigRule(id, names.map(Prefix + _), formula, "existing bounded opening-load contracts", f)
  val rules: Vector[ConfigRule] = Vector(
    ConfigRule(
      "LOAD-COMPLETE",
      all.map(_.path),
      "all productionLoad keys are set together",
      "opening-load configuration",
      m => all.forall(k => m.contains(k.path)),
      applies = Some(m => all.exists(k => m.contains(k.path)))
    ),
    rule(
      "LOAD-BATCH",
      Vector("vectorBatchSize", "vectorMaxBatchSize"),
      "vectorBatchSize <= vectorMaxBatchSize"
    )(m => num(m, "vectorBatchSize") <= num(m, "vectorMaxBatchSize")),
    rule(
      "LOAD-RETRY",
      Vector("vectorRetryBaseMillis", "vectorRetryMaxMillis"),
      "base retry delay <= maximum retry delay"
    )(m => num(m, "vectorRetryBaseMillis") <= num(m, "vectorRetryMaxMillis")),
    rule("LOAD-ITEMS", Vector("maxItemsPerCall", "maxRejected"), "maxItemsPerCall > maxRejected")(
      m => num(m, "maxItemsPerCall") > num(m, "maxRejected")
    ),
    rule("LOAD-UPSTREAM", Vector("upstreamMaxBytes"), "upstreamMaxBytes <= Int.MaxValue - 8")(m =>
      num(m, "upstreamMaxBytes") <= Int.MaxValue - 8
    ),
    rule(
      "LOAD-PREFIX",
      prefixes.toVector.sorted,
      "zone prefixes contain no dot traversal segments"
    )(m =>
      prefixes.forall(n =>
        !m(Prefix + n).asInstanceOf[String].split("/").exists(s => s == "." || s == "..")
      )
    )
  )

  def build(m: Map[String, Any]): ProductionLoadConfig = {
    def get[A](n: String): Option[A] = m.get(Prefix + n).map(_.asInstanceOf[A])
    ProductionLoadConfig(
      analysisConfigFile = get[String]("analysisConfigFile"),
      runDirectory = get[String]("runDirectory"),
      loadDirectory = get[String]("loadDirectory"),
      groupingDirectory = get[String]("groupingDirectory"),
      curatedDirectory = get[String]("curatedDirectory"),
      benchmarkDirectory = get[String]("benchmarkDirectory"),
      benchmarkFile = get[String]("benchmarkFile"),
      graphBucket = get[String]("graphBucket"),
      reportsBucket = get[String]("reportsBucket"),
      logsBucket = get[String]("logsBucket"),
      rawPrefix = get[String]("rawPrefix"),
      curatedPrefix = get[String]("curatedPrefix"),
      graphPrefix = get[String]("graphPrefix"),
      semanticContextPrefix = get[String]("semanticContextPrefix"),
      reportsPrefix = get[String]("reportsPrefix"),
      logsPrefix = get[String]("logsPrefix"),
      vectorDistance = get[String]("vectorDistance"),
      writerEndpoint = get[String]("writerEndpoint"),
      sourceMaxFiles = get[Int]("sourceMaxFiles"),
      sourceMaxDepth = get[Int]("sourceMaxDepth"),
      parquetMaxFooterBytes = get[Int]("parquetMaxFooterBytes"),
      parquetMaxPageBytes = get[Int]("parquetMaxPageBytes"),
      upstreamMaxRows = get[Int]("upstreamMaxRows"),
      keyValueMaxItemBytes = get[Int]("keyValueMaxItemBytes"),
      layoutMaxKeyBytes = get[Int]("layoutMaxKeyBytes"),
      vectorMaxBatchSize = get[Int]("vectorMaxBatchSize"),
      vectorMaxValueBytes = get[Int]("vectorMaxValueBytes"),
      vectorMaxFilterableBytes = get[Int]("vectorMaxFilterableBytes"),
      vectorMaxTotalBytes = get[Int]("vectorMaxTotalBytes"),
      vectorMaxKeys = get[Int]("vectorMaxKeys"),
      vectorMaxNonFilterableKeys = get[Int]("vectorMaxNonFilterableKeys"),
      vectorMaxListValues = get[Int]("vectorMaxListValues"),
      vectorMaxTopK = get[Int]("vectorMaxTopK"),
      vectorBatchSize = get[Int]("vectorBatchSize"),
      vectorRetryMaxAttempts = get[Int]("vectorRetryMaxAttempts"),
      maxRejected = get[Int]("maxRejected"),
      maxTextBytes = get[Int]("maxTextBytes"),
      maxKeyBytes = get[Int]("maxKeyBytes"),
      maxItemsPerCall = get[Int]("maxItemsPerCall"),
      maxEntityLinks = get[Int]("maxEntityLinks"),
      maxMentionIdsPerLink = get[Int]("maxMentionIdsPerLink"),
      sourceMaxBytes = get[Long]("sourceMaxBytes"),
      parquetMaxPartBytes = get[Long]("parquetMaxPartBytes"),
      parquetMaxRowsPerPart = get[Long]("parquetMaxRowsPerPart"),
      upstreamMaxBytes = get[Long]("upstreamMaxBytes"),
      benchmarkMaxBytes = get[Long]("benchmarkMaxBytes"),
      vectorRetryBaseMillis = get[Long]("vectorRetryBaseMillis"),
      vectorRetryMaxMillis = get[Long]("vectorRetryMaxMillis"),
      entityLinksExpiresAtEpochSeconds = get[Long]("entityLinksExpiresAtEpochSeconds")
    )
  }
  def settings(m: Map[String, Any]): ProductionLoadSettings = ProductionLoadSettings(
    analysisConfigFile = m(Prefix + "analysisConfigFile").asInstanceOf[String],
    runDirectory = m(Prefix + "runDirectory").asInstanceOf[String],
    loadDirectory = m(Prefix + "loadDirectory").asInstanceOf[String],
    groupingDirectory = m(Prefix + "groupingDirectory").asInstanceOf[String],
    curatedDirectory = m(Prefix + "curatedDirectory").asInstanceOf[String],
    benchmarkDirectory = m(Prefix + "benchmarkDirectory").asInstanceOf[String],
    benchmarkFile = m(Prefix + "benchmarkFile").asInstanceOf[String],
    graphBucket = m(Prefix + "graphBucket").asInstanceOf[String],
    reportsBucket = m(Prefix + "reportsBucket").asInstanceOf[String],
    logsBucket = m(Prefix + "logsBucket").asInstanceOf[String],
    rawPrefix = m(Prefix + "rawPrefix").asInstanceOf[String],
    curatedPrefix = m(Prefix + "curatedPrefix").asInstanceOf[String],
    graphPrefix = m(Prefix + "graphPrefix").asInstanceOf[String],
    semanticContextPrefix = m(Prefix + "semanticContextPrefix").asInstanceOf[String],
    reportsPrefix = m(Prefix + "reportsPrefix").asInstanceOf[String],
    logsPrefix = m(Prefix + "logsPrefix").asInstanceOf[String],
    vectorDistance = m(Prefix + "vectorDistance").asInstanceOf[String],
    writerEndpoint = m(Prefix + "writerEndpoint").asInstanceOf[String],
    sourceMaxFiles = m(Prefix + "sourceMaxFiles").asInstanceOf[Int],
    sourceMaxDepth = m(Prefix + "sourceMaxDepth").asInstanceOf[Int],
    parquetMaxFooterBytes = m(Prefix + "parquetMaxFooterBytes").asInstanceOf[Int],
    parquetMaxPageBytes = m(Prefix + "parquetMaxPageBytes").asInstanceOf[Int],
    upstreamMaxRows = m(Prefix + "upstreamMaxRows").asInstanceOf[Int],
    keyValueMaxItemBytes = m(Prefix + "keyValueMaxItemBytes").asInstanceOf[Int],
    layoutMaxKeyBytes = m(Prefix + "layoutMaxKeyBytes").asInstanceOf[Int],
    vectorMaxBatchSize = m(Prefix + "vectorMaxBatchSize").asInstanceOf[Int],
    vectorMaxValueBytes = m(Prefix + "vectorMaxValueBytes").asInstanceOf[Int],
    vectorMaxFilterableBytes = m(Prefix + "vectorMaxFilterableBytes").asInstanceOf[Int],
    vectorMaxTotalBytes = m(Prefix + "vectorMaxTotalBytes").asInstanceOf[Int],
    vectorMaxKeys = m(Prefix + "vectorMaxKeys").asInstanceOf[Int],
    vectorMaxNonFilterableKeys = m(Prefix + "vectorMaxNonFilterableKeys").asInstanceOf[Int],
    vectorMaxListValues = m(Prefix + "vectorMaxListValues").asInstanceOf[Int],
    vectorMaxTopK = m(Prefix + "vectorMaxTopK").asInstanceOf[Int],
    vectorBatchSize = m(Prefix + "vectorBatchSize").asInstanceOf[Int],
    vectorRetryMaxAttempts = m(Prefix + "vectorRetryMaxAttempts").asInstanceOf[Int],
    maxRejected = m(Prefix + "maxRejected").asInstanceOf[Int],
    maxTextBytes = m(Prefix + "maxTextBytes").asInstanceOf[Int],
    maxKeyBytes = m(Prefix + "maxKeyBytes").asInstanceOf[Int],
    maxItemsPerCall = m(Prefix + "maxItemsPerCall").asInstanceOf[Int],
    maxEntityLinks = m(Prefix + "maxEntityLinks").asInstanceOf[Int],
    maxMentionIdsPerLink = m(Prefix + "maxMentionIdsPerLink").asInstanceOf[Int],
    sourceMaxBytes = m(Prefix + "sourceMaxBytes").asInstanceOf[Long],
    parquetMaxPartBytes = m(Prefix + "parquetMaxPartBytes").asInstanceOf[Long],
    parquetMaxRowsPerPart = m(Prefix + "parquetMaxRowsPerPart").asInstanceOf[Long],
    upstreamMaxBytes = m(Prefix + "upstreamMaxBytes").asInstanceOf[Long],
    benchmarkMaxBytes = m(Prefix + "benchmarkMaxBytes").asInstanceOf[Long],
    vectorRetryBaseMillis = m(Prefix + "vectorRetryBaseMillis").asInstanceOf[Long],
    vectorRetryMaxMillis = m(Prefix + "vectorRetryMaxMillis").asInstanceOf[Long],
    entityLinksExpiresAtEpochSeconds =
      m(Prefix + "entityLinksExpiresAtEpochSeconds").asInstanceOf[Long]
  )
}

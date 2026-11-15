package scholarchain.core.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scholarchain.core.TimeWindow

/** All values below are synthetic test values (`.invalid` hosts, all-zero account ID). */
class ConfigLoaderSuite extends munit.FunSuite {

  /** A representative `scholarchain.config.v2` HOCON file. */
  val base: String =
    """# Synthetic ScholarChain configuration (schema scholarchain.config.v2).
      |configSchemaVersion = "scholarchain.config.v2"
      |environment = test
      |aws {
      |  region = "us-east-1"
      |  accountId = "000000000000"
      |}
      |storage {
      |  rawBucket = "example-raw-bucket"
      |  curatedBucket = "example-curated-bucket"
      |  contextBucket = "example-context-bucket"
      |  vectorBucket = "example-vector-bucket"
      |  vectorIndex = "example-index"
      |  dynamoTablePrefix = "example-"
      |  neptuneEndpoint = "neptune.example.invalid"
      |  openSearchEndpoint = null
      |}
      |models {
      |  llmModelId = "example-llm"
      |  embeddingModelId = "example-embedding"
      |  embeddingDimension = 8
      |}
      |graph {
      |  windows = [{ startYear = 2000, endYear = 2005 }, { startYear = 2006, endYear = 2010 }]
      |  policyVersion = "graph-policy-1.0.0"
      |  identityVersion = "v1"
      |}
      |bridge { algorithm = "betweenness-sampled", sampleSize = 64, randomSeed = 7, maxIterations = 10 }
      |community { algorithm = "label-propagation", maxIterations = 20 }
      |entityResolution {
      |  policyVersion = "er-policy-1.0.0"
      |  acceptThreshold = 0.9
      |  rejectThreshold = 0.2
      |  llmBandLower = 0.4
      |  llmBandUpper = 0.7
      |  promptVersion = "er-adjudication-prompt.v1"
      |}
      |runtime {
      |  maxPathHops = 4
      |  retrievalTopK = 10
      |  defaultWindow { startYear = 2015, endYear = 2024 }
      |}
      |""".stripMargin

  /** The same values written as strict JSON, which is also valid HOCON. */
  val jsonBase: String =
    """{
      |  "configSchemaVersion": "scholarchain.config.v2",
      |  "environment": "test",
      |  "aws": {"region": "us-east-1", "accountId": "000000000000"},
      |  "storage": {
      |    "rawBucket": "example-raw-bucket",
      |    "curatedBucket": "example-curated-bucket",
      |    "contextBucket": "example-context-bucket",
      |    "vectorBucket": "example-vector-bucket",
      |    "vectorIndex": "example-index",
      |    "dynamoTablePrefix": "example-",
      |    "neptuneEndpoint": "neptune.example.invalid",
      |    "openSearchEndpoint": null
      |  },
      |  "models": {"llmModelId": "example-llm", "embeddingModelId": "example-embedding", "embeddingDimension": 8},
      |  "graph": {
      |    "windows": [{"startYear": 2000, "endYear": 2005}, {"startYear": 2006, "endYear": 2010}],
      |    "policyVersion": "graph-policy-1.0.0",
      |    "identityVersion": "v1"
      |  },
      |  "bridge": {"algorithm": "betweenness-sampled", "sampleSize": 64, "randomSeed": 7, "maxIterations": 10},
      |  "community": {"algorithm": "label-propagation", "maxIterations": 20},
      |  "entityResolution": {"policyVersion": "er-policy-1.0.0", "acceptThreshold": 0.9, "rejectThreshold": 0.2, "llmBandLower": 0.4, "llmBandUpper": 0.7, "promptVersion": "er-adjudication-prompt.v1"},
      |  "runtime": {"maxPathHops": 4, "retrievalTopK": 10, "defaultWindow": {"startYear": 2015, "endYear": 2024}}
      |}""".stripMargin

  /** The same values again, using dotted path keys, `//` comments, `:` and unquoted values. */
  val dottedBase: String =
    """// flat form with dotted path keys
      |configSchemaVersion : scholarchain.config.v2
      |environment : test
      |aws.region = us-east-1
      |aws.accountId = "000000000000"   # quoted: an unquoted value would be a number
      |storage.rawBucket = example-raw-bucket
      |storage.curatedBucket = example-curated-bucket
      |storage.contextBucket = example-context-bucket
      |storage.vectorBucket = example-vector-bucket
      |storage.vectorIndex = example-index
      |storage.dynamoTablePrefix = example-
      |storage.neptuneEndpoint = neptune.example.invalid
      |storage.openSearchEndpoint = null
      |models.llmModelId = example-llm
      |models.embeddingModelId = example-embedding
      |models.embeddingDimension = 8
      |graph.windows = [
      |  {
      |    startYear = 2000
      |    endYear = 2005
      |  }
      |  { startYear = 2006, endYear = 2010 }
      |]
      |graph.policyVersion = graph-policy-1.0.0
      |graph.identityVersion = v1
      |bridge.algorithm = betweenness-sampled
      |bridge.sampleSize = 64
      |bridge.randomSeed = 7
      |bridge.maxIterations = 10
      |community.algorithm = label-propagation
      |community.maxIterations = 20
      |entityResolution.policyVersion = er-policy-1.0.0
      |entityResolution.acceptThreshold = 0.9
      |entityResolution.rejectThreshold = 0.2
      |entityResolution.llmBandLower = 0.4
      |entityResolution.llmBandUpper = 0.7
      |entityResolution.promptVersion = er-adjudication-prompt.v1
      |runtime.maxPathHops = 4
      |runtime.retrievalTopK = 10
      |runtime.defaultWindow.startYear = 2015
      |runtime.defaultWindow.endYear = 2024
      |""".stripMargin

  val overlay: String =
    """configSchemaVersion = "scholarchain.config.v2"
      |environment = dev
      |runtime.retrievalTopK = 25
      |""".stripMargin

  private def files(entries: (String, String)*): String => Either[String, String] = {
    val m = entries.toMap
    p => m.get(p).toRight(s"no such file: $p")
  }
  private val filesEnv = Map(ConfigLoader.FilesEnvVar -> "base.conf")

  private def loadOk(
      env: Map[String, String],
      read: String => Either[String, String]
  ): ScholarChainConfig =
    ConfigLoader
      .load(env, read)
      .fold(e => fail(s"unexpected errors: ${e.map(_.message)}"), identity)

  private def loadBase(text: String): Either[Vector[ConfigError], ScholarChainConfig] =
    ConfigLoader.load(filesEnv, files("base.conf" -> text))

  test("a complete base file loads with every value from configuration") {
    val c = loadOk(filesEnv, files("base.conf" -> base))
    assertEquals(c.configSchemaVersion, "scholarchain.config.v2")
    assertEquals(c.environment, "test")
    assertEquals(c.aws.accountId, "000000000000")
    assertEquals(c.storage.rawBucket, "example-raw-bucket")
    assertEquals(c.storage.openSearchEndpoint, None)
    assertEquals(c.models.llmModelId, "example-llm")
    assertEquals(c.graph.windows, Vector(TimeWindow(2000, 2005), TimeWindow(2006, 2010)))
    assertEquals(c.bridge, BridgeConfig("betweenness-sampled", 64, 7L, 10))
    assertEquals(c.entityResolution.acceptThreshold, 0.9)
    assertEquals(c.entityResolution.rejectThreshold, 0.2)
    assertEquals(
      c.entityResolution,
      EntityResolutionConfig("er-policy-1.0.0", 0.9, 0.2, 0.4, 0.7, "er-adjudication-prompt.v1")
    )
    assertEquals(c.runtime.defaultWindow, TimeWindow(2015, 2024))
  }

  test("HOCON features are accepted: comments, unquoted keys, dotted paths, multi-line objects") {
    val c = loadOk(filesEnv, files("base.conf" -> base))
    val dotted = loadOk(filesEnv, files("base.conf" -> dottedBase))
    val json = loadOk(filesEnv, files("base.conf" -> jsonBase))
    assertEquals(dotted, c)
    assertEquals(json, c, "strict JSON is valid HOCON and loads to the same values")
    assertEquals(ScholarChainConfig.digest(dotted), ScholarChainConfig.digest(c))
    assertEquals(ScholarChainConfig.digest(json), ScholarChainConfig.digest(c))
  }

  test("the syntax is HOCON regardless of the file name") {
    val c = loadOk(filesEnv, files("base.conf" -> base))
    for (name <- Vector("base.json", "base.properties", "base.yaml", "base"))
      assertEquals(loadOk(Map(ConfigLoader.FilesEnvVar -> name), files(name -> base)), c, name)
  }

  test("precedence: later files override earlier files, environment overrides files") {
    val read = files("base.conf" -> base, "dev.conf" -> overlay)
    val fromFiles = loadOk(Map(ConfigLoader.FilesEnvVar -> "base.conf,dev.conf"), read)
    assertEquals(fromFiles.environment, "dev")
    assertEquals(fromFiles.runtime.retrievalTopK, 25)
    assertEquals(fromFiles.runtime.maxPathHops, 4, "keys not in the overlay keep the base value")

    val reversed = loadOk(Map(ConfigLoader.FilesEnvVar -> "dev.conf,base.conf"), read)
    assertEquals(reversed.environment, "test")

    val withEnv = loadOk(
      Map(
        ConfigLoader.FilesEnvVar -> "base.conf,dev.conf",
        "SCHOLARCHAIN_CFG_RUNTIME__RETRIEVAL_TOP_K" -> "50",
        "SCHOLARCHAIN_CFG_GRAPH__WINDOWS" -> "2011-2015,2016-2020",
        "SCHOLARCHAIN_CFG_STORAGE__OPEN_SEARCH_ENDPOINT" -> "search.example.invalid",
        "SCHOLARCHAIN_CFG_BRIDGE__RANDOM_SEED" -> "9007199254740993"
      ),
      read
    )
    assertEquals(withEnv.runtime.retrievalTopK, 50)
    assertEquals(withEnv.graph.windows, Vector(TimeWindow(2011, 2015), TimeWindow(2016, 2020)))
    assertEquals(withEnv.storage.openSearchEndpoint, Some("search.example.invalid"))
    assertEquals(
      withEnv.bridge.randomSeed,
      9007199254740993L,
      "env integers keep full 64-bit precision"
    )
  }

  test("file integers keep full 64-bit precision; inexact decimals are rejected") {
    val big = base.replace("randomSeed = 7", "randomSeed = 9007199254740993")
    assertEquals(loadOk(filesEnv, files("base.conf" -> big)).bridge.randomSeed, 9007199254740993L)
    for (decimal <- Vector("randomSeed = 7.0", "randomSeed = 7e0"))
      assert(
        loadBase(base.replace("randomSeed = 7", decimal)).left.exists(_.exists {
          case ConfigError.InvalidValue(k, _, _) => k == "bridge.randomSeed"
          case _ => false
        }),
        decimal
      )
    val inexact = base.replace("randomSeed = 7", "randomSeed = 9007199254740993.0")
    assert(loadBase(inexact).left.exists(_.exists {
      case ConfigError.InvalidValue(k, _, _) => k == "bridge.randomSeed"
      case _ => false
    }))
  }

  test("duplicate keys merge inside one file and cannot break file-over-file precedence") {
    // Inside one file: a repeated leaf takes the later value; repeated objects merge.
    val dupLeaf = base + "runtime.retrievalTopK = 11\n"
    assertEquals(loadOk(filesEnv, files("base.conf" -> dupLeaf)).runtime.retrievalTopK, 11)
    val dupObject = base + "runtime { maxPathHops = 6 }\n"
    val merged = loadOk(filesEnv, files("base.conf" -> dupObject))
    assertEquals(merged.runtime.maxPathHops, 6)
    assertEquals(merged.runtime.retrievalTopK, 10, "object merge keeps sibling keys")
    // A later object replaces an earlier scalar, and a later list replaces an earlier list whole.
    val dupList = base + "graph.windows = [{ startYear = 2001, endYear = 2002 }]\n"
    assertEquals(
      loadOk(filesEnv, files("base.conf" -> dupList)).graph.windows,
      Vector(TimeWindow(2001, 2002))
    )

    // Across files: the overlay's own duplicates are merged first, then applied leaf by leaf.
    val overlayDup =
      overlay + "runtime { retrievalTopK = 30 }\nruntime { retrievalTopK = 26 }\n"
    val layered = loadOk(
      Map(ConfigLoader.FilesEnvVar -> "base.conf,dev.conf"),
      files("base.conf" -> base, "dev.conf" -> overlayDup)
    )
    assertEquals(layered.runtime.retrievalTopK, 26)
    assertEquals(layered.runtime.maxPathHops, 4, "the overlay never wipes the base subtree")
    assertEquals(layered.runtime.defaultWindow, TimeWindow(2015, 2024))

    // An overlay cannot delete a base subtree by assigning a scalar or null to an object prefix.
    for (wipe <- Vector("runtime = null\n", "runtime = 5\n")) {
      val r = ConfigLoader.load(
        Map(ConfigLoader.FilesEnvVar -> "base.conf,dev.conf"),
        files("base.conf" -> base, "dev.conf" -> (overlay + wipe))
      )
      assertEquals(r, Left(Vector(ConfigError.UnknownKey("file dev.conf", "runtime"))), wipe)
    }
    // Environment still overrides the merged files.
    val env = loadOk(
      Map(
        ConfigLoader.FilesEnvVar -> "base.conf,dev.conf",
        "SCHOLARCHAIN_CFG_RUNTIME__RETRIEVAL_TOP_K" -> "3"
      ),
      files("base.conf" -> base, "dev.conf" -> overlayDup)
    )
    assertEquals(env.runtime.retrievalTopK, 3)
  }

  test("include directives of every form are rejected with a typed error") {
    val included = Files.createTempFile("scholarchain-included", ".conf")
    try {
      Files.writeString(included, "environment = included\n", StandardCharsets.UTF_8)
      val p = included.toString
      val forms = Vector(
        s"""include "${p}"""" -> s"\"$p\"",
        s"""include file("${p}")""" -> s"file($p)",
        s"""include required(file("${p}"))""" -> s"file($p)",
        """include classpath("reference.conf")""" -> "classpath(reference.conf)",
        s"""include url("${included.toUri}")""" -> s"url(${included.toUri.toURL})",
        """include "missing-file.conf"""" -> "\"missing-file.conf\""
      )
      for ((directive, target) <- forms) {
        assertEquals(
          loadBase(base + directive + "\n"),
          Left(Vector(ConfigError.IncludeRejected("base.conf", target))),
          directive
        )
        assertEquals(
          loadBase(base + s"runtime {\n  $directive\n}\n"),
          Left(Vector(ConfigError.IncludeRejected("base.conf", target))),
          s"nested: $directive"
        )
      }
      // The same rule applies to a real file read from disk.
      val f = Files.createTempFile("scholarchain-config", ".conf")
      try {
        Files.writeString(f, base + s"""include file("${p}")""" + "\n", StandardCharsets.UTF_8)
        val r =
          ConfigLoader.load(Map(ConfigLoader.FilesEnvVar -> f.toString), ConfigLoader.readLocalFile)
        assert(r.left.exists(_.exists(_.isInstanceOf[ConfigError.IncludeRejected])), r)
      } finally Files.deleteIfExists(f)
    } finally Files.deleteIfExists(included)
  }

  test("substitutions, optional substitutions, and += are rejected with the key path") {
    def rejectedAt(text: String): Either[Vector[ConfigError], ScholarChainConfig] = loadBase(text)
    val cases = Vector(
      "environment = ${HOME}\n" -> "environment",
      "environment = ${?HOME}\n" -> "environment",
      "environment = ${?SCHOLARCHAIN_CFG_ENVIRONMENT}\n" -> "environment",
      "environment = ${?NO_SUCH_VARIABLE_FOR_SCHOLARCHAIN}\n" -> "environment",
      "runtime.retrievalTopK = ${runtime.maxPathHops}\n" -> "runtime.retrievalTopK",
      "models.llmModelId = \"prefix-\"${?USER}\n" -> "models.llmModelId",
      "graph.windows += { startYear = 2011, endYear = 2012 }\n" -> "graph.windows",
      "storage.neptuneEndpoint = [${?PATH}]\n" -> "storage.neptuneEndpoint",
      "notAKey = [1, ${?PATH}]\n" -> "notAKey[1]"
    )
    for ((line, key) <- cases)
      assertEquals(
        rejectedAt(base + line),
        Left(Vector(ConfigError.SubstitutionRejected("base.conf", key))),
        line
      )
    // A substitution in an overlay is rejected too, not silently skipped by precedence.
    val r = ConfigLoader.load(
      Map(ConfigLoader.FilesEnvVar -> "base.conf,dev.conf"),
      files("base.conf" -> base, "dev.conf" -> (overlay + "environment = ${?HOME}\n"))
    )
    assertEquals(r, Left(Vector(ConfigError.SubstitutionRejected("dev.conf", "environment"))))
  }

  test("environment and system properties never enter except through SCHOLARCHAIN_CFG_") {
    val props = Vector(
      "config.file" -> "/nonexistent/override.conf",
      "config.resource" -> "nonexistent.conf",
      "config.override_with_env_vars" -> "true",
      "runtime.retrievalTopK" -> "99",
      "environment" -> "from-system-property"
    )
    val saved = props.map { case (k, _) => k -> Option(System.getProperty(k)) }
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      val env = filesEnv ++ Map(
        "CONFIG_FORCE_runtime_retrievalTopK" -> "99",
        "CONFIG_FORCE_environment" -> "forced",
        "RUNTIME_RETRIEVAL_TOP_K" -> "99",
        "runtime.retrievalTopK" -> "99",
        "environment" -> "from-env"
      )
      val c = loadOk(env, files("base.conf" -> base))
      assertEquals(c.runtime.retrievalTopK, 10)
      assertEquals(c.environment, "test")
      assertEquals(c, loadOk(filesEnv, files("base.conf" -> base)))
      val viaPrefix = loadOk(
        env + ("SCHOLARCHAIN_CFG_RUNTIME__RETRIEVAL_TOP_K" -> "99"),
        files("base.conf" -> base)
      )
      assertEquals(viaPrefix.runtime.retrievalTopK, 99)
    } finally
      saved.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None) => System.clearProperty(k)
      }
  }

  test("non-object roots and syntax errors are rejected with typed errors naming the file") {
    val bad = Vector(
      "[]",
      "[1, 2]",
      "\"just a string\"",
      "environment = {",
      "runtime { maxPathHops = 4",
      "a = [1,",
      "}",
      "include = 5",
      base + "\nruntime { = 3 }\n"
    )
    for (text <- bad)
      loadBase(text) match {
        case Left(Vector(ConfigError.MalformedFile(p, detail))) =>
          assertEquals(p, "base.conf", text)
          assert(detail.nonEmpty, text)
        case other => fail(s"expected one MalformedFile for <$text>, got $other")
      }
    assertEquals(
      loadBase(""),
      Left(Vector(ConfigError.MissingSchemaVersion("base.conf"))),
      "an empty document is an empty object"
    )
  }

  test("environment variable names follow the documented mapping") {
    assertEquals(ConfigKeys.envNameOf("aws.accountId"), "SCHOLARCHAIN_CFG_AWS__ACCOUNT_ID")
    assertEquals(
      ConfigKeys.envNameOf("runtime.retrievalTopK"),
      "SCHOLARCHAIN_CFG_RUNTIME__RETRIEVAL_TOP_K"
    )
    assertEquals(ConfigKeys.envNameOf("environment"), "SCHOLARCHAIN_CFG_ENVIRONMENT")
    assertEquals(ConfigKeys.byEnv.size, ConfigKeys.all.size, "env names are unique")
  }

  test("the file list is required: there are no built-in defaults") {
    assertEquals(
      ConfigLoader.load(Map.empty, files()),
      Left(Vector(ConfigError.MissingFileList(ConfigLoader.FilesEnvVar)))
    )
  }

  test("a key set by no source is an error naming its environment variable") {
    val partial = base.replace("llmModelId = \"example-llm\"", "")
    assertEquals(
      loadBase(partial),
      Left(
        Vector(ConfigError.MissingKey("models.llmModelId", "SCHOLARCHAIN_CFG_MODELS__LLM_MODEL_ID"))
      )
    )
  }

  test("unsupported and missing config schema versions are rejected") {
    assertEquals(ConfigLoader.SupportedSchemaVersions, Vector("scholarchain.config.v2"))
    for (v <- ConfigLoader.RetiredSchemaVersions :+ "scholarchain.config.v3") {
      assertEquals(
        loadBase(base.replace("scholarchain.config.v2", v)),
        Left(
          Vector(
            ConfigError.UnsupportedSchemaVersion(
              "base.conf",
              v,
              ConfigLoader.SupportedSchemaVersions
            )
          )
        ),
        v
      )
    }
    assertEquals(ConfigLoader.RetiredSchemaVersions, Vector("scholarchain.config.v1"))
    val noVersion = base.replace("configSchemaVersion = \"scholarchain.config.v2\"", "")
    assertEquals(loadBase(noVersion), Left(Vector(ConfigError.MissingSchemaVersion("base.conf"))))
    val numericVersion = base.replace("\"scholarchain.config.v2\"", "1")
    assertEquals(
      loadBase(numericVersion),
      Left(Vector(ConfigError.MissingSchemaVersion("base.conf")))
    )
    val badOverlay = overlay.replace("v2", "v0")
    val r = ConfigLoader.load(
      Map(ConfigLoader.FilesEnvVar -> "base.conf,dev.conf"),
      files("base.conf" -> base, "dev.conf" -> badOverlay)
    )
    assert(r.left.exists(_.exists(_.isInstanceOf[ConfigError.UnsupportedSchemaVersion])))
  }

  test("unknown keys in files and unknown SCHOLARCHAIN_CFG_ variables are rejected") {
    val typo = base.replace("retrievalTopK =", "retrievalTopk =")
    assert(
      loadBase(typo).left
        .exists(_.contains(ConfigError.UnknownKey("file base.conf", "runtime.retrievalTopk")))
    )
    val quotedDot = base + "\"runtime.maxPathHops\" = 5\n"
    assertEquals(
      loadBase(quotedDot),
      Left(Vector(ConfigError.UnknownKey("file base.conf", "\"runtime.maxPathHops\""))),
      "a quoted key containing a dot is one literal segment, not a path"
    )
    val env = filesEnv + ("SCHOLARCHAIN_CFG_RUNTIME__TOP_K" -> "3")
    assertEquals(
      ConfigLoader.load(env, files("base.conf" -> base)),
      Left(Vector(ConfigError.UnknownKey("environment", "SCHOLARCHAIN_CFG_RUNTIME__TOP_K")))
    )
  }

  test("unrelated environment variables are ignored") {
    val env = filesEnv ++ Map(
      "AWS_PROFILE" -> "x",
      "SCHOLARCHAIN_READONLY_AWS_PROFILE" -> "y",
      "HOME" -> "/tmp"
    )
    assert(ConfigLoader.load(env, files("base.conf" -> base)).isRight)
  }

  test("invalid values are rejected with the key and source") {
    def errs(text: String, env: Map[String, String] = Map.empty) =
      ConfigLoader.load(filesEnv ++ env, files("base.conf" -> text)).left.getOrElse(Vector.empty)
    def invalid(key: String, e: Vector[ConfigError]) =
      e.exists { case ConfigError.InvalidValue(k, _, _) => k == key; case _ => false }

    assert(invalid("entityResolution.acceptThreshold", errs(base.replace("0.9", "1.5"))))
    assert(invalid("entityResolution.acceptThreshold", errs(base.replace("0.9", "1e999"))))
    assert(invalid("aws.accountId", errs(base.replace("000000000000", "123"))))
    assert(
      invalid("aws.accountId", errs(base.replace("\"000000000000\"", "0"))),
      "account ID must be a string"
    )
    assert(
      invalid("aws.accountId", errs(base.replace("\"000000000000\"", "000000000000"))),
      "an unquoted account ID is a number"
    )
    assert(invalid("storage.rawBucket", errs(base.replace("example-raw-bucket", "Bad_Bucket"))))
    assert(
      invalid(
        "graph.windows",
        errs(
          base.replace(
            "{ startYear = 2006, endYear = 2010 }",
            "{ startYear = 2010, endYear = 2006 }"
          )
        )
      )
    )
    assert(
      invalid("graph.windows", errs(base, Map("SCHOLARCHAIN_CFG_GRAPH__WINDOWS" -> "2010-2006")))
    )
    assert(
      invalid(
        "graph.windows",
        errs(
          base.replace(
            "[{ startYear = 2000, endYear = 2005 }, { startYear = 2006, endYear = 2010 }]",
            "[]"
          )
        )
      )
    )
    assert(invalid("runtime.maxPathHops", errs(base.replace("maxPathHops = 4", "maxPathHops = 0"))))
    assert(
      invalid("runtime.maxPathHops", errs(base.replace("maxPathHops = 4", "maxPathHops = \"4\""))),
      "numbers must not be quoted strings"
    )
    assert(
      invalid(
        "runtime.maxPathHops",
        errs(base, Map("SCHOLARCHAIN_CFG_RUNTIME__MAX_PATH_HOPS" -> "four"))
      )
    )
    assert(
      invalid(
        "graph.identityVersion",
        errs(base.replace("identityVersion = \"v1\"", "identityVersion = \"1\""))
      )
    )
    assert(invalid("environment", errs(base, Map("SCHOLARCHAIN_CFG_ENVIRONMENT" -> ""))))
  }

  test("reject threshold above accept threshold is rejected") {
    val swapped = base
      .replace("acceptThreshold = 0.9", "acceptThreshold = 0.2")
      .replace("rejectThreshold = 0.2", "rejectThreshold = 0.9")
    assert(
      loadBase(swapped).left
        .exists(_.exists {
          case ConfigError.InvalidValue(k, _, _) => k == "entityResolution.rejectThreshold"
          case _ => false
        })
    )
  }

  private def invalidKeys(r: Either[Vector[ConfigError], ScholarChainConfig]): Set[String] =
    r.left.getOrElse(Vector.empty).collect { case ConfigError.InvalidValue(k, _, _) => k }.toSet

  test("the LLM band must lie strictly inside (reject, accept): reject < lower < upper < accept") {
    def band(lower: String, upper: String) =
      loadBase(
        base
          .replace("llmBandLower = 0.4", s"llmBandLower = $lower")
          .replace("llmBandUpper = 0.7", s"llmBandUpper = $upper")
      )
    assert(band("0.21", "0.89").isRight, "a band just inside the thresholds is valid")
    assertEquals(
      invalidKeys(band("0.2", "0.7")),
      Set("entityResolution.llmBandLower"),
      "lower = reject"
    )
    assertEquals(
      invalidKeys(band("0.1", "0.7")),
      Set("entityResolution.llmBandLower"),
      "lower < reject"
    )
    assertEquals(
      invalidKeys(band("0.5", "0.5")),
      Set("entityResolution.llmBandUpper"),
      "lower = upper"
    )
    assertEquals(
      invalidKeys(band("0.6", "0.5")),
      Set("entityResolution.llmBandUpper"),
      "lower > upper"
    )
    assertEquals(
      invalidKeys(band("0.4", "0.9")),
      Set("entityResolution.llmBandUpper"),
      "upper = accept"
    )
    assertEquals(
      invalidKeys(band("0.4", "0.95")),
      Set("entityResolution.llmBandUpper"),
      "upper > accept"
    )
    val equal = base.replace("rejectThreshold = 0.2", "rejectThreshold = 0.9")
    assert(
      invalidKeys(loadBase(equal)).contains("entityResolution.rejectThreshold"),
      "reject = accept is rejected (the unresolved interval must be non-empty)"
    )
    // The same rules, reported through the environment as well.
    val env = Map(
      "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__LLM_BAND_LOWER" -> "0.8",
      "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__LLM_BAND_UPPER" -> "0.85"
    )
    assert(ConfigLoader.load(filesEnv ++ env, files("base.conf" -> base)).isRight)
    val badEnv = env.updated("SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__LLM_BAND_LOWER", "0.86")
    assertEquals(
      invalidKeys(ConfigLoader.load(filesEnv ++ badEnv, files("base.conf" -> base))),
      Set("entityResolution.llmBandUpper")
    )
  }

  test("band bounds are probabilities in [0,1]; the prompt version is a non-blank token") {
    assertEquals(
      invalidKeys(loadBase(base.replace("llmBandUpper = 0.7", "llmBandUpper = 1.5"))),
      Set("entityResolution.llmBandUpper")
    )
    assertEquals(
      invalidKeys(loadBase(base.replace("llmBandLower = 0.4", "llmBandLower = -0.1"))),
      Set("entityResolution.llmBandLower")
    )
    for (bad <- Vector("\"\"", "\" \"", "\"has space\"", "\"tab\\tprompt\""))
      assertEquals(
        invalidKeys(
          loadBase(
            base.replace(
              "promptVersion = \"er-adjudication-prompt.v1\"",
              s"promptVersion = $bad"
            )
          )
        ),
        Set("entityResolution.promptVersion"),
        bad
      )
    assertEquals(
      invalidKeys(
        ConfigLoader.load(
          filesEnv + ("SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__PROMPT_VERSION" -> ""),
          files("base.conf" -> base)
        )
      ),
      Set("entityResolution.promptVersion"),
      "an empty environment value is rejected"
    )
    val viaEnv = loadOk(
      filesEnv + ("SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__PROMPT_VERSION" -> "er-adjudication-prompt.v2"),
      files("base.conf" -> base)
    )
    assertEquals(viaEnv.entityResolution.promptVersion, "er-adjudication-prompt.v2")
  }

  test("the new entity-resolution keys are required, have no defaults, and map to env names") {
    for (
      (line, key, env) <- Vector(
        (
          "llmBandLower = 0.4",
          "entityResolution.llmBandLower",
          "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__LLM_BAND_LOWER"
        ),
        (
          "llmBandUpper = 0.7",
          "entityResolution.llmBandUpper",
          "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__LLM_BAND_UPPER"
        ),
        (
          "promptVersion = \"er-adjudication-prompt.v1\"",
          "entityResolution.promptVersion",
          "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__PROMPT_VERSION"
        )
      )
    ) {
      assertEquals(ConfigKeys.envNameOf(key), env)
      assertEquals(
        loadBase(base.replace(line, "")),
        Left(Vector(ConfigError.MissingKey(key, env))),
        key
      )
    }
  }

  test("EntityResolutionConfig.problems applies the loader's rules to in-memory values") {
    val ok =
      EntityResolutionConfig("er-policy-1.0.0", 0.9, 0.2, 0.4, 0.7, "er-adjudication-prompt.v1")
    assertEquals(EntityResolutionConfig.problems(ok), Vector.empty)
    assertEquals(
      EntityResolutionConfig.problems(ok.copy(llmBandLower = 0.1)).map(_._1),
      Vector("entityResolution.llmBandLower")
    )
    assertEquals(
      EntityResolutionConfig.problems(ok.copy(llmBandUpper = Double.NaN)).map(_._1),
      Vector("entityResolution.llmBandUpper"),
      "a non-finite value is reported once, without ordering noise"
    )
    assertEquals(
      EntityResolutionConfig.problems(ok.copy(promptVersion = " ", policyVersion = "")).map(_._1),
      Vector("entityResolution.policyVersion", "entityResolution.promptVersion")
    )
    assertEquals(EntityResolutionConfig.problems(null).map(_._1), Vector("entityResolution"))
  }

  test("unreadable files are reported, and empty list entries are rejected") {
    assertEquals(
      ConfigLoader.load(filesEnv, files()),
      Left(Vector(ConfigError.FileUnreadable("base.conf", "no such file: base.conf")))
    )
    assert(
      ConfigLoader
        .load(Map(ConfigLoader.FilesEnvVar -> "base.conf,"), files("base.conf" -> base))
        .isLeft
    )
  }

  test("loads real .conf files from the local file system") {
    val f = Files.createTempFile("scholarchain-config", ".conf")
    val g = Files.createTempFile("scholarchain-config-dev", ".conf")
    try {
      Files.writeString(f, base, StandardCharsets.UTF_8)
      Files.writeString(g, overlay, StandardCharsets.UTF_8)
      val c = loadOk(Map(ConfigLoader.FilesEnvVar -> f.toString), ConfigLoader.readLocalFile)
      assertEquals(c.environment, "test")
      val layered = loadOk(
        Map(ConfigLoader.FilesEnvVar -> s"$f,$g"),
        ConfigLoader.readLocalFile
      )
      assertEquals(layered.environment, "dev")
      assertEquals(layered.runtime.retrievalTopK, 25)
    } finally { Files.deleteIfExists(f); Files.deleteIfExists(g) }
  }

  test("config digest is deterministic and sensitive to every value") {
    val c = loadOk(filesEnv, files("base.conf" -> base))
    val d = ScholarChainConfig.digest(c)
    assertEquals(d.length, 64)
    assertEquals(ScholarChainConfig.digest(loadOk(filesEnv, files("base.conf" -> base))), d)
    assertNotEquals(ScholarChainConfig.digest(c.copy(environment = "other")), d)
    assertNotEquals(ScholarChainConfig.digest(c.copy(bridge = c.bridge.copy(randomSeed = 8L))), d)
  }
}

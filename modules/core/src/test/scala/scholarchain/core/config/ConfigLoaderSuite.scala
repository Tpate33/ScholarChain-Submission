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

  /**
   * The exact digest input of the base configuration, frozen before the Phase 6 optional keys were
   * added (DE-P6-01). A configuration that sets none of the optional keys must keep this JSON and
   * therefore its digest, so existing run manifests keep their configDigest.
   */
  val baseDigestInput: String =
    """{"configSchemaVersion":"scholarchain.config.v2","environment":"test","aws":{"region":"us-east-1","accountId":"000000000000"},"storage":{"rawBucket":"example-raw-bucket","curatedBucket":"example-curated-bucket","contextBucket":"example-context-bucket","vectorBucket":"example-vector-bucket","vectorIndex":"example-index","dynamoTablePrefix":"example-","neptuneEndpoint":"neptune.example.invalid","openSearchEndpoint":null},"models":{"llmModelId":"example-llm","embeddingModelId":"example-embedding","embeddingDimension":8},"graph":{"windows":[{"startYear":2000,"endYear":2005},{"startYear":2006,"endYear":2010}],"policyVersion":"graph-policy-1.0.0","identityVersion":"v1"},"bridge":{"algorithm":"betweenness-sampled","sampleSize":64,"randomSeed":7,"maxIterations":10},"community":{"algorithm":"label-propagation","maxIterations":20},"entityResolution":{"policyVersion":"er-policy-1.0.0","acceptThreshold":0.9,"rejectThreshold":0.2,"llmBandLower":0.4,"llmBandUpper":0.7,"promptVersion":"er-adjudication-prompt.v1"},"runtime":{"maxPathHops":4,"retrievalTopK":10,"defaultWindow":{"startYear":2015,"endYear":2024}}}"""

  test("an existing config keeps its exact digest input and digest (frozen)") {
    val c = loadOk(filesEnv, files("base.conf" -> base))
    assertEquals(upickle.default.write(c), baseDigestInput)
    assertEquals(
      ScholarChainConfig.digest(c),
      scholarchain.core.Canonical.sha256HexUtf8(baseDigestInput)
    )
  }

  // ---------------------------------------------------------------- Phase 6 optional keys (C4)

  private val groupingLines =
    """entityResolution.groupingPolicyVersion = "er-grouping.v1"
      |entityResolution.decisionRunRoot = "s3://example-curated-bucket/er/decisions"
      |entityResolution.assignmentRunRoot = "/var/tmp/scholarchain/er/assignments"
      |""".stripMargin

  test("C4: existing configs load unchanged; the optional keys are unset and keep the digest") {
    for (text <- Vector(base, jsonBase, dottedBase)) {
      val c = loadOk(filesEnv, files("base.conf" -> text))
      assertEquals(c.entityResolution.groupingPolicyVersion, None)
      assertEquals(c.entityResolution.decisionRunRoot, None)
      assertEquals(c.entityResolution.assignmentRunRoot, None)
      assertEquals(upickle.default.write(c), baseDigestInput)
    }
    // Explicit nulls are the same as unset.
    val nulls =
      base + "entityResolution.groupingPolicyVersion = null\nentityResolution.decisionRunRoot = null\n"
    assertEquals(
      ScholarChainConfig.digest(loadOk(filesEnv, files("base.conf" -> nulls))),
      scholarchain.core.Canonical.sha256HexUtf8(baseDigestInput)
    )
  }

  test("C4: the optional keys load from files and from the environment, and change the digest") {
    val c = loadOk(filesEnv, files("base.conf" -> (base + groupingLines)))
    assertEquals(c.entityResolution.groupingPolicyVersion, Some("er-grouping.v1"))
    assertEquals(
      c.entityResolution.decisionRunRoot,
      Some("s3://example-curated-bucket/er/decisions")
    )
    assertEquals(c.entityResolution.assignmentRunRoot, Some("/var/tmp/scholarchain/er/assignments"))
    assertNotEquals(
      ScholarChainConfig.digest(c),
      scholarchain.core.Canonical.sha256HexUtf8(baseDigestInput)
    )
    assertEquals(
      ConfigKeys.envNameOf(EntityResolutionConfig.GroupingPolicyVersionKey),
      "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__GROUPING_POLICY_VERSION"
    )
    assertEquals(
      ConfigKeys.envNameOf(EntityResolutionConfig.DecisionRunRootKey),
      "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__DECISION_RUN_ROOT"
    )
    assertEquals(
      ConfigKeys.envNameOf(EntityResolutionConfig.AssignmentRunRootKey),
      "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__ASSIGNMENT_RUN_ROOT"
    )
    val viaEnv = loadOk(
      filesEnv ++ Map(
        "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__GROUPING_POLICY_VERSION" -> "er-grouping.v2",
        "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__DECISION_RUN_ROOT" -> "s3://example-curated-bucket/d"
      ),
      files("base.conf" -> (base + groupingLines))
    )
    assertEquals(viaEnv.entityResolution.groupingPolicyVersion, Some("er-grouping.v2"))
    assertEquals(viaEnv.entityResolution.decisionRunRoot, Some("s3://example-curated-bucket/d"))
    // An empty environment value unsets an optional key.
    val unset = loadOk(
      filesEnv + ("SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__GROUPING_POLICY_VERSION" -> ""),
      files("base.conf" -> (base + groupingLines))
    )
    assertEquals(unset.entityResolution.groupingPolicyVersion, None)
  }

  test("F4: grouping-scope keys never change the digest of a non-grouping stage") {
    val baseDigest = scholarchain.core.Canonical.sha256HexUtf8(baseDigestInput)
    val plain = loadOk(filesEnv, files("base.conf" -> base))
    val grouped = loadOk(filesEnv, files("base.conf" -> (base + groupingLines)))
    // The frozen base digest is unchanged, and equals the non-grouping digest without the keys.
    assertEquals(ScholarChainConfig.digest(plain), baseDigest)
    assertEquals(ScholarChainConfig.digestWithoutGrouping(plain), baseDigest)
    // Adding, changing or moving grouping keys keeps the decision-stage digest.
    assertNotEquals(ScholarChainConfig.digest(grouped), baseDigest)
    assertEquals(ScholarChainConfig.digestWithoutGrouping(grouped), baseDigest)
    val moved = grouped.copy(entityResolution =
      grouped.entityResolution.copy(
        groupingPolicyVersion = Some("er-grouping.v2"),
        assignmentRunRoot = Some("/var/tmp/elsewhere")
      )
    )
    assertEquals(ScholarChainConfig.digestWithoutGrouping(moved), baseDigest)
    // Every other value still changes it.
    assertNotEquals(
      ScholarChainConfig.digestWithoutGrouping(
        grouped.copy(entityResolution = grouped.entityResolution.copy(acceptThreshold = 0.95))
      ),
      baseDigest
    )
    assertEquals(
      ScholarChainConfig.GroupingScopeKeys,
      Vector(
        "entityResolution.groupingPolicyVersion",
        "entityResolution.decisionRunRoot",
        "entityResolution.assignmentRunRoot"
      )
    )
    assertEquals(ScholarChainConfig.withoutGrouping(grouped), plain)
  }

  test("F4: the grouping run has its own config digest (frozen input)") {
    val baseDigest = scholarchain.core.Canonical.sha256HexUtf8(baseDigestInput)
    val grouped = loadOk(filesEnv, files("base.conf" -> (base + groupingLines)))
    val frozenInput =
      s"""{"schema":"scholarchain.grouping-config-digest.v1","baseConfigDigest":"$baseDigest","groupingPolicyVersion":"er-grouping.v1"}"""
    assertEquals(
      GroupingRunSettings.configDigest(grouped),
      Right(scholarchain.core.Canonical.sha256HexUtf8(frozenInput))
    )
    // The grouping policy version changes it; the run roots (locations) do not.
    val v2 = grouped.copy(entityResolution =
      grouped.entityResolution.copy(groupingPolicyVersion = Some("er-grouping.v2"))
    )
    assertNotEquals(GroupingRunSettings.configDigest(v2), GroupingRunSettings.configDigest(grouped))
    val moved = grouped.copy(entityResolution =
      grouped.entityResolution.copy(assignmentRunRoot = Some("/var/tmp/elsewhere"))
    )
    assertEquals(GroupingRunSettings.configDigest(moved), GroupingRunSettings.configDigest(grouped))
    // Upstream values change it; a configuration without the grouping keys is refused.
    val other = grouped.copy(environment = "other")
    assertNotEquals(
      GroupingRunSettings.configDigest(other),
      GroupingRunSettings.configDigest(grouped)
    )
    assert(
      GroupingRunSettings.configDigest(loadOk(filesEnv, files("base.conf" -> base))).isLeft
    )
  }

  test("C4: set values are validated: token, separate from the match policy, run locations") {
    def withLine(line: String) = loadBase(base + line + "\n")
    assertEquals(
      invalidKeys(withLine("entityResolution.groupingPolicyVersion = \"has space\"")),
      Set(EntityResolutionConfig.GroupingPolicyVersionKey)
    )
    assertEquals(
      invalidKeys(withLine("entityResolution.groupingPolicyVersion = \"er-policy-1.0.0\"")),
      Set(EntityResolutionConfig.GroupingPolicyVersionKey),
      "the grouping policy is versioned separately from the match policy"
    )
    assertEquals(
      invalidKeys(withLine("entityResolution.groupingPolicyVersion = 3")),
      Set(EntityResolutionConfig.GroupingPolicyVersionKey)
    )
    for (
      bad <- Vector(
        "relative/path",
        "s3://Bad_Bucket/x",
        "s3://example-curated-bucket/a/",
        "s3://example-curated-bucket//a",
        "/tmp/../etc",
        "/tmp/./x",
        "/",
        "/tmp/with space",
        "https://example.invalid/x",
        "C:\\\\runs"
      )
    )
      assertEquals(
        invalidKeys(withLine(s"entityResolution.decisionRunRoot = \"$bad\"")),
        Set(EntityResolutionConfig.DecisionRunRootKey),
        bad
      )
    for (
      good <- Vector("s3://example-curated-bucket", "s3://example-curated-bucket/er/d", "/tmp/er")
    )
      assert(withLine(s"entityResolution.decisionRunRoot = \"$good\"").isRight, good)
    assertEquals(
      invalidKeys(
        ConfigLoader.load(
          filesEnv + ("SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__ASSIGNMENT_RUN_ROOT" -> "rel"),
          files("base.conf" -> base)
        )
      ),
      Set(EntityResolutionConfig.AssignmentRunRootKey)
    )
  }

  test("C4: the decision and assignment roots never equal or contain each other") {
    def roots(d: String, a: String) =
      loadBase(
        base + s"entityResolution.decisionRunRoot = \"$d\"\nentityResolution.assignmentRunRoot = \"$a\"\n"
      )
    for (
      (d, a) <- Vector(
        "/r" -> "/r",
        "/r" -> "/r/a",
        "/r/d" -> "/r",
        "s3://example-curated-bucket" -> "s3://example-curated-bucket/a"
      )
    )
      assertEquals(
        invalidKeys(roots(d, a)),
        Set(EntityResolutionConfig.AssignmentRunRootKey),
        s"$d $a"
      )
    assert(roots("/r/d", "/r/dd").isRight, "a shared name prefix is not containment")
    assert(roots("/r/d", "/r/a").isRight)
  }

  test("C4: the grouping feature refuses a configuration without its keys; others are unaffected") {
    val plain = loadOk(filesEnv, files("base.conf" -> base))
    assertEquals(
      GroupingRunSettings.from(plain),
      Left(
        Vector(
          ConfigError.MissingKey(
            EntityResolutionConfig.GroupingPolicyVersionKey,
            "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__GROUPING_POLICY_VERSION"
          ),
          ConfigError.MissingKey(
            EntityResolutionConfig.DecisionRunRootKey,
            "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__DECISION_RUN_ROOT"
          ),
          ConfigError.MissingKey(
            EntityResolutionConfig.AssignmentRunRootKey,
            "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__ASSIGNMENT_RUN_ROOT"
          )
        )
      )
    )
    val full = loadOk(filesEnv, files("base.conf" -> (base + groupingLines)))
    assertEquals(
      GroupingRunSettings.from(full),
      Right(
        GroupingRunSettings(
          "er-grouping.v1",
          "er-policy-1.0.0",
          "v1",
          "s3://example-curated-bucket/er/decisions",
          "/var/tmp/scholarchain/er/assignments"
        )
      )
    )
    val partial = full.copy(entityResolution = full.entityResolution.copy(assignmentRunRoot = None))
    assertEquals(
      GroupingRunSettings.from(partial).left.map(_.map(_.message)),
      Left(
        Vector(
          ConfigError
            .MissingKey(
              EntityResolutionConfig.AssignmentRunRootKey,
              "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__ASSIGNMENT_RUN_ROOT"
            )
            .message
        )
      )
    )
    assertEquals(
      EntityResolutionConfig
        .requireKey(full.entityResolution, EntityResolutionConfig.DecisionRunRootKey),
      Right("s3://example-curated-bucket/er/decisions")
    )
    assert(
      EntityResolutionConfig
        .requireKey(full.entityResolution, "entityResolution.policyVersion")
        .left
        .exists(_.isInstanceOf[ConfigError.UnknownKey])
    )
    // In-memory configurations get the same rules.
    val clash = full.copy(entityResolution =
      full.entityResolution.copy(groupingPolicyVersion = Some("er-policy-1.0.0"))
    )
    assert(
      GroupingRunSettings
        .from(clash)
        .left
        .exists(_.exists {
          case ConfigError.InvalidValue(k, _, _) =>
            k == EntityResolutionConfig.GroupingPolicyVersionKey
          case _ => false
        })
    )
  }

  // ---------------------------------------------------------------- Phase 6 analysis keys (DE-P6-02)

  private val analysisLines =
    """community.randomSeed = 11
      |analysis {
      |  maxPathHops = 3
      |  maxPathsPerPair = 2
      |  maxEvidenceWorksPerHop = 5
      |  exportFormat = parquet
      |}
      |""".stripMargin

  test(
    "DE-P6-02: existing configs load unchanged; the analysis keys are unset and keep the digest"
  ) {
    for (text <- Vector(base, jsonBase, dottedBase)) {
      val c = loadOk(filesEnv, files("base.conf" -> text))
      assertEquals(c.analysis, AnalysisConfig())
      assertEquals(c.community.randomSeed, None)
      assertEquals(upickle.default.write(c), baseDigestInput)
    }
    val nulls = base + "analysis.maxPathHops = null\ncommunity.randomSeed = null\n"
    assertEquals(
      ScholarChainConfig.digest(loadOk(filesEnv, files("base.conf" -> nulls))),
      scholarchain.core.Canonical.sha256HexUtf8(baseDigestInput)
    )
  }

  test("DE-P6-02: the analysis keys load from files and the environment, with env names") {
    val c = loadOk(filesEnv, files("base.conf" -> (base + analysisLines)))
    assertEquals(c.analysis, AnalysisConfig(Some(3), Some(2), Some(5), Some("parquet")))
    assertEquals(c.community.randomSeed, Some(11L))
    assertEquals(
      ScholarChainConfig.AnalysisScopeKeys.map(ConfigKeys.envNameOf),
      Vector(
        "SCHOLARCHAIN_CFG_COMMUNITY__RANDOM_SEED",
        "SCHOLARCHAIN_CFG_ANALYSIS__MAX_PATH_HOPS",
        "SCHOLARCHAIN_CFG_ANALYSIS__MAX_PATHS_PER_PAIR",
        "SCHOLARCHAIN_CFG_ANALYSIS__MAX_EVIDENCE_WORKS_PER_HOP",
        "SCHOLARCHAIN_CFG_ANALYSIS__EXPORT_FORMAT"
      )
    )
    val viaEnv = loadOk(
      filesEnv ++ Map(
        "SCHOLARCHAIN_CFG_ANALYSIS__MAX_PATH_HOPS" -> "4",
        "SCHOLARCHAIN_CFG_ANALYSIS__EXPORT_FORMAT" -> "jsonl",
        "SCHOLARCHAIN_CFG_COMMUNITY__RANDOM_SEED" -> "9007199254740993"
      ),
      files("base.conf" -> (base + analysisLines))
    )
    assertEquals(viaEnv.analysis.maxPathHops, Some(4))
    assertEquals(viaEnv.analysis.exportFormat, Some("jsonl"))
    assertEquals(viaEnv.community.randomSeed, Some(9007199254740993L))
    val unset = loadOk(
      filesEnv + ("SCHOLARCHAIN_CFG_ANALYSIS__MAX_PATH_HOPS" -> ""),
      files("base.conf" -> (base + analysisLines))
    )
    assertEquals(unset.analysis.maxPathHops, None, "an empty environment value unsets the key")
  }

  test("DE-P6-02: set analysis values are validated") {
    def withLine(line: String) = loadBase(base + line + "\n")
    assertEquals(
      invalidKeys(withLine("analysis.maxPathHops = 0")),
      Set(AnalysisConfig.MaxPathHopsKey)
    )
    assertEquals(
      invalidKeys(withLine("analysis.maxPathsPerPair = \"2\"")),
      Set(AnalysisConfig.MaxPathsPerPairKey),
      "numbers must not be quoted strings"
    )
    assertEquals(
      invalidKeys(withLine("analysis.maxEvidenceWorksPerHop = 1.5")),
      Set(AnalysisConfig.MaxEvidenceWorksPerHopKey)
    )
    for (bad <- Vector("csv", "orc", "\"has space\""))
      assertEquals(
        invalidKeys(withLine(s"analysis.exportFormat = $bad")),
        Set(AnalysisConfig.ExportFormatKey),
        bad
      )
    assertEquals(
      invalidKeys(withLine("community.randomSeed = 7.0")),
      Set(AnalysisConfig.CommunityRandomSeedKey)
    )
    assertEquals(
      invalidKeys(
        ConfigLoader.load(
          filesEnv + ("SCHOLARCHAIN_CFG_ANALYSIS__MAX_PATH_HOPS" -> "x"),
          files("base.conf" -> base)
        )
      ),
      Set(AnalysisConfig.MaxPathHopsKey)
    )
    assert(
      loadBase(base + "analysis.unknownKey = 1\n").left
        .exists(_.contains(ConfigError.UnknownKey("file base.conf", "analysis.unknownKey")))
    )
  }

  test("DE-P6-02: analysis keys never change an upstream digest (decision or grouping run)") {
    val baseDigest = scholarchain.core.Canonical.sha256HexUtf8(baseDigestInput)
    val grouped = loadOk(filesEnv, files("base.conf" -> (base + groupingLines)))
    val both = loadOk(filesEnv, files("base.conf" -> (base + groupingLines + analysisLines)))
    assertNotEquals(ScholarChainConfig.digest(both), ScholarChainConfig.digest(grouped))
    assertEquals(ScholarChainConfig.digestWithoutGrouping(both), baseDigest)
    assertEquals(GroupingRunSettings.configDigest(both), GroupingRunSettings.configDigest(grouped))
    assertEquals(ScholarChainConfig.withoutAnalysis(both), grouped)
  }

  test("DE-P6-02: the analysis feature refuses a configuration without its keys") {
    val plain = loadOk(filesEnv, files("base.conf" -> base))
    assertEquals(
      AnalysisRunSettings.from(plain),
      Left(
        Vector(
          ConfigError
            .MissingKey(AnalysisConfig.MaxPathHopsKey, "SCHOLARCHAIN_CFG_ANALYSIS__MAX_PATH_HOPS"),
          ConfigError.MissingKey(
            AnalysisConfig.MaxPathsPerPairKey,
            "SCHOLARCHAIN_CFG_ANALYSIS__MAX_PATHS_PER_PAIR"
          ),
          ConfigError.MissingKey(
            AnalysisConfig.MaxEvidenceWorksPerHopKey,
            "SCHOLARCHAIN_CFG_ANALYSIS__MAX_EVIDENCE_WORKS_PER_HOP"
          ),
          ConfigError.MissingKey(
            AnalysisConfig.ExportFormatKey,
            "SCHOLARCHAIN_CFG_ANALYSIS__EXPORT_FORMAT"
          )
        )
      )
    )
    assert(AnalysisRunSettings.configDigest(plain).isLeft)
  }

  test(
    "DE-P6-02: analysis settings come from configuration only (windows, algorithms, bounds, format)"
  ) {
    val c = loadOk(filesEnv, files("base.conf" -> (base + analysisLines)))
    val s = AnalysisRunSettings.from(c).fold(e => fail(e.map(_.message).mkString("; ")), identity)
    assertEquals(s.windows, Vector(TimeWindow(2000, 2005), TimeWindow(2006, 2010)))
    assertEquals(s.graphPolicyVersion, "graph-policy-1.0.0")
    assertEquals(s.identityVersion, "v1")
    assertEquals(s.bridgeAlgorithm, "betweenness-sampled")
    assertEquals(
      s.bridgeParameters,
      scala.collection.immutable
        .SortedMap("maxIterations" -> "10", "randomSeed" -> "7", "sampleSize" -> "64")
    )
    assertEquals(s.communityAlgorithm, "label-propagation")
    assertEquals(
      s.communityParameters,
      scala.collection.immutable.SortedMap("maxIterations" -> "20", "randomSeed" -> "11")
    )
    assertEquals(s.pathBounds, scholarchain.core.PathBounds(3, 2, 5))
    assertEquals(s.exportFormat, scholarchain.core.PartFormat.Parquet)
    val noSeed = c.copy(community = c.community.copy(randomSeed = None))
    assertEquals(
      AnalysisRunSettings.from(noSeed).map(_.communityParameters),
      Right(scala.collection.immutable.SortedMap("maxIterations" -> "20"))
    )
    val dupWindows =
      c.copy(graph = c.graph.copy(windows = Vector(TimeWindow(2000, 2005), TimeWindow(2000, 2005))))
    assert(
      AnalysisRunSettings
        .from(dupWindows)
        .left
        .exists(_.exists {
          case ConfigError.InvalidValue(k, _, why) =>
            k == "graph.windows" && why.contains("distinct")
          case _ => false
        })
    )
  }

  test("DE-P6-02: the analysis run has its own config digest (frozen input)") {
    val baseDigest = scholarchain.core.Canonical.sha256HexUtf8(baseDigestInput)
    val c = loadOk(filesEnv, files("base.conf" -> (base + analysisLines)))
    val frozenInput =
      s"""{"schema":"scholarchain.analysis-config-digest.v1","baseConfigDigest":"$baseDigest","communityRandomSeed":11,"maxPathHops":3,"maxPathsPerPair":2,"maxEvidenceWorksPerHop":5,"exportFormat":"parquet"}"""
    assertEquals(
      AnalysisRunSettings.configDigest(c),
      Right(scholarchain.core.Canonical.sha256HexUtf8(frozenInput))
    )
    def digestOf(x: ScholarChainConfig) = AnalysisRunSettings.configDigest(x)
    val d = digestOf(c)
    assertNotEquals(digestOf(c.copy(analysis = c.analysis.copy(maxPathHops = Some(4)))), d)
    assertNotEquals(digestOf(c.copy(community = c.community.copy(randomSeed = None))), d)
    assertNotEquals(
      digestOf(c.copy(graph = c.graph.copy(windows = Vector(TimeWindow(2000, 2010))))),
      d
    )
    assertNotEquals(digestOf(c.copy(bridge = c.bridge.copy(sampleSize = 65))), d)
    // Grouping-scope keys are not analysis inputs.
    val grouped = loadOk(filesEnv, files("base.conf" -> (base + analysisLines + groupingLines)))
    assertEquals(digestOf(grouped), d)
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

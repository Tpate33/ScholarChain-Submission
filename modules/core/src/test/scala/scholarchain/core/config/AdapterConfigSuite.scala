package scholarchain.core.config

/**
 * DE-INT-04: configuration contracts of the storage adapters (S3, Neptune, S3 Vectors), the
 * explicit DynamoDB retry setting and the API-key source. All values are synthetic (`.invalid`
 * hosts, all-zero account ID); no AWS call.
 */
class AdapterConfigSuite extends munit.FunSuite {

  // The ProductionConfigSuite fixtures, reused unchanged (DE-INT-01 compatibility).
  private val fx = new ProductionConfigSuite

  private val Arn =
    "arn:aws:secretsmanager:us-east-1:000000000000:secret:example-api-keys-AbCdEf"

  /** The DE-INT-04 sections; every value sits at or inside a bound the tests below move. */
  val adapterLines: String =
    s"""storage.dynamodb.retryMaxAttempts = 3
       |storage.s3 {
       |  apiCallTimeoutMillis = 400
       |  apiCallAttemptTimeoutMillis = 150
       |  retryMaxAttempts = 3
       |  maxObjectBytes = 1048576
       |  maxListKeys = 4
       |}
       |storage.neptune {
       |  endpoint = "https://neptune.example.invalid:8182"
       |  apiCallTimeoutMillis = 1000
       |  apiCallAttemptTimeoutMillis = 400
       |  retryMaxAttempts = 3
       |  maxRows = 200
       |}
       |storage.s3vectors {
       |  apiCallTimeoutMillis = 1000
       |  apiCallAttemptTimeoutMillis = 400
       |  retryMaxAttempts = 3
       |  maxTopK = 10
       |  maxGetKeys = 10
       |}
       |services.apiKeys {
       |  secretId = "$Arn"
       |  apiCallTimeoutMillis = 1000
       |  apiCallAttemptTimeoutMillis = 400
       |  retryMaxAttempts = 3
       |  cacheTtlMillis = 60000
       |}
       |""".stripMargin

  val full: String = fx.full + adapterLines

  private val filesEnv = Map(ConfigLoader.FilesEnvVar -> "base.conf")
  private def load(text: String, env: Map[String, String] = Map.empty) =
    ConfigLoader.load(filesEnv ++ env, p => if (p == "base.conf") Right(text) else Left("absent"))
  private def loadOk(text: String, env: Map[String, String] = Map.empty): ScholarChainConfig =
    load(text, env).fold(e => fail(e.map(_.message).mkString("; ")), identity)
  private def invalidKeys(r: Either[Vector[ConfigError], ScholarChainConfig]): Set[String] =
    r.left.getOrElse(Vector.empty).collect { case ConfigError.InvalidValue(k, _, _) => k }.toSet
  private def reasons(r: Either[Vector[ConfigError], ScholarChainConfig]): Vector[String] =
    r.left.getOrElse(Vector.empty).collect { case ConfigError.InvalidValue(_, _, why) => why }
  private def ruleIds(r: Either[Vector[ConfigError], ScholarChainConfig]): Set[String] =
    reasons(r).map(_.takeWhile(_ != ':')).toSet
  private def messages(r: Either[Vector[ConfigError], ScholarChainConfig]): String =
    r.left.getOrElse(Vector.empty).map(_.message).mkString("\n")
  private def env(key: String, v: String) = Map(ConfigKeys.envNameOf(key) -> v)

  /** The key failed its OWN bound (not a cross-field rule). */
  private def boundRefused(r: Either[Vector[ConfigError], ScholarChainConfig], key: String) =
    r.left.exists(_.exists {
      case ConfigError.InvalidValue(k, src, _) => k == key && src != "resolved configuration"
      case _ => false
    })

  private def launcher(c: ScholarChainConfig, k: ServiceKindKey): LauncherSettings =
    ProductionSettings.forLauncher(c, k).fold(e => fail(e.map(_.message).mkString("; ")), identity)

  private val Q = "services.query-jobs."
  private val G = "services.graph."
  private val D = "storage.dynamodb."
  private val E = "services.evidence-explain."
  private val S3 = AdapterKeys.S3Prefix
  private val N = AdapterKeys.NeptunePrefix
  private val V = AdapterKeys.S3VectorsPrefix
  private val K = AdapterKeys.ApiKeysPrefix

  import ServiceKindKey.*

  // ------------------------------------------------------------------------ valid and complete

  test("a full adapter configuration loads and forLauncher maps every process exactly") {
    val c = loadOk(full)
    assertEquals(ProductionConfig.problems(c), Vector.empty)
    assertEquals(AdapterKeys.all.size, 1 + 6 + 5 + 6 + 6, "frozen DE-INT-04 key count")
    assertEquals(
      ProductionKeys.allKeys.size,
      ProductionKeys.all.size + AdapterKeys.all.size + ProductionWorkflowKeys.all.size + ProductionLoadKeys.all.size
    )
    assertEquals(ProductionKeys.allKeys.map(_.path).distinct.size, ProductionKeys.allKeys.size)
    val adapterPaths = AdapterKeys.all.map(_.path).toSet
    assertEquals(
      ProductionConfig.flatten(c).keySet.intersect(adapterPaths),
      adapterPaths -- AdapterKeys.OptionalKeys
    )
    val client =
      (call: Long, attempt: Long) => AdapterClientSettings("us-east-1", None, call, attempt, 3)
    val q = launcher(c, QueryJobs)
    assertEquals(ProductionSettings.forProcess(c, QueryJobs), Right(q.process))
    assertEquals(q.dynamoDbRetryMaxAttempts, Some(3))
    assertEquals(
      q.s3,
      Some(
        S3AdapterSettings(
          client(400, 150),
          Vector("example-raw-bucket", "example-curated-bucket", "example-context-bucket"),
          1048576,
          4
        )
      )
    )
    assertEquals(
      q.neptune,
      Some(
        NeptuneAdapterSettings(
          "us-east-1",
          "https://neptune.example.invalid:8182",
          1000,
          400,
          3,
          200
        )
      )
    )
    assertEquals(
      q.s3Vectors,
      Some(S3VectorsAdapterSettings(client(1000, 400), "example-vector-bucket", 10, 10))
    )
    assertEquals(q.apiKeys, ApiKeySourceSettings(Arn, client(1000, 400), 60000L))
    val e = launcher(c, EvidenceExplain)
    assertEquals(ProductionSettings.forProcess(c, EvidenceExplain), Right(e.process))
    assertEquals(
      (e.dynamoDbRetryMaxAttempts, e.s3, e.neptune, e.s3Vectors),
      (Some(3), None, None, None)
    )
    val g = launcher(c, Graph)
    assertEquals(ProductionSettings.forProcess(c, Graph), Right(g.process))
    assertEquals((g.dynamoDbRetryMaxAttempts, g.s3, g.s3Vectors), (None, None, None))
    assertEquals(g.neptune, q.neptune)
    assertEquals(g.apiKeys, q.apiKeys)
    // Optional endpoint overrides reach the client settings when set.
    val o = loadOk(
      full,
      env(S3 + "endpointOverride", "https://s3.example.invalid") ++
        env(V + "endpointOverride", "http://localhost:9000") ++
        env(K + "endpointOverride", "https://secrets.example.invalid")
    )
    val qo = launcher(o, QueryJobs)
    assertEquals(qo.s3.map(_.client.endpointOverride), Some(Some("https://s3.example.invalid")))
    assertEquals(qo.s3Vectors.map(_.client.endpointOverride), Some(Some("http://localhost:9000")))
    assertEquals(qo.apiKeys.client.endpointOverride, Some("https://secrets.example.invalid"))
  }

  // ------------------------------------------------------------------------ compatibility

  test("compatibility: existing configurations load unchanged, keep their digests and forProcess") {
    val plain = loadOk(fx.base)
    assertEquals(
      (
        plain.s3,
        plain.neptune,
        plain.s3Vectors,
        plain.services.apiKeys,
        plain.dynamoDb.retryMaxAttempts
      ),
      (S3Config(), NeptuneConfig(), S3VectorsConfig(), ApiKeySourceConfig(), None)
    )
    val json = upickle.default.write(plain)
    for (name <- Vector("\"s3\"", "\"neptune\"", "s3Vectors", "apiKeys", "retryMaxAttempts"))
      assert(!json.contains(name), s"$name in $json")
    // The DE-INT-01 fixture: same composition settings as before, adapter keys absent.
    val old = loadOk(fx.full)
    val prod = loadOk(full)
    for (k <- ServiceKindKey.values) {
      assert(ProductionSettings.forProcess(old, k).isRight, k.tag)
      assertEquals(ProductionSettings.forProcess(prod, k), ProductionSettings.forProcess(old, k))
      // A launcher needs the adapter sections: exactly those keys are missing.
      val missing = ProductionSettings
        .forLauncher(old, k)
        .left
        .getOrElse(Vector.empty)
        .collect { case ConfigError.MissingKey(key, _) => key }
      assertEquals(missing.toSet, AdapterKeys.requiredFor(k).toSet, k.tag)
    }
    // Adapter keys change the full digest only; never a pipeline-stage digest.
    assertNotEquals(ScholarChainConfig.digest(prod), ScholarChainConfig.digest(old))
    assertEquals(
      ScholarChainConfig.digestWithoutGrouping(prod),
      ScholarChainConfig.digest(plain)
    )
    assertEquals(ScholarChainConfig.withoutProduction(prod), plain)
    // The legacy free-text Neptune key keeps loading in its old host-only form.
    assertEquals(old.storage.neptuneEndpoint, "neptune.example.invalid")
  }

  // ------------------------------------------------------------------------ per-process keys

  test("each process requires exactly its adapter sections; endpoint overrides stay optional") {
    val sections = Vector(
      "dynamoDbRetry" -> AdapterKeys.dynamoDbRetry,
      "s3" -> AdapterKeys.s3,
      "neptune" -> AdapterKeys.neptune,
      "s3Vectors" -> AdapterKeys.s3Vectors,
      "apiKeys" -> AdapterKeys.apiKeys
    )
    val needs: Map[ServiceKindKey, Set[String]] = Map(
      QueryJobs -> Set("dynamoDbRetry", "s3", "neptune", "s3Vectors", "apiKeys"),
      EvidenceExplain -> Set("dynamoDbRetry", "apiKeys"),
      Graph -> Set("neptune", "apiKeys")
    )
    for ((name, specs) <- sections; k <- ServiceKindKey.values) {
      val unset = specs.map(s => ConfigKeys.envNameOf(s.path) -> "").toMap
      val c = loadOk(full, unset)
      val r = ProductionSettings.forLauncher(c, k)
      if (needs(k).contains(name))
        assertEquals(
          r.left.map(_.toSet),
          Left(
            specs
              .map(_.path)
              .filterNot(AdapterKeys.OptionalKeys.contains)
              .map(p => ConfigError.MissingKey(p, ConfigKeys.envNameOf(p)))
              .toSet
          ),
          s"${k.tag} without $name"
        )
      else assert(r.isRight, s"${k.tag} does not need $name: $r")
      // The composition contract is unchanged without any adapter section.
      assert(ProductionSettings.forProcess(c, k).isRight, s"${k.tag} forProcess without $name")
    }
    val base = (k: ServiceKindKey) => ProductionSettings.requiredKeys(k).size
    assertEquals(ProductionSettings.launcherRequiredKeys(QueryJobs).size, base(QueryJobs) + 21)
    assertEquals(
      ProductionSettings.launcherRequiredKeys(EvidenceExplain).size,
      base(EvidenceExplain) + 6
    )
    assertEquals(ProductionSettings.launcherRequiredKeys(Graph).size, base(Graph) + 10)
    for (k <- ServiceKindKey.values)
      assert(
        AdapterKeys.OptionalKeys
          .forall(o => !ProductionSettings.launcherRequiredKeys(k).contains(o))
      )
    // Graph launches without DynamoDB; the API-key secret is required by all three.
    assert(AdapterKeys.requiredFor(Graph).forall(p => !p.startsWith(D)))
    for (k <- ServiceKindKey.values) assert(AdapterKeys.requiredFor(k).contains(K + "secretId"))
    // Null inputs fail closed.
    assert(ProductionSettings.forLauncher(null, Graph).isLeft)
    val c = loadOk(full)
    assertEquals(
      ProductionSettings.forLauncher(c.copy(aws = null), Graph),
      Left(Vector(ConfigError.MissingKey("aws", ConfigKeys.envNameOf("aws"))))
    )
  }

  // ------------------------------------------------------------------------ individual bounds

  test("the bounds mirror the storage constructors exactly") {
    assertEquals(AdapterBounds.MaxRetryAttempts, 10)
    assertEquals(AdapterBounds.MaxS3ObjectBytes, 2147483638)
    assertEquals(AdapterBounds.MaxS3ListKeys, 1000)
    assertEquals(AdapterBounds.MaxS3VectorsTopK, 100)
    assertEquals(AdapterBounds.MaxS3VectorsGetKeys, 100)
    val bound = AdapterKeys.all.map(s => s.path -> s.bound).toMap
    for (p <- Vector(D, S3, N, V, K))
      assertEquals(bound(p + "retryMaxAttempts"), KeyBound.Range(1, 10), p)
    assertEquals(bound(S3 + "maxObjectBytes"), KeyBound.Range(1, 2147483638L))
    assertEquals(bound(S3 + "maxListKeys"), KeyBound.Range(1, 1000))
    assertEquals(bound(N + "maxRows"), KeyBound.Range(1, Int.MaxValue.toLong))
    assertEquals(bound(V + "maxTopK"), KeyBound.Range(1, 100))
    assertEquals(bound(V + "maxGetKeys"), KeyBound.Range(1, 100))
    // Whole configurations at the storage maxima load (the T-S3 budget made to fit).
    assert(load(full, env(V + "maxTopK", "100") ++ env(V + "maxGetKeys", "100")).isRight)
    assert(load(full, env(S3 + "maxObjectBytes", "2147483638")).isRight)
    val list1000 = env(S3 + "maxListKeys", "1000") ++ env(S3 + "apiCallTimeoutMillis", "2") ++
      env(S3 + "apiCallAttemptTimeoutMillis", "1") ++ env(Q + "readTimeoutMillis", "2002")
    assert(load(full, list1000).isRight)
    assert(
      load(full, env(K + "retryMaxAttempts", "10") ++ env(D + "retryMaxAttempts", "10")).isRight
    )
  }

  test("every DE-INT-04 Range key: min - 1 and max + 1 refused, min and max accepted by the key") {
    for (spec <- AdapterKeys.all) spec.bound match {
      case KeyBound.Range(min, max) =>
        for (bad <- Vector(BigInt(min) - 1, BigInt(max) + 1)) {
          val r = load(full, env(spec.path, bad.toString))
          assertEquals(invalidKeys(r), Set(spec.path), s"${spec.path} = $bad")
          assert(
            reasons(r).forall(why =>
              why == s"must be an integer in [$min, $max]" || why.startsWith("expected a ")
            ),
            s"${spec.path}: ${reasons(r)}"
          )
        }
        // At either bound the key itself is valid (a cross-field rule may still refuse it).
        for (ok <- Vector(min, max))
          assert(
            !boundRefused(load(full, env(spec.path, ok.toString)), spec.path),
            s"${spec.path} = $ok"
          )
        assert(boundRefused(load(full, env(spec.path, "1.5")), spec.path), spec.path)
      case _ => ()
    }
  }

  test("Long.MaxValue and Long.MinValue are refused for every DE-INT-04 numeric key") {
    for (spec <- AdapterKeys.all if spec.kind != ValueKind.OptionalString)
      for (v <- Vector(Long.MaxValue, Long.MinValue)) {
        assertEquals(invalidKeys(load(full, env(spec.path, v.toString))), Set(spec.path), spec.path)
        assertEquals(invalidKeys(load(full + s"${spec.path} = $v\n")), Set(spec.path), spec.path)
      }
  }

  // ------------------------------------------------------------------------ Neptune endpoint

  test("storage.neptune.endpoint is https://host:port, exactly the storage rule; value-free") {
    val key = N + "endpoint"
    for (
      ok <- Vector(
        "https://neptune.example.invalid:8182",
        "https://neptune.example.invalid:1",
        "https://neptune.example.invalid:65535",
        "https://neptune.example.invalid:8182/"
      )
    ) {
      assert(AdapterBounds.neptuneEndpointOk(ok), ok)
      assertEquals(loadOk(full + s"""$key = "$ok"\n""").neptune.endpoint, Some(ok))
    }
    for (
      bad <- Vector(
        "http://SECRETX.invalid:8182",
        "https://SECRETX.invalid",
        "https://SECRETX.invalid:0",
        "https://SECRETX.invalid:65536",
        "https://user:SECRETX@neptune.example.invalid:8182",
        "https://neptune.example.invalid:8182/SECRETX",
        "https://neptune.example.invalid:8182?q=SECRETX",
        "https://neptune.example.invalid:8182#SECRETX",
        "https://SECRETX .invalid:8182",
        " https://SECRETX.invalid:8182",
        "SECRETX.invalid",
        "SECRETX.invalid:8182",
        ""
      )
    ) {
      assert(!AdapterBounds.neptuneEndpointOk(bad), bad)
      val r = load(full + s"""$key = "$bad"\n""")
      assertEquals(invalidKeys(r), Set(key), bad)
      assert(boundRefused(r, key), bad)
      assert(!messages(r).contains("SECRETX"), messages(r))
    }
    assert(!AdapterBounds.neptuneEndpointOk(null))
  }

  // ------------------------------------------------------------------------ API-key secret

  test("services.apiKeys.secretId is a secret ARN or name; refusals never echo the value") {
    val key = K + "secretId"
    for (
      ok <- Vector(
        "scholarchain/dev/api-keys",
        "a",
        "x" * 512,
        Arn,
        "arn:aws-us-gov:secretsmanager:us-gov-west-1:000000000000:secret:x"
      )
    ) assertEquals(loadOk(full, env(key, ok)).services.apiKeys.secretId, Some(ok), ok)
    for (
      bad <- Vector(
        "y" * 513,
        "has space SECRETX",
        "arn:aws:secretsmanager:us-east-1:12345:secret:SECRETX",
        "arn:aws:s3:::SECRETX",
        "arn:aws:secretsmanager:us-east-1:000000000000:other:SECRETX",
        "SECRETX:colon"
      )
    ) {
      val r = load(full, env(key, bad))
      assertEquals(invalidKeys(r), Set(key), bad)
      assert(!messages(r).contains("SECRETX") && !messages(r).contains("y" * 513), messages(r))
    }
    assert(boundRefused(load(full + s"""$key = ""\n"""), key))
  }

  // ------------------------------------------------------------------------ cross-field rules

  test("each DE-INT-04 rule refuses at its exact boundary, reported on its first key") {
    val refused: Vector[(String, Map[String, String])] = Vector(
      "S3-1" -> env(S3 + "apiCallAttemptTimeoutMillis", "400"),
      "NEP-1" -> env(N + "apiCallAttemptTimeoutMillis", "1000"),
      "S3V-1" -> env(V + "apiCallAttemptTimeoutMillis", "1000"),
      "KEY-1" -> env(K + "apiCallAttemptTimeoutMillis", "1000"),
      "S3V-2" -> env(V + "maxGetKeys", "9"),
      "S3V-3" -> env("storage.vectorBucket", "example.vector-bucket"),
      "S3V-4" -> env("runtime.retrievalTopK", "11"),
      "NEP-2[query-jobs]" -> env(Q + "graphMaxResults", "201"),
      "NEP-2[graph]" -> env(G + "graphMaxResults", "201"),
      "NEP-3" -> env("storage.neptuneEndpoint", "other.example.invalid"),
      "ADP-1" -> env("aws.region", "us-east-100"),
      "T-S3[query-jobs]" -> env(S3 + "maxListKeys", "5"),
      "T-NEP[query-jobs]" -> (env(N + "apiCallTimeoutMillis", "2001") ++
        env(G + "readTimeoutMillis", "3000")),
      "T-NEP[graph]" -> env(G + "readTimeoutMillis", "999"),
      "T-S3V[query-jobs]" -> env(V + "apiCallTimeoutMillis", "1001")
    )
    assertEquals(refused.map(_._1).toSet, AdapterRules.all.map(_.id).toSet, "every rule covered")
    for ((id, overrides) <- refused) {
      val r = load(full, overrides)
      assertEquals(ruleIds(r), Set(id), s"$id: ${reasons(r)}")
      val rule = AdapterRules.all.find(_.id == id).get
      assertEquals(invalidKeys(r), Set(rule.keys.head), id)
      assert(
        r.left.exists(_.forall {
          case ConfigError.InvalidValue(_, src, _) => src == "resolved configuration"
          case _ => false
        }),
        id
      )
      // The rule names keys and constants, never the configured value.
      for (v <- overrides.values if v.length > 4) assert(!messages(r).contains(v), s"$id: $v")
    }
    // The valid side of each boundary.
    val accepted: Vector[(String, Map[String, String])] = Vector(
      "S3-1" -> env(S3 + "apiCallAttemptTimeoutMillis", "399"),
      "NEP-1" -> env(N + "apiCallAttemptTimeoutMillis", "999"),
      "S3V-1" -> env(V + "apiCallAttemptTimeoutMillis", "999"),
      "KEY-1" -> env(K + "apiCallAttemptTimeoutMillis", "999"),
      "S3V-2" -> env(V + "maxGetKeys", "10"),
      "S3V-4" -> env("runtime.retrievalTopK", "10"),
      "NEP-2" -> (env(N + "maxRows", "201") ++ env(Q + "graphMaxResults", "201") ++
        env(G + "graphMaxResults", "201")),
      "NEP-3 host:port" -> env("storage.neptuneEndpoint", "neptune.example.invalid:8182"),
      "NEP-3 uri" -> env("storage.neptuneEndpoint", "https://neptune.example.invalid:8182"),
      "ADP-1" -> env("aws.region", "eu-central-1"),
      "T-S3" -> (env(S3 + "maxListKeys", "9") ++ env(S3 + "apiCallTimeoutMillis", "200")),
      "T-NEP" -> env(N + "apiCallTimeoutMillis", "2000"),
      "T-NEP[graph]" -> env(G + "readTimeoutMillis", "1000"),
      "T-S3V" -> env(V + "apiCallTimeoutMillis", "1000")
    )
    for ((id, overrides) <- accepted) assert(load(full, overrides).isRight, id)
    // Further boundaries: maxRows one below both graph limits; NEP-3 port mismatch; T-S3 on the
    // call timeout (5 x 401 = 2005).
    assertEquals(
      ruleIds(load(full, env(N + "maxRows", "199"))),
      Set("NEP-2[query-jobs]", "NEP-2[graph]")
    )
    assertEquals(
      ruleIds(load(full, env("storage.neptuneEndpoint", "neptune.example.invalid:8183"))),
      Set("NEP-3")
    )
    assertEquals(
      ruleIds(load(full, env(S3 + "apiCallTimeoutMillis", "401"))),
      Set("T-S3[query-jobs]")
    )
    // The non-production inputs alone never trigger a rule (no adapter section set).
    for (
      o <- Vector(
        env("storage.vectorBucket", "example.vector-bucket"),
        env("aws.region", "us-east-100"),
        env("runtime.retrievalTopK", "500"),
        env("storage.neptuneEndpoint", "other.example.invalid")
      )
    ) assert(load(fx.full, o).isRight, o.toString)
  }

  test("the adapter rule table is data: unique IDs, known keys, formulas naming their keys") {
    val ids = ProductionRules.every.map(_.id)
    assertEquals(ids.distinct.size, ids.size)
    assertEquals(
      ProductionRules.every.size,
      ProductionRules.all.size + 15 + ProductionWorkflowKeys.rules.size + ProductionLoadKeys.rules.size
    )
    for (r <- AdapterRules.all) {
      assert(
        r.keys.forall(k =>
          ProductionKeys.byPath.contains(k) || AdapterRules.NonProductionInputs.contains(k)
        ),
        r.id
      )
      assert(r.source.nonEmpty, r.id)
      assert(r.keys.forall(r.formula.contains), r.id)
    }
    assertEquals(
      ProductionRules.violations(ProductionConfig.ruleInputs(loadOk(full))),
      Vector.empty
    )
  }

  // ------------------------------------------------------------------------ timing

  test("timing budgets: sequential SDK calls x apiCallTimeout, saturating, never x retries") {
    import AdapterBudgets.*
    assertEquals(
      (
        AdapterBounds.S3HeadSdkCalls,
        AdapterBounds.S3GetSdkCalls,
        AdapterBounds.S3ListPageSdkCalls,
        AdapterBounds.NeptuneRunSdkCalls,
        AdapterBounds.S3VectorsVerifiedQuerySdkCalls,
        AdapterBounds.S3VectorsGetSdkCalls,
        AdapterBounds.S3VectorsDescribeSdkCalls
      ),
      (1, 1, 1, 1, 2, 1, 1)
    )
    assertEquals(s3ListSdkCalls(4), 5L)
    assertEquals(s3WorstSdkCalls(0), 1L)
    assertEquals(s3AttemptBudgetMillis(4, 400), 2000L)
    assertEquals(neptuneAttemptBudgetMillis(1000), 1000L)
    assertEquals(s3VectorsAttemptBudgetMillis(1000), 2000L)
    assertEquals(s3ListSdkCalls(Long.MaxValue), Long.MaxValue)
    assertEquals(s3AttemptBudgetMillis(Long.MaxValue, 2), Long.MaxValue)
    assertEquals(s3AttemptBudgetMillis(1000, Long.MaxValue), Long.MaxValue)
    assertEquals(neptuneAttemptBudgetMillis(Long.MaxValue), Long.MaxValue)
    assertEquals(s3VectorsAttemptBudgetMillis(Long.MaxValue / 2 + 1), Long.MaxValue)
    // A wrapping product would be negative and pass; the saturated one refuses.
    val rule = (id: String) => AdapterRules.all.find(_.id == id).get
    val tS3 = Map[String, Any](
      S3 + "apiCallTimeoutMillis" -> (Long.MaxValue / 2),
      S3 + "maxListKeys" -> Int.MaxValue,
      Q + "readTimeoutMillis" -> (Long.MaxValue - 1)
    )
    assert(!rule("T-S3[query-jobs]").check(tS3))
    val tS3V = Map[String, Any](
      V + "apiCallTimeoutMillis" -> (Long.MaxValue / 2 + 1),
      Q + "readTimeoutMillis" -> (Long.MaxValue - 1)
    )
    assert(!rule("T-S3V[query-jobs]").check(tS3V))
    // Retries add nothing: apiCallTimeout already covers every SDK attempt (Q4). The same
    // timeouts pass with 1 and with 10 attempts, and the same boundary refuses with both.
    for (retries <- Vector("1", "10")) {
      val r = env(S3 + "retryMaxAttempts", retries) ++ env(N + "retryMaxAttempts", retries) ++
        env(V + "retryMaxAttempts", retries) ++ env(D + "retryMaxAttempts", retries)
      assert(load(full, r).isRight, retries)
      assertEquals(
        ruleIds(load(full, r ++ env(V + "apiCallTimeoutMillis", "1001"))),
        Set("T-S3V[query-jobs]")
      )
      // DynamoDB X-4[query-jobs] stays 3 x apiCallTimeout (DE-INT-03): 666 accepted, 667 refused.
      val evidenceRoom = env(D + "maxQueryPages", "1") ++ env(E + "readTimeoutMillis", "7000") ++
        env(E + "readRequestTimeoutMillis", "7000")
      assert(load(full, r ++ evidenceRoom ++ env(D + "apiCallTimeoutMillis", "666")).isRight)
      assertEquals(
        ruleIds(load(full, r ++ evidenceRoom ++ env(D + "apiCallTimeoutMillis", "667"))),
        Set("X-4[query-jobs]")
      )
    }
  }

  // ------------------------------------------------------------------------ names and keys

  test("environment names of the new keys, and unknown keys in the new sections") {
    assertEquals(
      ConfigKeys.envNameOf(V + "maxTopK"),
      "SCHOLARCHAIN_CFG_STORAGE__S3VECTORS__MAX_TOP_K"
    )
    assertEquals(
      ConfigKeys.envNameOf(N + "endpoint"),
      "SCHOLARCHAIN_CFG_STORAGE__NEPTUNE__ENDPOINT"
    )
    assertEquals(
      ConfigKeys.envNameOf(AdapterKeys.DynamoDbRetryKey),
      "SCHOLARCHAIN_CFG_STORAGE__DYNAMODB__RETRY_MAX_ATTEMPTS"
    )
    assertEquals(
      ConfigKeys.envNameOf(K + "secretId"),
      "SCHOLARCHAIN_CFG_SERVICES__API_KEYS__SECRET_ID"
    )
    assertEquals(ConfigKeys.byEnv.size, ConfigKeys.all.size, "env names stay unique")
    for (
      (line, key) <- Vector(
        s"${S3}unknown = 1" -> s"${S3}unknown",
        s"${N}port = 8182" -> s"${N}port",
        s"${K}secretValue = x" -> s"${K}secretValue",
        s"${V}maxKeys = 1" -> s"${V}maxKeys"
      )
    )
      assert(
        load(full + line + "\n").left
          .exists(_.contains(ConfigError.UnknownKey("file base.conf", key))),
        line
      )
  }
}

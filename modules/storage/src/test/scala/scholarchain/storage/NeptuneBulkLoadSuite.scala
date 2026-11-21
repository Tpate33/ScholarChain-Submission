package scholarchain.storage

import scholarchain.core.{Json, StoreLoadManifest, TargetStore}
import scholarchain.storage.fake.InMemoryKeyValueStore
import scholarchain.storage.load.*
import scholarchain.storage.port.{BulkLoadPlan, KeyValueReader}
import Fixtures.*

/**
 * SE-P7-05 B6 (checklist N1): the Neptune bulk-loader requests for the fixture's Neptune load are
 * built by a pure function from configuration (test values below) and, since CR-P7-07 n6, only from
 * a `neptune-bulk-load` [[LoadPermit]] issued by the PRODUCTION gate (every Neptune check performed
 * on the real fixtures, upstream read by the production readers); golden tested, never sent:
 * storage has no Neptune client.
 */
class NeptuneBulkLoadSuite extends munit.FunSuite {

  /** Test configuration: a dummy account and role, never a real resource. */
  private val settings = NeptuneLoaderSettings
    .of(
      "arn:aws:iam::123456789012:role/test-neptune-load",
      "us-east-1",
      true,
      LoaderParallelism.Medium,
      true
    )
    .fold(e => fail(e.toString), identity)

  private def manifest(t: TargetStore): StoreLoadManifest =
    Json
      .decode[StoreLoadManifest](
        text(loadDir(run1Root).resolve(s"${t.tag}.json")).stripSuffix("\n")
      )
      .fold(e => fail(e.toString), identity)

  private lazy val report: VerificationReport = LoadVerifier.verify(
    source(runDir(run1Root)),
    source(loadDir(run1Root), loadBounds),
    KeyValueReader.readOnly(new InMemoryKeyValueStore(100)),
    Some(UpstreamFixture.context)
  )

  private def plan(s: NeptuneLoaderSettings): BulkLoadPlan =
    LoadGate.proceed(report, TargetStore.NeptuneBulkLoad)(p =>
      NeptuneBulkLoad.plan(p, TestData.layout, s)
    ) match {
      case Left(rs) => fail(s"gate refused: $rs")
      case Right(r) => r.fold(e => fail(e.toString), identity)
    }

  private val vertexSteps = Vector(
    "researcher-vertices",
    "author-mention-vertices",
    "work-vertices",
    "venue-vertices",
    "institution-vertices",
    "topic-vertices"
  )
  private val edgeSteps = Vector(
    "authored-edges",
    "resolves-to-edges",
    "coauthored-with-edges",
    "published-in-edges",
    "affiliated-with-edges",
    "has-topic-edges"
  )

  test("golden: one request per role, vertices first, edges depending on every vertex step") {
    val plan = this.plan(settings)
    assertEquals(plan.runId, RunId)
    assertEquals(plan.loadId, manifest(TargetStore.NeptuneBulkLoad).loadId)
    assertEquals(plan.requests.map(_.step), vertexSteps ++ edgeSteps)
    plan.requests.take(6).foreach(r => assertEquals(r.dependsOnSteps, Vector.empty, r.step))
    plan.requests.drop(6).foreach(r => assertEquals(r.dependsOnSteps, vertexSteps, r.step))
    assertEquals(
      plan.requests.head.body,
      "{\"source\":\"s3://test-data-bucket/graph/runs/" + RunHex + "/researcher-vertices/\"," +
        "\"format\":\"csv\",\"iamRoleArn\":\"arn:aws:iam::123456789012:role/test-neptune-load\"," +
        "\"region\":\"us-east-1\",\"failOnError\":\"TRUE\",\"parallelism\":\"MEDIUM\"," +
        "\"queueRequest\":\"TRUE\"}"
    )
    assertEquals(
      plan.requests.last.body,
      "{\"source\":\"s3://test-data-bucket/graph/runs/" + RunHex + "/has-topic-edges/\"," +
        "\"format\":\"csv\",\"iamRoleArn\":\"arn:aws:iam::123456789012:role/test-neptune-load\"," +
        "\"region\":\"us-east-1\",\"failOnError\":\"TRUE\",\"parallelism\":\"MEDIUM\"," +
        "\"queueRequest\":\"TRUE\"}"
    )
    // Every value comes from the manifest or the settings.
    plan.requests.foreach { r =>
      assertEquals(
        (r.format, r.iamRoleArn, r.region, r.parallelism),
        ("csv", settings.iamRoleArn, "us-east-1", "MEDIUM")
      )
      assert(
        r.sourceUri.startsWith(
          TestData.layout.zonePrefix(scholarchain.storage.model.Zone.Graph).uri
        )
      )
    }
  }

  test("settings change the request; invalid settings are refused") {
    val other = NeptuneLoaderSettings
      .of("arn:aws:iam::210987654321:role/other", "eu-west-1", false, LoaderParallelism.Low, false)
      .fold(e => fail(e.toString), identity)
    val r = plan(other).requests.head
    assert(
      r.body.contains("\"failOnError\":\"FALSE\"") && r.body.contains("\"parallelism\":\"LOW\""),
      r.body
    )
    assert(r.body.contains("eu-west-1") && r.body.contains("210987654321"), r.body)

    assert(
      NeptuneLoaderSettings
        .of("arn:aws:iam::1:role/x", "us-east-1", true, LoaderParallelism.Low, true)
        .isLeft
    )
    assert(
      NeptuneLoaderSettings
        .of(settings.iamRoleArn, "Mars", true, LoaderParallelism.Low, true)
        .isLeft
    )
    assert(NeptuneLoaderSettings.of(settings.iamRoleArn, "us-east-1", true, null, true).isLeft)
  }

  test("CR-P7-07 n6: a plan needs a gate permit; a bare load manifest is not accepted") {
    assert(
      compileErrors(
        "NeptuneBulkLoad.plan(manifest(TargetStore.NeptuneBulkLoad), TestData.layout, settings)"
      ).nonEmpty
    )
    assert(NeptuneBulkLoad.plan(null, TestData.layout, settings).isLeft)
    // Without the upstream the gate refuses Neptune, so no plan is made.
    val refused = LoadVerifier.verify(
      source(runDir(run1Root)),
      source(loadDir(run1Root), loadBounds),
      KeyValueReader.readOnly(new InMemoryKeyValueStore(100)),
      Some(RunSettingsFixture.context)
    )
    var called = false
    val out = LoadGate.proceed(refused, TargetStore.NeptuneBulkLoad) { p =>
      called = true
      NeptuneBulkLoad.plan(p, TestData.layout, settings)
    }
    assert(out.isLeft && !called)
  }
}

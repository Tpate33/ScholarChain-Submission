package scholarchain.tests.services

import scala.util.control.NonFatal

import scholarchain.services.{ServiceConfig, ServiceKind, ServiceRuntime}

/**
 * TE-P11-02 T9 (Phase 11 category 21, configuration omissions): null and missing fields of the
 * service configuration and of the composition's ports. Required: `ServiceConfig.problems` and
 * `ServiceRuntime.build` return the problem (Left / a non-empty problem list naming the field) and
 * never throw, so a deployment with an omitted value fails at start with a reason.
 */
class ConfigOmissionAdversarialSuite extends munit.FunSuite {

  private def attempt[A](tag: String)(f: => A): Either[String, A] =
    try Right(f)
    catch { case NonFatal(e) => Left(s"$tag threw ${e.getClass.getName}: ${e.getMessage}") }

  test("T9 null configuration fields are reported as problems, never thrown") {
    val base = ApiWorld.config()
    assertEquals(ServiceConfig.problems(base), Vector.empty[String], "the base config is valid")
    val cases: Vector[(String, ServiceConfig, String)] = Vector(
      ("graphDefaultWindow = null", base.copy(graphDefaultWindow = null), "graphDefaultWindow"),
      ("graphEdgeTypes = null", base.copy(graphEdgeTypes = null), "graphEdgeTypes"),
      ("graphEdgeTypes = Vector(null)", base.copy(graphEdgeTypes = Vector(null)), "graphEdgeTypes"),
      ("graphEdgeTypes = empty", base.copy(graphEdgeTypes = Vector.empty), "graphEdgeTypes"),
      ("maxBodyBytes = 0", base.copy(maxBodyBytes = 0), "maxBodyBytes"),
      ("retryAfterSeconds = 0", base.copy(retryAfterSeconds = 0), "retryAfterSeconds")
    )
    val thrown = cases.flatMap { case (tag, c, field) =>
      attempt(tag)(ServiceConfig.problems(c)) match {
        case Left(t) => Vector(t)
        case Right(ps) =>
          assert(ps.exists(_.startsWith(field)), s"$tag: problems do not name $field: $ps")
          Vector.empty
      }
    }
    val built = cases.flatMap { case (tag, c, _) =>
      val w = new ApiWorld
      attempt(s"build($tag)")(
        ServiceRuntime.build(
          w.runtime,
          w.ragWorkflow(),
          new Keys,
          c,
          w.access,
          new RecordingLog,
          new SvcClock,
          new CountingIds
        )
      ) match {
        case Left(t) => Vector(t)
        case Right(Left(_)) => Vector.empty
        case Right(Right(rt)) => rt.close(); Vector(s"build($tag) accepted the config")
      }
    }
    assert(
      thrown.isEmpty && built.isEmpty,
      "DEFECT (service-engineer, ServiceConfig.scala:178 / ServiceRuntime.scala:58: a null field " +
        s"is not reported as a problem): ${(thrown ++ built).mkString("; ")}"
    )
    assertEquals(ServiceConfig.problems(null), Vector("config: missing"))
  }

  test("T9 every null port of the composition is a Left naming it, never an exception") {
    val w = new ApiWorld
    val c = ApiWorld.config()
    val ports: Vector[(String, () => Either[String, ServiceRuntime])] = Vector(
      "runtime" -> (() =>
        ServiceRuntime.build(
          null,
          w.ragWorkflow(),
          new Keys,
          c,
          w.access,
          new RecordingLog,
          new SvcClock,
          new CountingIds
        )
      ),
      "workflow" -> (() =>
        ServiceRuntime.build(
          w.runtime,
          null,
          new Keys,
          c,
          w.access,
          new RecordingLog,
          new SvcClock,
          new CountingIds
        )
      ),
      "keys" -> (() =>
        ServiceRuntime.build(
          w.runtime,
          w.ragWorkflow(),
          null,
          c,
          w.access,
          new RecordingLog,
          new SvcClock,
          new CountingIds
        )
      ),
      "config" -> (() =>
        ServiceRuntime.build(
          w.runtime,
          w.ragWorkflow(),
          new Keys,
          null,
          w.access,
          new RecordingLog,
          new SvcClock,
          new CountingIds
        )
      ),
      "access" -> (() =>
        ServiceRuntime.build(
          w.runtime,
          w.ragWorkflow(),
          new Keys,
          c,
          null,
          new RecordingLog,
          new SvcClock,
          new CountingIds
        )
      ),
      "accessLog" -> (() =>
        ServiceRuntime.build(
          w.runtime,
          w.ragWorkflow(),
          new Keys,
          c,
          w.access,
          null,
          new SvcClock,
          new CountingIds
        )
      ),
      "clock" -> (() =>
        ServiceRuntime.build(
          w.runtime,
          w.ragWorkflow(),
          new Keys,
          c,
          w.access,
          new RecordingLog,
          null,
          new CountingIds
        )
      ),
      "ids" -> (() =>
        ServiceRuntime.build(
          w.runtime,
          w.ragWorkflow(),
          new Keys,
          c,
          w.access,
          new RecordingLog,
          new SvcClock,
          null
        )
      ),
      "kind" -> (() =>
        ServiceRuntime.build(
          w.runtime,
          w.ragWorkflow(),
          new Keys,
          c,
          w.access,
          new RecordingLog,
          new SvcClock,
          new CountingIds,
          null
        )
      ),
      "ownership (split query-jobs)" -> (() =>
        ServiceRuntime.build(
          w.runtime,
          w.ragWorkflow(),
          new Keys,
          c,
          w.access,
          new RecordingLog,
          new SvcClock,
          new CountingIds,
          ServiceKind.QueryJobs,
          null
        )
      )
    )
    val bad = ports.flatMap { case (tag, f) =>
      attempt(tag)(f()) match {
        case Left(t) => Vector(t)
        case Right(Left(_)) => Vector.empty
        case Right(Right(rt)) => rt.close(); Vector(s"$tag: null accepted")
      }
    }
    assert(
      bad.isEmpty,
      s"DEFECT (service-engineer, ServiceRuntime.scala:46-71): ${bad.mkString("; ")}"
    )
  }
}

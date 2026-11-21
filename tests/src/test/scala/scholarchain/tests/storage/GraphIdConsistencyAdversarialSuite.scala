package scholarchain.tests.storage

import java.nio.file.Path

import scholarchain.storage.{Fixtures, OpeningFixture}
import scholarchain.storage.OpeningFixture.{World, sources}
import scholarchain.storage.load.*

import StorageAdversarial.*

/**
 * TE-P7-01 case 2 (checklist G-ID, N2, N3, N5, L1): identifier consistency of the Neptune CSV,
 * through the public [[OpeningLoad.run]] only. Every change is made to a temporary copy and
 * RESEALED ([[Fixtures.resealCsv]]: the part manifest and every load manifest re-derived), so the
 * structural checks pass and only the identifier content is wrong. Each case asserts the exact
 * check tag, a refusal in all four stores and zero write calls.
 */
class GraphIdConsistencyAdversarialSuite extends munit.FunSuite {

  private def load(run: Path, loads: Path): (World, OpeningLoadResult) = {
    val w = new World
    (w, OpeningFixture.run(w, sources(run = run, loads = loads)))
  }

  private def dataLines(s: String): Vector[String] =
    s.split("\n", -1).toVector.drop(1).filter(_.nonEmpty)

  test("a vertex ~id duplicated across two CSV parts: neptune-graph-ids, zero writes") {
    Fixtures.withCopy { (run, loads) =>
      val first = dataLines(Fixtures.text(run.resolve("researcher-vertices/part-00000.csv"))).head
      val id = first.takeWhile(_ != ',')
      assert(id.startsWith("researcher:"), id)
      Fixtures.resealCsv(run, loads, "researcher-vertices/part-00001.csv")(s => s + first + "\n")
      val (w, res) = load(run, loads)
      println("TE-P7-01 duplicate ~id: " + res.report.rejections.take(3))
      assertRefusedEverywhere(
        res,
        w,
        VerificationCheck.NeptuneGraphIds,
        Set(VerificationCheck.NeptuneGraphIds)
      )
      assert(
        res.report.rejections.exists(x =>
          x.subject.startsWith("researcher-vertices/part-00001.csv#") &&
            x.error.message == s"~id $id appears 2 times"
        ),
        show(res.report)
      )
    }
  }

  test("an edge whose endpoint vertex is missing: neptune-graph-ids (dangling), zero writes") {
    Fixtures.withCopy { (run, loads) =>
      val path = "work-vertices/part-00001.csv"
      val removed = dataLines(Fixtures.text(run.resolve(path))).last
      val work = removed.takeWhile(_ != ',')
      assert(work.startsWith("work:"), work)
      Fixtures.resealCsv(run, loads, path)(s => s.replace(removed + "\n", ""))
      val (w, res) = load(run, loads)
      assertRefusedEverywhere(
        res,
        w,
        VerificationCheck.NeptuneGraphIds,
        Set(VerificationCheck.NeptuneGraphIds)
      )
      val dangling = res.report.rejections.filter(_.error.message.contains(s"endpoint $work:"))
      assert(dangling.nonEmpty, show(res.report))
      assert(
        dangling.forall(x =>
          Set(
            s"dangling ~to endpoint $work: no vertex of the run",
            s"dangling ~from endpoint $work: no vertex of the run"
          ).contains(x.error.message)
        ),
        dangling.toString
      )
    }
  }

  test("a vertex ~id that is not a valid stable ID: neptune-row-contract, zero writes") {
    Fixtures.withCopy { (run, loads) =>
      val path = "researcher-vertices/part-00000.csv"
      val first = dataLines(Fixtures.text(run.resolve(path))).head
      val id = first.takeWhile(_ != ',')
      Fixtures.resealCsv(run, loads, path)(s =>
        s.replace(first, "researcher:notascheme:x y" + first.drop(id.length))
      )
      val (w, res) = load(run, loads)
      println("TE-P7-01 invalid ~id: " + res.report.rejections.take(3))
      assertRefusedEverywhere(
        res,
        w,
        VerificationCheck.NeptuneRowContract,
        Set(VerificationCheck.NeptuneRowContract)
      )
      assert(
        res.report.rejections.exists(x =>
          x.subject == s"$path#1" && x.error.message.contains("~id")
        ),
        show(res.report)
      )
    }
  }

  test("graph-id-parity: a CSV venue name that differs from its graph-nodes record, zero writes") {
    Fixtures.withCopy { (run, loads) =>
      val path = "venue-vertices/part-00001.csv"
      assert(Fixtures.text(run.resolve(path)).contains(",Venue,AAAI,"))
      Fixtures.resealCsv(run, loads, path)(_.replace(",Venue,AAAI,", ",Venue,AAAI-2,"))
      val (w, res) = load(run, loads)
      println("TE-P7-01 venue parity: " + tags(res.report) + " " + res.report.rejections.take(3))
      assertRefusedEverywhere(
        res,
        w,
        VerificationCheck.GraphIdParity,
        Set(VerificationCheck.GraphIdParity)
      )
      assertEquals(VerificationCheck.GraphIdParity.tag, "graph-id-parity")
    }
  }

  test("graph-id-parity: a CSV coauthor count that differs from its graph-edges record") {
    Fixtures.withCopy { (run, loads) =>
      val path = "coauthored-with-edges/part-00000.csv"
      val first = dataLines(Fixtures.text(run.resolve(path))).head
      val cols = first.split(",", -1).toVector
      // ~id,~from,~to,~label,firstCollaborationYear,jointPublicationCount,...
      val bumped = cols.updated(5, (cols(5).toInt + 1).toString).mkString(",")
      Fixtures.resealCsv(run, loads, path)(_.replace(first, bumped))
      val (w, res) = load(run, loads)
      println("TE-P7-01 coauthor parity: " + tags(res.report) + " " + res.report.rejections.take(3))
      // The count is also recomputed from the grouping run (analysis-recompute, CR-P7-08 M2).
      assertRefusedEverywhere(
        res,
        w,
        VerificationCheck.GraphIdParity,
        Set(VerificationCheck.GraphIdParity, VerificationCheck.AnalysisRecompute)
      )
    }
  }

  test("an openCypher payload in a CSV property (Parquet unchanged) is refused: graph-id-parity") {
    Fixtures.withCopy { (run, loads) =>
      val payload = "AAAI\"}) MATCH (n) DETACH DELETE n //"
      val quoted = "\"" + payload.replace("\"", "\"\"") + "\""
      Fixtures.resealCsv(run, loads, "venue-vertices/part-00001.csv")(
        _.replace(",Venue,AAAI,", s",Venue,$quoted,")
      )
      val (w, res) = load(run, loads)
      assertRefusedEverywhere(
        res,
        w,
        VerificationCheck.GraphIdParity,
        Set(VerificationCheck.GraphIdParity)
      )
      assertEquals(w.graph.store.executedTemplates, Vector.empty, "no write template ran")
    }
  }
}

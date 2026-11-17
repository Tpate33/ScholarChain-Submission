package scholarchain.spark.output

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.util.Comparator

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

import scholarchain.core.*
import scholarchain.spark.input.{RunTree, TreeEntry}

/** The published, re-verified load manifests of one run. */
final case class PublishedLoads(
    dir: Path,
    partManifestSha256: String,
    manifests: Vector[StoreLoadManifest]
) {
  def of(t: TargetStore): Option[StoreLoadManifest] = manifests.find(_.targetStore == t)
}

/**
 * The store load manifests of a published analysis run (course item 11; SP-P6-03 step 2C;
 * `store-load-manifest.v1`, core StoreLoad.scala, roles from the frozen `LoadRoles`; checked by
 * `StructureValidation.checkLoadManifests`). Serialization only: no loader, no AWS client, no
 * physical name (table, index, bucket, endpoint); storage-engineer's loaders map roles to them.
 *
 * One manifest per target store (`neptune-bulk-load`, `dynamodb`, `s3-vectors`, `s3`), each listing
 * every part of the run that one of the store's roles accepts (same record schema, an allowed
 * format), copied from the part manifest (path, schema, format, rows, bytes, SHA-256), with its
 * role: the only role of the store for that schema and format, or, when several share them (the
 * Neptune vertex and edge roles), the role named like the part's dataset directory (the Neptune CSV
 * datasets are named by their roles, [[NeptuneCsvParts]]). `partManifestSha256` is the SHA-256 of
 * the published `_parts.json` bytes, so a manifest names exactly one published run.
 *
 * Location: a load manifest names the `_parts.json` hash, so it cannot be inside the run it
 * describes (the part manifest would have to list it). The manifests are written beside the run,
 * under the same output root: `load=<run hex>/<store tag>.json` (one LF-terminated JSON row each),
 * staged in `.staging-load-<run hex>` (create-once), claimed with a create-once `mkdir` and one
 * `ATOMIC_MOVE` rename (the [[RunPublisher]] scheme), then re-read and checked against the
 * published run ([[verify]]). Nothing else is created; nothing is overwritten.
 */
object LoadManifests {

  val DirPrefix = "load="
  val StagingPrefix = ".staging-load-"

  def fileName(t: TargetStore): String = s"${t.tag}.json"

  /** The role of `part` in store `t` (None: the store does not load it). */
  def roleOf(t: TargetStore, part: PartFileV2): Either[String, Option[String]] = {
    val dataset = part.path.takeWhile(_ != '/')
    LoadRoles.all.filter(r =>
      r.target == t && r.recordSchemaVersion == part.recordSchemaVersion &&
        r.formats.contains(part.format)
    ) match {
      case Vector() => Right(None)
      case Vector(r) => Right(Some(r.role))
      case many =>
        many
          .find(_.role == dataset)
          .map(r => Right(Some(r.role)))
          .getOrElse(
            Left(
              s"${part.path}: ${many.size} ${t.tag} roles hold ${part.recordSchemaVersion} ${part.format.tag} parts and none is named $dataset"
            )
          )
    }
  }

  /** One manifest per target store from a published part manifest and its observed SHA-256. */
  def build(
      pm: PartManifestV2,
      sha256: String
  ): Either[Vector[String], Vector[StoreLoadManifest]] = {
    val built = TargetStore.values.toVector.map { t =>
      val roles =
        pm.files
          .filter(_.path != PartManifest.RunManifestFile)
          .map(p => roleOf(t, p).map(_.map(p.path -> _)))
      val bad = roles.collect { case Left(w) => w }
      if (bad.nonEmpty) Left(bad)
      else
        StoreLoadManifest
          .build(t, Right(pm), sha256, roles.collect { case Right(Some(x)) => x }.toMap)
          .left
          .map(_.map(e => s"${t.tag} load manifest: $e"))
    }
    val bad = built.collect { case Left(w) => w }.flatten
    if (bad.nonEmpty) Left(bad) else Right(built.collect { case Right(m) => m })
  }

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS))
      Using.resource(Files.walk(root)) { s =>
        s.sorted(Comparator.reverseOrder[Path]()).iterator().asScala.foreach { p =>
          Files.deleteIfExists(p)
          ()
        }
      }

  /** The SHA-256 of the published `_parts.json` bytes, observed from the file. */
  def partManifestSha256(published: PublishedRun): Either[String, String] =
    RunTree.observe(published.dir, PartManifest.FileName).map(_.sha256)

  /**
   * Builds, writes (staged, create-once, renamed) and re-verifies the load manifests of `published`
   * under `outputRoot` (its parent). Refuses when `load=<hex>` or the staging directory exists; on
   * any refusal before the rename the staging directory this call created is removed.
   */
  def publish(
      outputRoot: Path,
      published: PublishedRun,
      maxMetadataBytes: Long
  ): Either[Vector[String], PublishedLoads] = {
    val hex = published.runId.stripPrefix("run:")
    val finalDir = outputRoot.resolve(DirPrefix + hex)
    val staging = outputRoot.resolve(StagingPrefix + hex)
    if (published.dir.getParent != outputRoot)
      Left(Vector(s"the run ${published.dir} is not directly under the output root $outputRoot"))
    else if (RunTree.exists(finalDir) || RunTree.exists(staging))
      Left(Vector(s"$finalDir or $staging already exists: never overwritten"))
    else
      for {
        sha <- partManifestSha256(published).left.map(Vector(_))
        manifests <- build(published.partManifest, sha)
        _ <- {
          val created =
            try { Files.createDirectory(staging); None }
            catch { case e: IOException => Some(s"cannot create $staging (create-once): $e") }
          created match {
            case Some(w) => Left(Vector(w))
            case None =>
              val written =
                try {
                  manifests.foreach { m =>
                    Files.write(
                      staging.resolve(fileName(m.targetStore)),
                      (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8),
                      StandardOpenOption.CREATE_NEW,
                      StandardOpenOption.WRITE
                    )
                  }
                  Files.createDirectory(finalDir)
                  Files.move(staging, finalDir, StandardCopyOption.ATOMIC_MOVE)
                  Right(())
                } catch {
                  case NonFatal(e) =>
                    deleteTree(staging)
                    val claim =
                      try {
                        Files.deleteIfExists(finalDir)
                        "an empty claim was removed"
                      } catch { case NonFatal(_) => "the claim was not empty and was kept" }
                    Left(Vector(s"writing the load manifests failed ($e): $claim"))
                }
              written
          }
        }
        loads <- verify(finalDir, published, maxMetadataBytes)
      } yield loads
  }

  /**
   * Re-reads a load-manifest directory: exactly one regular file per target store (nothing else),
   * each at most `maxMetadataBytes`, one LF-terminated row decoding through the core contract, for
   * its own store; and the four manifests pass `StructureValidation.checkLoadManifests` against the
   * published run's part manifest and the observed SHA-256 of its `_parts.json`.
   */
  def verify(
      dir: Path,
      published: PublishedRun,
      maxMetadataBytes: Long
  ): Either[Vector[String], PublishedLoads] = {
    val expected = TargetStore.values.toVector.map(fileName).sorted
    for {
      entries <- RunTree.walk(dir, expected.size + 1, 1).left.map(Vector(_))
      _ <-
        if (
          entries.map(_.relativePath).sorted == expected &&
          entries.forall(_.kind == TreeEntry.Kind.Regular)
        ) Right(())
        else
          Left(
            Vector(
              s"$dir must hold exactly the regular files ${expected.mkString(", ")}; found ${entries.map(_.relativePath).mkString(", ")}"
            )
          )
      manifests <- {
        val read = TargetStore.values.toVector.map { t =>
          for {
            text <- RunTree.readUtf8(dir.resolve(fileName(t)), maxMetadataBytes)
            _ <-
              if (text.endsWith("\n") && !text.stripSuffix("\n").contains('\n')) Right(())
              else Left(s"${fileName(t)} is not one LF-terminated row")
            m <- Json.decode[StoreLoadManifest](text.stripSuffix("\n")).left.map(_.mkString("; "))
            _ <-
              if (m.targetStore == t) Right(())
              else Left(s"${fileName(t)} holds the ${m.targetStore.tag} manifest")
          } yield m
        }
        val bad = read.collect { case Left(w) => w }
        if (bad.nonEmpty) Left(bad) else Right(read.collect { case Right(m) => m })
      }
      sha <- partManifestSha256(published).left.map(Vector(_))
      _ <- {
        val e =
          StructureValidation.checkLoadManifests(manifests, Right(published.partManifest), sha)
        if (e.isEmpty) Right(()) else Left(e.map(_.toString))
      }
    } yield PublishedLoads(dir, sha, manifests)
  }
}

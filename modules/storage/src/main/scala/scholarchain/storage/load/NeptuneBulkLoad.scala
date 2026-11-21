package scholarchain.storage.load

import scholarchain.core.{CanonicalGraph, LoadRoles, StoreLoadManifest, TargetStore}
import scholarchain.storage.model.{S3Layout, StoreError, Zone}
import scholarchain.storage.port.{BulkLoadPlan, BulkLoadRequest}

/** Neptune loader `parallelism` values (frozen tags as the loader expects them; UNVERIFIED). */
enum LoaderParallelism(val tag: String) {
  case Low extends LoaderParallelism("LOW")
  case Medium extends LoaderParallelism("MEDIUM")
  case High extends LoaderParallelism("HIGH")
  case Oversubscribe extends LoaderParallelism("OVERSUBSCRIBE")
}

/**
 * Bulk-loader settings (SE-P7-05 B6). Every value comes from configuration; nothing has a default
 * and no ARN, Region or bucket is a literal. Binding them to `core` configuration keys is pending
 * (core `StorageConfig` has `neptuneEndpoint` only; see the report).
 *
 * @param iamRoleArn
 *   the role Neptune assumes to read the graph zone (created by infra-engineer's Terraform)
 * @param region
 *   the Region of the graph-zone bucket
 */
final case class NeptuneLoaderSettings private (
    iamRoleArn: String,
    region: String,
    failOnError: Boolean,
    parallelism: LoaderParallelism,
    queueRequest: Boolean
)

object NeptuneLoaderSettings {
  private val RoleArn = "^arn:aws[a-z-]*:iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_/-]{1,512}$".r
  private val Region = "^[a-z]{2}(-[a-z]+)+-[0-9]$".r

  def of(
      iamRoleArn: String,
      region: String,
      failOnError: Boolean,
      parallelism: LoaderParallelism,
      queueRequest: Boolean
  ): Either[StoreError, NeptuneLoaderSettings] =
    if (iamRoleArn == null || !RoleArn.matches(iamRoleArn))
      Left(StoreError.Refused("iamRoleArn: not an IAM role ARN"))
    else if (region == null || !Region.matches(region))
      Left(StoreError.Refused("region: not a Region name"))
    else if (parallelism == null) Left(StoreError.Refused("parallelism: missing"))
    else
      Right(new NeptuneLoaderSettings(iamRoleArn, region, failOnError, parallelism, queueRequest))
}

/**
 * The Neptune bulk-load plan of one `neptune-bulk-load` store load (SE-P7-05 B6; checklist N1): a
 * pure function, never sent (no AWS client exists in storage; the submitting adapter is deferred,
 * A1). One request per dataset directory (role) of the load, vertex roles first in core label
 * order, then edge roles; every edge request depends on every vertex request, so no edge is loaded
 * before its endpoints. The source of a request is the role's prefix in the graph zone,
 * `<graph>/runs/<runHex>/<role>/` ([[S3Layout.runObject]]), so the CSV parts must first be copied
 * there (create-once) by the S3 loader.
 */
object NeptuneBulkLoad {

  val Format = "csv"

  /**
   * The plan of the Neptune load a gate permit grants (CR-P7-07 n6): only a [[LoadPermit]] for
   * `neptune-bulk-load`, which the gate issues after every Neptune check passed, yields a plan.
   */
  def plan(
      permit: LoadPermit,
      layout: S3Layout,
      settings: NeptuneLoaderSettings
  ): Either[StoreError, BulkLoadPlan] =
    if (permit == null) Left(StoreError.Refused("plan: a load permit is required"))
    else if (permit.target != TargetStore.NeptuneBulkLoad)
      Left(StoreError.Refused(s"plan: the permit is for ${permit.target.tag}"))
    else planOf(permit.load, layout, settings)

  private[load] def planOf(
      load: StoreLoadManifest,
      layout: S3Layout,
      settings: NeptuneLoaderSettings
  ): Either[StoreError, BulkLoadPlan] =
    if (load == null || layout == null || settings == null)
      Left(StoreError.Refused("plan: load, layout and settings are required"))
    else if (load.targetStore != TargetStore.NeptuneBulkLoad)
      Left(StoreError.Refused(s"plan: ${load.targetStore.tag} is not the Neptune load"))
    else {
      val roles = load.items.map(_.targetRole).toSet
      val vertexRoles = CanonicalGraph.NodeLabels.map(LoadRoles.vertexRole).filter(roles.contains)
      val edgeRoles = CanonicalGraph.EdgeLabels.map(LoadRoles.edgeRole).filter(roles.contains)
      val unknown = roles -- vertexRoles -- edgeRoles
      val misplaced = load.items.filter(i => i.path.takeWhile(_ != '/') != i.targetRole)
      if (unknown.nonEmpty)
        Left(
          StoreError.Refused(s"plan: not Neptune roles: ${unknown.toVector.sorted.mkString(", ")}")
        )
      else if (misplaced.nonEmpty)
        Left(StoreError.Refused(s"plan: ${misplaced.head.path} is not in its role's directory"))
      else {
        def request(role: String, deps: Vector[String]) =
          layout.runObject(Zone.Graph, load.sourceRunId, role).map { ref =>
            BulkLoadRequest(
              role,
              ref.uri + "/",
              Format,
              settings.iamRoleArn,
              settings.region,
              settings.failOnError,
              settings.parallelism.tag,
              settings.queueRequest,
              deps
            )
          }
        val all =
          vertexRoles.map(request(_, Vector.empty)) ++ edgeRoles.map(request(_, vertexRoles))
        all.collectFirst { case Left(e) => e } match {
          case Some(e) => Left(e)
          case None =>
            Right(BulkLoadPlan(load.sourceRunId, load.loadId, all.collect { case Right(r) => r }))
        }
      }
    }
}

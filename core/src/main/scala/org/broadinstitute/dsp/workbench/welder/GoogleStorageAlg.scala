package org.broadinstitute.dsp.workbench.welder

import java.nio.file.Path

import cats.effect.{ContextShift, IO, Timer}
import fs2.Stream
import io.chrisdavenport.linebacker.Linebacker
import io.chrisdavenport.log4cats.Logger
import org.broadinstitute.dsde.workbench.google2.{Crc32, GoogleStorageService, RemoveObjectResult}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsp.workbench.welder.SourceUri.GsPath

trait GoogleStorageAlg {
  def updateMetadata(gsPath: GsPath, traceId: TraceId, metadata: Map[String, String]): IO[UpdateMetadataResponse]
  def retrieveAdaptedGcsMetadata(localPath: RelativePath, gsPath: GsPath, traceId: TraceId): IO[Option[AdaptedGcsMetadata]]
  def removeObject(gsPath: GsPath, traceId: TraceId, generation: Option[Long]): Stream[IO, RemoveObjectResult]
  /**
    * Overwrites the file if it already exists locally
    */
  def gcsToLocalFile(localAbsolutePath: java.nio.file.Path, gsPath: GsPath, traceId: TraceId): Stream[IO, AdaptedGcsMetadata]
  def delocalize(
      localObjectPath: RelativePath,
      gsPath: GsPath,
      generation: Long,
      userDefinedMeta: Map[String, String],
      traceId: TraceId
  ): IO[DelocalizeResponse]
}

object GoogleStorageAlg {
  val LAST_LOCKED_BY = "lastLockedBy"
  val LOCK_EXPIRES_AT = "lockExpiresAt"

  def fromGoogle(
      config: GoogleStorageAlgConfig,
      googleStorageService: GoogleStorageService[IO]
  )(implicit logger: Logger[IO], timer: Timer[IO], linerBacker: Linebacker[IO], cs: ContextShift[IO]): GoogleStorageAlg =
    new GoogleStorageInterp(config, googleStorageService)
}

final case class GoogleStorageAlgConfig(workingDirectory: Path)
final case class DelocalizeResponse(generation: Long, crc32c: Crc32)

sealed trait UpdateMetadataResponse extends Product with Serializable
object UpdateMetadataResponse {
  final case object DirectMetadataUpdate extends UpdateMetadataResponse
  final case class ReUploadObject(generation: Long, crc32c: Crc32) extends UpdateMetadataResponse
}